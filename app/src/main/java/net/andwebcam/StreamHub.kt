package net.andwebcam

import android.util.Log
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Fan-out of MJPEG frames to connected viewers.
 *
 * Each viewer gets its own writer thread and only ever holds the newest frame,
 * so a slow client skips frames instead of holding up the camera. Writing from
 * the camera thread instead looked fine with ffmpeg, but a client that read
 * slowly blocked it for up to 800 ms a frame and dragged production down with
 * it.
 *
 * A dead client is dropped after a few consecutive write errors.
 */
class StreamHub {
    companion object {
        const val BOUNDARY = "andwebcamframe"
    }

    class Client(val out: OutputStream) {
        val done = CountDownLatch(1)

        private val lock = Object()
        private var frame: ByteArray? = null
        private var seq = 0L
        private var sent = -1L
        private var closed = false
        private var failures = 0
        private var thread: Thread? = null

        /** Start delivering to this client. The latest frame, if any, goes first. */
        fun start() {
            thread = Thread({ pump() }, "andwebcam-viewer").apply {
                isDaemon = true
                start()
            }
        }

        /** Hand over the newest frame; the writer thread sends it when it can. */
        fun offer(jpeg: ByteArray) {
            synchronized(lock) {
                frame = jpeg
                seq++
                lock.notifyAll()
            }
        }

        private fun pump() {
            while (true) {
                val jpeg: ByteArray
                synchronized(lock) {
                    while (!closed && seq == sent) {
                        try {
                            lock.wait(1000)
                        } catch (_: InterruptedException) {
                        }
                    }
                    if (closed) return
                    jpeg = frame ?: return
                    sent = seq
                }
                if (writePart(jpeg)) {
                    synchronized(lock) { failures = 0 }
                } else {
                    val dead = synchronized(lock) { ++failures >= 3 }
                    if (dead) {
                        Log.w("andWebCam", "viewer dropped after $failures failed writes")
                        close()
                        return
                    }
                }
            }
        }

        private fun writePart(jpeg: ByteArray): Boolean = synchronized(this) {
            try {
                val header = "--$BOUNDARY\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpeg.size}\r\n\r\n"
                out.write(header.toByteArray())
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
                true
            } catch (e: Exception) {
                Log.w("andWebCam", "viewer write failed: ${e.message}")
                false
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
            try { out.close() } catch (_: Exception) {}
            done.countDown()
        }

        /** True once the writer thread has stopped (the client was dropped). */
        val dropped: Boolean get() = done.count == 0L
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
        latest?.let { c.offer(it) }
        c.start()
        State.clients = clients.size
        onClientsChanged?.invoke(clients.size)
        return c
    }

    fun remove(c: Client) {
        if (clients.remove(c)) {
            c.close()
            State.clients = clients.size
            Log.i("andWebCam", "viewer left (${clients.size} left)")
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
            if (c.dropped) {
                remove(c)
            } else {
                c.offer(jpeg)
            }
        }
    }
}
