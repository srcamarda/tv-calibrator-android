package tvcal.probe

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import tvcal.kit.CameraToXYZ
import tvcal.kit.Cfa
import tvcal.kit.DngColour
import tvcal.kit.DngColourData
import tvcal.kit.DngMethod
import tvcal.kit.Mat3

/** Everything the meter needs to know about one camera, read from its characteristics. */
object CameraFacts {
    fun matrix(t: ColorSpaceTransform?): Mat3? =
        t?.let { Array(3) { r -> DoubleArray(3) { c -> it.getElement(c, r).toDouble() } } }

    fun dngColour(ch: CameraCharacteristics): DngColourData? {
        val cm1 = matrix(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: return null
        val ill1 = (ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) as Number?)?.toInt() ?: 17
        val ill2 = (ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) as Number?)?.toInt()
        return DngColourData(
            colorMatrix1 = cm1,
            colorMatrix2 = matrix(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
            forwardMatrix1 = matrix(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
            forwardMatrix2 = matrix(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
            calibration1 = matrix(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
            calibration2 = matrix(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
            illuminant1 = ill1,
            illuminant2 = ill2,
        )
    }

    fun conversions(ch: CameraCharacteristics): Pair<CameraToXYZ, CameraToXYZ>? {
        val data = dngColour(ch) ?: return null
        return DngColour.cameraToXYZ(data, DngMethod.FORWARD_MATRIX) to
            DngColour.cameraToXYZ(data, DngMethod.COLOR_MATRIX)
    }

    fun capabilities(ch: CameraCharacteristics): Set<Int> =
        ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()

    fun hasRaw(ch: CameraCharacteristics) =
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities(ch)

    fun rawSizes(ch: CameraCharacteristics): List<Size> =
        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.sortedByDescending { it.width.toLong() * it.height }.orEmpty()

    fun cfa(ch: CameraCharacteristics): Cfa? =
        ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let { Cfa.fromCamera2(it) }

    fun facing(ch: CameraCharacteristics) = when (ch.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "?"
    }

    fun focalLengths(ch: CameraCharacteristics) =
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { "%.2f".format(it) } ?: "?"

    /** One line naming the camera, for pickers. */
    fun title(id: String, ch: CameraCharacteristics): String {
        val px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val raw = if (hasRaw(ch)) "RAW" else "no RAW"
        return "Camera $id: ${facing(ch)}, f=${focalLengths(ch)} mm, ${px?.width}x${px?.height}, $raw"
    }

    /** Cameras that can be a meter (RAW, manual exposure, Bayer): back cameras first, biggest sensor first. */
    fun meterCameras(manager: CameraManager): List<String> =
        allCameraIds(manager).filter {
            val ch = manager.getCameraCharacteristics(it)
            hasRaw(ch) && cfa(ch) != null &&
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities(ch)
        }.sortedWith(compareBy<String> {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK
        }.thenByDescending {
            val s = manager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            (s?.width ?: 0f) * (s?.height ?: 0f)
        })

    /** The listed cameras plus the physical cameras behind any logical one. */
    fun allCameraIds(manager: CameraManager): List<String> {
        val ids = LinkedHashSet<String>()
        for (id in manager.cameraIdList) {
            ids += id
            runCatching { ids += manager.getCameraCharacteristics(id).physicalCameraIds }
        }
        return ids.toList()
    }
}

/** A plain-text report of what Camera2 offers on this phone, for deciding whether it can be a meter. */
object CameraReport {
    private val capabilityNames = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE", 7 to "YUV_REPROCESSING",
        8 to "DEPTH_OUTPUT", 9 to "CONSTRAINED_HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA", 14 to "SYSTEM_CAMERA",
        15 to "OFFLINE_PROCESSING", 16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT", 19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES",
    )
    private val levelNames = mapOf(0 to "LIMITED", 1 to "FULL", 2 to "LEGACY", 3 to "LEVEL_3", 4 to "EXTERNAL")

    fun build(manager: CameraManager, permission: Boolean): String = buildString {
        appendLine("TV Cal Probe camera report")
        if (permission) appendLine("Camera permission: granted")
        else appendLine("Camera permission: NOT granted. Android hides colour matrices, focus range and more; allow it and rebuild.")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine("Listed cameras: ${manager.cameraIdList.joinToString()}")
        appendLine()
        for (id in CameraFacts.allCameraIds(manager)) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            summary(id, ch)
            appendLine()
        }
        appendLine("=".repeat(60))
        appendLine("Full characteristics")
        for (id in CameraFacts.allCameraIds(manager)) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            appendLine()
            appendLine("--- Camera $id ---")
            for (key in ch.keys.sortedBy { it.name }) {
                val value = runCatching { ch.get(key) }.getOrElse { "<error: ${it.message}>" }
                appendLine("${key.name} = ${format(value)}")
            }
        }
    }

    private fun StringBuilder.summary(id: String, ch: CameraCharacteristics) {
        appendLine("=== ${CameraFacts.title(id, ch)} ===")
        val caps = CameraFacts.capabilities(ch)
        val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        appendLine("Hardware level: ${levelNames[level] ?: level}")
        val hidden = runCatching { ch.keysNeedingPermission.filter { ch.get(it) == null }.map { it.name } }.getOrDefault(emptyList())
        if (hidden.isNotEmpty()) appendLine("Hidden or missing (need camera permission): ${hidden.joinToString()}")
        appendLine("Capabilities: ${caps.sorted().joinToString { capabilityNames[it] ?: it.toString() }}")
        if (ch.physicalCameraIds.isNotEmpty()) appendLine("Physical cameras: ${ch.physicalCameraIds.joinToString()}")

        val raw = CameraFacts.rawSizes(ch)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val white = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val black = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val syncLatency = ch.get(CameraCharacteristics.SYNC_MAX_LATENCY)
        val resultKeys = ch.availableCaptureResultKeys.map { it.name }.toSet()
        val dyn = CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL.name in resultKeys

        appendLine("RAW sizes: ${raw.joinToString { "${it.width}x${it.height}" }.ifEmpty { "none" }}")
        appendLine("CFA: ${CameraFacts.cfa(ch) ?: "not Bayer ($cfa)"}")
        appendLine("ISO range: $iso   exposure: ${exp?.let { "${fmtTime(it.lower)} .. ${fmtTime(it.upper)}" }}")
        appendLine("Min focus distance: $minFocus diopters${if (minFocus == 0f) " (fixed focus)" else ""}")
        appendLine("Apertures: ${apertures?.joinToString { "f/$it" }}")
        appendLine("White level: $white   black pattern: ${format(black)}   dynamic black level: $dyn")
        appendLine("Per-frame control: ${if (syncLatency == 0) "yes" else "no (sync latency $syncLatency)"}")

        appendLine("Meter checklist:")
        fun check(ok: Boolean, what: String) = appendLine("  [${if (ok) "OK" else "--"}] $what")
        check(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps, "RAW capture")
        check(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps, "manual exposure and ISO")
        check(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS in caps, "reports the settings it used")
        check(CameraFacts.cfa(ch) != null, "Bayer colour filter")
        check(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null, "forward matrices (camera RGB -> XYZ)")
        check((minFocus ?: 0f) > 0f, "adjustable focus (to blur the TV's pixel grid)")
        check(syncLatency == 0, "per-frame control")

        val conv = runCatching { CameraFacts.conversions(ch) }.getOrNull()
        if (conv != null) {
            val ill1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
            val ill2 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
            appendLine("Reference illuminants: $ill1 / $ill2")
            appendLine("Camera RGB -> XYZ (D65), forward-matrix method:")
            appendMatrix(conv.first.matrix)
            appendLine("Camera RGB -> XYZ (D65), colour-matrix method:")
            appendMatrix(conv.second.matrix)
        }
    }

    private fun StringBuilder.appendMatrix(m: Mat3) {
        for (row in m) appendLine("  " + row.joinToString("  ") { "%+.5f".format(it) })
    }

    private fun fmtTime(ns: Long): String =
        if (ns >= 1_000_000_000L) "%.2f s".format(ns / 1e9)
        else if (ns >= 1_000_000L) "%.3f ms (1/%.0f s)".format(ns / 1e6, 1e9 / ns)
        else "%.1f us".format(ns / 1e3)

    fun format(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(prefix = "[", postfix = "]")
        is LongArray -> v.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> v.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> v.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> v.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { format(it) }
        is Rational -> "${v.numerator}/${v.denominator}"
        is ColorSpaceTransform -> (0..2).joinToString(prefix = "[", postfix = "]") { r ->
            (0..2).joinToString(" ") { c -> "%.4f".format(v.getElement(c, r).toDouble()) }
        }
        is BlackLevelPattern -> (0..3).joinToString(prefix = "[", postfix = "]") { v.getOffsetForIndex(it % 2, it / 2).toString() }
        is StreamConfigurationMap -> streamMap(v)
        is Range<*> -> "${v.lower}..${v.upper}"
        is Size, is SizeF, is Rect -> v.toString()
        else -> v.toString()
    }

    private fun streamMap(m: StreamConfigurationMap): String {
        val formats = mapOf(
            ImageFormat.RAW_SENSOR to "RAW_SENSOR", ImageFormat.RAW10 to "RAW10", ImageFormat.RAW12 to "RAW12",
            ImageFormat.RAW_PRIVATE to "RAW_PRIVATE", ImageFormat.YUV_420_888 to "YUV_420_888",
            ImageFormat.JPEG to "JPEG", ImageFormat.PRIVATE to "PRIVATE",
        )
        return m.outputFormats.joinToString("; ") { f ->
            val sizes = runCatching { m.getOutputSizes(f) }.getOrNull().orEmpty()
            "${formats[f] ?: "0x%x".format(f)}: ${sizes.joinToString(",") { "${it.width}x${it.height}" }}"
        }
    }
}
