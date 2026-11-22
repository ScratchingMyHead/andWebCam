package net.andwebcam

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Motion activation and the live object highlights in the motion pane.
 *
 * Frames from the camera thread are kept in [latest]; one analysis thread
 * decodes the newest frame to a small greyscale image and hands it to the
 * selected [FrameDetector]. Both detectors produce normalized object boxes;
 * the rest - tracking across ticks, matching objects to zones, triggering
 * recordings and feeding the pane - is shared, so switching detectors changes
 * only how objects are found, not what a zone means.
 *
 * Analysis runs while motion is armed **or** while the motion pane is open.
 * The latter is a lease refreshed by the pane's poll so that boxes are drawn
 * even with motion switched off, and so a closed browser releases the camera.
 */
class Motion {
    companion object {
        private const val TAG = "andWebCam"
        private const val ANALYSE_EVERY_MS = 200L   // ~5 fps is plenty for motion
        private const val COOLDOWN_MS = 5000L       // quiet time between triggers
        private const val HISTORY_MS = 20000L       // how far back a late poll can reach
        private const val TARGET_W = 320            // analysis frame width
        private const val PREVIEW_MS = 10_000L      // pane lease; poll refreshes it
        private const val MIN_HITS = 2              // object must survive two ticks
        private const val FIRST_SIGHT_LEVEL = 10    // big enough to draw on first sighting
        private const val UNCONFIRMED_MS = 600L     // a one-tick track is short-lived
        private const val TRACK_MS = 1500L          // confirmed tracks linger a little
    }

    /** A parsed zone with its live state. */
    class Zone(val sensitivity: Int, val max: Int, val points: List<Pair<Double, Double>>) {
        var level = 0            // largest object seen in the zone, on the slider's scale
    }

    /** A tracked object. Boxes are normalized to the displayed frame. */
    private class Track(
        var x: Float, var y: Float, var w: Float, var h: Float,
        var level: Int, val id: Int,
    ) {
        var hits = 1
        var seen = System.currentTimeMillis()
        var zone = 0
        var firing = false
    }

    /** Invoked when a zone fires; argument is the zone's 1-based position. */
    @Volatile var onTrigger: ((Int) -> Unit)? = null

    @Volatile var enabled = false
        private set

    private val zones = ArrayList<Zone>()
    private var aspect = 0.0

    /** Newest frame; the camera thread only sets it. */
    private val latest = AtomicReference<ByteArray?>()

    @Volatile private var thread: Thread? = null

    @Volatile private var previewUntil = 0L

    private val grid = GridDetector()
    private var cv: CvDetector? = null
    @Volatile private var detector: FrameDetector = grid
    @Volatile var detectorName: String = "grid"
        private set
    @Volatile var detectorError: String? = null
        private set

    private val tracks = ArrayList<Track>()
    private var nextTrackId = 1
    @Volatile private var boxes: List<Map<String, Any>> = emptyList()

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
    @Volatile private var diagRejected = 0
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
        "rejected" to diagRejected,
        "boxes" to boxes.size,
        "detector" to detectorName,
        "preview" to previewing(),
    )

    /** Newest activation event last; a small ring buffer for the debug pane. */
    fun eventLog(): List<Map<String, Any>> = synchronized(events) { events.toList() }

    fun zoneCount(): Int = synchronized(zones) { zones.size }

    /** The aspect ratio the zones were drawn for, or 0 with no zones. */
    fun zoneAspect(): Double = synchronized(zones) { aspect }

    /** The current object boxes for the pane's overlay. */
    fun boxSnapshot(): List<Map<String, Any>> = boxes

    fun previewing(): Boolean = System.currentTimeMillis() < previewUntil

    /**
     * The motion pane keeps its analysis alive by polling. A stale lease
     * expires on its own, so closing the tab lets the camera go to sleep.
     */
    fun leasePreview(ms: Long = PREVIEW_MS) {
        previewUntil = System.currentTimeMillis() + ms
        start()
    }

    fun stopPreview() {
        previewUntil = 0
        if (!enabled) stop()
    }

    class Snapshot(
        val id: Int,
        val sensitivity: Int,
        val max: Int,
        val points: List<Pair<Double, Double>>,
        val level: Int,
        val peakSince: Int,
    )

    /**
     * The zone list plus, for each zone, the highest level since [since] (a
     * server timestamp the page echoes back from its previous poll). The page
     * polls faster than the analysis runs, so this is how a spike between two
     * polls is still delivered.
     */
    fun snapshotSince(since: Long): List<Snapshot> = synchronized(zones) {
        val peaks = IntArray(zones.size)
        if (since > 0) {
            synchronized(history) {
                for (tick in history) {
                    if (tick.t <= since) continue
                    val n = minOf(peaks.size, tick.levels.size)
                    for (i in 0 until n) if (tick.levels[i] > peaks[i]) peaks[i] = tick.levels[i]
                }
            }
        }
        zones.mapIndexed { i, z ->
            val peak = if (since > 0) maxOf(peaks[i], z.level) else z.level
            Snapshot(i + 1, z.sensitivity, z.max, z.points, z.level, peak)
        }
    }

    // ---- configuration ----------------------------------------------------

    /** Load zones and the enabled flag from Prefs; called at service start. */
    fun resume() {
        load(Prefs.motionZones)
        selectDetector(Prefs.motionDetector)
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
        if (on) start() else if (!previewing()) stop()
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

    fun setTriggerLevel(v: Int) { Prefs.motionLevel = v }

    /** Grid cells across the long edge, 4-160; the next tick picks it up. */
    fun gridSize(): Int = Prefs.motionGrid

    fun setGrid(cells: Int) { Prefs.motionGrid = cells }

    fun setQuiet(seconds: Int) { Prefs.motionQuiet = seconds }

    /**
     * Switch analysers. OpenCV is loaded on first use, so grid-only users pay
     * nothing at runtime; if its native library is missing we keep using the
     * grid and report the reason in the pane.
     */
    fun selectDetector(name: String): String? {
        if (name !in listOf("grid", "opencv")) return "unknown detector"
        Prefs.motionDetector = name
        val next: FrameDetector
        if (name == "opencv") {
            val c = cv ?: CvDetector().also { cv = it }
            if (c.error != null) {
                detector = grid
                detectorName = "grid"
                detectorError = c.error
                Log.w(TAG, "OpenCV unavailable; staying on grid: ${c.error}")
                return c.error
            }
            next = c
        } else {
            next = grid
        }
        // The two detectors keep independent backgrounds; a switch starts
        // fresh so an old model cannot fire on the new detector's first frame.
        next.reset()
        detector = next
        detectorName = name
        detectorError = null
        Log.i(TAG, "motion detector: $name")
        return null
    }

    fun lightGate(): Boolean = Prefs.motionLightGate

    fun setLightGate(on: Boolean) { Prefs.motionLightGate = on }

    private fun load(spec: String?) {
        synchronized(zones) {
            zones.clear()
            aspect = 0.0
            spec ?: return
            try {
                val parts = spec.split(';')
                aspect = parts[0].toDoubleOrNull() ?: 0.0
                for (z in parts.drop(1)) {
                    // Zero would keep a zone firing on an empty frame.
                    val s = z.substringBefore(':').toIntOrNull()?.coerceIn(1, 100) ?: continue
                    // New form is "min:max:x,y,..."; older zones have no max
                    // and keep their old meaning of "anything above min".
                    val rest = z.substringAfter(':')
                    val max: Int
                    val coords: String
                    if (rest.contains(':')) {
                        max = rest.substringBefore(':').toIntOrNull()?.coerceIn(0, 100) ?: 100
                        coords = rest.substringAfter(':')
                    } else {
                        max = 100
                        coords = rest
                    }
                    val nums = coords.split(',').map { it.toDouble() }
                    if (nums.size < 6 || nums.size % 2 != 0) continue
                    zones.add(Zone(s, max, nums.chunked(2).map { Pair(it[0], it[1]) }))
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad zone data: ${e.message}")
                zones.clear()
            }
        }
    }

    // ---- frame path -------------------------------------------------------

    /** Cheap camera-thread callback: only remember the newest frame. */
    fun push(jpeg: ByteArray) {
        if (enabled || previewing()) latest.set(jpeg)
    }

    @Synchronized
    private fun start() {
        if (thread?.isAlive == true) return
        thread = Thread({
            while (enabled || previewing()) {
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
        tracks.clear()
        boxes = emptyList()
        synchronized(zones) { zones.forEach { it.level = 0 } }
        synchronized(history) { history.clear() }
    }

    // ---- the analysis -----------------------------------------------------

    private fun analyse(jpeg: ByteArray) {
        // Decode straight to the size the analyser wants. ImageDecoder honours
        // EXIF orientation, so the analysis frame matches what the viewer sees.
        var srcW = 0
        var srcH = 0
        var tw = TARGET_W
        var th = TARGET_W
        val bmp = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(ByteBuffer.wrap(jpeg)),
        ) { decoder, info, _ ->
            srcW = info.size.width
            srcH = info.size.height
            if (srcW >= srcH) {
                tw = TARGET_W
                th = max(1, round(TARGET_W.toFloat() * srcH / srcW).toInt())
            } else {
                th = TARGET_W
                tw = max(1, round(TARGET_W.toFloat() * srcW / srcH).toInt())
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetSize(tw, th)
        }
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val sig = "$srcW x $srcH -> $tw x $th"
        if (sig != lastDecodeSig) {
            lastDecodeSig = sig
            Log.i(TAG, "motion decode: $sig (got ${w}x${h})")
        }

        val grey = ByteArray(w * h)
        for (i in pixels.indices) {
            val v = pixels[i]
            grey[i] = (((v shr 16 and 0xFF) * 66 + (v shr 8 and 0xFF) * 129 + (v and 0xFF) * 25) shr 8).toByte()
        }
        // OpenCV needs the bitmap itself; the grid works from the grey bytes.
        val result = try {
            detector.analyse(bmp, grey, w, h, Prefs.motionLevel)
        } finally {
            bmp.recycle()
        }

        if (result.skip) {
            tracks.clear()
            boxes = emptyList()
            synchronized(zones) { zones.forEach { it.level = 0 } }
            active = false
            lastOverall = 0
            record(result, 0, true)
            return
        }

        // Track objects between ticks. A light sweep or a single noisy frame
        // never reaches two hits; a real object keeps its box and gets an id,
        // which also makes the pane's highlights stay still.
        val cand = result.candidates
        val matched = BooleanArray(cand.size)
        val now = System.currentTimeMillis()
        for (track in tracks) {
            var best = -1
            var bestDist = Float.MAX_VALUE
            for (i in cand.indices) {
                if (matched[i]) continue
                val c = cand[i]
                val d = trackDistance(track, c)
                if (d < bestDist) { bestDist = d; best = i }
            }
            // A vehicle near the camera can cross half the picture between
            // two ticks. IoU matching would call it a new object every time,
            // so associate by how far the centre moved instead.
            if (best >= 0 && bestDist < 0.45f) {
                val c = cand[best]
                matched[best] = true
                track.x += (c.x - track.x) * 0.7f
                track.y += (c.y - track.y) * 0.7f
                track.w += (c.w - track.w) * 0.7f
                track.h += (c.h - track.h) * 0.7f
                track.level = c.level
                track.hits++
                track.seen = now
            }
        }
        for (i in cand.indices) if (!matched[i]) {
            val c = cand[i]
            tracks.add(Track(c.x, c.y, c.w, c.h, c.level, nextTrackId++))
        }
        // An object seen on only one tick is probably real but unproven, so it
        // is kept just long enough to draw a box, not for a second and a half.
        tracks.removeAll {
            now - it.seen > if (it.hits >= MIN_HITS) TRACK_MS else UNCONFIRMED_MS
        }
        while (tracks.size > 16) tracks.removeAt(0)
        // A reasonably sized object is worth drawing on the first sighting;
        // small specks still need two ticks to prove they are not noise.
        val visible = tracks.filter { it.hits >= MIN_HITS || it.level >= FIRST_SIGHT_LEVEL }

        var nowActive = false
        val fired = ArrayList<Int>()
        var biggestAny = 0
        var overall = 0
        val levels = StringBuilder()
        val tickLevels = IntArray(zones.size)
        for (track in tracks) { track.zone = 0; track.firing = false }
        synchronized(zones) {
            for ((index, zone) in zones.withIndex()) {
                var biggest = 0
                var acceptable = 0
                for (track in visible) {
                    if (!overlapsZone(track, zone.points)) continue
                    if (track.level > biggest) biggest = track.level
                    // Levels stop at 100, so max 100 means no upper bound.
                    val fits = track.level >= zone.sensitivity &&
                        (zone.max >= 100 || track.level <= zone.max)
                    if (fits && track.level > acceptable) acceptable = track.level
                    if (track.zone == 0) {
                        track.zone = index + 1
                        track.firing = fits
                    }
                }
                if (biggest > biggestAny) biggestAny = biggest
                zone.level = biggest
                tickLevels[index] = biggest
                if (biggest > overall) overall = biggest
                if (levels.isNotEmpty()) levels.append(' ')
                levels.append("z${index + 1}=$biggest")
                if (enabled && acceptable > 0) {
                    nowActive = true
                    if (now - lastTrigger > COOLDOWN_MS) {
                        lastTrigger = now
                        fired.add(index + 1)
                    }
                }
            }
        }
        synchronized(history) {
            history.addLast(Tick(now, tickLevels))
            while (history.isNotEmpty() && now - history.first().t > HISTORY_MS) history.removeFirst()
        }
        active = nowActive
        boxes = visible.map { t ->
            mapOf(
                "x" to round(t.x * 1000) / 1000f,
                "y" to round(t.y * 1000) / 1000f,
                "w" to round(t.w * 1000) / 1000f,
                "h" to round(t.h * 1000) / 1000f,
                "id" to t.id,
                "level" to t.level,
                "zone" to t.zone,
                "firing" to t.firing,
            )
        }
        record(result, biggestAny, false, levels.toString())
        if (overall - lastOverall > 2) {
            synchronized(events) {
                events.addLast(
                    mapOf(
                        "t" to System.currentTimeMillis(),
                        "overall" to overall,
                        "changed" to result.changed,
                        "cells" to result.total,
                        "shift" to Math.round(result.shift).toInt(),
                        "peak" to Math.round(result.peak).toInt(),
                        "biggest" to biggestAny,
                        "rejected" to result.rejectedLight,
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

    /** How far a candidate's centre moved from a track's centre, 0-1 frame. */
    private fun trackDistance(a: Track, b: Detection): Float {
        val ax = a.x + a.w / 2f
        val ay = a.y + a.h / 2f
        val bx = b.x + b.w / 2f
        val by = b.y + b.h / 2f
        return kotlin.math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }

    /** Centroid or a corner inside the polygon is enough to count as inside. */
    private fun overlapsZone(t: Track, points: List<Pair<Double, Double>>): Boolean {
        val cx = t.x + t.w / 2.0
        val cy = t.y + t.h / 2.0
        if (inside(points, cx, cy)) return true
        for (x in listOf(t.x.toDouble(), (t.x + t.w).toDouble())) {
            for (y in listOf(t.y.toDouble(), (t.y + t.h).toDouble())) {
                if (inside(points, x, y)) return true
            }
        }
        for (p in points) if (p.first in t.x..(t.x + t.w) && p.second in t.y..(t.y + t.h)) return true
        return false
    }

    private fun record(result: Analysis, biggest: Int, skip: Boolean, levels: String = "") {
        diagChanged = result.changed
        diagCells = result.total
        diagShift = Math.round(result.shift).toInt()
        diagPeak = Math.round(result.peak).toInt()
        diagBiggest = biggest
        diagSkip = skip
        diagRejected = result.rejectedLight
        val now = System.currentTimeMillis()
        if ((!skip && result.changed == 0) || now - lastDiagLog < 1000) return
        lastDiagLog = now
        Log.i(
            TAG,
            "motion tick: changed ${result.changed}/${result.total} shift $diagShift peak $diagPeak " +
                "biggest $biggest rejected $diagRejected${if (levels.isEmpty()) "" else " $levels"}" +
                (if (skip) " (skipped)" else ""),
        )
    }

    private fun inside(points: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val (xi, yi) = points[i]
            val (xj, yj) = points[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
