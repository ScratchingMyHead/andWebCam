package net.andwebcam

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Downscales JPEG frames for viewers, leaving the camera thread alone.
 *
 * The recorder, the pre-roll ring and motion detection all keep the original
 * full-resolution frame. Only the stream to browsers gets a smaller JPEG,
 * and only while somebody is actually watching. The worker keeps just the
 * newest pending frame, so a slow decoder drops frames rather than holding
 * up the camera.
 */
class StreamScaler(
    private val broadcast: (ByteArray) -> Unit,
    private val hasViewers: () -> Boolean,
) {
    private data class Pending(val jpeg: ByteArray, val scale: Int, val quality: Int)

    private val lock = Any()
    private var pending: Pending? = null

    private val thread = Thread({ loop() }, "andwebcam-stream-scaler").apply {
        isDaemon = true
        start()
    }

    /** Hand over a camera frame; full scale or nobody watching means no work. */
    fun offer(jpeg: ByteArray, scale: Int, quality: Int) {
        if (scale >= 100 || !hasViewers()) {
            broadcast(jpeg)
            return
        }
        synchronized(lock) {
            pending = Pending(jpeg, scale, quality)
            (lock as java.lang.Object).notifyAll()
        }
    }

    private fun loop() {
        while (true) {
            val p: Pending?
            synchronized(lock) {
                while (pending == null) {
                    try {
                        (lock as java.lang.Object).wait(1000)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                p = pending
                pending = null
            }
            val frame = p ?: continue
            val out = try {
                shrink(frame)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "stream downscale out of memory; sending the full frame")
                frame.jpeg
            } catch (e: Exception) {
                Log.w(TAG, "stream downscale failed: ${e.message}")
                frame.jpeg
            }
            broadcast(out)
        }
    }

    private fun shrink(p: Pending): ByteArray {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(p.jpeg))
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = Math.max(1, info.size.width * p.scale / 100)
            val h = Math.max(1, info.size.height * p.scale / 100)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetSize(w, h)
        }
        return try {
            val out = ByteArrayOutputStream(Math.max(1024, bmp.byteCount / 4))
            bmp.compress(Bitmap.CompressFormat.JPEG, p.quality.coerceIn(1, 100), out)
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        private const val TAG = "andWebCam"
    }
}
