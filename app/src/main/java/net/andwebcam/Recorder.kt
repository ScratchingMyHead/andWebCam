package net.andwebcam

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Manual video recording: the last 10 seconds of the stream (the VideoRing)
 * plus everything after the button press, encoded to one MP4 per session in
 * a videos/ subfolder of the chosen log folder.
 *
 * Pipeline: JPEG -> Bitmap -> NV12 -> H.264 (MediaCodec) -> MediaMuxer.
 * The muxer needs an OS file descriptor, which SAF offers through
 * openFileDescriptor(); the file is created under a temporary name and
 * renamed when the session ends, so a crash mid-recording leaves only a
 * throwaway that the next service start deletes.
 *
 * The session stops itself, oldest recordings first, when the total would
 * cross the storage cap set on the page; the cap is a percentage of the
 * volume's free space, with a small margin so the last write cannot
 * overshoot it.
 *
 * Everything that touches the encoder, the file or the SAF tree happens on
 * one worker thread; other threads only ask it to.
 */
class Recorder(private val ctx: Context) {
    companion object {
        private const val TAG = "andWebCam"
        const val VIDEOS_DIR = "videos"

        /** Stop at this fraction of the cap, so the last write fits. */
        private const val STOP_MARGIN = 0.95

        private const val MIME_AVC = "video/avc"

        // The SDK stub of this build does not carry MUXER_TYPE_MPEG_4,
        // whose value is 0 (the other container types follow).
        private const val MUXER_MPEG_4 = 0
    }

    @Volatile var recording = false
        private set

    /** A short note about the latest start or stop, shown on the page. */
    @Volatile var lastNote: String? = null

    /** The temporary file name while recording, null otherwise. */
    @Volatile var tempFile: String? = null

    /** The ring the sessions carry forward; set once by the service. */
    @Volatile var ringRef: VideoRing? = null

    /** Invoked on the worker thread when a session has finished, whatever its outcome. */
    @Volatile var onStopped: (() -> Unit)? = null

    private val cr: ContentResolver get() = ctx.contentResolver
    private val frameQueue = LinkedBlockingQueue<VideoRing.Frame>(128)
    private val jobs = LinkedBlockingQueue<() -> Unit>()

    private val worker = Thread({
        while (true) {
            val job = jobs.poll(2, TimeUnit.SECONDS) ?: continue
            try {
                job()
            } catch (e: Exception) {
                Log.w(TAG, "recorder: ${e.message}")
            }
        }
    }, "andwebcam-recorder").apply { isDaemon = true }

    init {
        worker.start()
    }

    // starting and stopRequested are also written from other threads; the
    // rest is worker-thread-only: a session's pieces and its running totals.
    @Volatile private var starting = false
    @Volatile private var stopRequested = false
    private var stopForCap = false
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackId = -1
    private var pfd: ParcelFileDescriptor? = null
    private var doc: Uri? = null
    private var dir: Uri? = null
    private var tree: Uri? = null
    private var startTs = ""
    private var t0Ms = 0L
    private var lastPtsUs = -1L
    private var sentAnyInput = false
    private var writtenBytes = 0L
    private var framesFed = 0L
    /** The finished name of the file the last session saved, if it did. */
    private var savedName: String? = null
    private var capBytes: Long? = null
    private var existingBytes = 0L
    private var lastCapCheck = 0L
    private var ringFrames = emptyList<VideoRing.Frame>()
    private var ringIdx = 0
    private var pixels = IntArray(0)
    private var yBuf = ByteArray(0)
    private var uvBuf = ByteArray(0)

    /** Durations already read, keyed by "name:size" so a rewritten file re-reads. */
    private val durationCache = ConcurrentHashMap<String, Long>()

    // ---- public API (any thread) -----------------------------------------

    /**
     * Begin a session. [w]x[h] is the camera size at that moment. Returns
     * null when it was accepted (it starts asynchronously on the worker) or
     * a sentence saying why not.
     */
    fun start(w: Int, h: Int): String? {
        synchronized(this) {
            if (recording || starting) return null
            starting = true
        }
        jobs.offer { doStart(w, h) }
        return null
    }

    /** Ask the session to end; the file is finalized on the worker thread. */
    fun stop() {
        stopRequested = true
    }

    /** Stop and wait for the file to be finalized; for shutting the service down. */
    fun stopBlocking(timeoutMs: Long) {
        stop()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!recording && !starting) return
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return
            }
        }
        Log.w(TAG, "recording still running after ${timeoutMs}ms; it will end on its own")
    }

    /** The stream's frame path feeds this; a no-op while no session runs. */
    fun push(jpeg: ByteArray) {
        if (!recording && !starting) return
        val f = VideoRing.Frame(jpeg, System.currentTimeMillis())
        if (!frameQueue.offer(f)) {
            // The encoder fell behind: drop the oldest frame, keep the newest.
            frameQueue.poll()
            frameQueue.offer(f)
        }
    }

    /**
     * Delete the temporary files a crash mid-recording left behind. Called
     * once at service start, when no session can be running.
     */
    fun cleanupOrphans() {
        val t = Saf.tree ?: return
        val d = Saf.findDoc(cr, t, t, VIDEOS_DIR) ?: return
        for (c in Saf.listChildren(cr, t, d)) {
            if (c.name.endsWith(".tmp.mp4") && Saf.deleteDoc(cr, c.doc)) {
                Log.i(TAG, "removed the recording ${c.name} an interrupted session left behind")
            }
        }
    }

    /**
     * The pane's data: the storage figures and the finished videos.
     * free and cap are null when the volume's space cannot be determined
     * (a folder on removable storage); the figures are then only estimates.
     */
    fun list(): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        out["recording"] = recording
        out["note"] = lastNote
        out["capPercent"] = Prefs.videoCapPercent
        val t = Saf.tree
        if (t == null) {
            out["free"] = null
            out["used"] = 0L
            out["cap"] = null
            out["videos"] = emptyList<Any>()
            return out
        }
        val free = Saf.freeBytes(t)
        val cap = (free?.let { (it * Prefs.videoCapPercent) / 100L })?.takeIf { it > 0L }
        val d = Saf.findDoc(cr, t, t, VIDEOS_DIR)
        val kids = if (d != null) Saf.listChildren(cr, t, d) else emptyList()
        val videos = kids
            .filter { it.name.endsWith(".mp4") && !it.name.endsWith(".tmp.mp4") }
            .sortedBy { it.name }
            .map { c ->
                mapOf(
                    "name" to c.name,
                    "size" to c.size,
                    "modified" to c.modifiedMs,
                    "seconds" to durationOf(t, d!!, c),
                )
            }
        out["free"] = free
        out["used"] = kids.sumOf { it.size }
        out["cap"] = cap
        out["videos"] = videos
        return out
    }

    /** Delete a finished video by name. Only names the app itself makes. */
    fun delete(name: String): String? {
        if (!name.startsWith("video-") || !name.endsWith(".mp4")) return "no such video"
        val t = Saf.tree ?: return "no folder has been chosen"
        val d = Saf.findDoc(cr, t, t, VIDEOS_DIR) ?: return "no videos folder"
        val doc = Saf.findDoc(cr, t, d, name) ?: return "no such video"
        return if (Saf.deleteDoc(cr, doc)) {
            Log.i(TAG, "deleted video $name")
            null
        } else {
            "could not delete $name"
        }
    }

    /** A finished video, for playback through the server. */
    class File(val name: String, val size: Long, private val opener: () -> InputStream?) {
        fun open(): InputStream? = try {
            opener()
        } catch (e: Exception) {
            Log.w(TAG, "cannot open $name for playback: ${e.message}")
            null
        }
    }

    fun videoFile(name: String): File? {
        if (!name.startsWith("video-") || !name.endsWith(".mp4")) return null
        val t = Saf.tree ?: return null
        val d = Saf.findDoc(cr, t, t, VIDEOS_DIR) ?: return null
        val c = Saf.listChildren(cr, t, d).firstOrNull { it.name == name } ?: return null
        return File(c.name, c.size) { cr.openInputStream(c.doc) }
    }

    // ---- worker ------------------------------------------------------------

    /**
     * Runs on the worker. [starting] is cleared by the time this returns,
     // unless a session has taken over - so a failed start never blocks the
     * next one, and a second start request cannot sneak in behind the first.
     */
    private fun doStart(w: Int, h: Int) {
        if (recording) return
        try {
            beginSession(w, h)
        } finally {
            if (!recording) starting = false
        }
    }

    private fun beginSession(w: Int, h: Int) {
        val t = Saf.tree
        if (t == null) {
            note("Choose a log folder in the settings pane first: recordings are stored in its videos subfolder.")
            return
        }
        if (w < 32 || h < 32) {
            note("The camera size is not usable yet; try again in a moment.")
            return
        }
        val d = Saf.ensureDir(cr, t, VIDEOS_DIR)
        if (d == null) {
            note("The videos folder could not be opened in ${Saf.describe(ctx) ?: "the log folder"}.")
            return
        }

        // Room for this session before it writes a byte: the oldest finished
        // videos go first, and the cap margin is left for this one.
        val free = Saf.freeBytes(t)
        capBytes = (free?.let { (it * Prefs.videoCapPercent) / 100L })?.takeIf { it > 0L }
        existingBytes = prune(d, t, capBytes, 0L)

        startTs = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val tempName = "rec-$startTs.tmp.mp4"
        val doc = Saf.findDoc(cr, t, d, tempName) ?: Saf.createDoc(cr, d, tempName, "*/*")
        if (doc == null) {
            note("The recording file could not be created.")
            return
        }
        val pfd = try {
            cr.openFileDescriptor(doc, "rw")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            // The next service start removes whatever this leaves behind.
            Saf.deleteDoc(cr, doc)
            note("The recording file could not be opened for writing.")
            return
        }
        val enc = selectEncoder(w, h)
        if (enc == null) {
            pfd.close()
            Saf.deleteDoc(cr, doc)
            note("No H.264 encoder on this phone accepts YUV frames; recording is not available.")
            return
        }
        val mux: MediaMuxer
        try {
            // The muxer borrows the descriptor and does not close it; the
            // pfd is what closes it, once, when the session ends.
            mux = MediaMuxer(pfd.fileDescriptor, MUXER_MPEG_4)
        } catch (e: Exception) {
            pfd.close()
            enc.release()
            Saf.deleteDoc(cr, doc)
            note("The recording file could not be opened for writing: ${e.message}")
            return
        }

        this.pfd = pfd
        this.doc = doc
        this.dir = d
        this.tree = t
        this.encoder = enc
        this.muxer = mux
        this.muxerStarted = false
        this.trackId = -1
        this.writtenBytes = 0
        this.framesFed = 0
        this.savedName = null
        this.lastPtsUs = -1
        this.t0Ms = 0L
        this.sentAnyInput = false
        this.stopRequested = false
        this.stopForCap = false
        this.lastCapCheck = System.currentTimeMillis()

        // The service may have stopped while the setup ran; do not bring a
        // session to life after that.
        if (stopRequested) {
            mux.release()
            pfd.close()
            enc.release()
            Saf.deleteDoc(cr, doc)
            return
        }
        // Frames queued since the press; a previous start may have left some
        // behind, and those would be stale.
        frameQueue.clear()

        // The ring holds the seconds before the press. Live frames queued in
        // the meantime come after it: the camera feeds the ring and this
        // queue in the same order, and the ring was emptied before any of
        // them was queued.
        ringFrames = ringRef?.drain() ?: emptyList()
        ringIdx = 0

        tempFile = tempName
        recording = true
        lastNote = "Recording."
        Log.i(TAG, "recording started: $tempName (${w}x$h) with ${enc.name}, ring carried ${ringFrames.size} frames")
        recordSession(enc, w, h)
    }

    /** The whole session: feed frames until asked to stop, then finalize. */
    private fun recordSession(enc: MediaCodec, w: Int, h: Int) {
        val ySize = w * h
        // NV12's chroma plane is interleaved U,V: twice as many bytes as
        // one of the planar layouts' single U plane.
        val uvSize = (w / 2) * (h / 2) * 2
        if (yBuf.size < ySize) yBuf = ByteArray(ySize)
        if (uvBuf.size < uvSize) uvBuf = ByteArray(uvSize)
        if (pixels.size < ySize) pixels = IntArray(ySize)

        try {
            while (true) {
                if (stopRequested) break
                val f = nextFrame()
                if (f == null) {
                    // The camera went quiet (or is between frames): keep
                    // waiting for the next frame or the stop request.
                    continue
                }
                feedFrame(enc, f, w, h)
                drainEncoder(enc, w, h, 0)
                checkCap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording stopped on an error: ${e.message}", e)
        }

        val finished = finishSession(enc, w, h)
        val name = tempFile
        recording = false
        tempFile = null
        // Frames that arrived while the file was being finalized belong to
        // the session that just ended, not to whatever comes next.
        frameQueue.clear()
        when {
            finished == 1 ->
                note(if (stopForCap)
                    "Recording stopped: the storage cap was reached."
                else
                    "Saved ${savedName ?: name} (${fmtSize(writtenBytes)}).")
            finished == 2 ->
                note("Saved, but the file could not be renamed; it is left under a temporary name.")
            else -> {
                doc?.let { Saf.deleteDoc(cr, it) }
                val tail = if (stopForCap) ", the storage cap was reached" else ""
                note("Recording stopped: it could not be finished$tail.")
            }
        }
        onStopped?.invoke()
    }

    private fun nextFrame(): VideoRing.Frame? {
        if (ringIdx < ringFrames.size) return ringFrames[ringIdx++]
        return frameQueue.poll(200, TimeUnit.MILLISECONDS)
    }

    /** Decode one frame, convert it, push it to the encoder. */
    private fun feedFrame(enc: MediaCodec, f: VideoRing.Frame, w: Int, h: Int) {
        if (!jpegToNv12(f.jpeg, w, h)) return
        if (t0Ms == 0L) t0Ms = f.timeMs
        var pts = (f.timeMs - t0Ms) * 1000L
        if (pts <= lastPtsUs) pts = lastPtsUs + 1
        lastPtsUs = pts
        val idx = enc.dequeueInputBuffer(100)
        if (idx < 0) return
        val buf = enc.getInputBuffer(idx) ?: return
        buf.clear()
        buf.put(yBuf)
        buf.put(uvBuf)
        enc.queueInputBuffer(
            idx, 0, yBuf.size + uvBuf.size, pts,
            if (sentAnyInput) 0 else MediaCodec.BUFFER_FLAG_SYNC_FRAME,
        )
        sentAnyInput = true
        framesFed++
    }

    /**
     * Move finished encoded data into the muxer. [timeoutUs] is 0 while the
     * session runs (there is always more input coming) and a real wait while
     * flushing at the end.
     */
    private fun drainEncoder(enc: MediaCodec, w: Int, h: Int, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, timeoutUs)
            // The two informational results are negative numbers too, so they
            // are tested before the give-up case.
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                startMuxer(enc, w, h)
                continue
            }
            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (idx < 0) return false
            // The format event does not always arrive first (or at all, on
            // some bridges): the first real sample starts the muxer too.
            if (info.size > 0) startMuxer(enc, w, h)
            // A codec-config buffer is the SPS/PPS, which the track format
            // already carries; writing it as a sample would be wrong.
            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (muxerStarted && info.size > 0 && !config) {
                val b = enc.getOutputBuffer(idx)
                if (b != null) {
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    muxer!!.writeSampleData(trackId, b, info)
                    writtenBytes += info.size
                }
            }
            enc.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
        }
    }

    /**
     * Add the video track and open the file for samples, once per session.
     * The encoder's own output format is the best source (it carries the
     * resolution and the SPS/PPS the muxer needs); the session's settings
     * fill in anything it leaves out.
     */
    private fun startMuxer(enc: MediaCodec, w: Int, h: Int) {
        if (muxerStarted) return
        val mx = muxer ?: return
        val fmt = try {
            enc.getOutputFormat()
        } catch (e: Exception) {
            null
        } ?: MediaFormat.createVideoFormat(MIME_AVC, w, h)
        if (!fmt.containsKey(MediaFormat.KEY_MIME)) fmt.setString(MediaFormat.KEY_MIME, MIME_AVC)
        if (!fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.setInteger(MediaFormat.KEY_WIDTH, w)
        if (!fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.setInteger(MediaFormat.KEY_HEIGHT, h)
        trackId = mx.addTrack(fmt)
        mx.start()
        muxerStarted = true
    }

    /**
     * Keep under the cap: delete oldest finished videos until the running
     * total (existing plus what this session has written) fits the margin,
     * and ask the session to stop if even an empty folder would not do.
     */
    private fun checkCap() {
        val now = System.currentTimeMillis()
        if (now - lastCapCheck < 1000) return
        lastCapCheck = now
        val cap = capBytes ?: return
        val d = dir ?: return
        val tr = tree ?: return
        existingBytes = prune(d, tr, cap, writtenBytes)
        if (existingBytes + writtenBytes >= (cap * STOP_MARGIN).toLong()) {
            stopRequested = true
            stopForCap = true
        }
    }

    /**
     * Delete oldest finished videos until existing + [inFlight] fits the
     * cap's margin; returns what the remaining videos still occupy.
     */
    private fun prune(d: Uri, t: Uri, cap: Long?, inFlight: Long): Long {
        val kids = Saf.listChildren(cr, t, d)
            .filter { it.name.endsWith(".mp4") && !it.name.endsWith(".tmp.mp4") }
        var used = kids.sumOf { it.size }
        if (cap == null) return used
        val limit = (cap * STOP_MARGIN).toLong()
        // The name carries the start time, newest last: oldest goes first.
        for (victim in kids.sortedBy { it.name }) {
            if (used + inFlight < limit) break
            if (Saf.deleteDoc(cr, victim.doc)) {
                used -= victim.size
                Log.i(TAG, "storage cap: deleted oldest video ${victim.name} (${fmtSize(victim.size)})")
            } else {
                break
            }
        }
        return used
    }

    /**
     * Flush the encoder, close the muxer, rename the temporary file.
     * Returns 1 when the file is complete under its finished name, 2 when it
     * is complete but the rename failed, 0 when it is unplayable.
     */
    private fun finishSession(enc: MediaCodec, w: Int, h: Int): Int {
        val mx = muxer
        var ok = false
        try {
            // Ask for end of stream by queueing an empty input buffer that
            // carries the flag: that is the buffer-API way in. The encoder
            // then returns the frames it still holds, and the drain below
            // sees the end-of-stream flag on the last one. If no buffer is
            // free the file is finished without the flush; it still plays,
            // minus the tail.
            var flushed = false
            val idx = try {
                enc.dequeueInputBuffer(200_000)
            } catch (e: Exception) {
                -1
            }
            if (idx >= 0) {
                try {
                    enc.queueInputBuffer(
                        idx, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    flushed = true
                } catch (e: Exception) {
                    Log.w(TAG, "end-of-stream refused (${e.message})")
                }
            }
            val deadline = System.currentTimeMillis() + 10_000
            var done = !flushed
            while (!done && System.currentTimeMillis() < deadline) {
                done = drainEncoder(enc, w, h, if (flushed) 20_000 else 200)
            }
            enc.stop()
            if (mx != null && muxerStarted && writtenBytes > 0) {
                mx.stop()
                ok = true
            }
            Log.i(
                TAG,
                "session ended: $framesFed frames fed, ${fmtSize(writtenBytes)} encoded" +
                    if (flushed) ", flushed" else ", no flush"
            )
        } catch (e: Exception) {
            Log.w(TAG, "finishing the recording failed: ${e.message}", e)
            ok = false
        } finally {
            try { enc.release() } catch (_: Exception) {}
            try { mx?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            pfd = null
            this.encoder = null
            this.muxer = null
        }
        if (!ok) return 0

        val d = doc
        val pd = dir
        val tr = tree
        if (d == null || pd == null || tr == null) return 2
        val finalName = "video-$startTs.mp4"
        return if (Saf.renameDoc(cr, tr, pd, d, finalName) != null) {
            savedName = finalName
            Log.i(TAG, "recording saved as $finalName (${fmtSize(writtenBytes)})")
            1
        } else {
            Log.w(TAG, "could not rename the finished recording to $finalName")
            2
        }
    }

    // ---- frame conversion --------------------------------------------------

    /**
     * JPEG into the shared NV12 buffers. Reuses one pixel array; a frame
     * whose size does not match (the camera size changed mid-session) is
     * scaled. The chroma planes average each 2x2 block; a block at the edge
     * of an odd-sized frame averages what it has.
     */
    private fun jpegToNv12(jpeg: ByteArray, w: Int, h: Int): Boolean {
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
        if (bmp.width != w || bmp.height != h) {
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            bmp.recycle()
            bmp = scaled
        }
        try {
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (e: Exception) {
            bmp.recycle()
            return false
        }
        for (i in pixels.indices) {
            val v = pixels[i]
            val r = (v shr 16) and 0xFF
            val g = (v shr 8) and 0xFF
            val b = v and 0xFF
            yBuf[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) and 0xFF).toByte()
        }
        var u = 0
        var py = 0
        while (py < h) {
            var px = 0
            while (px < w) {
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = px + dx
                        val yy = py + dy
                        if (x < w && yy < h) {
                            val v = pixels[yy * w + x]
                            sr += (v shr 16) and 0xFF
                            sg += (v shr 8) and 0xFF
                            sb += v and 0xFF
                            n++
                        }
                    }
                }
                if (n > 0) {
                    val ar = sr / n
                    val ag = sg / n
                    val ab = sb / n
                    // Cb and Cr are signed differences: the +128 inside the
                    // shift rounds, the +128 outside puts them back on the
                    // 0..255 scale (128 = no colour). Without that second
                    // one a neutral pixel becomes 0, i.e. maximum colour.
                    uvBuf[u++] = ((((-38 * ar - 74 * ag + 112 * ab + 128) shr 8) + 128)).toByte()
                    uvBuf[u++] = ((((112 * ar - 94 * ag - 18 * ab + 128) shr 8) + 128)).toByte()
                } else {
                    uvBuf[u++] = 128.toByte()
                    uvBuf[u++] = 128.toByte()
                }
                px += 2
            }
            py += 2
        }
        bmp.recycle()
        return true
    }

    // ---- codec selection ----------------------------------------------------

    /**
     * The first H.264 encoder that accepts the session's settings, hardware
     * first, started and ready to take frames. This build's SDK stub does
     * not expose codec capability lists, so "accepts" is decided by the
     * attempt itself: configure and start, and try the next on failure. The
     * platform default encoder is the last candidate - every device has one
     * and it always takes YUV through the buffer API, so a session can
     * always be started even where a hardware codec refuses.
     */
    private fun selectEncoder(w: Int, h: Int): MediaCodec? {
        val infos = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .getCodecInfos().toList()
        } catch (e: Exception) {
            emptyList()
        }
        val candidates = infos
            .filter { it.isEncoder && MIME_AVC in it.getSupportedTypes() }
            .sortedBy { if (it.isHardwareAccelerated()) 0 else 1 }
        for (info in candidates) {
            val codec = try {
                MediaCodec.createByCodecName(info.name)
            } catch (e: Exception) {
                continue
            }
            try {
                codec.configure(codecFormat(w, h), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                Log.i(TAG, "encoder: ${info.name} (${w}x$h)")
                return codec
            } catch (e: Exception) {
                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }
        }
        return defaultEncoder(w, h)
    }

    /** The plain-type factory: the device's default encoder, YUV-capable on all of them. */
    private fun defaultEncoder(w: Int, h: Int): MediaCodec? = try {
        MediaCodec.createEncoderByType(MIME_AVC).also {
            it.configure(codecFormat(w, h), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
            Log.i(TAG, "encoder (default): ${it.name} (${w}x$h)")
        }
    } catch (e: Exception) {
        Log.w(TAG, "no usable H.264 encoder: ${e.message}")
        null
    }

    private fun codecFormat(w: Int, h: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // About a quarter bit per pixel per frame: ~3.5 Mbit/s at 720p15.
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 15) / 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    // ---- little helpers ------------------------------------------------------

    private fun note(msg: String) {
        lastNote = msg
        Log.i(TAG, msg)
    }

    private fun durationOf(t: Uri, d: Uri, c: Saf.Child): Long {
        val key = c.name + ":" + c.size
        durationCache[key]?.let { return it }
        val secs = mp4Seconds(c.doc, c.size) ?: 0L
        if (secs > 0) durationCache[key] = secs
        return secs
    }

    /**
     * The movie header's duration, read from the end of the file: MediaMuxer
     * writes the moov atom last, and the header is its first child, so a
     * short scan of the tail finds it without reading the whole file.
     */
    private fun mp4Seconds(doc: Uri, size: Long): Long? {
        if (size < 64) return null
        val window = minOf(128L * 1024, size).toInt()
        return try {
            val stream = cr.openInputStream(doc) ?: return null
            stream.use { s ->
                var skip = size - window
                while (skip > 0) {
                    val n = s.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
                val tail = ByteArray(window)
                var off = 0
                while (off < window) {
                    val n = s.read(tail, off, window - off)
                    if (n < 0) break
                    off += n
                }
                val i = indexOf(tail, byteArrayOf(0x6D, 0x76, 0x68, 0x64))
                if (i < 0) return null
                val version = tail[i + 4].toInt() and 0xFF
                val timescale = readInt(tail, i + 16 + version * 8)
                if (timescale == 0L) return null
                val dur = if (version == 0) readInt(tail, i + 20) else readLong(tail, i + 28)
                // Round to the nearest second: 5.98 s is six seconds to a
                // person reading the list, not five.
                (dur + timescale / 2) / timescale
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the duration of ${doc.lastPathSegment}: ${e.message}")
            null
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readInt(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..3) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun readLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun fmtSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / (1024.0 * 1024))
    }
}
