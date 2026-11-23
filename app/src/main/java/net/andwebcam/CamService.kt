package net.andwebcam

import android.Manifest
import android.app.ActivityManager
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
    private lateinit var scaler: StreamScaler
    private val lastFull = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
    private lateinit var server: HttpServer
    private var publicServer: HttpServer? = null
    private val ring = VideoRing()
    private val audio = AudioHub(this)
    private val recorder = Recorder(this, audio)
    private val motion = Motion()
    private val publicFeed = PublicFeed()

    /** True while the running recording was started by motion, not by hand. */
    private var motionOwned = false

    /** When the motion recording ends if no further motion is seen. */
    private var quietUntil = 0L
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
        if (started && (recorder.recording || motion.enabled || motion.previewing() ||
                publicFeed.isEnabled() || (streamingEnabled && hub.count > 0))) camera.start()
    }
    /**
     * Close the camera and end the ring's run of frames. The pre-roll must
     * be continuous live video: frames from before the camera paused would
     * leave a hole at the start of the next recording.
     */
    private fun closeCamera() {
        camera.stop()
        ring.clear()
    }

    private val idleStop = Runnable {
        if (started && hub.count == 0 && !recorder.recording && !motion.enabled &&
            !motion.previewing() && !publicFeed.isEnabled()) {
            Log.i(TAG, "no viewers; closing camera to save power")
            closeCamera()
            updateNotification()
        }
    }
    /**
     * Backstop for the motion pane's preview lease. The pane polls every
     * few hundred milliseconds; if it stops (tab closed, phone asleep) this
     * releases the camera and wake lock after the lease runs out.
     */
    private val previewTimeout = Runnable {
        if (started && !motion.previewing()) {
            updateLocks(watchingSomething())
            if (hub.count == 0 && !recorder.recording && !motion.enabled && !publicFeed.isEnabled()) {
                Log.i(TAG, "motion pane closed; closing camera")
                closeCamera()
            }
            updateNotification()
        }
    }

    private val refreshNet = Runnable { refreshNetwork() }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        publicFeed.configure(Prefs.publicFeedUrl, Prefs.publicFeedToken, Prefs.viewerPassword)
        hub = StreamHub()
        State.clients = 0
        audio.onClientsChanged = { count ->
            mainHandler.post {
                if (started) updateLocks(watchingSomething())
            }
        }
        scaler = StreamScaler({ jpeg -> hub.broadcast(jpeg) }, { hub.count > 0 })
        recorder.ringRef = ring
        recorder.onStopped = {
            motionOwned = false
            // The camera may now be the last reason to stay open.
            mainHandler.post {
                if (!started) return@post
                mainHandler.removeCallbacks(idleStop)
                if (hub.count == 0) {
                    if (streamingEnabled) {
                        mainHandler.postDelayed(idleStop, 5000)
                    } else if (camera.active && !motion.enabled && !publicFeed.isEnabled()) {
                        Log.i(TAG, "recording finished and no viewers; closing camera")
                        closeCamera()
                    }
                }
                updateNotification()
            }
        }
        // A zone firing is a request for a recording with the seconds before
        // it in the file: the ring supplies those. A recording that is already
        // running - by hand, say - is left alone.
        motion.onTrigger = { zone ->
            val err = if (recorder.recording) "recording already running" else preRollBlock()
            if (err == null) {
                val s = camera.currentSize
                recorder.start(s.width, s.height, "zone$zone")
                motionOwned = true
                quietUntil = System.currentTimeMillis() + motion.quietSeconds() * 1000L
                Log.i(TAG, "motion recording started (zone $zone)")
            } else if (err != "recording already running") {
                Log.w(TAG, "motion recording refused: $err")
            }
            mainHandler.post { updateNotification() }
        }
        camera = CameraController(
            this,
            onFrame = { jpeg ->
                if (State.lastError != null) State.lastError = null
                lastFull.set(jpeg)
                ring.push(jpeg)
                recorder.push(jpeg)
                motion.push(jpeg)
                scaler.offer(jpeg, Prefs.streamScale, Prefs.quality)
            },
            onError = { msg ->
                State.lastError = msg
                if (msg.contains("allocation failed") && recorder.recording) recorder.failForMemory()
                Log.w(TAG, msg)
                scheduleCameraRetry()
            },
        )
        server = HttpServer(this, hub, audio, this, Tls.dualSslContext(this))
        // The key managers read the certificate on each handshake, so a
        // renewal or a switch needs no restart: rebuilding the HTTP server
        // would discard every logged-in session.
        Acme.start(this) { mainHandler.post { refreshAcmeListeners() } }
        configureRing()
        mainHandler.postDelayed({ configureRing() }, 3000)
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
        State.serviceName = Prefs.serviceName
        State.port = Prefs.port
        State.ip = localIp()

        streamingEnabled = Prefs.autoStartStreaming
        audio.setRecordSource(streamingEnabled && Prefs.recordAudio && AudioHub.hasPermission(this))
        updateLocks(watchingSomething())   // start asleep unless something is watching or recording
        hub.onClientsChanged = { count -> onClientsChanged(count) }
        // The camera is not opened here: it stays closed until someone is
        // actually watching, and closes again when the last viewer leaves.

        startNetworking()
        watchNetwork()
        scheduleMaintenance()
        LogFolder.mirror(this)   // also picks up the lines a previous run left
        Thread { recorder.cleanupOrphans() }.apply { isDaemon = true }.start()

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
                    // A motion recording runs on while motion is seen, and ends
                    // after the quiet period once it is not.
                    if (motionOwned && recorder.recording) {
                        if (motion.active) {
                            quietUntil = System.currentTimeMillis() + motion.quietSeconds() * 1000L
                        } else if (System.currentTimeMillis() >= quietUntil) {
                            Log.i(TAG, "motion quiet; ending the recording")
                            motionOwned = false
                            recorder.stop()
                        }
                    }
                }, 1, 1, TimeUnit.SECONDS)
            }
        }
        motion.resume()
        if (motion.enabled) {
            // Monitoring survives restarts: the camera is one of the reasons
            // the service has to be doing something.
            mainHandler.post {
                if (!camera.active) camera.start()
                updateLocks(watchingSomething())
                updateNotification()
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

    /**
     * The aspect ratio of the frame after its EXIF turn: what the viewer and
     * the zone drawing actually see. 0 when the resolution is unreadable.
     */
    private fun rotatedAspect(resolution: String, orientation: Int): Double {
        val parts = resolution.split('x')
        val w = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val h = parts.getOrNull(1)?.toDoubleOrNull() ?: return 0.0
        return if (((orientation % 180) + 180) % 180 == 90) h / w else w / h
    }

    private data class Battery(val level: Int?, val charging: Boolean, val tempC: Float?)

    /**
     * Battery percentage, charging state and temperature from the sticky
     * battery broadcast: no listener, just the last state the system
     * announced. Null when the phone will not say.
     */
    private fun batteryInfo(): Battery {
        return try {
            val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?: return Battery(null, false, null)
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
            val charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
                i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ==
                android.os.BatteryManager.BATTERY_STATUS_FULL
            // The broadcast carries tenths of a degree; 0 means "no sensor".
            val tenths = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)
            val temp = if (tenths > 0) tenths / 10f else null
            Battery(pct, charging, temp)
        } catch (e: Exception) {
            Battery(null, false, null)
        }
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
        certPublicPort = Prefs.publicPort
        certAcmePort = Prefs.acmePort
        restartPublicServer()
        restartChallengeServer()
        restartMdns()
    }

    private var certPublicPort = 0
    private var certAcmePort = 0

    /** Only a port change needs a listener rebuilt; cert changes do not. */
    private fun refreshAcmeListeners() {
        if (certPublicPort != Prefs.publicPort) {
            certPublicPort = Prefs.publicPort
            restartPublicServer()
        }
        if (certAcmePort != Prefs.acmePort) {
            certAcmePort = Prefs.acmePort
            restartChallengeServer()
        }
    }

    private fun restartPublicServer() {
        publicServer?.stop()
        publicServer = HttpServer(this, hub, audio, this, Tls.publicSslContext(this))
        val port = Prefs.publicPort
        if (!publicServer!!.start(port)) {
            Log.w(TAG, "Could not bind the public HTTPS port $port")
        }
    }

    private fun restartChallengeServer() {
        if (Prefs.certDomain.isNotEmpty() && Prefs.certMethod == "http-01") {
            ChallengeServer.start(Prefs.acmePort)
        } else {
            ChallengeServer.stop()
        }
    }

    private fun restartMdns() {
        mdns?.stop()
        mdns = null
        if (!Prefs.mdnsEnabled) return
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
        val changed = ip != State.ip
        if (changed) Log.i(TAG, "address is now ${ip ?: "none"}")
        State.ip = ip
        // A new address on this hotspot almost certainly means a new public
        // one too: re-check DDNS now instead of waiting for the next tick.
        if (changed) Acme.onNetworkChanged(this)
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

    /** Anything that needs the CPU awake: viewers, sound capture or motion. */
    private fun watchingSomething(): Boolean =
        hub.count > 0 || audio.count > 0 || audio.recordingSource || motion.enabled || publicFeed.isEnabled()

    /**
     * Camera power policy: open it only while somebody is watching, and close
     * it after a short grace period once the last viewer leaves.
     */
    private fun onClientsChanged(count: Int) {
        mainHandler.post {
            if (!started) return@post
            mainHandler.removeCallbacks(idleStop)
            updateLocks(count > 0 || motion.enabled || publicFeed.isEnabled())
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
        audio.setRecordSource(on && Prefs.recordAudio && AudioHub.hasPermission(this))
        Log.i(TAG, "streaming enabled=$on")
        if (on) {
            if (hub.count > 0) camera.start()
        } else {
            hub.disconnectAll()
            audio.disconnectAll()
            mainHandler.removeCallbacks(idleStop)
            // The webcam is off, so it stops recording too; the file is
            // finished first, and the camera stays open only for motion.
            if (recorder.recording) recorder.stop()
            else if (!motion.enabled && !publicFeed.isEnabled()) closeCamera()
        }
        updateLocks(watchingSomething())
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
        audio.shutdown()
        publicFeed.shutdown()
        mdns?.stop()
        mdns = null
        unwatchNetwork()
        cancelMaintenance()
        server.stop()
        publicServer?.stop()
        publicServer = null
        ChallengeServer.stop()
        Acme.stop()
        // A running session must end with the service, or its file is left
        // unfinishable and the next start deletes it.
        recorder.stopBlocking(3000)
        closeCamera()
        releaseLocks()
        scheduler?.shutdownNow()
        scheduler = null
        try { stopForeground(true) } catch (_: Exception) {}
    }

    // ---- HttpServer.Host ----------------------------------------------

    override fun statusJson(): MutableMap<String, Any?> {
        val caps = camera.capabilities()
        val sz = camera.currentSize
        val battery = batteryInfo()
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
            "streamScale" to Prefs.streamScale,
            "torch" to Prefs.torch,
            "battery" to battery.level,
            "charging" to battery.charging,
            "batteryTemp" to battery.tempC,
                        "zoom" to Prefs.zoom,
            "orientation" to Prefs.orientation,
            "clients" to hub.count,
            "frames" to hub.frames,
            // Live sound: the page offers a Sound button only when this is
            // turned on, and says why if the mic is unavailable.
            "audio" to mapOf(
                "enabled" to Prefs.audioEnabled,
                "available" to AudioHub.hasPermission(this),
                "listening" to audio.count,
                "capturing" to audio.capturing,
                "recordAudio" to Prefs.recordAudio,
                "recordingSource" to audio.recordingSource,
                "ring" to audio.ring.size,
                "error" to audio.error,
            ),
            "width" to sz.width,
            "height" to sz.height,
            "streaming" to streamingEnabled,
            "cameraActive" to camera.active,
            // The recording pane shows its details from /api/record/list; the
            // status carries the state so the banner can announce a session
            // even with the pane closed.
            "recording" to recorder.recording,
            "recStart" to if (recorder.recording) recorder.recStartMs else null,
            "recNote" to recorder.lastNote,
            // Motion state for the banner and the pane: how many zones are
            // set and what aspect they were drawn for, so a resolution change
            // can warn before it invalidates them.
            "motion" to motion.enabled,
            "motionDetector" to motion.detectorName,
            "motionPreview" to motion.previewing(),
            "motionZones" to motion.zoneCount(),
            "motionAspect" to motion.zoneAspect(),
            "preRoll" to Prefs.motionPreRoll,
            // Latest analysis-tick numbers, for tuning from a distance.
            "motionDiag" to motion.diagnostics(),
            "autoStartStreaming" to Prefs.autoStartStreaming,
            "mdnsEnabled" to Prefs.mdnsEnabled,
            "publicFeed" to mapOf(
                "url" to Prefs.publicFeedUrl,
                "token" to Prefs.publicFeedToken,
                "viewerPassword" to Prefs.viewerPassword,
                "enabled" to publicFeed.isEnabled(),
                "connected" to publicFeed.isConnected(),
                "id" to publicFeed.cameraId(),
                "error" to publicFeed.error(),
            ),
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
            "cert" to Acme.status(this),
        )
    }

    override fun applySettings(vals: Map<String, String>) {
        var restartCamera = false
        var restartNet = false
        var restartMdns = false
        val oldResolution = Prefs.resolution
        val oldOrientation = Prefs.orientation
        for ((k, v) in vals) {
            when (k) {
                "facing" -> if (Prefs.facing != v) { Prefs.facing = v; restartCamera = true }
                "resolution" -> if (Prefs.resolution != v) { Prefs.resolution = v; restartCamera = true }
                "fps" -> v.toIntOrNull()?.let { Prefs.fps = it }
                "quality" -> v.toIntOrNull()?.let { Prefs.quality = it }
                "streamScale" -> v.toIntOrNull()?.let { Prefs.streamScale = it }
                "torch" -> Prefs.torch = v.toBoolean()
                "zoom" -> v.toFloatOrNull()?.let { Prefs.zoom = it }
                "orientation" -> v.toIntOrNull()?.let { Prefs.orientation = it }
                "anonView" -> Prefs.anonView = v.toBoolean()
                "autoStartStreaming" -> Prefs.autoStartStreaming = v.toBoolean()
                "streaming" -> setStreaming(v.toBoolean())
                "serviceName" -> if (Prefs.serviceName != v.lowercase()) { Prefs.serviceName = v; restartNet = true }
                "port" -> v.toIntOrNull()?.let { if (it != Prefs.port) { Prefs.port = it; restartNet = true } }
                "adminPath" -> Prefs.adminPath = v
                "motionDetector" -> motion.selectDetector(v)
                "motionLightGate" -> motion.setLightGate(v.toBoolean())
                "mdnsEnabled" -> if (Prefs.mdnsEnabled != v.toBoolean()) {
                    Prefs.mdnsEnabled = v.toBoolean(); restartMdns = true
                }
                "audioEnabled" -> {
                    Prefs.audioEnabled = v.toBoolean()
                    if (!Prefs.audioEnabled) audio.disconnectAll()
                }
                "recordAudio" -> {
                    Prefs.recordAudio = v.toBoolean()
                    audio.setRecordSource(streamingEnabled && Prefs.recordAudio && AudioHub.hasPermission(this))
                }
                "publicFeedUrl" -> {
                    Prefs.publicFeedUrl = v
                    publicFeed.configure(v, Prefs.publicFeedToken, Prefs.viewerPassword)
                }
                "publicFeedToken" -> {
                    Prefs.publicFeedToken = v
                    publicFeed.configure(Prefs.publicFeedUrl, v, Prefs.viewerPassword)
                }
                "viewerPassword" -> {
                    Prefs.viewerPassword = v
                    publicFeed.configure(Prefs.publicFeedUrl, Prefs.publicFeedToken, v)
                }
            }
        }
        // Zones are drawn in the displayed frame's coordinates. A change that
        // keeps the aspect ratio leaves them valid; anything else invalidates
        // them, so they go - the page warns first.
        if ((restartCamera || oldOrientation != Prefs.orientation) && motion.zoneCount() > 0) {
            val oldAspect = rotatedAspect(oldResolution, oldOrientation)
            val newAspect = rotatedAspect(Prefs.resolution, Prefs.orientation)
            if (oldAspect != 0.0 && newAspect != 0.0 &&
                Math.abs(oldAspect - newAspect) / Math.max(oldAspect, newAspect) > 0.01
            ) {
                motion.clearZones()
                Log.i(TAG, "zones cleared: the aspect ratio changed")
            }
        }
        if (restartCamera) {
            if (streamingEnabled && hub.count > 0) camera.start() else camera.applyLive()
        } else {
            camera.applyLive()
        }
        configureRing()
        if (restartMdns) restartMdns()
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
     * newest full-resolution frame is reused; the stream may be downscaled,
     * so the hub's copy is not necessarily full size.
     */
    override fun snapshot(): ByteArray? {
        if (camera.active) lastFull.get()?.let { return it }

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
        // unless somebody started watching or a recording is running.
        mainHandler.post {
            if (started && hub.count == 0 && !recorder.recording) closeCamera()
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

    // ---- recording -----------------------------------------------------

    override fun startRecording(): String? {
        if (recorder.recording) return null
        // Recording is part of the webcam being on: with streaming disabled
        // the phone is meant to be idle, so it does not open the camera for
        // a file either.
        if (!streamingEnabled) return "Streaming is off. Turn it on to record."
        preRollBlock()?.let { return it }
        // Frames come from the camera; a session that starts with it closed
        // would just fill the file with silence, so open it first. Opening an
        // already-running camera tears its session down and reopens it, which
        // leaves a visible gap right at the pre-roll/live boundary.
        if (!camera.active) camera.start()
        return recorder.start(camera.currentSize.width, camera.currentSize.height)
    }

    override fun stopRecording() = recorder.stop()

    override fun setMotion(on: Boolean): String? {
        val err = motion.setEnabled(on)
        if (err == null) {
            mainHandler.post {
                if (motion.enabled && !camera.active) camera.start()
                updateLocks(watchingSomething())
                updateNotification()
            }
        }
        return err
    }

    /** Human-readable megabytes, for the pre-roll warning. */
    private fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)

    /**
     * How much memory the current pre-roll would need, and whether it fits.
     * Based on the observed JPEG size and frame rate, not on a resolution
     * guess: scene complexity changes JPEG size a lot.
     */
    fun preRollEstimate(): Map<String, Any?> {
        val secs = Prefs.motionPreRoll
        val fps = Prefs.fps.coerceAtLeast(1)
        val avg = camera.avgFrameBytes
        val bytes = if (secs == 0 || avg <= 0L) 0L else avg * fps * secs.toLong()
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val headroom = (rt.maxMemory() - used).coerceAtLeast(0L)
        val tooHigh = bytes > 0L &&
            (bytes > headroom * 0.6 || bytes > info.availMem * 0.6)
        val message = when {
            secs == 0 -> "Pre-roll is off; recordings start at the trigger."
            avg <= 0L -> "Measuring frame sizes..."
            tooHigh -> "Warning: ${secs} s at ${fps} fps will use ${mb(bytes)} " +
                "(leaving ${mb((minOf(headroom, info.availMem) - bytes).coerceAtLeast(0))} free). " +
                "Reduce resolution or pre-roll."
            else -> "About ${mb(bytes)} for ${secs} s at ${fps} fps."
        }
        return mapOf(
            "seconds" to secs,
            "fps" to fps,
            "avgFrame" to avg,
            "bytes" to bytes,
            "headroom" to headroom,
            "systemAvail" to info.availMem,
            "maxHeap" to rt.maxMemory(),
            "memoryClass" to (am?.memoryClass ?: 0),
            "largeMemoryClass" to (am?.largeMemoryClass ?: 0),
            "tooHigh" to tooHigh,
            "message" to message,
        )
    }

    /** Size both rings from the setting: seconds x rate, nothing estimated. */
    private fun configureRing() {
        val secs = Prefs.motionPreRoll
        val frames = (secs * Prefs.fps).coerceAtLeast(1)
        val chunks = (secs * AudioHub.CHUNKS_PER_SEC).coerceAtLeast(1)
        ring.configure(secs * 1000L, frames)
        audio.configureRing(secs * 1000L, chunks)
    }

    /** Refuse a start that would ask for more pre-roll memory than we have. */
    private fun preRollBlock(): String? {
        val est = preRollEstimate()
        return if (est["tooHigh"] == true) est["message"] as String else null
    }

    /**
     * The motion pane's poll doubles as the preview lease. Opening the pane
     * starts the analyser and the camera even when motion is disarmed, so the
     * object boxes can be drawn; leaving it lets both go within ten seconds.
     */
    override fun motionInfo(since: Long): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val wasPreviewing = motion.previewing()
        motion.leasePreview()
        mainHandler.removeCallbacks(previewTimeout)
        mainHandler.postDelayed(previewTimeout, 11_000L)
        if (!wasPreviewing) {
            mainHandler.post {
                if (started && !camera.active) camera.start()
                updateLocks(true)
            }
        }
        val zones = motion.snapshotSince(since).map {
            mapOf(
                "id" to it.id,
                "sensitivity" to it.sensitivity,
                "max" to it.max,
                "level" to it.level,
                "peakSince" to it.peakSince,
                "points" to it.points.map { p -> listOf(p.first, p.second) },
            )
        }
        return mapOf(
            "enabled" to motion.enabled,
            "quiet" to motion.quietSeconds(),
            "preRoll" to Prefs.motionPreRoll,
            "preRollEstimate" to preRollEstimate(),
            "level" to motion.triggerLevel(),
            "grid" to motion.gridSize(),
            "detector" to motion.detectorName,
            "detectorError" to motion.detectorError,
            "lightGate" to motion.lightGate(),
            "previewing" to motion.previewing(),
            "boxes" to motion.boxSnapshot(),
            "aspect" to motion.zoneAspect(),
            "zones" to zones,
            "events" to motion.eventLog(),
            "now" to now,
        )
    }

    override fun saveMotionZones(zones: String): String? = motion.setZones(zones)

    override fun setMotionQuiet(seconds: Int) {
        motion.setQuiet(seconds)
    }

    override fun setMotionPreRoll(seconds: Int) {
        Prefs.motionPreRoll = seconds
        configureRing()
    }

    override fun setMotionLevel(level: Int) {
        motion.setTriggerLevel(level)
    }

    override fun setMotionGrid(cells: Int) {
        motion.setGrid(cells)
    }

    override fun setMotionPreview(on: Boolean) {
        if (!on) {
            motion.stopPreview()
            mainHandler.removeCallbacks(previewTimeout)
            mainHandler.post { previewTimeout.run() }
        }
    }

    override fun setMotionDetector(name: String, lightGate: Boolean?): String? {
        val err = motion.selectDetector(name)
        if (lightGate != null) motion.setLightGate(lightGate)
        return err
    }

    override fun setRecordingCap(percent: Int): String? {
        if (percent !in 1..100) return "the cap is between 1 and 100 percent"
        Prefs.videoCapPercent = percent
        return null
    }

    override fun recordingInfo(): Map<String, Any?> = recorder.list()

    override fun deleteRecording(name: String): String? = recorder.delete(name)

    override fun deleteAllRecordings(): String? = recorder.deleteAll()

    override fun videoFile(name: String): Recorder.File? = recorder.videoFile(name)

    /**
     * The phone's "Restart service" button, from the web: a fresh pass
     * through the startup, which re-registers the foreground service and
     * clears states like a camera the framework has stopped trusting.
     */
    override fun restartService() = CamService.start(this)

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
        } + if (motion.enabled) " - motion on" else ""
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
            // Declare the microphone type only once the permission exists: on
            // Android 14 starting a microphone foreground service without it
            // throws. Audio capture is unavailable until the permission is
            // granted, and the service is restarted when it is.
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (Build.VERSION.SDK_INT >= 30 && AudioHub.hasPermission(this)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, n, type)
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
