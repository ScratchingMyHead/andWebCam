package net.andwebcam

/**
 * Process-wide, read-only-by-the-UI snapshot of what the service is doing.
 * Written with @Volatile fields so the Activity can poll it without locks.
 */
object State {
    @Volatile var running: Boolean = false
    @Volatile var ip: String? = null
    @Volatile var port: Int = 8443
    @Volatile var serviceName: String = "andwebcam"
    @Volatile var lastError: String? = null
    @Volatile var clients: Int = 0
    @Volatile var frames: Long = 0
    @Volatile var framesPerSec: Int = 0

    val url: String get() = "https://$serviceName.local:$port/"
}
