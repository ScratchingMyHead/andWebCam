package net.andwebcam

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * What a frame analyser produces for one tick: candidate moving objects in
 * normalized frame coordinates, plus the numbers the debug pane shows.
 *
 * Both detectors work on the same small frame and return the same shape of
 * result, so zone matching, object tracking and triggering stay identical.
 */
class Detection(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** Position on the zone slider's 0-100 "minimum object size" scale. */
    val level: Int,
)

class Analysis(
    val candidates: List<Detection> = emptyList(),
    val changed: Int = 0,
    val total: Int = 0,
    val shift: Double = 0.0,
    val peak: Double = 0.0,
    val skip: Boolean = false,
    val rejectedLight: Int = 0,
)

interface FrameDetector {
    val name: String
    val error: String?
    fun analyse(bitmap: Bitmap, grey: ByteArray, w: Int, h: Int, sensitivity: Int): Analysis
    fun reset()
}

/**
 * The original detector: cell means against a running background, with the
 * frame-wide median removed to survive exposure steps. Blobs of cells become
 * objects. Kept as the default because it has no native dependency.
 */
class GridDetector : FrameDetector {
    override val name = "grid"
    override val error: String? = null

    private var bg: DoubleArray? = null
    private var gridW = 0
    private var gridH = 0

    override fun reset() {
        bg = null
        gridW = 0
        gridH = 0
    }

    override fun analyse(bitmap: Bitmap, grey: ByteArray, w: Int, h: Int, sensitivity: Int): Analysis {
        val cells = Prefs.motionGrid.coerceIn(4, 160)
        val gw: Int
        val gh: Int
        if (w >= h) {
            gw = cells
            gh = max(2, round(cells * h.toFloat() / w).toInt())
        } else {
            gh = cells
            gw = max(2, round(cells * w.toFloat() / h).toInt())
        }
        val means = DoubleArray(gw * gh)
        val counts = IntArray(gw * gh)
        for (y in 0 until h) {
            val cy = min(gh - 1, y * gh / h)
            val row = cy * gw
            for (x in 0 until w) {
                val c = row + min(gw - 1, x * gw / w)
                means[c] = means[c] + (grey[y * w + x].toInt() and 0xFF)
                counts[c]++
            }
        }
        for (c in means.indices) if (counts[c] > 0) means[c] = means[c] / counts[c]

        var background = bg
        if (background == null || gridW != gw || gridH != gh || background.size != means.size) {
            bg = means
            gridW = gw
            gridH = gh
            return Analysis(total = means.size)
        }

        // The median of the frame's changes is the exposure step. Subtracting
        // it leaves local objects while removing a whole-frame brightness jump.
        val deltas = DoubleArray(means.size) { means[it] - background[it] }
        val sorted = deltas.copyOf()
        sorted.sort()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val threshold = 44.0 - sensitivity * 0.4
        val changed = BooleanArray(means.size)
        var changedCount = 0
        var peak = 0.0
        for (c in means.indices) {
            val d = kotlin.math.abs(deltas[c] - median)
            if (d > peak) peak = d
            changed[c] = d > threshold
            if (changed[c]) changedCount++
        }
        if (changedCount > means.size * 0.5) {
            bg = means.copyOf()
            return Analysis(total = means.size, changed = changedCount, shift = median, peak = peak, skip = true)
        }
        for (c in background.indices) background[c] += (means[c] - background[c]) / 32.0

        // 8-connected blobs of changed cells.
        val blobOf = IntArray(means.size) { -1 }
        val sizes = ArrayList<Int>()
        val boxes = ArrayList<IntArray>()  // minX, minY, maxX, maxY per blob
        for (start in means.indices) {
            if (!changed[start] || blobOf[start] >= 0) continue
            val id = sizes.size
            var size = 0
            var minX = gw
            var minY = gh
            var maxX = -1
            var maxY = -1
            val stack = ArrayDeque<Int>()
            stack.add(start)
            blobOf[start] = id
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                size++
                val cx = c % gw
                val cy = c / gw
                minX = min(minX, cx); maxX = max(maxX, cx)
                minY = min(minY, cy); maxY = max(maxY, cy)
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
            sizes.add(size)
            boxes.add(intArrayOf(minX, minY, maxX, maxY))
        }

        val maxBlob = max(4, gw * gh * 60 / 576)
        val out = ArrayList<Detection>()
        for ((id, size) in sizes.withIndex()) {
            if (size <= 0) continue
            val b = boxes[id]
            out.add(
                Detection(
                    x = b[0].toFloat() / gw,
                    y = b[1].toFloat() / gh,
                    w = (b[2] - b[0] + 1).toFloat() / gw,
                    h = (b[3] - b[1] + 1).toFloat() / gh,
                    level = (size * 100 / maxBlob).coerceIn(0, 100),
                )
            )
        }
        return Analysis(out, changedCount, means.size, median, peak)
    }
}

/**
 * OpenCV detector: real connected components after morphology, plus a
 * texture-preservation test that tells illumination changes from objects.
 *
 * A reflection scales the surface's existing texture, so the crop inside the
 * candidate still correlates strongly with the background crop. A moving
 * object replaces that texture and correlates poorly. Bright reflections that
 * clip to flat white are rejected as low variance. The check is deliberately
 * conservative; the pane draws every candidate so it can be judged by eye.
 */
class CvDetector : FrameDetector {
    override val name = "opencv"

    private val loadError: String?
    private val mogLock = Any()

    private var mog: org.opencv.video.BackgroundSubtractor? = null
    private var bg: FloatArray? = null
    private var bgW = 0
    private var bgH = 0
    private var threshold = 16.0
    private var lastSensitivity = -1

    init {
        loadError = try {
            System.loadLibrary("opencv_java4")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV native library unavailable: ${e.message}")
            "OpenCV is unavailable: ${e.message}"
        }
    }

    override val error: String? get() = loadError

    override fun reset() {
        synchronized(mogLock) {
            bg = null
            bgW = 0
            bgH = 0
            mog = null
            lastSensitivity = -1
        }
    }

    private fun newMog(sensitivity: Int) {
        // Higher sensitivity means a lower difference is enough to count.
        threshold = (40 - sensitivity * 0.28).coerceIn(8.0, 40.0)
        mog = org.opencv.video.Video.createBackgroundSubtractorMOG2(400, threshold, true)
        lastSensitivity = sensitivity
    }

    override fun analyse(bitmap: Bitmap, grey: ByteArray, w: Int, h: Int, sensitivity: Int): Analysis {
        val err = loadError
        if (err != null) return Analysis(skip = true)
        val n = w * h
        val cur = FloatArray(n) { (grey[it].toInt() and 0xFF).toFloat() }

        var background = bg
        if (background == null || bgW != w || bgH != h || background.size != n) {
            bg = cur
            bgW = w
            bgH = h
            synchronized(mogLock) { newMog(sensitivity) }
            return Analysis(total = n)
        }

        val rgba = org.opencv.core.Mat()
        val gmat = org.opencv.core.Mat()
        val mask = org.opencv.core.Mat()
        val opened = org.opencv.core.Mat()
        val cleaned = org.opencv.core.Mat()
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = org.opencv.core.Mat()
        try {
            val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            org.opencv.android.Utils.bitmapToMat(bmp, rgba)
            org.opencv.imgproc.Imgproc.cvtColor(rgba, gmat, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)

            synchronized(mogLock) {
                if (mog == null || lastSensitivity != sensitivity) newMog(sensitivity)
                mog!!.apply(gmat, mask)
            }
            // MOG2 paints shadow pixels 127. They are not objects.
            val bytes = ByteArray(n)
            mask.get(0, 0, bytes)
            var shadowCount = 0
            for (i in 0 until n) {
                val v = bytes[i].toInt() and 0xFF
                if (v == 127) { bytes[i] = 0; shadowCount++ }
            }
            mask.put(0, 0, bytes)

            var changed = 0
            for (i in 0 until n) if (bytes[i].toInt() and 0xFF != 0) changed++
            if (changed > n * 0.45) {
                // A scene cut or a big exposure step: reseat both models.
                synchronized(mogLock) { newMog(sensitivity) }
                bg = cur
                return Analysis(total = n, changed = changed, skip = true)
            }

            val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
                org.opencv.imgproc.Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(3.0, 3.0)
            )
            org.opencv.imgproc.Imgproc.morphologyEx(
                mask, opened, org.opencv.imgproc.Imgproc.MORPH_OPEN, kernel
            )
            org.opencv.imgproc.Imgproc.dilate(opened, cleaned, kernel)
            contours.clear()
            org.opencv.imgproc.Imgproc.findContours(
                cleaned, contours, hierarchy,
                org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
                org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val gate = Prefs.motionLightGate
            val minArea = n * (0.003 + (100 - sensitivity) * 0.00012)
            val maxArea = n * 0.12
            val out = ArrayList<Detection>()
            var rejected = 0
            var peak = 0.0
            var sumDelta = 0.0
            for (i in 0 until n) {
                val d = kotlin.math.abs(cur[i] - background!![i])
                sumDelta += d
                if (d > peak) peak = d.toDouble()
            }
            for (c in contours) {
                val area = org.opencv.imgproc.Imgproc.contourArea(c)
                if (area < minArea) continue
                val r = org.opencv.imgproc.Imgproc.boundingRect(c)
                if (r.width < 3 || r.height < 3) continue
                if (r.width.toFloat() / r.height > 6f || r.height.toFloat() / r.width > 6f) continue
                if (area / (r.width * r.height).toDouble() < 0.25) continue
                if (gate && looksLikeLight(background!!, cur, w, h, r.x, r.y, r.width, r.height)) {
                    rejected++
                    continue
                }
                out.add(
                    Detection(
                        x = r.x.toFloat() / w,
                        y = r.y.toFloat() / h,
                        w = r.width.toFloat() / w,
                        h = r.height.toFloat() / h,
                        level = (area * 100 / maxArea).toInt().coerceIn(1, 100),
                    )
                )
            }
            // Slow background absorption: somebody standing still becomes
            // scenery in a few tens of seconds and a recording can end.
            for (i in 0 until n) background!![i] += (cur[i] - background!![i]) / 24f
            return Analysis(out, changed, n, sumDelta / n, peak, false, rejected + shadowCount)
        } finally {
            rgba.release(); gmat.release(); mask.release()
            opened.release(); cleaned.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Correlation of the mean-centred crops. Illumination scales the surface
     * pattern, so the shapes still line up; a different object does not.
     * A clipped white reflection has almost no texture of its own and is also
     * treated as light, provided the background behind it had texture.
     */
    private fun looksLikeLight(
        bg: FloatArray, cur: FloatArray, w: Int, h: Int,
        x: Int, y: Int, bw: Int, bh: Int,
    ): Boolean {
        val x0 = max(0, x); val y0 = max(0, y)
        val x1 = min(w, x + bw); val y1 = min(h, y + bh)
        if (x1 - x0 < 4 || y1 - y0 < 4) return false
        var count = 0
        var sb = 0.0; var sc = 0.0
        for (yy in y0 until y1 step 2) for (xx in x0 until x1 step 2) {
            val i = yy * w + xx
            sb += bg[i]; sc += cur[i]; count++
        }
        if (count < 12) return false
        val mb = sb / count; val mc = sc / count
        var vb = 0.0; var vc = 0.0; var cov = 0.0
        for (yy in y0 until y1 step 2) for (xx in x0 until x1 step 2) {
            val i = yy * w + xx
            val a = bg[i] - mb
            val b = cur[i] - mc
            vb += a * a; vc += b * b; cov += a * b
        }
        if (vb < 1.0) return false  // a flat background: compare normally
        if (vc < vb * 0.15) return true  // clipped to a flat bright patch
        val corr = cov / sqrt(vb * vc)
        return corr > 0.55
    }

    private companion object {
        const val TAG = "andWebCam"
    }
}
