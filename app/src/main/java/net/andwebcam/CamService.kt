package net.andwebcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
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
        private const val CH_ID = "andwebcam"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

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
    private var scheduler: ScheduledExecutorService? = null
    private var lastFrameCount = 0L
    private var started = false

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
            onError = { msg -> State.lastError = msg; Log.w(TAG, msg) },
        )
        server = HttpServer(this, hub, this)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                camera.start()
                return START_STICKY
            }
            else -> startEverything()
        }
        return START_STICKY
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
        acquireLocks()
        State.serviceName = Prefs.serviceName
        State.port = Prefs.port
        State.ip = localIp()

        startNetworking()

        if (camera.hasPermission()) {
            camera.start()
        } else {
            State.lastError = "Camera permission not granted (run: adb shell pm grant net.andwebcam android.permission.CAMERA)"
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
    }

    private fun startNetworking() {
        State.port = Prefs.port
        State.serviceName = Prefs.serviceName
        val ok = server.start(Prefs.port)
        if (!ok) {
            State.lastError = "Could not bind port ${Prefs.port}"
        }
        mdns?.stop()
        mdns = Mdns(this, Prefs.serviceName, Prefs.port).also { it.start() }
    }

    private fun scheduleNetRestart() {
        Thread {
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            startNetworking()
            updateNotification()
        }.start()
    }

    private fun stopEverything() {
        State.running = false
        started = false
        mdns?.stop()
        mdns = null
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
            "lastError" to State.lastError,
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
                "startOnBoot" -> Prefs.startOnBoot = v.toBoolean()
                "serviceName" -> if (Prefs.serviceName != v.lowercase()) { Prefs.serviceName = v; restartNet = true }
                "port" -> v.toIntOrNull()?.let { if (it != Prefs.port) { Prefs.port = it; restartNet = true } }
            }
        }
        if (restartCamera) camera.start() else camera.applyLive()
        if (restartNet) scheduleNetRestart()
        updateNotification()
    }

    override fun changePassword(newPass: String) {
        Prefs.setAdminPassword(newPass)
        updateNotification()
    }

    // ---- locks / notification -----------------------------------------

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andwebcam:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (multicastLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wm.createMulticastLock("andwebcam").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
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
        return Notification.Builder(this, CH_ID)
            .setContentTitle("andWebCam")
            .setContentText("Streaming at ${State.url}")
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
