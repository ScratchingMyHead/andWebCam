package net.andwebcam

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.round

/**
 * Motion activation: watches the stream's own JPEG frames for objects moving
 * inside user-drawn zones, and says when a zone fires.
 *
 * The analysis runs on a small greyscale copy of the frame at a fraction of
 * the stream's rate, as a pipeline:
 *
 * 1. **Cell means.** The frame is divided into a grid of square-ish cells
 *    (32 across the long edge by default) and each cell's mean luminance is
 *    taken. Averaging many pixels crushes sensor noise, which is what keeps
 *    high-ISO static out of the trigger - a blur would do the same job less
 *    well and with an extra pass.
 * 2. **Background.** Each cell keeps a running average of what it usually
 *    looks like, absorbing change over about twenty seconds: someone who
 *    stands still becomes scenery eventually, which is what lets a recording
 *    end, and slow exposure drift is not mistaken for motion.
 * 3. **Exposure correction.** The median cell change is subtracted from every
 *    cell before comparing. A camera exposure step darkens or brightens the
 *    whole frame together, so it leaves almost no residual; a white car on a
 *    dark fence does not, so it is not thrown away with the exposure change.
 *    If more than half the cells still change after that, it really is a
 *    scene cut: the background is re-seated and the tick is skipped.
 * 4. **Blobs.** Connected changed cells (8-connected) are grouped, and a
 *    blob's size in cells is the size of the object, independent of how big
 *    the zone around it is.
 *
 * A zone (a polygon in normalized coordinates) fires when it contains a blob
 * of at least the size its sensitivity asks for: sensitivity 0 accepts a
 * single cell, 100 demands the biggest object the slider scale describes
 * (about a tenth of the frame). Because the threshold is a fraction of the
 * grid, the same head triggers the same way at any grid size.
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
        private const val GLOBAL_SKIP = 0.5         // most of the frame changing = exposure, not motion
        private const val COOLDOWN_MS = 5000L       // quiet time between triggers
        private const val HISTORY_MS = 20000L       // how far back a late poll can reach
        private const val TARGET_W = 320            // analysis frame width
        private const val BG_RATE = 32              // background absorbs 1/32 per tick (~20 s)

        /**
         * The user's 0-100 trigger sensitivity as a cell-mean threshold in
         * grey levels: 100 demands only 4 levels of change (very sensitive),
         * 0 demands 44 (a spotlight on the moon). The stored default of 85
         * sits at 10, where the fixed threshold used to be.
         */
        fun cellDelta(): Double = 44.0 - Prefs.motionLevel * 0.4

        /**
         * Blob size in cells that the top of the sensitivity slider asks
         * for - about a tenth of the frame, i.e. a person at room distance.
         * The slider maps 0-100 onto 1..this, so it keeps its meaning when
         * the grid is made finer or coarser.
         */
        fun maxBlob(gw: Int, gh: Int): Int = Math.max(4, gw * gh * 60 / 576)
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
    private var lastDecodeSig = ""

    // Latest tick's numbers, for the log and /api/status while tuning.
    @Volatile private var diagChanged = 0
    @Volatile private var diagCells = 0
    @Volatile private var diagShift = 0
    @Volatile private var diagPeak = 0
    @Volatile private var diagBiggest = 0
    @Volatile private var diagSkip = false
    private var lastDiagLog = 0L

    /** Recent activation events: overall level jumped by more than 2 points. */
    private val events = ArrayDeque<Map<String, Any>>()
    private var lastOverall = 0

    /** One analysis tick's per-zone levels, kept briefly for late polls. */
    private class Tick(val t: Long, val levels: IntArray)
    private val history = ArrayDeque<Tick>()

    fun diagnostics(): Map<String, Any> = mapOf(
        "changed" to diagChanged,
        "cells" to diagCells,
        "shift" to diagShift,
        "peak" to diagPeak,
        "biggest" to diagBiggest,
        "skip" to diagSkip,
    )

    /** Newest activation event last; a small ring buffer for the debug pane. */
    fun eventLog(): List<Map<String, Any>> = synchronized(events) { events.toList() }

    fun zoneCount(): Int = synchronized(zones) { zones.size }

    /** The aspect ratio the zones were drawn for, or 0 with no zones. */
    fun zoneAspect(): Double = synchronized(zones) { aspect }

    /**
     * A stable copy of the zone list for the page: 1-based id, sensitivity,
     * normalized points and the live level.
     */
    class Snapshot(
        val id: Int,
        val sensitivity: Int,
        val points: List<Pair<Double, Double>>,
        val level: Int,
        val peakSince: Int,
    )

    /**
     * The zone list plus, for each zone, the highest level since [since] (a
     * server timestamp the page echoes back from its previous poll). The page
     * polls slower than the analysis runs, so this is how a spike between two
     * polls is still delivered.
     */
    fun snapshotSince(since: Long): List<Snapshot> = synchronized(zones) {
        val peaks = IntArray(zones.size)
        if (since > 0) {
            synchronized(history) {
                for (tick in history) {
                    if (tick.t <= since) continue
                    val n = Math.min(peaks.size, tick.levels.size)
                    for (i in 0 until n) {
                        if (tick.levels[i] > peaks[i]) peaks[i] = tick.levels[i]
                    }
                }
            }
        }
        zones.mapIndexed { i, z ->
            val peak = if (since > 0) Math.max(peaks[i], z.level) else z.level
            Snapshot(i + 1, z.sensitivity, z.points, z.level, peak)
        }
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
        synchronized(history) { history.clear() }
        Log.i(TAG, "zones set: ${zoneCount()} at aspect $aspect")
        return null
    }

    /** Forget the zones; used when a resolution change invalidates them. */
    fun clearZones() {
        synchronized(zones) { zones.clear() }
        Prefs.motionZones = null
        synchronized(history) { history.clear() }
    }

    /** Seconds of quiet after the last motion before a motion recording ends. */
    fun quietSeconds(): Int = Prefs.motionQuiet

    /** The configured trigger sensitivity, 0-100, higher = more sensitive. */
    fun triggerLevel(): Int = Prefs.motionLevel

    fun setTriggerLevel(v: Int) {
        Prefs.motionLevel = v
    }

    /** Grid cells across the long edge, 4-160; the next tick picks it up. */
    fun gridSize(): Int = Prefs.motionGrid

    fun setGrid(cells: Int) {
        Prefs.motionGrid = cells
    }

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
        lastOverall = 0
        synchronized(zones) { zones.forEach { it.level = 0 } }
        synchronized(history) { history.clear() }
    }

    // ---- the analysis -----------------------------------------------------

    private fun analyse(jpeg: ByteArray) {
        // Decode straight to the size the analyser wants, whatever the camera
        // was set to. ImageDecoder can target an arbitrary size (unlike
        // BitmapFactory's power-of-two sampling) and honours the JPEG's EXIF
        // orientation, so the analysis frame matches what the viewer sees.
        var srcW = 0
        var srcH = 0
        var tw = TARGET_W
        var th = TARGET_W
        val bmp = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(ByteBuffer.wrap(jpeg)),
        ) { decoder, info, _ ->
            // ImageInfo already accounts for the JPEG's EXIF orientation, so
            // these are the dimensions the viewer sees. No extra rotation.
            srcW = info.size.width
            srcH = info.size.height
            if (srcW >= srcH) {
                tw = TARGET_W
                th = Math.max(1, round(TARGET_W.toFloat() * srcH / srcW).toInt())
            } else {
                th = TARGET_W
                tw = Math.max(1, round(TARGET_W.toFloat() * srcW / srcH).toInt())
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetSize(tw, th)
        }
        var w = bmp.width
        var h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        val sig = "$srcW x $srcH -> $tw x $th"
        if (sig != lastDecodeSig) {
            lastDecodeSig = sig
            Log.i(TAG, "motion decode: $sig (got ${w}x${h})")
        }

        // Luma only. ImageDecoder has already applied the JPEG's EXIF
        // orientation, so no further rotation is needed here.
        var grey = ByteArray(w * h)
        for (i in pixels.indices) {
            val v = pixels[i]
            grey[i] = (((v shr 16 and 0xFF) * 66 + (v shr 8 and 0xFF) * 129 + (v and 0xFF) * 25) shr 8).toByte()
        }
        // The grid follows the user's setting, keeping cells roughly square
        // and the cell count along the long edge fixed.
        val cells = Prefs.motionGrid.coerceIn(4, 160)
        val gw: Int
        val gh: Int
        if (w >= h) {
            gw = cells
            gh = Math.max(2, Math.round(cells * h.toFloat() / w))
        } else {
            gh = cells
            gw = Math.max(2, Math.round(cells * w.toFloat() / h))
        }
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

        // Change is measured against the running background, with the median
        // change of the whole frame removed. That median is the camera's
        // exposure moving (a bright car makes the AE darken everything a
        // little): it is scenery-wide, so subtracting it keeps a car from
        // taking the whole tick with it.
        val changed = BooleanArray(means.size)
        val deltas = DoubleArray(means.size)
        for (c in means.indices) deltas[c] = means[c] - bg[c]
        val sorted = deltas.copyOf()
        sorted.sort()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        var changedCount = 0
        var peak = 0.0
        for (c in means.indices) {
            val d = Math.abs(deltas[c] - median)
            if (d > peak) peak = d
            changed[c] = d > cellDelta()
            if (changed[c]) changedCount++
        }

        // Even after the exposure correction, most of the frame changing is
        // a scene cut rather than motion: re-seat the background and skip.
        if (changedCount > means.size * GLOBAL_SKIP) {
            cellBg = means.copyOf()
            active = false
            synchronized(zones) { zones.forEach { it.level = 0 } }
            lastOverall = 0
            record(changedCount, means.size, median, peak, 0, true, "")
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
        val mb = maxBlob(gw, gh)
        var biggestAny = 0
        var overall = 0
        val levels = StringBuilder()
        val tickNow = System.currentTimeMillis()
        var tickLevels: IntArray
        synchronized(zones) {
            tickLevels = IntArray(zones.size)
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
                if (biggest > biggestAny) biggestAny = biggest
                // Slider scale: level is the sensitivity that would fire on
                // this blob, so the fill reaching the handle means firing.
                zone.level = ((biggest * 100) / mb).coerceIn(0, 100)
                tickLevels[index] = zone.level
                if (zone.level > overall) overall = zone.level
                val need = 1 + (zone.sensitivity * (mb - 1)) / 100
                if (levels.isNotEmpty()) levels.append(' ')
                levels.append("z${index + 1}=$biggest")
                if (biggest >= need) {
                    nowActive = true
                    if (tickNow - lastTrigger > COOLDOWN_MS) {
                        lastTrigger = tickNow
                        fired.add(index + 1)
                    }
                }
            }
        }
        synchronized(history) {
            history.addLast(Tick(tickNow, tickLevels))
            while (history.isNotEmpty() && tickNow - history.first().t > HISTORY_MS) {
                history.removeFirst()
            }
        }
        active = nowActive
        record(changedCount, means.size, median, peak, biggestAny, false, levels.toString())
        // The user watches the strongest zone's level. A rise of more than
        // two points is worth a debug line: it is an object appearing or
        // growing, not noise hovering around zero.
        if (overall - lastOverall > 2) {
            synchronized(events) {
                events.addLast(
                    mapOf(
                        "t" to System.currentTimeMillis(),
                        "overall" to overall,
                        "changed" to changedCount,
                        "cells" to means.size,
                        "shift" to diagShift,
                        "peak" to diagPeak,
                        "biggest" to biggestAny,
                        "zones" to levels.toString(),
                    ),
                )
                while (events.size > 40) events.removeFirst()
            }
        }
        lastOverall = overall
        if (fired.isNotEmpty()) {
            Log.i(TAG, "motion in zone ${fired.joinToString(", ")}")
            onTrigger?.invoke(fired.first())
        }
    }

    /**
     * Keep the latest tick's numbers and, at most once a second, write a line
     * when something happened. A static scene stays silent, so a car going by
     * is easy to find in the log afterwards.
     */
    private fun record(
        changed: Int,
        total: Int,
        median: Double,
        peak: Double,
        biggest: Int,
        skip: Boolean,
        levels: String,
    ) {
        diagChanged = changed
        diagCells = total
        diagShift = Math.round(median).toInt()
        diagPeak = Math.round(peak).toInt()
        diagBiggest = biggest
        diagSkip = skip
        val now = System.currentTimeMillis()
        if ((!skip && changed == 0) || now - lastDiagLog < 1000) return
        lastDiagLog = now
        Log.i(
            TAG,
            "motion tick: changed $changed/$total shift $diagShift peak $diagPeak " +
                "biggest $biggest${if (levels.isEmpty()) "" else " $levels"}" +
                (if (skip) " (skipped)" else ""),
        )
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
}
