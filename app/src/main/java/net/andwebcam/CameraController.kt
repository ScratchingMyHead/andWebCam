package net.andwebcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import kotlin.math.abs

/**
 * Owns a single camera2 session and pushes JPEG frames out through [onFrame].
 *
 * Everything runs on one private HandlerThread. Frame delivery is throttled to
 * the configured fps; the sensor may run faster, in which case extra frames are
 * dropped rather than encoded/sent.
 */
class CameraController(
    private val ctx: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    data class Caps(
        val facing: String,
        val resolutions: List<String>,
        val maxZoom: Float,
        val torchAvailable: Boolean,
        val fpsOptions: List<Int>,
        /** Highest rate the JPEG path advertises for the current size; 0 = unstated. */
        val maxFps: Int = 0,
    )

    companion object { private const val TAG = "andWebCam" }

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("andwebcam-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var loggedFirstFrame = false

    @Volatile private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private var chars: CameraCharacteristics? = null

    @Volatile
    private var size = Size(1280, 720)

    /** Current capture size; safe to read from the UI thread. */
    val currentSize: Size get() = size

    private var facing = "back"
    private var fps = 15
    private var quality = 80
    private var torch = false
    private var zoom = 1.0f
    private var orientation = 0

    private var nextFrameAt = 0.0
    private var opening = false
    private var restartQueued = false
    private var wantOpen = false

    val active: Boolean get() = device != null

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Frame rates the JPEG path can actually reach at each supported size. The
     * AE ranges say what the sensor can do, which is not the same thing: the
     * encoder's stall time can cap the stream well below it, and it does so
     * almost independently of the picture size.
     */
    private fun jpegRateTable(c: CameraCharacteristics): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return out
        map.getOutputSizes(ImageFormat.JPEG)?.forEach { s ->
            val cost = try {
                maxOf(
                    map.getOutputMinFrameDuration(ImageFormat.JPEG, s),
                    map.getOutputStallDuration(ImageFormat.JPEG, s),
                )
            } catch (e: Exception) {
                0L
            }
            out["${s.width}x${s.height}"] = if (cost > 0) (1_000_000_000.0 / cost).toInt() else 0
        }
        return out
    }

    fun capabilities(): Caps = try {
        val c = mgr.getCameraCharacteristics(findCameraId(Prefs.facing))
        val rates = jpegRateTable(c)
        // A size the encoder cannot drive at all is not offered for streaming.
        val sizes = rates.filterValues { it == 0 || it >= 5 }.keys.toList()
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val torchOk = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val rawRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val milli = rawRanges.any { it.upper > 1000 }
        val sensorFps = rawRanges.map { if (milli) it.upper / 1000 else it.upper }.distinct().sorted()
        // Only offer rates the current size can reach.
        val wanted = chooseSize(c, Prefs.resolution)
        val maxFps = rates["${wanted.width}x${wanted.height}"] ?: 0
        val fpsOpts = (if (maxFps > 0) sensorFps.filter { it <= maxFps } else sensorFps)
            .ifEmpty { sensorFps.take(1) }
        Caps(Prefs.facing, sizes, maxZoom, torchOk, fpsOpts, maxFps)
    } catch (e: Exception) {
        Caps(Prefs.facing, emptyList(), 1f, false, listOf(15, 24, 30))
    }

    /** (Re)open the camera from the current preferences. */
    fun start() = handler.post {
        wantOpen = true
        requestStart()
    }

    /** Apply settings that can change on a live repeating request. */
    fun applyLive() = handler.post { applyLiveInternal() }

    fun stop() = handler.post {
        wantOpen = false
        opening = false
        restartQueued = false
        stopInternal()
    }

    fun release() {
        handler.post {
            wantOpen = false
            stopInternal()
        }
        thread.quitSafely()
    }

    /**
     * Coalesces overlapping start requests: changing two camera settings at once
     * used to fire two concurrent openCamera() calls and disconnect the device.
     */
    private fun requestStart() {
        if (opening) {
            restartQueued = true
            return
        }
        startInternal()
    }

    // ------------------------------------------------------------------

    private fun startInternal() {
        fps = Prefs.fps
        quality = Prefs.quality
        torch = Prefs.torch
        zoom = Prefs.zoom
        orientation = Prefs.orientation
        facing = Prefs.facing

        if (!hasPermission()) {
            onError("Camera permission not granted")
            return
        }
        try {
            val id = findCameraId(facing)
            chars = mgr.getCameraCharacteristics(id)
            size = chooseSize(chars!!, diag?.resolution?.takeIf { it.isNotBlank() } ?: Prefs.resolution)
            val ranges = chars!!.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            Log.i(TAG, "open camera id=$id facing=$facing size=${size.width}x${size.height} fps=$fps")
            Log.i(TAG, "ae fps ranges: ${ranges?.joinToString { "(${it.lower},${it.upper})" } ?: "none"}")
            Log.i(
                TAG,
                "jpeg rate by size: " + jpegRateTable(chars!!)
                    .entries.joinToString(" ") { "${it.key}=${it.value}" },
            )
            loggedFirstFrame = false
            openDevice(id)
        } catch (e: Exception) {
            onError("Cannot open camera: ${e.message}")
        }
    }

    private fun openDevice(id: String) {
        stopInternal()
        opening = true
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    if (!wantOpen || restartQueued) {
                        restartQueued = false
                        try { d.close() } catch (_: Exception) {}
                        if (wantOpen) requestStart()
                        return
                    }
                    device = d
                    createSession(d)
                }

                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    d.close()
                    if (device === d) device = null
                    onError("Camera disconnected")
                    if (wantOpen && restartQueued) { restartQueued = false; requestStart() }
                }

                override fun onError(d: CameraDevice, error: Int) {
                    opening = false
                    d.close()
                    if (device === d) device = null
                    onError("Camera error $error")
                    if (wantOpen && restartQueued) { restartQueued = false; requestStart() }
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            onError("openCamera failed: ${e.message}")
        }
    }

    /**
     * Periodically logs what the sensor is doing against what we actually send.
     * Worth keeping: the advertised rates on some HALs bear no relation to the
     * ones they deliver, so this is the only way to see the truth on a device.
     */
    private var rateAt = 0L
    private var rateResults = 0
    private var rateJpegs = 0
    private val stageSum = LongArray(4)
    private val stageMin = LongArray(4) { Long.MAX_VALUE }
    private val stageMax = LongArray(4)
    private var stageN = 0

    private fun stage(i: Int, from: Long) {
        val d = System.nanoTime() - from
        stageSum[i] += d
        if (d < stageMin[i]) stageMin[i] = d
        if (d > stageMax[i]) stageMax[i] = d
    }
    private val rateProbe = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            rateResults++
            if (diag != null) {
                diagResults++
                diagFrameMs = (result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L) / 1_000_000f
                diagExposureMs = (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1_000_000f
                diagRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.toString() ?: "?"
                return
            }
            val now = System.currentTimeMillis()
            if (rateAt == 0L) { rateAt = now; return }
            if (now - rateAt < 10000) return
            val span = (now - rateAt) / 1000f
            val n = stageN.coerceAtLeast(1)
            fun ms(key: CaptureResult.Key<Long>) =
                result.get(key)?.let { "%.1f".format(it / 1_000_000f) } ?: "?"
            val names = arrayOf("acquire", "copy", "trim", "send")
            val parts = StringBuilder()
            for (i in names.indices) {
                if (stageSum[i] == 0L) continue
                parts.append(
                    "%s=%.1f/%.1f/%.1f ".format(
                        names[i], stageMin[i] / 1e6, stageSum[i] / 1e6 / n, stageMax[i] / 1e6,
                    ),
                )
            }
            val line = "rate: sensor results=%.1f/s sent=%.1f/s of %d asked, frameDuration=%sms " +
                "exposure=%sms range=%s, ms/frame min/avg/max: %s"
            Log.i(
                TAG,
                line.format(
                    rateResults / span, rateJpegs / span, fps,
                    ms(CaptureResult.SENSOR_FRAME_DURATION),
                    ms(CaptureResult.SENSOR_EXPOSURE_TIME),
                    result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE),
                    parts.toString(),
                ),
            )
            rateAt = now; rateResults = 0; rateJpegs = 0
            stageSum.fill(0); stageMin.fill(Long.MAX_VALUE); stageMax.fill(0); stageN = 0
        }
    }

    /**
     * Diagnostic: measure what the camera can output with no processing and no
     * streaming at all, optionally without the JPEG encoder in the path.
     */
    class Diag(val mode: String, val seconds: Int, val resolution: String)

    @Volatile private var diag: Diag? = null
    private var diagImages = 0
    private var diagResults = 0
    private var diagAt = 0L
    private var diagFrameMs = 0f
    private var diagExposureMs = 0f
    private var diagRange = ""

    /** Called on the camera thread when a run finishes, with a one-line report. */
    @Volatile var onDiagDone: ((String) -> Unit)? = null

    fun runDiag(mode: String, seconds: Int, resolution: String) = handler.post {
        if (diag != null) return@post
        val resume = wantOpen
        diag = Diag(mode, seconds, resolution)
        diagImages = 0
        diagResults = 0
        stopInternal()
        wantOpen = true          // openDevice() closes it again if this is false
        startInternal()
        diagAt = System.currentTimeMillis()
        handler.postDelayed({
            val d = diag
            if (d != null) {
                val span = (System.currentTimeMillis() - diagAt) / 1000.0
                val line = ("diag %s %dx%d %.1fs: capture results=%.1f/s, images=%.1f/s (%d total), " +
                    "frameDuration=%.1fms exposure=%.1fms range=%s").format(
                    d.mode, size.width, size.height, span,
                    diagResults / span, diagImages / span, diagImages,
                    diagFrameMs, diagExposureMs, diagRange,
                )
                Log.i(TAG, line)
                diag = null
                stopInternal()
                wantOpen = resume
                if (resume) requestStart()
                onDiagDone?.invoke(line)
            }
        }, seconds * 1000L + 500)
    }

    private fun createSession(d: CameraDevice) {
        val fmt = if (diag?.mode?.startsWith("yuv") == true) ImageFormat.YUV_420_888 else ImageFormat.JPEG
        val r = ImageReader.newInstance(size.width, size.height, fmt, 4)
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
        reader = r

        // JPEG-only session. Adding a preview SurfaceTexture here (without a GL
        // consumer) starved the Exynos preview pipe and crashed cameraserver with
        // a null-deref in ExynosCameraFrame::searchEntityByPipeId.
        try {
            d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    Log.i(TAG, "capture session configured (surfaces=1)")
                    try {
                        s.setRepeatingRequest(buildRequest(d, s), rateProbe, handler)
                        Log.i(TAG, "repeating request set")
                    } catch (e: Exception) {
                        onError("setRepeatingRequest failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("Capture session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            onError("createCaptureSession failed: ${e.message}")
        }
    }

    private fun applyLiveInternal() {
        fps = Prefs.fps
        quality = Prefs.quality
        torch = Prefs.torch
        zoom = Prefs.zoom
        orientation = Prefs.orientation
        val d = device ?: return
        val s = session ?: return
        try {
            s.setRepeatingRequest(buildRequest(d, s), null, handler)
        } catch (e: Exception) {
            onError("apply failed: ${e.message}")
        }
    }

    private fun buildRequest(d: CameraDevice, s: CameraCaptureSession): CaptureRequest {
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        reader?.let { b.addTarget(it.surface) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.JPEG_QUALITY, quality.coerceIn(1, 100).toByte())
        b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
        chars?.let { c ->
            val range = fpsRange(c, fps)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            // "jpeg+fixed": take exposure out of the picture, so the measurement
            // is the pipeline's ceiling rather than what AE chose for the light.
            if (diag?.mode?.endsWith("+fixed") == true) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 5_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 1600)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, (1_000_000_000L / range.upper))
            }
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && torch) {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            // Ask for no embedded thumbnail: some HALs put a full-size one in
            // every frame, which is pure overhead for a stream.
            val thumbs = c.get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES) ?: emptyArray()
            if (thumbs.any { it.width == 0 && it.height == 0 }) {
                b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, Size(0, 0))
            }
            cropRegion(c, zoom)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
        }
        return b.build()
    }

    private fun onImage(rd: ImageReader) {
        val t0 = System.nanoTime()
        val img = try { rd.acquireNextImage() } catch (e: Exception) { null } ?: return
        try {
            if (diag != null) {            // count only: no copy, trim or send
                diagImages++
                return
            }
            stage(0, t0)                   // acquire
            val t1 = System.nanoTime()
            val buf = img.planes[0].buffer

            // Rate limit to the requested frame rate. The budget stays a
            // fraction so that dropping from a faster pipeline gives the exact
            // average rate rather than a multiple of the arriving interval.
            val now = System.currentTimeMillis()
            val interval = 1000.0 / fps.coerceAtLeast(1)
            if (nextFrameAt == 0.0) nextFrameAt = now.toDouble()
            if (now < nextFrameAt) return
            nextFrameAt += interval
            if (nextFrameAt < now) nextFrameAt = now + interval   // no burst after a stall

            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val t2 = System.nanoTime()
            stage(1, t1)                   // copy
            val size = trimSegmentPadding(bytes, bytes.size)
            val t3 = System.nanoTime()
            stage(2, t2)                   // trim
            rateJpegs++
            stageN++
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                Log.i(TAG, "first frame: ${bytes.size} bytes, ${size} after trimming")
            }
            onFrame(if (size == bytes.size) bytes else bytes.copyOf(size))
            stage(3, t3)                   // send
        } catch (e: Exception) {
            onError("frame read failed: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun trimSegmentPadding(src: ByteArray, len: Int): Int {
        if (len < 4 || src[0] != 0xFF.toByte() || src[1] != 0xD8.toByte()) return len
        var i = 2
        var out = 2
        while (i + 3 < len && src[i] == 0xFF.toByte()) {
            val m = src[i + 1].toInt() and 0xFF
            if (m == 0x01 || m in 0xD0..0xD9) {           // standalone marker, no length
                src[out] = src[i]; src[out + 1] = src[i + 1]
                out += 2; i += 2
                continue
            }
            if (m == 0xDA) {                              // start of scan: copy the rest
                System.arraycopy(src, i, src, out, len - i)
                return out + (len - i)
            }
            val seg = ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
            if (seg < 2 || i + 2 + seg > len) break
            val from = i + 4
            var end = i + 2 + seg
            if (m in 0xE0..0xEF || m == 0xFE) {
                while (end > from && src[end - 1] == 0.toByte()) end--
                var zeros = 0
                for (p in from until end) if (src[p] == 0.toByte()) zeros++
                if (zeros * 10 >= (end - from) * 9) end = from   // almost all padding
            }
            if (end > from) {
                val newSeg = (end - from) + 2
                src[out] = 0xFF.toByte(); src[out + 1] = src[i + 1]
                src[out + 2] = (newSeg shr 8).toByte()
                src[out + 3] = newSeg.toByte()
                System.arraycopy(src, from, src, out + 4, end - from)
                out += 2 + newSeg
            }
            i += 2 + seg
        }
        if (i < len) {                                    // unexpected: keep the remainder
            System.arraycopy(src, i, src, out, len - i)
            out += len - i
        }
        return out
    }

    private fun stopInternal() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null
        device = null
        reader = null
    }

    private fun findCameraId(facing: String): String {
        val want = if (facing == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        for (id in mgr.cameraIdList) {
            if (mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == want) return id
        }
        return mgr.cameraIdList.firstOrNull() ?: throw IllegalStateException("No cameras")
    }

    private fun chooseSize(c: CameraCharacteristics, wanted: String): Size {
        val map: StreamConfigurationMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(1280, 720)
        if (wanted.isNotBlank()) {
            val parts = wanted.split("x")
            if (parts.size == 2) {
                val w = parts[0].toIntOrNull()
                val h = parts[1].toIntOrNull()
                if (w != null && h != null) {
                    sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
                }
            }
        }
        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }

    /**
     * Some vendors report the AE fps ranges in plain frames per second instead
     * of the spec's fps*1000, so we detect the scale.
     */
    private fun fpsScaleMilli(c: CameraCharacteristics): Boolean {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return true
        return ranges.any { it.upper > 1000 }
    }

    private fun fpsRange(c: CameraCharacteristics, want: Int): Range<Int> {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(want, want)
        val target = if (fpsScaleMilli(c)) want * 1000 else want
        // Let the pipeline run as fast as it will and throttle in software below.
        // Asking for a fixed range looks tidier, but the HAL then under-delivers
        // it: with (15,15) it managed only 12.5 results a second while the sensor
        // reported a 66.7 ms frame, whereas a wide range keeps it at 20+ and the
        // requested rate can actually be met.
        ranges.maxByOrNull { it.upper }?.let { return it }
        ranges.firstOrNull { it.lower <= target && target <= it.upper }?.let { return it }
        return ranges.minByOrNull { abs(it.upper - target) } ?: Range(target, target)
    }

    private fun cropRegion(c: CameraCharacteristics, zoomRatio: Float): Rect? {
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val z = zoomRatio.coerceIn(1f, maxZoom)
        if (z <= 1.01f) return null
        val w = (active.width() / z).toInt().coerceIn(1, active.width())
        val h = (active.height() / z).toInt().coerceIn(1, active.height())
        val left = (active.centerX() - w / 2).coerceIn(active.left, active.right - w)
        val top = (active.centerY() - h / 2).coerceIn(active.top, active.bottom - h)
        return Rect(left, top, left + w, top + h)
    }
}
