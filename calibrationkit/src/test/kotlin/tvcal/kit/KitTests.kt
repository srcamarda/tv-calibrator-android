package tvcal.kit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColourTests {
    private fun close(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol, "$a != $b")

    @Test
    fun inverseUndoesMatrix() {
        val m = ColourMath.mat(
            doubleArrayOf(0.41, 0.36, 0.18), doubleArrayOf(0.21, 0.72, 0.07), doubleArrayOf(0.02, 0.12, 0.95)
        )
        val p = ColourMath.matmul(m, ColourMath.inverse(m))
        for (r in 0..2) for (c in 0..2) close(p[r][c], if (r == c) 1.0 else 0.0, 1e-12)
    }

    @Test
    fun bradfordMapsWhiteToWhite() {
        val out = ColourMath.matvec(ColourMath.adaptation(ColourMath.d50, ColourMath.d65), ColourMath.xyYToXYZ(ColourMath.d50, 1.0))
        val xy = ColourMath.xyzToXY(out)
        close(xy.x, ColourMath.d65.x, 1e-9)
        close(xy.y, ColourMath.d65.y, 1e-9)
        close(out[1], 1.0, 1e-9)
    }

    @Test
    fun de2000MatchesSharmaTable() {
        // Sharma, Wu & Dalal (2005), test pair 1.
        close(ColourMath.de2000(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485)), 2.0425, 1e-4)
        // Pair 17.
        close(ColourMath.de2000(doubleArrayOf(50.0, 2.5, 0.0), doubleArrayOf(73.0, 25.0, -18.0)), 27.1492, 1e-4)
    }

    @Test
    fun cctOfD65() {
        close(ColourMath.cct(ColourMath.d65), 6504.0, 5.0)
        close(ColourMath.whiteDE(ColourMath.xyYToXYZ(ColourMath.d65, 42.0)), 0.0, 1e-9)
    }
}

class DngColourTests {
    // A made-up camera: XYZ -> camera RGB.
    private val camera = ColourMath.mat(
        doubleArrayOf(0.70, 0.40, -0.15), doubleArrayOf(-0.25, 1.20, 0.10), doubleArrayOf(0.05, -0.20, 0.95)
    )

    /** What a DNG writer would store for a perfectly linear camera, both illuminants D65. */
    private fun perfectCamera(withForward: Boolean): DngColourData {
        val neutral = ColourMath.matvec(camera, ColourMath.xyYToXYZ(ColourMath.d65, 1.0))
        val fm = ColourMath.matmul(
            ColourMath.adaptation(ColourMath.d65, ColourMath.d50),
            ColourMath.matmul(ColourMath.inverse(camera), ColourMath.diag(neutral))
        )
        return DngColourData(
            colorMatrix1 = camera, colorMatrix2 = camera,
            forwardMatrix1 = if (withForward) fm else null, forwardMatrix2 = if (withForward) fm else null,
            calibration1 = null, calibration2 = null,
            illuminant1 = 17, illuminant2 = 21,
        )
    }

    private fun assertRecovers(conv: CameraToXYZ) {
        for (xyz in listOf(doubleArrayOf(0.95, 1.0, 1.09), doubleArrayOf(0.41, 0.21, 0.02), doubleArrayOf(0.18, 0.07, 0.95))) {
            val got = conv.apply(ColourMath.matvec(camera, xyz))
            for (i in 0..2) assertTrue(abs(got[i] - xyz[i]) < 1e-9, "channel $i: ${got[i]} != ${xyz[i]}")
        }
    }

    @Test
    fun forwardMatrixRecoversXYZ() {
        val conv = DngColour.cameraToXYZ(perfectCamera(true))
        assertEquals(DngMethod.FORWARD_MATRIX, conv.method)
        assertRecovers(conv)
    }

    @Test
    fun colorMatrixRecoversXYZ() {
        val conv = DngColour.cameraToXYZ(perfectCamera(false))
        assertEquals(DngMethod.COLOR_MATRIX, conv.method)
        assertRecovers(conv)
    }

    @Test
    fun interpolationWeights() {
        assertEquals(0.0, DngColour.weight1(6504.0, 2856.0, 6504.0))
        assertEquals(1.0, DngColour.weight1(2856.0, 2856.0, 6504.0))
        assertEquals(0.0, DngColour.weight1(9000.0, 2856.0, 6504.0))
    }

    @Test
    fun anchoringScalesToNits() {
        val conv = DngColour.cameraToXYZ(perfectCamera(true))
        val rgb = ColourMath.matvec(camera, doubleArrayOf(0.95, 1.0, 1.09))
        assertTrue(abs(conv.anchored(rgb, 100.0).apply(rgb)[1] - 100.0) < 1e-9)
    }
}

class RawAnalysisTests {
    private val w = 400
    private val h = 300

    /** An RGGB frame with flat channels, plus optional noise. */
    private fun frame(r: Int, g: Int, b: Int, noise: Int = 0): RawPixels {
        // Gaussian, like real sensor noise: its brightest values are a thin tail, not a pile-up.
        val rng = java.util.Random(42)
        val px = IntArray(w * h) { i ->
            val v = when (Cfa.RGGB.cell[((i / w) % 2) * 2 + (i % w) % 2]) { 0 -> r; 1 -> g; else -> b }
            v + if (noise == 0) 0 else Math.round(rng.nextGaussian() * noise).toInt()
        }
        return RawPixels { x, y -> px[y * w + x] }
    }

    @Test
    fun averagesChannelsAndSubtractsBlack() {
        // Real photos always have noise; a perfectly flat frame would look like a plateau (clipped).
        val s = RawAnalysis.measure(frame(1064, 2064, 564, noise = 20), w, h, Cfa.RGGB, DoubleArray(4) { 64.0 }, 4095)
        assertEquals(1000.0, s.mean[0], 1.0)
        assertEquals(2000.0, s.mean[1], 1.0)
        assertEquals(500.0, s.mean[2], 1.0)
        assertFalse(s.clipped)
        assertTrue(s.uneven < 0.01)
    }

    @Test
    fun sameSceneOtherLayoutReadsTheSame() {
        // A BGGR sensor sees blue where RGGB sees red: same scene, same answer.
        val bggr = RawPixels { x, y ->
            when (Cfa.BGGR.cell[(y % 2) * 2 + x % 2]) { 0 -> 1064; 1 -> 2064; else -> 564 }
        }
        val s = RawAnalysis.measure(bggr, w, h, Cfa.BGGR, DoubleArray(4) { 64.0 }, 4095)
        assertEquals(1000.0, s.mean[0], 1e-9)
        assertEquals(500.0, s.mean[2], 1e-9)
    }

    @Test
    fun clippedAtCeiling() {
        val s = RawAnalysis.measure(frame(4095, 4095, 3000), w, h, Cfa.RGGB, DoubleArray(4) { 64.0 }, 4095)
        assertTrue(s.clipped)
    }

    @Test
    fun plateauBelowStatedWhiteIsClipped() {
        // The sensor tops out at 3500 though it claims 4095 (the iPhone 15 Pro case upstream).
        val s = RawAnalysis.measure(frame(3500, 3500, 3500), w, h, Cfa.RGGB, DoubleArray(4) { 64.0 }, 4095)
        assertTrue(s.plateau)
        assertTrue(s.clipped)
    }

    @Test
    fun noisyBlackIsNotAPlateau() {
        val s = RawAnalysis.measure(frame(66, 66, 66, noise = 3), w, h, Cfa.RGGB, DoubleArray(4) { 64.0 }, 4095)
        assertFalse(s.clipped)
    }

    @Test
    fun exposureControl() {
        val s = RawAnalysis.measure(frame(464, 464, 464), w, h, Cfa.RGGB, DoubleArray(4) { 64.0 }, 4064)
        // fill = 400 / 4000 = 0.1, so aim 4.5x longer.
        assertEquals(45_000_000L, RawAnalysis.nextExposure(10_000_000L, s, 100_000L, 250_000_000L))
        val clipped = RawAnalysis.measure(frame(4064, 4064, 4064), w, h, Cfa.RGGB, DoubleArray(4) { 64.0 }, 4064)
        assertEquals(2_500_000L, RawAnalysis.nextExposure(10_000_000L, clipped, 100_000L, 250_000_000L))
    }
}
