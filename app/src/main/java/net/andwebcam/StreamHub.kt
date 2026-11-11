package net.andwebcam

import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Fan-out of MJPEG frames to connected viewers.
 *
 * The camera thread calls [broadcast] with each JPEG; every registered client
 * gets it packaged as a multipart/x-mixed-replace part. A dead client is
 * dropped after a few consecutive write errors instead of stalling the camera.
 */
class StreamHub {
    companion object {
        const val BOUNDARY = "andwebcamframe"
    }

    class Client(val out: OutputStream) {
        val done = CountDownLatch(1)

        /** Consecutive failed writes; a client is dropped after a few. */
        var failures = 0
        private val lock = Any()

        fun writePart(jpeg: ByteArray): Boolean {
            synchronized(lock) {
                return try {
                    val header = "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${jpeg.size}\r\n\r\n"
                    out.write(header.toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    true
                } catch (e: Exception) {
                    android.util.Log.w("andWebCam", "viewer write failed: ${e.message}")
                    false
                }
            }
        }

        fun close() {
            try { out.close() } catch (_: Exception) {}
            done.countDown()
        }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val frameCount = AtomicLong()

    /** Notified with the number of connected viewers whenever it changes. */
    @Volatile
    var onClientsChanged: ((Int) -> Unit)? = null

    @Volatile
    var latest: ByteArray? = null
        private set

    val count: Int get() = clients.size
    val frames: Long get() = frameCount.get()

    fun add(out: OutputStream): Client {
        val c = Client(out)
        clients.add(c)
        State.clients = clients.size
        latest?.let { c.writePart(it) }
        onClientsChanged?.invoke(clients.size)
        return c
    }

    fun remove(c: Client) {
        if (clients.remove(c)) {
            c.close()
            State.clients = clients.size
            android.util.Log.i("andWebCam", "viewer left (${clients.size} left)")
            onClientsChanged?.invoke(clients.size)
        }
    }

    /** Drop every viewer, e.g. when streaming is stopped. */
    fun disconnectAll() {
        for (c in clients.toList()) remove(c)
    }

    fun broadcast(jpeg: ByteArray) {
        latest = jpeg
        frameCount.incrementAndGet()
        if (clients.isEmpty()) return
        for (c in clients) {
            if (c.writePart(jpeg)) {
                c.failures = 0
            } else if (++c.failures >= 3) {
                remove(c)
            }
        }
    }
}
