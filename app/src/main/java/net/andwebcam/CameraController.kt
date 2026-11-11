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

    private var lastFrameAt = 0L
    private var opening = false
    private var restartQueued = false
    private var wantOpen = false

    val active: Boolean get() = device != null

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun capabilities(): Caps = try {
        val c = mgr.getCameraCharacteristics(findCameraId(Prefs.facing))
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            ?.map { "${it.width}x${it.height}" } ?: emptyList()
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val torchOk = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val rawRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val milli = rawRanges.any { it.upper > 1000 }
        val fpsOpts = rawRanges.map { if (milli) it.upper / 1000 else it.upper }.distinct().sorted()
        Caps(Prefs.facing, sizes, maxZoom, torchOk, fpsOpts.ifEmpty { listOf(15, 24, 30) })
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
            size = chooseSize(chars!!, Prefs.resolution)
            val ranges = chars!!.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            Log.i(TAG, "open camera id=$id facing=$facing size=${size.width}x${size.height} fps=$fps")
            Log.i(TAG, "ae fps ranges: ${ranges?.joinToString { "(${it.lower},${it.upper})" } ?: "none"}")
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

    private fun createSession(d: CameraDevice) {
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
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
                        s.setRepeatingRequest(buildRequest(d, s), null, handler)
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
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(c, fps))
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && torch) {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            cropRegion(c, zoom)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
        }
        return b.build()
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val buf = img.planes[0].buffer
            val now = System.currentTimeMillis()
            val minInterval = (1000.0 / fps.coerceAtLeast(1)).toLong()
            if (now - lastFrameAt < minInterval) return
            lastFrameAt = now
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                Log.i(TAG, "first frame: ${bytes.size} bytes")
            }
            onFrame(bytes)
        } catch (e: Exception) {
            onError("frame read failed: ${e.message}")
        } finally {
            img.close()
        }
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
     * Some vendors (this Samsung among them) report the AE fps ranges in plain
     * frames per second instead of the spec's fps*1000, so we detect the scale.
     */
    private fun fpsScaleMilli(c: CameraCharacteristics): Boolean {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return true
        return ranges.any { it.upper > 1000 }
    }

    private fun fpsRange(c: CameraCharacteristics, want: Int): Range<Int> {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(want, want)
        val target = if (fpsScaleMilli(c)) want * 1000 else want
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
