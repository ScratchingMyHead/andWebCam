package net.andwebcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Live microphone audio for the web page.
 *
 * The stream is 16 kHz mono PCM16 with no container: the browser reads the
 * bytes and plays them with its AudioContext, so there is no codec to
 * negotiate and the latency is only the network's. [CHUNK_BYTES] is 20 ms, a
 * unit the browser can queue directly.
 *
 * The microphone is open only while somebody is listening. The first /audio
 * request opens it, and it closes a few seconds after the last listener
 * leaves, so the app does not sit on the mic with nobody there. Each listener
 * has a small queue and loses the oldest sound when it falls behind, which
 * keeps a slow link from building up minutes of delay.
 */
class AudioHub(private val ctx: Context) {
    companion object {
        private const val TAG = "andWebCam"

        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1

        /** 20 ms of mono PCM16. */
        const val CHUNK_BYTES = SAMPLE_RATE / 50 * 2

        /** Queued sound per listener; 25 chunks is half a second. */
        private const val MAX_QUEUED = 25

        /** Keep the mic open this long after the last listener leaves. */
        private const val STOP_GRACE_MS = 3000L

        fun hasPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** onClose lets the hub forget a client that died on its own. */
    class Client(
        val out: OutputStream,
        val token: String? = null,
        private val onClose: (Client) -> Unit = {},
    ) {
        val done = CountDownLatch(1)

        private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED)
        private val thread = Thread({ pump() }, "andwebcam-audio").apply { isDaemon = true }

        @Volatile
        private var closed = false

        fun start() {
            thread.start()
        }

        /** Hand over the newest sound; old sound is dropped if it is behind. */
        fun offer(pcm: ByteArray) {
            if (closed) return
            if (!queue.offer(pcm)) {
                queue.poll()
                queue.offer(pcm)
            }
        }

        private fun pump() {
            while (true) {
                if (closed) return
                val chunk = try {
                    queue.poll(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    continue
                } ?: continue
                if (closed) return
                try {
                    out.write(chunk)
                    out.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "audio write failed: ${e.message}")
                    close()
                    return
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try { out.close() } catch (_: Exception) {}
            done.countDown()
            onClose(this)
        }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val chunkCount = AtomicLong()

    /** Why the microphone could not be opened, or why it stopped. */
    @Volatile
    var error: String? = null
        private set

    @Volatile
    private var capture: AudioRecord? = null
    @Volatile
    private var captureThread: Thread? = null

    /** When the mic should close because nobody is listening; 0 = not yet. */
    @Volatile
    private var stopAt = 0L

    val count: Int get() = clients.size
    val chunks: Long get() = chunkCount.get()
    val capturing: Boolean get() = captureThread != null

    /** Notified with the number of listeners whenever it changes. */
    @Volatile
    var onClientsChanged: ((Int) -> Unit)? = null

    fun add(out: OutputStream, token: String? = null): Client {
        if (!token.isNullOrEmpty()) {
            // The page can reconnect while its previous request is still
            // closing; one tab should never hold two listeners.
            for (old in clients.toList()) {
                if (old.token == token) remove(old)
            }
        }
        stopAt = 0L
        val c = Client(out, token, { client -> remove(client) })
        clients.add(c)
        Log.i(TAG, "audio listener connected (${clients.size} total)")
        if (clients.size == 1) ensureCapture()
        c.start()
        onClientsChanged?.invoke(clients.size)
        return c
    }

    fun remove(c: Client) {
        if (clients.remove(c)) {
            c.close()
            if (clients.isEmpty()) stopAt = System.currentTimeMillis() + STOP_GRACE_MS
            Log.i(TAG, "audio listener left (${clients.size} left)")
            onClientsChanged?.invoke(clients.size)
        }
    }

    /** Drop every listener, e.g. when streaming is stopped. */
    fun disconnectAll() {
        for (c in clients.toList()) remove(c)
    }

    fun shutdown() {
        disconnectAll()
        stopAt = System.currentTimeMillis()
    }

    /**
     * Open the microphone unless it is already open. Returns null on success,
     * or a sentence for the page explaining why there is no sound.
     */
    fun ensureCapture(): String? = synchronized(this) {
        if (captureThread != null) return@synchronized null
        if (!hasPermission(ctx)) {
            error = "microphone permission is not granted"
            return@synchronized error
        }
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) {
            error = "the microphone cannot be opened on this device"
            return@synchronized error
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK_BYTES * 8),
            )
        } catch (e: Exception) {
            error = "the microphone cannot be opened: ${e.message}"
            return@synchronized error
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Exception) {}
            error = "the microphone is busy or unavailable"
            return@synchronized error
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            try { rec.release() } catch (_: Exception) {}
            error = "the microphone could not start: ${e.message}"
            return@synchronized error
        }
        capture = rec
        error = null
        stopAt = 0L
        captureThread = Thread({ recordLoop(rec) }, "andwebcam-mic").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "microphone opened for audio streaming")
        null
    }

    private fun recordLoop(rec: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        try {
            while (true) {
                val now = System.currentTimeMillis()
                if (clients.isEmpty()) {
                    // Either nobody has ever listened (stopAt == 0, which
                    // closes the mic as soon as it is opened) or the grace
                    // period after the last listener has run out.
                    if (stopAt == 0L || now >= stopAt) break
                }
                val n = rec.read(buf, 0, buf.size)
                if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                    error = "the microphone was taken by another app"
                    break
                }
                if (n < 0) {
                    // A transient error must not turn into a busy loop.
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }
                if (n == 0) continue
                chunkCount.incrementAndGet()
                val copy = buf.copyOf(n)
                for (c in clients) c.offer(copy)
            }
        } catch (e: Exception) {
            error = "the microphone stopped: ${e.message}"
            Log.w(TAG, "audio capture failed: ${e.message}")
        } finally {
            synchronized(this) {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                if (capture === rec) capture = null
                captureThread = null
            }
            Log.i(TAG, "microphone closed")
            onClientsChanged?.invoke(clients.size)
        }
    }
}
