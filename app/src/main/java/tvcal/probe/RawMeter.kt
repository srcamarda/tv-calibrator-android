package tvcal.probe

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tvcal.kit.CameraToXYZ
import tvcal.kit.Cfa
import tvcal.kit.DngColour
import tvcal.kit.DngColourData
import tvcal.kit.DngMethod
import tvcal.kit.RawAnalysis
import tvcal.kit.RawPixels
import tvcal.kit.RawStats
import tvcal.kit.Vec3
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One reading of the centre of the frame. */
class Reading(
    val stats: RawStats,
    val exposureNs: Long,
    val iso: Int,
    val focus: Float?,
    val lensMoving: Boolean,
    val black: DoubleArray,
    val white: Int,
    /** Black-subtracted camera RGB per second of exposure. */
    val rgbPerSecond: Vec3,
    /** XYZ (relative units) from the forward-matrix and the colour-matrix conversions; null without matrices. */
    val xyzForward: Vec3?,
    val xyzColour: Vec3?,
    /** The physical camera a logical camera actually used. */
    val physicalCamera: String?,
    /** Photos taken to get here (exposure search included). */
    val attempts: Int,
    val notes: List<String>,
)

/**
 * The phone camera as a light meter, the Android counterpart of upstream's `CameraEngine`: RAW photos
 * with everything that affects the numbers locked (lens, focus at its closest, lowest ISO, noise
 * reduction and stabilisation off). Only the shutter time changes, and readings are divided by it.
 */
class RawMeter(private val manager: CameraManager) {
    class Setup(
        val cameraId: String,
        val chars: CameraCharacteristics,
        val rawSize: Size,
        val previewSize: Size,
        val cfa: Cfa,
        val iso: Int,
        val minExposureNs: Long,
        val maxExposureNs: Long,
        val minFocus: Float,
        val aperture: Float?,
        /** Camera RGB -> XYZ, when colour matrices are known (from Camera2 or an imported DNG). */
        val forward: CameraToXYZ?,
        val colour: CameraToXYZ?,
        /** Where the matrices came from, for the screen and the log. */
        val colourSource: String,
    ) {
        /** Focus distance to lock, in diopters: the closest, as upstream. Phones that don't report
         * their closest get a near value; the camera clamps it and the result says what it used. */
        val focusTarget: Float get() = if (minFocus > 0f) minFocus else 10f
    }

    private val thread = HandlerThread("tvcal-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var pendingImage: CompletableDeferred<Image>? = null

    var setup: Setup? = null
        private set
    /** The last exposure that read well: the next reading starts there. */
    private var lastExposureNs = 16_666_667L
    var onError: ((String) -> Unit)? = null

    /** Works out the locked settings for a camera without opening it. Throws if it can't be a meter. */
    fun plan(cameraId: String, imported: Pair<DngColourData, String>? = null): Setup {
        val ch = manager.getCameraCharacteristics(cameraId)
        require(CameraFacts.hasRaw(ch)) { "Camera $cameraId has no RAW output." }
        require(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in CameraFacts.capabilities(ch)) {
            "Camera $cameraId has no manual exposure."
        }
        val cfa = requireNotNull(CameraFacts.cfa(ch)) { "Camera $cameraId isn't a Bayer sensor." }
        val sensor = runCatching { CameraFacts.conversions(ch) }.getOrNull()
        val fromDng = imported?.let { (data, name) ->
            runCatching {
                Triple(DngColour.cameraToXYZ(data, DngMethod.FORWARD_MATRIX), DngColour.cameraToXYZ(data, DngMethod.COLOR_MATRIX), name)
            }.getOrNull()
        }
        val (forward, colour, colourSource) = when {
            sensor != null -> Triple(sensor.first, sensor.second, "Camera2 sensor matrices")
            fromDng != null -> Triple(fromDng.first, fromDng.second, "DNG ${fromDng.third}")
            else -> Triple(null, null, "none (raw ratios only)")
        }
        val raw = CameraFacts.rawSizes(ch).first()
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val aspect = raw.width.toDouble() / raw.height
        val preview = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            .filter { it.width <= 1920 && it.height <= 1440 }
            .minByOrNull { kotlin.math.abs(it.width.toDouble() / it.height - aspect) * 10_000 - it.width }
            ?: Size(1280, 960)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!.lower
        return Setup(
            cameraId = cameraId, chars = ch, rawSize = raw, previewSize = preview, cfa = cfa, iso = iso,
            minExposureNs = exp.lower,
            // Upstream caps the shutter at 1/4 s.
            maxExposureNs = minOf(exp.upper, 250_000_000L),
            minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            aperture = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
            forward = forward, colour = colour, colourSource = colourSource,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun open(plan: Setup, preview: Surface) {
        close()
        val dev = suspendCancellableCoroutine { cont ->
            manager.openCamera(plan.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                    else { close(); onError?.invoke("The camera was disconnected (another app took it?).") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                    else { close(); onError?.invoke("Camera error $error.") }
                }
            })
        }
        device = dev
        val rd = ImageReader.newInstance(plan.rawSize.width, plan.rawSize.height, ImageFormat.RAW_SENSOR, 2)
        rd.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val waiting = pendingImage
            if (waiting == null || !waiting.complete(img)) img.close()
        }, handler)
        reader = rd

        session = suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview), OutputConfiguration(rd.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        cont.resumeWithException(IllegalStateException("Couldn't set up preview + RAW together."))
                    }
                },
            )
            dev.createCaptureSession(config)
        }

        // The preview exposes itself (only for aiming), but focus is already locked where readings use it.
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            lockLens(plan)
        }
        session!!.setRepeatingRequest(req.build(), null, handler)
        setup = plan
    }

    private fun CaptureRequest.Builder.lockLens(plan: Setup) {
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        // Closest focus, so the TV's pixel grid blurs out (as upstream).
        set(CaptureRequest.LENS_FOCUS_DISTANCE, plan.focusTarget)
        // 1x: a logical camera must stay on its main sensor, not switch lenses.
        val zoom = plan.chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (zoom != null && 1f in zoom.lower..zoom.upper) set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        plan.aperture?.let { set(CaptureRequest.LENS_APERTURE, it) }
        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
    }

    /** One RAW photo at `exposureNs`, analysed. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun shoot(plan: Setup, exposureNs: Long): Triple<RawStats, CaptureResult, DoubleArray> {
        val dev = checkNotNull(device) { "Camera isn't open." }
        val s = checkNotNull(session) { "Camera isn't open." }
        val rd = checkNotNull(reader)
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
            addTarget(rd.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
            set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
            set(CaptureRequest.BLACK_LEVEL_LOCK, true)
            lockLens(plan)
        }.build()

        val image = CompletableDeferred<Image>()
        val result = CompletableDeferred<TotalCaptureResult>()
        pendingImage = image
        s.capture(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                result.complete(r)
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, f: CaptureFailure) {
                result.completeExceptionally(IllegalStateException("Capture failed (reason ${f.reason})"))
            }
        }, handler)

        val timeout = exposureNs / 1_000_000 + 4_000
        val (img, res) = try {
            withTimeout(timeout) { image.await() to result.await() }
        } catch (e: Throwable) {
            pendingImage = null
            // The photo may have arrived even though its result failed or timed out.
            if (image.isCompleted) runCatching { image.getCompleted().close() }
            image.cancel()
            throw e
        }
        pendingImage = null
        try {
            val white = res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                ?: plan.chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val black = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.map { it.toDouble() }?.toDoubleArray()
                ?: plan.chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                    DoubleArray(4) { p.getOffsetForIndex(it % 2, it / 2).toDouble() }
                } ?: DoubleArray(4)
            val stats = withContext(Dispatchers.Default) {
                val plane = img.planes[0]
                val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                RawAnalysis.measure(
                    RawPixels { x, y -> buf.getShort(y * rowStride + x * pixelStride).toInt() and 0xFFFF },
                    img.width, img.height, plan.cfa, black, white,
                )
            }
            return Triple(stats, res, black)
        } finally {
            img.close()
        }
    }

    /** A reading of the centre of the frame, searching for an exposure that isn't clipped or too dark. */
    suspend fun measure(maxAttempts: Int = 7): Reading {
        val plan = checkNotNull(setup) { "Camera isn't open." }
        var exposure = lastExposureNs.coerceIn(plan.minExposureNs, plan.maxExposureNs)
        val notes = mutableListOf<String>()
        for (attempt in 1..maxAttempts) {
            val (stats, res, black) = shoot(plan, exposure)
            val actual = res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposure
            val iso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: plan.iso
            val atMax = exposure >= plan.maxExposureNs
            if (RawAnalysis.usable(stats, atMax) || attempt == maxAttempts) {
                if (actual != exposure && kotlin.math.abs(actual - exposure) > exposure / 50) {
                    notes += "asked for ${exposure / 1000} us, camera used ${actual / 1000} us"
                }
                if (iso != plan.iso) notes += "asked for ISO ${plan.iso}, camera used $iso"
                if (stats.clipped) notes += if (exposure <= plan.minExposureNs) "clipped even at the shortest exposure"
                    else "clipped: no good exposure found in $attempt photos"
                if (stats.uneven > 0.05) notes += "uneven box (%.0f%%): aim at a flat patch".format(stats.uneven * 100)
                if (atMax && stats.fill < 0.08) notes += "dark: at the longest exposure, reading is noisy"
                val lensMoving = res.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING
                if (lensMoving) notes += "lens was still moving"
                lastExposureNs = actual
                val perSecond = 1e9 / actual
                val rgb = DoubleArray(3) { stats.mean[it] * perSecond }
                return Reading(
                    stats = stats, exposureNs = actual, iso = iso,
                    focus = res.get(CaptureResult.LENS_FOCUS_DISTANCE), lensMoving = lensMoving,
                    black = black, white = res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                        ?: plan.chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0,
                    rgbPerSecond = rgb,
                    xyzForward = plan.forward?.apply(rgb), xyzColour = plan.colour?.apply(rgb),
                    physicalCamera = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                    attempts = attempt, notes = notes,
                )
            }
            exposure = RawAnalysis.nextExposure(exposure, stats, plan.minExposureNs, plan.maxExposureNs)
        }
        error("unreachable")
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        setup = null
    }

    fun release() {
        close()
        thread.quitSafely()
    }
}
