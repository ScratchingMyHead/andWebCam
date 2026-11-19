package net.andwebcam

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The whole product runs here: camera -> MJPEG hub -> HTTPS server -> mDNS.
 *
 * It is a foreground service so Android keeps it alive with the screen off, and
 * it is started at boot so the phone can be left unattended.
 */
class CamService : Service(), HttpServer.Host {
    companion object {
        private const val TAG = "andWebCam"
        const val ACTION_START = "net.andwebcam.START"
        const val ACTION_STOP = "net.andwebcam.STOP"
        const val ACTION_RELOAD = "net.andwebcam.RELOAD"
        const val ACTION_MAINTENANCE = "net.andwebcam.MAINTENANCE"
        private const val CH_ID = "andwebcam"
        private const val NOTIF_ID = 1
        private const val REQ_MAINTENANCE = 2

        /** How often to wake up and make sure the network is still there. */
        private const val MAINTENANCE_MS = 5 * 60_000L

        fun start(ctx: Context) = send(ctx, ACTION_START)

        fun stop(ctx: Context) {
            // stopService() when there is nothing to tear down. Sending ACTION_STOP
            // would ask the system to start the service for it, and a service that
            // is only being stopped never calls startForeground(), which Android
            // treats as a fatal error.
            if (State.running) {
                send(ctx, ACTION_STOP)
            } else {
                ctx.stopService(Intent(ctx, CamService::class.java))
            }
        }

        /** Re-open the camera (e.g. after the permission is granted). */
        fun reload(ctx: Context) = send(ctx, ACTION_RELOAD)

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, CamService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var hub: StreamHub
    private lateinit var camera: CameraController
    private lateinit var server: HttpServer
    private var mdns: Mdns? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var scheduler: ScheduledExecutorService? = null
    private var lastFrameCount = 0L
    private var started = false

    /** Whether streaming is enabled (viewers are accepted and wake the camera). */
    @Volatile
    private var streamingEnabled = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraRetry = Runnable {
        if (started && streamingEnabled && hub.count > 0) camera.start()
    }
    private val idleStop = Runnable {
        if (started && hub.count == 0) {
            Log.i(TAG, "no viewers; closing camera to save power")
            camera.stop()
            updateNotification()
        }
    }
    private val refreshNet = Runnable { refreshNetwork() }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        hub = StreamHub()
        camera = CameraController(
            this,
            onFrame = { jpeg ->
                if (State.lastError != null) State.lastError = null
                hub.broadcast(jpeg)
            },
            onError = { msg ->
                State.lastError = msg
                Log.w(TAG, msg)
                scheduleCameraRetry()
            },
        )
        server = HttpServer(this, hub, this)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop always works; everything else has to be allowed to run.
        if (intent?.action != ACTION_STOP && disabledNow()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                // Only reached when the system started the service to deliver
                // this call: the startForegroundService() contract has to be
                // honoured even though it is about to go away.
                if (!started) startForegroundCompat()
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                camera.start()
                return START_STICKY
            }
            ACTION_MAINTENANCE -> {
                // The alarm is also a safety net: if the process died, this
                // brings the whole service back.
                if (!started) startEverything() else maintainNetwork()
                return START_STICKY
            }
            else -> startEverything()
        }
        return START_STICKY
    }

    /**
     * Switched off on the phone: nothing runs and nothing may bring it back,
     * not the alarm that was armed before it was switched off and not the
     * system restarting the process. A startForegroundService() call still has
     * to be honoured before the service goes away.
     */
    private fun disabledNow(): Boolean {
        if (Prefs.enabled) return false
        Log.i(TAG, "disabled on the phone; not starting")
        cancelMaintenance()
        startForegroundCompat()
        stopEverything()
        stopSelf()
        return true
    }


    override fun onDestroy() {
        stopEverything()
        camera.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun startEverything() {
        startForegroundCompat()
        if (started) {
            // Already up (e.g. the Activity re-launched us): don't reopen the camera.
            updateNotification()
            return
        }
        updateLocks(false)          // start asleep: only the multicast lock
        State.serviceName = Prefs.serviceName
        State.port = Prefs.port
        State.ip = localIp()

        streamingEnabled = Prefs.autoStartStreaming
        hub.onClientsChanged = { count -> onClientsChanged(count) }
        // The camera is not opened here: it stays closed until someone is
        // actually watching, and closes again when the last viewer leaves.

        startNetworking()
        watchNetwork()
        scheduleMaintenance()
        LogFolder.mirror(this)   // also picks up the lines a previous run left

        if (!camera.hasPermission()) {
            State.lastError = "Camera permission not granted. Allow the camera for andWebCam in the phone's app settings."
        }

        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor().also { s ->
                s.scheduleAtFixedRate({
                    val f = hub.frames
                    State.framesPerSec = (f - lastFrameCount).toInt().coerceAtLeast(0)
                    lastFrameCount = f
                    State.frames = f
                }, 1, 1, TimeUnit.SECONDS)
            }
        }
        started = true
        State.running = true
        updateNotification()
        // One line per boot that says which build is running, where to find it,
        // and whether the run before it ended on purpose: with a camera at a
        // remote site that is the first thing to ask.
        val previous = Prefs.lastStart
        val clean = Prefs.cleanExit
        Prefs.lastStart = System.currentTimeMillis()
        Prefs.cleanExit = false
        Log.i(
            TAG,
            "service started: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_ID}) on " +
                "port ${Prefs.port}, https://${Prefs.serviceName}.local:${Prefs.port}/, " +
                "ip ${State.ip ?: "none"}, streaming on start=$streamingEnabled" +
                if (previous == 0L) "" else ", previous run " + ago(previous) +
                    (if (clean) " ended cleanly" else " ended unexpectedly"),
        )
    }

    private fun ago(ms: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    private fun startNetworking() {
        State.port = Prefs.port
        State.serviceName = Prefs.serviceName
        val ok = server.start(Prefs.port)
        if (!ok) {
            State.lastError = "Could not bind port ${Prefs.port}"
        }
        restartMdns()
    }

    private fun restartMdns() {
        mdns?.stop()
        mdns = Mdns(this, Prefs.serviceName, Prefs.port).also { it.start() }
    }

    // ---- network upkeep -----------------------------------------------

    /**
     * Watches the address rather than assuming it. After the access point
     * restarts the phone can come back on a different one, and nothing else
     * here would notice: the server binds to every interface so it keeps
     * working, but the page, the notification and the mDNS A record would all
     * still name the address it used to have.
     */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()
            override fun onLost(network: Network) = networkChanged()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = networkChanged()
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch the network: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    /** Association changes arrive in bursts; wait for the dust to settle. */
    private fun networkChanged() {
        mainHandler.post {
            mainHandler.removeCallbacks(refreshNet)
            mainHandler.postDelayed(refreshNet, 600)
        }
    }

    private fun refreshNetwork() {
        if (!started) return
        val ip = currentIp()
        if (ip != State.ip) Log.i(TAG, "address is now ${ip ?: "none"}")
        State.ip = ip
        // Rebuild rather than patch up. A socket whose multicast membership died
        // with the interface stays open and simply never receives again - the
        // threads look perfectly healthy - so there is nothing to detect and
        // nothing to repair in place. A fresh responder also re-announces.
        restartMdns()
        updateNotification()
    }

    private fun currentIp(): String? {
        // Ask the system what the Wi-Fi link has: the interface list can lag
        // behind a re-association, and this is the address that actually works.
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        if (cm != null && net != null) {
            val caps = cm.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                for (la in cm.getLinkProperties(net)?.linkAddresses.orEmpty()) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        }
        return localIp()
    }

    /**
     * Deep sleep turns the radio off, and a phone that was asleep when the
     * access point went away does not notice it came back until something wakes
     * it - which used to be the screen. This alarm is that something: a few
     * minutes apart it wakes the service long enough to ask Wi-Fi to
     * reassociate and to put the right address back on the screen.
     */
    private fun maintenanceIntent(): PendingIntent = PendingIntent.getService(
        this, REQ_MAINTENANCE,
        Intent(this, CamService::class.java).setAction(ACTION_MAINTENANCE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleMaintenance() {
        try {
            getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + MAINTENANCE_MS,
                    maintenanceIntent()
                )
        } catch (e: Exception) {
            Log.w(TAG, "cannot schedule maintenance: ${e.message}")
        }
    }

    private fun cancelMaintenance() {
        try {
            getSystemService(AlarmManager::class.java)?.cancel(maintenanceIntent())
        } catch (_: Exception) {
        }
    }

    private fun maintainNetwork() {
        scheduleMaintenance()
        if (!started) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val caps = if (cm != null && net != null) cm.getNetworkCapabilities(net) else null
        val wm = getSystemService(WifiManager::class.java)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true && wm?.isWifiEnabled == true) {
            Log.i(TAG, "no wifi connection; asking for one")
            askWifiToReconnect(wm)
        }
        refreshNetwork()
        // The alarm that woke us doubles as the log mirror's clock.
        LogFolder.mirror(this)
    }

    @Suppress("DEPRECATION")
    private fun askWifiToReconnect(wm: WifiManager) {
        try {
            wm.reconnect()
        } catch (e: Exception) {
            Log.w(TAG, "asking wifi to reconnect failed: ${e.message}")
        }
    }


    private fun scheduleNetRestart() {
        Thread {
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            startNetworking()
            updateNotification()
        }.start()
    }

    /** Unattended safety net: if the camera errors, try again shortly. */
    private fun scheduleCameraRetry() {
        mainHandler.removeCallbacks(cameraRetry)
        mainHandler.postDelayed(cameraRetry, 5000)
    }

    /**
     * Camera power policy: open it only while somebody is watching, and close
     * it after a short grace period once the last viewer leaves.
     */
    private fun onClientsChanged(count: Int) {
        mainHandler.post {
            if (!started) return@post
            mainHandler.removeCallbacks(idleStop)
            updateLocks(count > 0)
            if (count > 0) {
                if (streamingEnabled && !camera.active) {
                    Log.i(TAG, "viewer connected; opening camera")
                    camera.start()
                }
            } else {
                mainHandler.postDelayed(idleStop, 5000)
            }
            updateNotification()
        }
    }

    private fun setStreaming(on: Boolean) {
        streamingEnabled = on
        Log.i(TAG, "streaming enabled=$on")
        if (on) {
            if (hub.count > 0) camera.start()
        } else {
            hub.disconnectAll()
            mainHandler.removeCallbacks(idleStop)
            camera.stop()
        }
        updateNotification()
    }

    private fun stopEverything() {
        State.running = false
        started = false
        Prefs.cleanExit = true
        LogFolder.mirrorNow(this)   // the last chance to finish the file
        mainHandler.removeCallbacks(cameraRetry)
        mainHandler.removeCallbacks(idleStop)
        mainHandler.removeCallbacks(refreshNet)
        hub.disconnectAll()
        mdns?.stop()
        mdns = null
        unwatchNetwork()
        cancelMaintenance()
        server.stop()
        camera.stop()
        releaseLocks()
        scheduler?.shutdownNow()
        scheduler = null
        try { stopForeground(true) } catch (_: Exception) {}
    }

    // ---- HttpServer.Host ----------------------------------------------

    override fun statusJson(): MutableMap<String, Any?> {
        val caps = camera.capabilities()
        val sz = camera.currentSize
        return mutableMapOf(
            "running" to State.running,
            "ip" to State.ip,
            "port" to Prefs.port,
            "serviceName" to Prefs.serviceName,
            "facing" to Prefs.facing,
            "resolutions" to caps.resolutions,
            "maxZoom" to caps.maxZoom,
            "torchAvailable" to caps.torchAvailable,
            "fpsOptions" to caps.fpsOptions,
            "fps" to Prefs.fps,
            "fpsActive" to State.framesPerSec,
            "quality" to Prefs.quality,
            "torch" to Prefs.torch,
            "zoom" to Prefs.zoom,
            "orientation" to Prefs.orientation,
            "clients" to State.clients,
            "width" to sz.width,
            "height" to sz.height,
            "streaming" to streamingEnabled,
            "cameraActive" to camera.active,
            "autoStartStreaming" to Prefs.autoStartStreaming,
            "lastError" to State.lastError,
            // The folder holding the on-disk copy of the log; null while none
            // has been chosen. The page shows it and offers the picker.
            "logFolder" to LogFolder.describe(this),
            "version" to BuildConfig.VERSION_NAME,
            "build" to BuildConfig.BUILD_ID,
            // Restarting needs device owner; the page hides the button without it.
            "canReboot" to (getSystemService(DevicePolicyManager::class.java)
                ?.isDeviceOwnerApp(packageName) == true),
            "updateNote" to Prefs.updateNote,
        )
    }

    override fun applySettings(vals: Map<String, String>) {
        var restartCamera = false
        var restartNet = false
        for ((k, v) in vals) {
            when (k) {
                "facing" -> if (Prefs.facing != v) { Prefs.facing = v; restartCamera = true }
                "resolution" -> if (Prefs.resolution != v) { Prefs.resolution = v; restartCamera = true }
                "fps" -> v.toIntOrNull()?.let { Prefs.fps = it }
                "quality" -> v.toIntOrNull()?.let { Prefs.quality = it }
                "torch" -> Prefs.torch = v.toBoolean()
                "zoom" -> v.toFloatOrNull()?.let { Prefs.zoom = it }
                "orientation" -> v.toIntOrNull()?.let { Prefs.orientation = it }
                "anonView" -> Prefs.anonView = v.toBoolean()
                "autoStartStreaming" -> Prefs.autoStartStreaming = v.toBoolean()
                "streaming" -> setStreaming(v.toBoolean())
                "serviceName" -> if (Prefs.serviceName != v.lowercase()) { Prefs.serviceName = v; restartNet = true }
                "port" -> v.toIntOrNull()?.let { if (it != Prefs.port) { Prefs.port = it; restartNet = true } }
            }
        }
        if (restartCamera) {
            if (streamingEnabled && hub.count > 0) camera.start() else camera.applyLive()
        } else {
            camera.applyLive()
        }
        if (restartNet) scheduleNetRestart()
        updateNotification()
    }

    override fun changePassword(newPass: String) {
        Prefs.setAdminPassword(newPass)
        updateNotification()
    }

    override fun isStreaming(): Boolean = streamingEnabled

    /**
     * A single JPEG at the current settings. While the camera is running the
     * newest streamed frame is exactly what is on screen, so it is reused;
     * otherwise one frame is captured, opening the camera if nobody is
     * watching (and closing it again afterwards).
     */
    override fun snapshot(): ByteArray? {
        if (camera.active) hub.latest?.let { return it }

        val latch = java.util.concurrent.CountDownLatch(1)
        val out = arrayOfNulls<ByteArray>(1)
        val viewerWaiting = hub.count > 0
        camera.captureOnce(openIfClosed = !viewerWaiting) { jpeg ->
            out[0] = jpeg
            latch.countDown()
        }
        try {
            latch.await(7, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        // If the camera was opened just for this picture, close it again
        // unless somebody started watching in the meantime.
        mainHandler.post {
            if (started && hub.count == 0) camera.stop()
        }
        return out[0]
    }

    override fun diag(mode: String, seconds: Int, resolution: String): String {
        if (mode == "threads") {
            val sb = StringBuilder()
            Thread.getAllStackTraces().forEach { (t, stack) ->
                sb.append("${t.name} state=${t.state}\n")
                stack.take(14).forEach { sb.append("    at $it\n") }
            }
            return sb.toString()
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var report = "no report"
        camera.onDiagDone = { r ->
            report = r
            latch.countDown()
        }
        camera.runDiag(mode, seconds, resolution)
        latch.await(seconds + 20L, java.util.concurrent.TimeUnit.SECONDS)
        camera.onDiagDone = null
        return report
    }

    // ---- software update ----------------------------------------------

    /** The session created by [stageUpdate], waiting to be committed. */
    private var staged: Updater.Staged? = null

    override fun stageUpdate(apk: File): String? {
        staged?.discard()
        staged = null
        return try {
            staged = Updater.stage(this, apk)
            null
        } catch (e: Updater.Bad) {
            e.reason
        } catch (e: Exception) {
            "Could not prepare the update: ${e.message}"
        }
    }

    override fun commitUpdate(): String? {
        val s = staged ?: return "nothing was staged"
        staged = null
        // Replacing this APK kills this process, service and all. The
        // maintenance alarm is what normally brings the service back; arm it
        // once, soon, so an update does not leave the phone dark for minutes.
        try {
            getSystemService(AlarmManager::class.java)
                ?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30_000, maintenanceIntent())
        } catch (e: Exception) {
            Log.w(TAG, "cannot arm the restart after the update: ${e.message}")
        }
        val err = s.commit(this)
        Prefs.cleanExit = true       // the process is about to be replaced on purpose
        Prefs.updateNote = if (err == null) "Installing ${s.label}..." else "Update failed: $err"
        return err
    }

    /**
     * Starts the picker on the phone's screen. The service cannot ask for an
     * activity result itself, so the invisible stand-in does it and keeps the
     * answer.
     */
    override fun pickFolder(): String? = try {
        startActivity(
            Intent(this, FolderPickActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (e: Exception) {
        "Could not open the folder picker: ${e.message}"
    }

    override fun clearFolder() {
        LogFolder.clear(this)
    }

    override fun reboot(): String? {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(packageName)) {
            return "This phone is not managed by andWebCam, so it cannot be restarted from here. Use its power button."
        }
        // Deliberate, so the next start does not report it as a disappearance.
        Prefs.cleanExit = true
        Thread {
            try {
                Thread.sleep(800)
                dpm.reboot(admin)
            } catch (_: Exception) {
            }
        }.start()
        return null
    }

    /**
     * The radio is kept out of power save for as long as the service runs, not
     * only while somebody is watching. It is the radio, not the CPU, that has
     * to notice the access point disappearing: with it asleep the phone sat
     * there without a network until the screen was turned on, and a webcam
     * nobody can reach is no webcam. The CPU is a different matter - it is only
     * held awake while somebody is actually watching, so an idle phone still
     * sleeps between the maintenance alarms.
     */
    private fun updateLocks(watching: Boolean) {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        if (multicastLock == null) {
            multicastLock = wm.createMulticastLock("andwebcam").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "andwebcam:radio").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (watching) {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andwebcam:stream").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            try { wakeLock?.release() } catch (_: Exception) {}
            wakeLock = null
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "andWebCam", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Camera streaming service"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        State.streaming = streamingEnabled
        State.cameraActive = camera.active
        val text = when {
            !streamingEnabled -> "Streaming stopped - open ${State.url}"
            camera.active -> "Streaming at ${State.url}"
            else -> "Idle (no viewers) - ${State.url}"
        }
        return Notification.Builder(this, CH_ID)
            .setContentTitle("andWebCam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!started) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun localIp(): String? {
        return try {
            var fallback: String? = null
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    if (nif.name.startsWith("wlan")) return addr.hostAddress
                    if (fallback == null) fallback = addr.hostAddress
                }
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }
}
