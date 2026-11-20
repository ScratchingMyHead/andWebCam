package net.andwebcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Motion activation: watches the stream's own JPEG frames for objects moving
 * inside user-drawn zones, and says when a zone fires.
 *
 * The analysis runs on a small greyscale copy of the frame at a fraction of
 * the stream's rate, as a pipeline:
 *
 * 1. **Cell means.** The frame is divided into a 32x18 grid (18x32 in
 *    portrait) of 10-pixel cells and each cell's mean luminance is taken.
 *    Averaging 100 pixels crushes sensor noise by ten times, which is what
 *    keeps high-ISO static out of the trigger - a blur would do the same job
 *    less well and with an extra pass.
 * 2. **Background.** Each cell keeps a running average of what it usually
 *    looks like, absorbing change over about twenty seconds: someone who
 *    stands still becomes scenery eventually, which is what lets a recording
 *    end, and slow exposure drift is not mistaken for motion.
 * 3. **Global rejection.** When more than half the cells change in the same
 *    tick, that is an exposure step or a scene cut, not motion: the
 *    background is re-seated and the tick is skipped.
 * 4. **Blobs.** Connected changed cells (8-connected) are grouped, and a
 *    blob's size in cells is the size of the object, independent of how big
 *    the zone around it is.
 *
 * A zone (a polygon in normalized coordinates) fires when it contains a blob
 * of at least the size its sensitivity asks for: sensitivity 0 accepts a
 * single cell, 100 demands a 15-cell object. Because the threshold is in
 * cells, the same head triggers the same way in a doorway-sized zone or a
 * room-sized one.
 *
 * Zones arrive as the compact string Prefs stores:
 * "aspect;sens:x,y,x,y,...;sens:...". The same per-zone state feeds the
 * page's live meters, whose scale matches the slider: the fill shows the
 * largest blob seen in the zone, and when it reaches the handle, the zone is
 * firing.
 */
class Motion {
    companion object {
        private const val TAG = "andWebCam"
        private const val ANALYSE_EVERY_MS = 200L   // ~5 fps is plenty for motion
        private const val CELL_DELTA = 10.0         // grey levels of cell-mean change that count
        private const val GLOBAL_SKIP = 0.5         // most of the frame changing = exposure, not motion
        private const val COOLDOWN_MS = 5000L       // quiet time between triggers
        private const val TARGET_W = 320            // analysis frame width
        private const val BG_RATE = 32              // background absorbs 1/32 per tick (~20 s)

        /**
         * Blob size in cells that the top of the sensitivity slider asks
         * for - about a tenth of the frame, i.e. a person at room distance.
         * The slider maps 0-100 onto 1..[MAX_BLOB] cells, so its whole range
         * spans the objects that actually move in front of this camera.
         */
        private const val MAX_BLOB = 60
    }

    /** A parsed zone with its live state. */
    class Zone(val sensitivity: Int, val points: List<Pair<Double, Double>>) {
        var level = 0            // largest blob seen in the zone, on the slider's scale
    }

    /** Invoked when a zone fires; argument is the zone's 1-based position. */
    @Volatile var onTrigger: ((Int) -> Unit)? = null

    @Volatile var enabled = false
        private set

    private val zones = ArrayList<Zone>()
    private var aspect = 0.0

    /** The newest frame waiting to be analysed; the camera thread only sets it. */
    private val latest = java.util.concurrent.atomic.AtomicReference<ByteArray?>()

    @Volatile private var thread: Thread? = null

    /** Per-cell running background, sized to the grid of the last frame. */
    private var cellBg: DoubleArray? = null
    private var gridW = 0
    private var gridH = 0

    /** Whether any zone is over its threshold right now. */
    @Volatile var active = false
        private set

    private var lastTrigger = 0L

    fun zoneCount(): Int = synchronized(zones) { zones.size }

    /** The aspect ratio the zones were drawn for, or 0 with no zones. */
    fun zoneAspect(): Double = synchronized(zones) { aspect }

    /**
     * A stable copy of the zone list for the page: 1-based id, sensitivity,
     * normalized points and the live level.
     */
    class Snapshot(val id: Int, val sensitivity: Int, val points: List<Pair<Double, Double>>, val level: Int)

    fun snapshot(): List<Snapshot> = synchronized(zones) {
        zones.mapIndexed { i, z -> Snapshot(i + 1, z.sensitivity, z.points, z.level) }
    }

    // ---- configuration ----------------------------------------------------

    /** Load zones and the enabled flag from Prefs; called at service start. */
    fun resume() {
        load(Prefs.motionZones)
        setEnabled(Prefs.motionEnabled && Saf.tree != null)
    }

    /** Arm or disarm monitoring. Arming needs somewhere to put recordings. */
    fun setEnabled(on: Boolean): String? {
        if (on && Saf.tree == null) {
            return "Choose a storage location on the phone first: motion recordings go into its video subfolder."
        }
        enabled = on
        Prefs.motionEnabled = on
        Log.i(TAG, "motion activation ${if (on) "on" else "off"} with ${zoneCount()} zones")
        if (on) start() else stop()
        return null
    }

    /** Replace the zones ("aspect;sens:x,y,...;...") and remember them. */
    fun setZones(spec: String): String? {
        load(spec)
        Prefs.motionZones = if (zones.isEmpty()) null else spec
        Log.i(TAG, "zones set: ${zoneCount()} at aspect $aspect")
        return null
    }

    /** Forget the zones; used when a resolution change invalidates them. */
    fun clearZones() {
        synchronized(zones) { zones.clear() }
        Prefs.motionZones = null
    }

    /** Seconds of quiet after the last motion before a motion recording ends. */
    fun quietSeconds(): Int = Prefs.motionQuiet

    fun setQuiet(seconds: Int) {
        Prefs.motionQuiet = seconds
    }

    private fun load(spec: String?) {
        synchronized(zones) {
            zones.clear()
            aspect = 0.0
            spec ?: return
            try {
                val parts = spec.split(';')
                aspect = parts[0].toDoubleOrNull() ?: 0.0
                for (z in parts.drop(1)) {
                    val s = z.substringBefore(':').toIntOrNull()?.coerceIn(0, 100) ?: continue
                    val nums = z.substringAfter(':').split(',').map { it.toDouble() }
                    if (nums.size < 6 || nums.size % 2 != 0) continue
                    zones.add(Zone(s, nums.chunked(2).map { Pair(it[0], it[1]) }))
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad zone data: ${e.message}")
                zones.clear()
            }
        }
    }

    // ---- frame path -------------------------------------------------------

    /**
     * The camera's frame path calls this; it only remembers the newest frame
     * and must stay cheap.
     */
    fun push(jpeg: ByteArray) {
        if (enabled && zones.isNotEmpty()) latest.set(jpeg)
    }

    @Synchronized
    private fun start() {
        if (thread?.isAlive == true) return
        cellBg = null
        thread = Thread({
            while (enabled) {
                val jpeg = latest.getAndSet(null)
                if (jpeg != null) {
                    try {
                        analyse(jpeg)
                    } catch (e: Exception) {
                        Log.w(TAG, "motion analysis failed: ${e.message}")
                    }
                }
                try { Thread.sleep(ANALYSE_EVERY_MS) } catch (_: InterruptedException) { break }
            }
        }, "andwebcam-motion").apply { isDaemon = true; start() }
    }

    private fun stop() {
        thread?.interrupt()
        thread = null
        latest.set(null)
        active = false
        synchronized(zones) { zones.forEach { it.level = 0 } }
    }

    // ---- the analysis -----------------------------------------------------

    private fun analyse(jpeg: ByteArray) {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize() }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return
        var w = bmp.width
        var h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        // Luma only, then turned the same way up as the stream's viewer sees
        // it: the zones were drawn on the rotated image.
        var grey = ByteArray(w * h)
        for (i in pixels.indices) {
            val v = pixels[i]
            grey[i] = (((v shr 16 and 0xFF) * 66 + (v shr 8 and 0xFF) * 129 + (v and 0xFF) * 25) shr 8).toByte()
        }
        val turned = turn(grey, w, h, Prefs.orientation)
        grey = turned.first
        w = turned.second
        h = turned.third

        val gw = if (w >= h) 32 else 18
        val gh = if (w >= h) 18 else 32
        var means = DoubleArray(gw * gh)
        val counts = IntArray(gw * gh)
        for (y in 0 until h) {
            val cy = Math.min(gh - 1, y * gh / h)
            val row = cy * gw
            for (x in 0 until w) {
                val c = row + Math.min(gw - 1, x * gw / w)
                val g = grey[y * w + x].toInt() and 0xFF
                means[c] = means[c] + g
                counts[c]++
            }
        }
        for (c in means.indices) {
            if (counts[c] > 0) {
                means[c] = means[c] / counts[c]
            }
        }

        var bg = cellBg
        if (bg == null || gridW != gw || gridH != gh || bg.size != means.size) {
            cellBg = means          // first frame, or the frame shape moved
            gridW = gw
            gridH = gh
            return
        }

        // Changed cells, and the global-rejection check: an exposure step or a
        // scene cut moves every cell at once, which is scenery, not motion.
        val changed = BooleanArray(means.size)
        var changedCount = 0
        for (c in means.indices) {
            val d = Math.abs(means[c] - bg[c])
            changed[c] = d > CELL_DELTA
            if (changed[c]) changedCount++
        }
        if (changedCount > means.size * GLOBAL_SKIP) {
            cellBg = means.copyOf()
            active = false
            synchronized(zones) { zones.forEach { it.level = 0 } }
            return
        }

        // Scenery absorbs slowly, wherever it came from: a person who stands
        // still becomes background in about twenty seconds, which is what
        // lets a recording end.
        for (c in bg.indices) {
            bg[c] += (means[c] - bg[c]) / BG_RATE
        }

        // Blobs of connected changed cells (8-connected).
        val blobOf = IntArray(means.size) { -1 }
        val blobSizes = ArrayList<Int>()
        for (start in means.indices) {
            if (!changed[start] || blobOf[start] >= 0) continue
            val id = blobSizes.size
            var size = 0
            val stack = ArrayDeque<Int>()
            stack.add(start)
            blobOf[start] = id
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                size++
                val cx = c % gw
                val cy = c / gw
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val n = ny * gw + nx
                    if (changed[n] && blobOf[n] < 0) {
                        blobOf[n] = id
                        stack.add(n)
                    }
                }
            }
            blobSizes.add(size)
        }

        // A blob belongs to a zone when any of its cells' centres is inside
        // the zone's polygon; the zone's level is its biggest such blob.
        var nowActive = false
        val fired = ArrayList<Int>()
        synchronized(zones) {
            for ((index, zone) in zones.withIndex()) {
                var biggest = 0
                for ((id, size) in blobSizes.withIndex()) {
                    if (size <= biggest) continue
                    for (c in 0 until means.size) {
                        if (blobOf[c] != id) continue
                        if (inside(zone.points, (c % gw + 0.5) / gw, (c / gw + 0.5) / gh)) {
                            biggest = size
                            break
                        }
                    }
                }
                // Slider scale: level is the sensitivity that would fire on
                // this blob, so the fill reaching the handle means firing.
                zone.level = (((biggest - 1) * 100) / MAX_BLOB).coerceIn(0, 100)
                val need = 1 + (zone.sensitivity * MAX_BLOB) / 100
                if (biggest >= need) {
                    nowActive = true
                    val now = System.currentTimeMillis()
                    if (now - lastTrigger > COOLDOWN_MS) {
                        lastTrigger = now
                        fired.add(index + 1)
                    }
                }
            }
        }
        active = nowActive
        if (fired.isNotEmpty()) {
            Log.i(TAG, "motion in zone ${fired.joinToString(", ")}")
            onTrigger?.invoke(fired.first())
        }
    }

    private fun inside(points: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val (xi, yi) = points[i]
            val (xj, yj) = points[j]
            if ((yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi) + xi
            ) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Decode small enough that the analysis frame is about [TARGET_W] wide:
     * the sample size must be a power of two, so the real width lands within
     * one step of the target.
     */
    private fun sampleSize(): Int {
        val target = Prefs.resolution.split('x').let { (it.getOrNull(0)?.toIntOrNull() ?: TARGET_W) }
        var sample = 1
        while (target / (sample * 2) >= TARGET_W) sample *= 2
        return sample
    }

    /** Turn grey pixels the way the JPEG's EXIF turns them for viewers. */
    private fun turn(
        grey: ByteArray,
        w: Int,
        h: Int,
        degrees: Int,
    ): Triple<ByteArray, Int, Int> {
        when (((degrees % 360) + 360) % 360) {
            90 -> {
                val out = ByteArray(grey.size)
                for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * h + x] = grey[y * w + x]
                return Triple(out, h, w)
            }
            180 -> {
                val out = ByteArray(grey.size)
                for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = grey[y * w + x]
                return Triple(out, w, h)
            }
            270 -> {
                val out = ByteArray(grey.size)
                for (y in 0 until h) for (x in 0 until w) out[y * h + (w - 1 - x)] = grey[y * w + x]
                return Triple(out, h, w)
            }
            else -> return Triple(grey, w, h)
        }
    }
}
