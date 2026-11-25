package tvcal.kit

import kotlin.math.max

/** Colour filter layout of a Bayer sensor, as Camera2's `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`. */
enum class Cfa(
    /** Colour of each photosite in a 2x2 cell, indexed (y % 2) * 2 + (x % 2): 0 red, 1 green, 2 blue. */
    val cell: IntArray,
) {
    RGGB(intArrayOf(0, 1, 1, 2)),
    GRBG(intArrayOf(1, 0, 2, 1)),
    GBRG(intArrayOf(1, 2, 0, 1)),
    BGGR(intArrayOf(2, 1, 1, 0));

    companion object {
        /** From the Camera2 constant (0..3); null for RGB, mono and NIR sensors. */
        fun fromCamera2(value: Int): Cfa? = entries.getOrNull(value)
    }
}

/** What one RAW photo's centre box reads. Same fields as upstream's `RawStats`, plus [fill]. */
class RawStats(
    /** Average R, G, B photosite value in the centre box, black level subtracted (raw counts). */
    val mean: DoubleArray,
    /** Photosites at the sensor's ceiling. */
    val clipped: Boolean,
    /** Brightness spread across the box's four quarters (0 = even). */
    val uneven: Double,
    /** The brightest photosite in the box (raw, before black subtraction). */
    val maxRaw: Int,
    /** Many photosites pile up at the brightest value: the sensor's real ceiling. */
    val plateau: Boolean,
    /** Mean of the box as a fraction of the usable range (white - black). For exposure control. */
    val fill: Double,
)

/** Reads one raw photosite. Lets the analysis run on an Android `Image` and in unit tests alike. */
fun interface RawPixels {
    operator fun get(x: Int, y: Int): Int
}

object RawAnalysis {
    /**
     * Port of upstream's `RawAnalysis.measure`: averages the red, green and blue photosites in a box in
     * the centre of the frame (`boxFraction` of the width and height, aligned to whole 2x2 cells).
     *
     * `black` is the black level of each photosite of a 2x2 cell, indexed (y % 2) * 2 + (x % 2), as
     * Camera2's `SENSOR_DYNAMIC_BLACK_LEVEL` / `BlackLevelPattern`. `white` is where photosites saturate.
     */
    fun measure(
        pixels: RawPixels,
        width: Int,
        height: Int,
        cfa: Cfa,
        black: DoubleArray,
        white: Int,
        boxFraction: Double = 0.1,
    ): RawStats {
        val boxW = (width * boxFraction).toInt() and 1.inv()
        val boxH = (height * boxFraction).toInt() and 1.inv()
        val x0 = (width / 2 - boxW / 2) and 1.inv()
        val y0 = (height / 2 - boxH / 2) and 1.inv()

        val sum = DoubleArray(3)
        val count = DoubleArray(3)
        val quarter = DoubleArray(4)
        val quarterN = DoubleArray(4)
        val clipLevel = (white * 0.98).toInt()
        var clipped = false
        var maxRaw = 0
        val histogram = IntArray(max(white, 1) + 1)
        // Quarters split on whole 2x2 cells, so each holds the same mix of red, green and blue.
        val midX = x0 + ((boxW / 2) and 1.inv())
        val midY = y0 + ((boxH / 2) and 1.inv())

        for (y in y0 until y0 + boxH) {
            val qy = if (y < midY) 0 else 2
            val parity = (y and 1) * 2
            for (x in x0 until x0 + boxW) {
                val raw = pixels[x, y]
                if (raw >= clipLevel) clipped = true
                if (raw > maxRaw) maxRaw = raw
                histogram[raw.coerceIn(0, histogram.size - 1)]++
                val idx = parity + (x and 1)
                val v = raw - black[idx]
                val c = cfa.cell[idx]
                sum[c] += v
                count[c] += 1
                val q = qy + if (x < midX) 0 else 1
                quarter[q] += v
                quarterN[q] += 1
            }
        }
        val mean = DoubleArray(3) { if (count[it] > 0) sum[it] / count[it] else 0.0 }
        val qMeans = DoubleArray(4) { if (quarterN[it] > 0) quarter[it] / quarterN[it] else 0.0 }
        val qAvg = qMeans.average()
        val uneven = if (qAvg > 0) (qMeans.max() - qMeans.min()) / qAvg else 0.0

        // A real patch's brightest photosites are a thin tail of the noise; at the sensor's ceiling they
        // pile up. More than 1% of the box within a hair of the top value means it's clipped. Only above
        // the dark end: on a black patch every photosite sits near the black level.
        val top = maxRaw.coerceAtMost(histogram.size - 1)
        val band = max(2, top * 3 / 1000)
        var nearTop = 0
        for (i in max(0, top - band)..top) nearTop += histogram[i]
        val total = boxW * boxH
        val blackAvg = black.average()
        val plateau = top > blackAvg + (white - blackAvg) * 0.12 && total > 0 &&
            nearTop.toDouble() / total > 0.01
        if (plateau) clipped = true

        val range = (white - blackAvg).coerceAtLeast(1.0)
        return RawStats(mean, clipped, uneven, maxRaw, plateau, mean.max() / range)
    }

    /**
     * Next exposure time after a reading that came back at `fill` (0..1 of the sensor's range), aiming
     * for `target`. Clipped readings drop by 4x. Like upstream, only the shutter time ever changes, and
     * readings are divided by it.
     */
    fun nextExposure(currentNs: Long, stats: RawStats, minNs: Long, maxNs: Long, target: Double = 0.45): Long {
        val next = when {
            stats.clipped -> currentNs / 4
            stats.fill <= 0.0005 -> currentNs * 8
            else -> (currentNs * (target / stats.fill)).toLong()
        }
        return next.coerceIn(minNs, maxNs)
    }

    /** True when a reading is exposed well enough to keep: not clipped, and its brightest channel is
     * at least `lowest` of the range (or the exposure is already as long as allowed). */
    fun usable(stats: RawStats, atMaxExposure: Boolean, lowest: Double = 0.08): Boolean =
        !stats.clipped && (stats.fill >= lowest || atMaxExposure)
}
