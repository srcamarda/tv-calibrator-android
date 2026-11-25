package tvcal.kit

/**
 * The colour data a camera reports about its own sensor, as in a DNG file (Android's Camera2
 * `SENSOR_COLOR_TRANSFORM1/2`, `SENSOR_FORWARD_MATRIX1/2`, `SENSOR_CALIBRATION_TRANSFORM1/2` and
 * `SENSOR_REFERENCE_ILLUMINANT1/2`).
 *
 * Upstream trains a camera RGB -> XYZ matrix per iPhone against a colorimeter. Without one, the
 * manufacturer's own matrices give a first approximation: fair for greys and the white point, worse for
 * saturated colours, because they were made for scenes lit by broadband light and a TV's primaries are
 * narrow. Luminance is only relative until anchored (see [CameraToXYZ.anchored]).
 */
data class DngColourData(
    /** XYZ -> reference camera space, for illuminant 1 and 2 (DNG ColorMatrix1/2). */
    val colorMatrix1: Mat3,
    val colorMatrix2: Mat3?,
    /** White-balanced camera space -> XYZ D50 (DNG ForwardMatrix1/2). */
    val forwardMatrix1: Mat3?,
    val forwardMatrix2: Mat3?,
    /** Reference camera space -> this unit's camera space (DNG CameraCalibration1/2). */
    val calibration1: Mat3?,
    val calibration2: Mat3?,
    /** Camera2's `SENSOR_REFERENCE_ILLUMINANT*` values (EXIF LightSource codes). */
    val illuminant1: Int,
    val illuminant2: Int?,
)

/** Which of the camera's matrices built the conversion. */
enum class DngMethod { FORWARD_MATRIX, COLOR_MATRIX }

/**
 * Camera RGB (black-subtracted raw counts per second of exposure) -> XYZ.
 * [matrix] gives XYZ in arbitrary units; [scale] turns them into cd/m2 once a reference is known.
 */
data class CameraToXYZ(val matrix: Mat3, val method: DngMethod, val scale: Double = 1.0) {
    fun apply(rgb: Vec3): Vec3 {
        val v = ColourMath.matvec(matrix, rgb)
        return doubleArrayOf(v[0] * scale, v[1] * scale, v[2] * scale)
    }

    /** The same conversion, scaled so that `rgb` reads `nits` cd/m2. */
    fun anchored(rgb: Vec3, nits: Double): CameraToXYZ {
        val y = ColourMath.matvec(matrix, rgb)[1]
        require(y > 0) { "the reference reads no light" }
        return copy(scale = nits / y)
    }

    /**
     * The same conversion, corrected so that `ref` (camera RGB of a white known to be at `white`) reads
     * exactly that white at the same brightness: a per-channel gain in camera space, like a camera's own
     * white balance. Fixes the bias of the generic matrices toward this kind of screen's white.
     */
    fun whiteBalanced(ref: Vec3, white: Point2 = ColourMath.d65): CameraToXYZ {
        val y = ColourMath.matvec(matrix, ref)[1]
        require(y > 0 && ref.all { it > 0 }) { "the reference reads no light" }
        val want = ColourMath.solve3(matrix, ColourMath.xyYToXYZ(white, y))
        val gains = DoubleArray(3) { want[it] / ref[it] }
        require(gains.all { it.isFinite() && it > 0 }) { "the reference white can't be reached from this camera's matrix" }
        return copy(matrix = ColourMath.matmul(matrix, ColourMath.diag(gains)))
    }
}

object DngColour {
    /** Colour temperature of each EXIF LightSource code Camera2 can report, for interpolation. */
    fun illuminantCct(code: Int): Double? = when (code) {
        1, 4, 9 -> 5500.0          // daylight, flash, fine weather
        2, 14 -> 4150.0            // fluorescent, cool white fluorescent
        3, 17 -> 2856.0            // tungsten, standard A
        10 -> 6500.0               // cloudy
        11 -> 7500.0               // shade
        12 -> 6430.0               // daylight fluorescent
        13 -> 5000.0               // day white fluorescent
        15 -> 3450.0               // white fluorescent
        18 -> 4874.0               // standard B
        19 -> 6774.0               // standard C
        20 -> 5503.0               // D55
        21 -> 6504.0               // D65
        22 -> 7504.0               // D75
        23 -> 5003.0               // D50
        24 -> 3200.0               // ISO studio tungsten
        else -> null
    }

    /**
     * DNG interpolation weight for matrix 1 at colour temperature `cct`: linear in 1/T between the two
     * illuminants, clamped at the ends.
     */
    fun weight1(cct: Double, t1: Double, t2: Double): Double {
        if (t1 == t2) return 1.0
        val w = (1 / cct - 1 / t2) / (1 / t1 - 1 / t2)
        return w.coerceIn(0.0, 1.0)
    }

    private fun blend(a: Mat3, b: Mat3?, w: Double): Mat3 =
        if (b == null) a else Array(3) { r -> DoubleArray(3) { c -> a[r][c] * w + b[r][c] * (1 - w) } }

    /**
     * Camera RGB -> XYZ for light whose white is D65 (a TV's target), built the way a DNG reader would.
     *
     * - FORWARD_MATRIX: XYZ = Bradford(D50->D65) * FM * diag(refNeutral)^-1 * CC^-1 * rgb, with the camera
     *   neutral of D65 worked out from the colour matrices. A grey the camera sees as D65 reads D65.
     * - COLOR_MATRIX: XYZ = (CC * CM)^-1 * rgb. No adaptation step; used when the camera has no forward
     *   matrices, and as a cross-check of the first.
     */
    fun cameraToXYZ(data: DngColourData, method: DngMethod = DngMethod.FORWARD_MATRIX): CameraToXYZ {
        val t1 = illuminantCct(data.illuminant1) ?: 2856.0
        val t2 = data.illuminant2?.let { illuminantCct(it) } ?: 6504.0
        val w = if (data.colorMatrix2 == null) 1.0 else weight1(6504.0, t1, t2)
        val cm = blend(data.colorMatrix1, data.colorMatrix2, w)
        val cc = blend(data.calibration1 ?: ColourMath.identity(), data.calibration2 ?: data.calibration1, w)
        val xyzToCamera = ColourMath.matmul(cc, cm)

        val fm1 = data.forwardMatrix1
        if (method == DngMethod.FORWARD_MATRIX && fm1 != null) {
            // The camera's response to D65 white, in the reference camera space.
            val neutral = ColourMath.matvec(cm, ColourMath.xyYToXYZ(ColourMath.d65, 1.0))
            val fm = blend(fm1, data.forwardMatrix2, w)
            val toD50 = ColourMath.matmul(
                fm, ColourMath.matmul(ColourMath.inverse(ColourMath.diag(neutral)), ColourMath.inverse(cc))
            )
            return CameraToXYZ(ColourMath.matmul(ColourMath.adaptation(ColourMath.d50, ColourMath.d65), toD50), method)
        }
        return CameraToXYZ(ColourMath.inverse(xyzToCamera), DngMethod.COLOR_MATRIX)
    }
}
