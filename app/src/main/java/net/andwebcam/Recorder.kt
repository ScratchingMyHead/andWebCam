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
 * the video/ subfolder of the chosen storage location.
 *
 * Pipeline: JPEG -> Bitmap -> NV12 -> H.264 (MediaCodec) -> MediaMuxer,
 * with an optional AAC track fed from the audio ring's pre-roll and the
 * microphone's live chunks. Both tracks share the file's time base.
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
class Recorder(private val ctx: Context, private val audio: AudioHub) {
    companion object {
        private const val TAG = "andWebCam"
        const val VIDEOS_DIR = "video"
        private const val MIME_AAC = "audio/mp4a-latm"
        const val MEMORY_ERROR =
            "Recording stopped: resolution and/or pre-roll is too high. Lower the resolution or pre-roll."

        /** Stop at this fraction of the cap, so the last write fits. */
        private const val STOP_MARGIN = 0.95

        private const val MIME_AVC = "video/avc"

        // The SDK stub of this build does not carry MUXER_TYPE_MPEG_4,
        // whose value is 0 (the other container types follow).
        private const val MUXER_MPEG_4 = 0
    }

    @Volatile var recording = false
        private set

    /** Wall clock when the running session started, for elapsed displays. */
    @Volatile var recStartMs = 0L
        private set

    /** A short note about the latest start or stop, shown on the page. */
    @Volatile var lastNote: String? = null

    /** The temporary file name while recording, null otherwise. */
    @Volatile var tempFile: String? = null

    /** The ring the sessions carry forward; set once by the service. */
    @Volatile var ringRef: VideoRing? = null

    /** Invoked on the worker thread when a session has finished, whatever its outcome. */
    @Volatile var onStopped: (() -> Unit)? = null

    @Volatile private var memoryFailure = false

    /** Called when the camera reports an allocation failure during a session. */
    fun failForMemory() {
        memoryFailure = true
        note(MEMORY_ERROR)
        stopRequested = true
    }

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
    private var audioTrackId = -1
    private var audioEnc: MediaCodec? = null
    private var audioFormat: MediaFormat? = null
    private var videoFormat: MediaFormat? = null
    private val pendingVideo = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
    private val pendingAudio = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
    private val audioQueue = LinkedBlockingQueue<AudioRing.Chunk>(200)
    private var audioPreroll = emptyList<AudioRing.Chunk>()
    private var audioIdx = 0
    private var audioPtsUs = -1L
    private var audioBytes = 0L
    private var audioChunks = 0
    private var audioDone = false
    private var audioFeeder: Thread? = null
    @Volatile private var feedAudio = false
    @Volatile private var audioCutoffPtsUs = Long.MAX_VALUE
    private var lastVideoTimeMs = 0L

    /** Whether the last finished file really carries an audio track. */
    @Volatile
    var soundInLastFile = false
        private set
    private var pfd: ParcelFileDescriptor? = null
    private var doc: Uri? = null
    private var dir: Uri? = null
    private var tree: Uri? = null
    private var startTs = ""
    private var triggerPrefix = "video"
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

    /** One mutable bitmap, refilled each frame instead of allocating a new one. */
    private var reusableBitmap: Bitmap? = null

    /** Durations already read, keyed by "name:size" so a rewritten file re-reads. */
    private val durationCache = ConcurrentHashMap<String, Long>()

    // ---- public API (any thread) -----------------------------------------

    /**
     * Begin a session. [w]x[h] is the camera size at that moment. Returns
     * null when it was accepted (it starts asynchronously on the worker) or
     * a sentence saying why not.
     */
    fun start(w: Int, h: Int, prefix: String = "video"): String? {
        synchronized(this) {
            if (recording || starting) return null
            starting = true
        }
        jobs.offer { doStart(w, h, prefix) }
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
        out["sound"] = soundInLastFile
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
            .filter { isVideoName(it.name) }
            .sortedWith(compareBy({ nameOrder(it.name) }, { it.name }))
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
    /**
     * A finished recording: "video-" for sessions started by hand and
     * "zone<N>-" for ones a motion zone started.
     */
    private fun isVideoName(name: String): Boolean =
        name.endsWith(".mp4") && !name.endsWith(".tmp.mp4") &&
            (name.startsWith("video-") || Regex("^zone\\d+-").containsMatchIn(name))

    /**
     * Chronological order for a recording, whatever started it: the name is
     * "<prefix>-<yyyyMMdd-HHmmss>.mp4", and the prefix must not affect where
     * the row sorts.
     */
    private fun nameOrder(name: String): String = name.substringAfter('-')

    fun delete(name: String): String? {
        if (!isVideoName(name)) return "no such video"
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

    /**
     * Delete every finished video. A recording that is running now is not
     * touched: its file is still a temporary, and it will be named like the
     * rest when it finishes.
     */
    fun deleteAll(): String? {
        val t = Saf.tree ?: return "no folder has been chosen"
        val d = Saf.findDoc(cr, t, t, VIDEOS_DIR) ?: return "no videos folder"
        if (recording) return "a recording is running; stop it first"
        var n = 0
        for (c in Saf.listChildren(cr, t, d)) {
            if (!isVideoName(c.name)) continue
            if (Saf.deleteDoc(cr, c.doc)) n++ else Log.w(TAG, "could not delete ${c.name}")
        }
        durationCache.clear()
        Log.i(TAG, "deleted all videos: $n files")
        lastNote = "Deleted $n videos."
        return null
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
        if (!isVideoName(name)) return null
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
    private fun doStart(w: Int, h: Int, prefix: String) {
        if (recording) return
        memoryFailure = false
        try {
            beginSession(w, h, prefix)
        } catch (e: OutOfMemoryError) {
            note(MEMORY_ERROR)
        } finally {
            if (!recording) starting = false
        }
    }

    private fun beginSession(w: Int, h: Int, prefix: String = "video") {
        val t = Saf.tree
        if (t == null) {
            note("Choose a storage location on the phone first: recordings go into its video subfolder.")
            return
        }
        if (w < 32 || h < 32) {
            note("The camera size is not usable yet; try again in a moment.")
            return
        }
        val d = Saf.ensureDir(cr, t, VIDEOS_DIR)
        if (d == null) {
            note("The video folder could not be opened in ${Saf.describe(ctx) ?: "the log folder"}.")
            return
        }

        // Room for this session before it writes a byte: the oldest finished
        // videos go first, and the cap margin is left for this one.
        val free = Saf.freeBytes(t)
        capBytes = (free?.let { (it * Prefs.videoCapPercent) / 100L })?.takeIf { it > 0L }
        existingBytes = prune(d, t, capBytes, 0L)

        startTs = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        triggerPrefix = prefix
        val tempName = "$prefix-$startTs.tmp.mp4"
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
        this.audioTrackId = -1
        this.audioEnc = null
        this.audioFormat = null
        this.videoFormat = null
        this.pendingVideo.clear()
        this.pendingAudio.clear()
        this.audioQueue.clear()
        this.audioPreroll = emptyList()
        this.audioIdx = 0
        this.audioPtsUs = -1L
        this.audioBytes = 0L
        this.audioChunks = 0
        this.audioDone = false
        this.audioFeeder = null
        this.feedAudio = false
        this.audioCutoffPtsUs = Long.MAX_VALUE
        this.loggedBoundary = false
        this.lastVideoTimeMs = 0L
        this.soundInLastFile = false
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

        // Sound: the audio ring carries the same window of pre-roll as the
        // video ring. The encoder is optional; if none is available the file
        // is still a valid video-only recording.
        if (Prefs.audioEnabled && AudioHub.hasPermission(ctx)) {
            val aenc = selectAudioEncoder()
            if (aenc != null) {
                audioEnc = aenc
                audioPreroll = audio.beginRecording { c ->
                    if (!audioQueue.offer(c)) {
                        audioQueue.poll()
                        audioQueue.offer(c)
                    }
                }
            } else {
                Log.w(TAG, "no AAC encoder; recording video only")
            }
        }
        // Both tracks share one time base. Anchor it to the video pre-roll:
        // its byte budget can be shorter than the sound ring's window, and
        // starting sound earlier would leave the picture beginning several
        // seconds into an otherwise silent recording.
        t0Ms = ringFrames.firstOrNull()?.timeMs ?: 0L
        Log.i(TAG, "pre-roll video ${ringFrames.size} [${ringFrames.firstOrNull()?.timeMs}," +
            " ${ringFrames.lastOrNull()?.timeMs}] audio ${audioPreroll.size} " +
            "[${audioPreroll.firstOrNull()?.timeMs}, ${audioPreroll.lastOrNull()?.timeMs}] t0=$t0Ms")
        if (t0Ms != 0L && audioPreroll.isNotEmpty()) {
            audioPreroll = audioPreroll.filter { it.timeMs >= t0Ms }
            Log.i(TAG, "pre-roll audio after trim: ${audioPreroll.size}")
        }

        if (audioEnc != null) {
            feedAudio = true
            audioFeeder = Thread({ audioFeedLoop() }, "andwebcam-audio-feed").apply {
                isDaemon = true
                start()
            }
        }

        tempFile = tempName
        recording = true
        recStartMs = System.currentTimeMillis()
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
                if (!feedOneFrame(enc, w, h)) {
                    // No frame yet: drain what the encoders have produced
                    // without spinning on an empty queue.
                    drainEncoder(enc, w, h, 0)
                    drainAudio(0)
                    try { Thread.sleep(10) } catch (_: InterruptedException) {}
                    continue
                }
                // The AAC sink may still be waiting for its first output;
                // after enough pre-roll it clearly is not coming, so finish
                // the file as video only rather than never starting.
                if (videoFormat != null && audioEnc != null && audioFormat == null && audioChunks > 40) {
                    Log.w(TAG, "AAC encoder produced no format; saving video only")
                    audio.endRecording()
                    audioEnc = null
                }
                drainAudio(0)
                checkCap()
            }
            // Stop leaves frames in the queue that were captured before the
            // press of Stop. Encode them too, or the picture ends early and
            // the player freezes its last frame while the sound carries on.
            val stopAtMs = System.currentTimeMillis() + 100
            val tailDeadline = stopAtMs + 3000
            while (System.currentTimeMillis() < tailDeadline) {
                val f = nextFrame() ?: break
                if (f.timeMs > stopAtMs) break
                if (!feedOneFrame(enc, w, h)) break
                drainAudio(0)
            }
            // The camera can stop delivering a moment before Stop, which
            // would otherwise leave sound running past the last frame.
            if (lastVideoTimeMs > t0Ms) {
                audioCutoffPtsUs = (lastVideoTimeMs - t0Ms) * 1000L + 100_000L
            }
        } catch (e: OutOfMemoryError) {
            memoryFailure = true
            stopRequested = true
        } catch (e: Exception) {
            Log.w(TAG, "recording stopped on an error: ${e.message}", e)
        }

        // Finish feeding sound before flushing its encoder: otherwise the
        // chunks still queued at the stop are lost and the tail goes silent.
        stopAudioFeeder()
        val finished = finishSession(enc, w, h)
        val name = tempFile
        recording = false
        recStartMs = 0
        tempFile = null
        // Frames that arrived while the file was being finalized belong to
        // the session that just ended, not to whatever comes next.
        frameQueue.clear()
        audioQueue.clear()
        when {
            memoryFailure -> note(MEMORY_ERROR)
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

    private enum class FeedResult { QUEUED, BUSY, BAD }

    private fun nextFrame(): VideoRing.Frame? =
        if (ringIdx < ringFrames.size) ringFrames[ringIdx] else frameQueue.peek()

    /** Only advance past a frame once the encoder has actually taken it. */
    private fun commitFrame() {
        if (ringIdx < ringFrames.size) ringIdx++ else frameQueue.poll()
    }

    private var loggedBoundary = false

    /** Feed one frame; a busy encoder keeps the frame for the next attempt. */
    private fun feedOneFrame(enc: MediaCodec, w: Int, h: Int): Boolean {
        val f = nextFrame() ?: return false
        if (!loggedBoundary && ringIdx == ringFrames.size && ringFrames.isNotEmpty()) {
            loggedBoundary = true
            Log.i(TAG, "video boundary: last pre-roll ${ringFrames.last().timeMs}," +
                " first live ${f.timeMs}, gap ${f.timeMs - ringFrames.last().timeMs}ms")
        }
        when (feedFrame(enc, f, w, h)) {
            FeedResult.QUEUED -> {
                commitFrame()
                drainEncoder(enc, w, h, 0)
            }
            FeedResult.BUSY -> drainEncoder(enc, w, h, 0)
            FeedResult.BAD -> commitFrame()
        }
        return true
    }

    /** Decode one frame, convert it, push it to the encoder. */
    private fun feedFrame(enc: MediaCodec, f: VideoRing.Frame, w: Int, h: Int): FeedResult {
        if (!jpegToNv12(f.jpeg, w, h)) return FeedResult.BAD
        if (t0Ms == 0L) t0Ms = f.timeMs
        lastVideoTimeMs = f.timeMs
        var pts = (f.timeMs - t0Ms) * 1000L
        if (pts <= lastPtsUs) pts = lastPtsUs + 1
        val idx = try { enc.dequeueInputBuffer(100) } catch (e: Exception) { -1 }
        if (idx < 0) return FeedResult.BUSY
        val buf = enc.getInputBuffer(idx) ?: return FeedResult.BAD
        buf.clear()
        buf.put(yBuf)
        buf.put(uvBuf)
        enc.queueInputBuffer(
            idx, 0, yBuf.size + uvBuf.size, pts,
            if (sentAnyInput) 0 else MediaCodec.BUFFER_FLAG_SYNC_FRAME,
        )
        lastPtsUs = pts
        sentAnyInput = true
        framesFed++
        return FeedResult.QUEUED
    }

    /**
     * Sound is fed from its own thread. The encoder only has a few input
     * buffers, and output is drained on the recorder thread; feeding from
     * that thread used to drop a chunk whenever no buffer was free, which
     * removed most of the pre-roll and left a huge gap in the audio track.
     */
    private fun audioFeedLoop() {
        while (true) {
            val c = nextAudioChunk()
            if (c == null) {
                if (!feedAudio && audioIdx >= audioPreroll.size && audioQueue.isEmpty()) return
                try { Thread.sleep(10) } catch (_: InterruptedException) { return }
                continue
            }
            if (t0Ms == 0L || c.timeMs < t0Ms) continue   // before the picture starts
            if (!feedAudioChunk(c)) {
                // Encoder busy: keep the chunk and try again, never drop it.
                try { Thread.sleep(5) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun nextAudioChunk(): AudioRing.Chunk? {
        if (audioIdx < audioPreroll.size) {
            val c = audioPreroll[audioIdx]
            audioIdx++
            return c
        }
        return audioQueue.poll()
    }

    /** Queue one chunk; false means the encoder had no free input buffer. */
    private fun feedAudioChunk(c: AudioRing.Chunk): Boolean {
        val enc = audioEnc ?: return true
        var pts = (c.timeMs - t0Ms) * 1000L
        if (pts < 0) pts = 0
        if (pts >= audioCutoffPtsUs) return true   // consume, but past the picture
        if (pts <= audioPtsUs) pts = audioPtsUs + 1
        val idx = try { enc.dequeueInputBuffer(50_000) } catch (e: Exception) { return false }
        if (idx < 0) return false
        val buf = enc.getInputBuffer(idx) ?: return false
        buf.clear()
        buf.put(c.pcm)
        enc.queueInputBuffer(idx, 0, c.pcm.size, pts, 0)
        audioPtsUs = pts
        audioChunks++
        return true
    }

    /** Let the feeder put the last chunks in before the encoder is flushed. */
    private fun stopAudioFeeder() {
        feedAudio = false
        val feeder = audioFeeder ?: return
        val deadline = System.currentTimeMillis() + 10_000
        while (feeder.isAlive && System.currentTimeMillis() < deadline) {
            // Drain while it feeds, or the encoder's output buffers fill up
            // and the last chunks cannot get in.
            drainAudio(0)
            try { feeder.join(20) } catch (_: InterruptedException) { break }
        }
        audioFeeder = null
    }

    /** Move finished AAC packets into the muxer, or buffer until it starts. */
    private fun drainAudio(timeoutUs: Long) {
        val enc = audioEnc ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                enc.dequeueOutputBuffer(info, timeoutUs)
            } catch (e: Exception) {
                return
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = try { enc.outputFormat } catch (e: Exception) { null }
                tryStartMuxer()
                continue
            }
            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (idx < 0) return
            if (audioFormat == null && info.size > 0) {
                audioFormat = try { enc.outputFormat } catch (e: Exception) { null }
                tryStartMuxer()
            }
            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (info.size > 0 && !config) {
                val b = enc.getOutputBuffer(idx)
                if (b != null) {
                    val sample = copySample(b, info)
                    audioBytes += info.size
                    if (muxerStarted) writeSample(audioTrackId, sample)
                    else pendingAudio.add(sample)
                }
            }
            enc.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                audioDone = true
                return
            }
        }
    }

    private fun drainEncoder(enc: MediaCodec, w: Int, h: Int, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, timeoutUs)
            // The two informational results are negative numbers too, so they
            // are tested before the give-up case.
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                captureVideoFormat(enc, w, h)
                continue
            }
            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (idx < 0) return false
            // The format event does not always arrive first (or at all, on
            // some bridges): the first real sample starts it too.
            if (videoFormat == null) captureVideoFormat(enc, w, h)
            // A codec-config buffer is the SPS/PPS, which the track format
            // already carries; writing it as a sample would be wrong.
            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (info.size > 0 && !config) {
                val b = enc.getOutputBuffer(idx)
                if (b != null) {
                    val sample = copySample(b, info)
                    if (muxerStarted) writeSample(trackId, sample)
                    else pendingVideo.add(sample)
                }
            }
            enc.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
        }
    }

    /** Remember the video track format once the encoder publishes it. */
    private fun captureVideoFormat(enc: MediaCodec, w: Int, h: Int) {
        if (videoFormat != null) return
        val fmt = try {
            enc.outputFormat
        } catch (e: Exception) {
            null
        } ?: MediaFormat.createVideoFormat(MIME_AVC, w, h)
        if (!fmt.containsKey(MediaFormat.KEY_MIME)) fmt.setString(MediaFormat.KEY_MIME, MIME_AVC)
        if (!fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.setInteger(MediaFormat.KEY_WIDTH, w)
        if (!fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.setInteger(MediaFormat.KEY_HEIGHT, h)
        videoFormat = fmt
        tryStartMuxer()
    }

    /**
     * Both tracks must be present before MediaMuxer.start(). Sound normally
     * arrives within a few frames, but packets produced while we wait are
     * kept and written as soon as it does.
     */
    private fun tryStartMuxer() {
        if (muxerStarted) return
        val mx = muxer ?: return
        val vf = videoFormat ?: return
        if (audioEnc != null && audioFormat == null) return
        val af = audioFormat
        trackId = mx.addTrack(vf)
        if (audioEnc != null && af != null) {
            audioTrackId = mx.addTrack(af)
        }
        mx.start()
        muxerStarted = true
        soundInLastFile = audioTrackId >= 0
        for (s in pendingVideo) writeSample(trackId, s)
        pendingVideo.clear()
        for (s in pendingAudio) writeSample(audioTrackId, s)
        pendingAudio.clear()
    }

    private fun copySample(b: java.nio.ByteBuffer, info: MediaCodec.BufferInfo): Pair<ByteArray, MediaCodec.BufferInfo> {
        val bytes = ByteArray(info.size)
        b.position(info.offset)
        b.get(bytes)
        val copy = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }
        return bytes to copy
    }

    private fun writeSample(track: Int, sample: Pair<ByteArray, MediaCodec.BufferInfo>) {
        if (track < 0) return
        val (bytes, info) = sample
        muxer?.writeSampleData(track, java.nio.ByteBuffer.wrap(bytes), info)
        writtenBytes += info.size
    }

    /** The device's AAC-LC encoder, started and ready for 16 kHz mono PCM. */
    private fun selectAudioEncoder(): MediaCodec? {
        val info = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .getCodecInfos().toList()
                .firstOrNull { it.isEncoder && MIME_AAC in it.getSupportedTypes() }
        } catch (e: Exception) {
            null
        }
        val candidates = if (info == null) emptyList() else listOf(info)
        for (c in candidates) {
            val codec = try {
                MediaCodec.createByCodecName(c.name)
            } catch (e: Exception) {
                continue
            }
            if (configureAudio(codec)) return codec
            try { codec.release() } catch (_: Exception) {}
        }
        val fallback = try { MediaCodec.createEncoderByType(MIME_AAC) } catch (e: Exception) { null }
        return if (fallback != null && configureAudio(fallback)) fallback else null
    }

    private fun configureAudio(codec: MediaCodec): Boolean = try {
        val fmt = MediaFormat.createAudioFormat(MIME_AAC, AudioHub.SAMPLE_RATE, AudioHub.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Log.i(TAG, "audio encoder: ${codec.name}")
        true
    } catch (e: Exception) {
        Log.w(TAG, "audio encoder refused the format: ${e.message}")
        false
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
            .filter { isVideoName(it.name) }
        var used = kids.sumOf { it.size }
        if (cap == null) return used
        val limit = (cap * STOP_MARGIN).toLong()
        // The name carries the start time, newest last: oldest goes first.
        for (victim in kids.sortedWith(compareBy({ nameOrder(it.name) }, { it.name }))) {
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
                drainAudio(0)
            }
            // The audio encoder needs the same end-of-stream treatment.
            val aenc = audioEnc
            if (aenc != null) {
                try {
                    val ai = aenc.dequeueInputBuffer(200_000)
                    if (ai >= 0) {
                        aenc.queueInputBuffer(
                            ai, 0, 0, audioPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "audio end-of-stream refused (${e.message})")
                }
                val audioDeadline = System.currentTimeMillis() + 5_000
                while (!audioDone && System.currentTimeMillis() < audioDeadline) {
                    drainAudio(20_000)
                }
                try { aenc.stop() } catch (_: Exception) {}
            }
            enc.stop()
            if (mx != null && muxerStarted && writtenBytes > 0) {
                mx.stop()
                ok = true
            }
            Log.i(
                TAG,
                "session ended: $framesFed frames fed, ${fmtSize(writtenBytes)} encoded" +
                    (if (flushed) ", flushed" else ", no flush") +
                    if (soundInLastFile) ", $audioChunks sound chunks" else ", silent"
            )
        } catch (e: Exception) {
            Log.w(TAG, "finishing the recording failed: ${e.message}", e)
            ok = false
        } finally {
            try { enc.release() } catch (_: Exception) {}
            try { audioEnc?.release() } catch (_: Exception) {}
            audio.endRecording()
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
        val finalName = "$triggerPrefix-$startTs.mp4"
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
        // Refill the same bitmap every frame. A 4K bitmap is ~33 MB, so
        // allocating one per frame would create huge GC pressure. If the
        // camera size changed, the old bitmap cannot be reused; scale in a
        // temporary one and let the next frame allocate at the new size.
        var bmp: Bitmap? = null
        var temporary = false
        val prior = reusableBitmap
        if (prior != null) {
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inBitmap = prior
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            try {
                bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            } catch (e: IllegalArgumentException) {
                bmp = null
            }
            if (bmp == null) {
                prior.recycle()
                reusableBitmap = null
            }
        }
        if (bmp == null) {
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
        }
        if (bmp !== prior && prior != null && !prior.isRecycled) prior.recycle()
        if (bmp.width != w || bmp.height != h) {
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            bmp.recycle()
            bmp = scaled
            temporary = true
        }
        if (temporary) reusableBitmap = null else reusableBitmap = bmp
        try {
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (e: Exception) {
            if (!temporary) reusableBitmap = null
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
        if (temporary) bmp.recycle()
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
