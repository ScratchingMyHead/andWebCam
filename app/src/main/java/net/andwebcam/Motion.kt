package net.andwebcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Motion activation: watches the stream's own JPEG frames for change inside
 * user-drawn zones, and says when a zone fires.
 *
 * The analysis deliberately runs at a fraction of the stream's rate and on a
 * tiny greyscale copy of the frame - a decode a couple of hundred pixels wide
 * every fifth frame costs a few milliseconds - so a phone can monitor all
 * night. Each zone is a polygon in normalized coordinates (0..1 of the
 * *displayed* frame), so the same zones work at any resolution with the
 * aspect ratio they were drawn for.
 *
 * Zones arrive as the compact string Prefs stores:
 * "aspect;sens:x,y,x,y,...;sens:...". Sensitivity 0..100 maps to how small a
 * fraction of the zone must change: higher sensitivity, smaller fraction.
 *
 * A trigger is reported once per activation through [onTrigger]; the service
 * decides what that means. [active] says whether any zone is currently above
 * its threshold, which is what keeps a motion recording alive.
 */
class Motion {
    companion object {
        private const val TAG = "andWebCam"
        private const val ANALYSE_EVERY_MS = 200L   // ~5 fps is plenty for motion
        private const val PIXEL_DELTA = 20          // grey levels that count as changed
        private const val COOLDOWN_MS = 5000L       // quiet time between triggers
        private const val TARGET_W = 160            // analysis frame width
    }

    /** A parsed zone with its live state. */
    class Zone(val sensitivity: Int, val points: List<Pair<Double, Double>>) {
        var level = 0                        // last measured change, percent of the zone
        internal var mask = IntArray(0)      // indices of the zone's pixels in the analysis frame
        internal var maskW = 0
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
    private var prevGrey: ByteArray? = null
    private var prevW = 0
    private var prevH = 0
    private var lastTrigger = 0L

    /** Whether any zone is over its threshold right now. */
    @Volatile var active = false
        private set

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
        prevGrey = null
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

        val prev = prevGrey
        val comparable = prev != null && prev.size == grey.size &&
            synchronized(zones) { zones.isNotEmpty() && zones.all { it.maskW == w && it.mask.size > 0 } }
        if (comparable) {
            var nowActive = false
            val fired = ArrayList<Int>()
            synchronized(zones) {
                for ((index, zone) in zones.withIndex()) {
                    var changed = 0
                    for (p in zone.mask) {
                        val d = (grey[p] - prev!![p]).toInt()
                        if (d > PIXEL_DELTA || d < -PIXEL_DELTA) changed++
                    }
                    val level = (changed * 100 / zone.mask.size).coerceIn(0, 100)
                    zone.level = level
                    // Sensitivity 100 demands 0.5 percent of the zone to
                    // change, 0 demands 40 percent: a curve that keeps the top
                    // of the slider honest instead of firing on nothing.
                    val s = zone.sensitivity / 100.0
                    val need = (0.4 * (1 - s) * (1 - s) * 100).toInt() + 1
                    if (level >= need) {
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

        // The zone masks are rebuilt whenever the frame size moves or the
        // zones change, so they always match this decode size.
        rebuildMasks(w, h)
        prevGrey = grey
        prevW = w
        prevH = h
    }

    private fun rebuildMasks(w: Int, h: Int) {
        synchronized(zones) {
            for (zone in zones) {
                if (zone.maskW == w && zone.mask.size > 0) continue
                val mask = ArrayList<Int>(w * h / 4)
                // A point is inside the polygon when a ray to the left crosses
                // an odd number of its edges.
                for (y in 0 until h) {
                    val ny = (y + 0.5) / h
                    for (x in 0 until w) {
                        val nx = (x + 0.5) / w
                        if (inside(zone.points, nx, ny)) mask.add(y * w + x)
                    }
                }
                zone.mask = mask.toIntArray()
                zone.maskW = w
            }
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
