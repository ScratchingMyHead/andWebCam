package net.andwebcam

/**
 * The last [maxAgeMs] of the stream's own JPEG frames, kept in memory.
 *
 * A recording that starts now can begin with them - the user gets the
 * seconds before the button press - and motion detection later will look
 * at the same history, so neither adds work to the camera path: the frames
 * are exactly the ones the stream already decodes and broadcasts, and
 * [push] is a lock-protected list append.
 *
 * Frames are dropped from the head as they age out or the byte budget is
 * exceeded, so the memory cost is bounded by both time and size.
 */
class VideoRing(
    /** Keep frames this many milliseconds old at most. */
    val maxAgeMs: Long = 10_000,
    /** Hold at most this many bytes of frames; the oldest drop first. */
    private val maxBytes: Long = 24L * 1024 * 1024,
) {
    class Frame(val jpeg: ByteArray, val timeMs: Long)

    private val lock = Any()
    private val frames = ArrayList<Frame>()
    private var bytes = 0L

    /**
     * Called from the camera's frame path; cheap, and must stay that way.
     * The caller's array is kept as is: the camera allocates a fresh one
     * for every frame and nothing in the pipeline rewrites it after this.
     */
    fun push(jpeg: ByteArray) {
        val t = System.currentTimeMillis()
        synchronized(lock) {
            frames.add(Frame(jpeg, t))
            bytes += jpeg.size
            val cutoff = t - maxAgeMs
            while (frames.size > 1 && (frames[0].timeMs < cutoff || bytes > maxBytes)) {
                bytes -= frames.removeAt(0).jpeg.size
            }
        }
    }

    /**
     * Everything held that is still inside the window, oldest first, and the
     * ring empties out. Frames pushed before the camera paused are dropped
     * here: the pre-roll is the last seconds of live video, not whatever was
     * on screen the last time the camera was open.
     */
    fun drain(): List<Frame> = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val out = frames.filter { it.timeMs >= cutoff }
        frames.clear()
        bytes = 0
        out
    }

    /** Drop everything: the camera has stopped, so the run of live frames ends. */
    fun clear() = synchronized(lock) {
        frames.clear()
        bytes = 0
    }

    /** How much of the [maxAgeMs] window is filled; 1.0 once it has. */
    fun fillFraction(): Double = synchronized(lock) {
        if (frames.size < 2) return 0.0
        (frames.last().timeMs - frames[0].timeMs).coerceAtLeast(0L) / maxAgeMs.toDouble()
    }
}
