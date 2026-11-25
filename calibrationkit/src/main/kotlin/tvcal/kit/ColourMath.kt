package tvcal.kit

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** X, Y, Z (or R, G, B): three numbers. */
typealias Vec3 = DoubleArray

/** A row-major 3x3 matrix, as upstream's `Mat3` (`[[Double]]`): m[row][column]. */
typealias Mat3 = Array<DoubleArray>

/** A point on a 2D chart: CIE xy or u'v'. */
data class Point2(val x: Double, val y: Double)

fun vec3(a: Double, b: Double, c: Double): Vec3 = doubleArrayOf(a, b, c)

operator fun Vec3.plus(o: Vec3): Vec3 = DoubleArray(3) { this[it] + o[it] }
operator fun Vec3.minus(o: Vec3): Vec3 = DoubleArray(3) { this[it] - o[it] }
operator fun Vec3.times(k: Double): Vec3 = DoubleArray(3) { this[it] * k }
operator fun Vec3.div(k: Double): Vec3 = DoubleArray(3) { this[it] / k }
val Vec3.x get() = this[0]
val Vec3.y get() = this[1]
val Vec3.z get() = this[2]

/** Python's round(): halves go to the even neighbour. The engine's click counts use it. */
fun pyRound(x: Double): Double = Math.rint(x)

/** Python's round(x, digits): rounds the exact binary value, halves to even (round(0.15, 1) == 0.1). */
fun pyRound(x: Double, digits: Int): Double =
    if (!x.isFinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

/** C's "%.Nf": the exact binary value, halves to even (Java's String.format rounds halves up). */
fun fmtFixed(x: Double, digits: Int): String =
    if (!x.isFinite()) x.toString() else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

/** C's "%g" for the plain numbers the engine prints (e.g. 22.5 -> "22.5", 20.0 -> "20"). */
fun fmtG(x: Double): String {
    if (!x.isFinite()) return x.toString()
    val bd = BigDecimal(x).round(java.math.MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    return if (bd.scale() <= 0) bd.toBigInteger().toString() else bd.toPlainString()
}

fun clamp(x: Double, lo: Double, hi: Double): Double = minOf(maxOf(x, lo), hi)

/** Colour maths: a port of upstream's `ColourMath.swift`, plus what the DNG conversion needs. */
object ColourMath {
    val d65 = Point2(0.3127, 0.3290)
    val d50 = Point2(0.3457, 0.3585)

    // MARK: basic conversions

    fun xyzToXY(v: Vec3): Point2 {
        val s = v[0] + v[1] + v[2]
        return if (s > 0) Point2(v[0] / s, v[1] / s) else Point2(0.0, 0.0)
    }

    fun xyYToXYZ(xy: Point2, bigY: Double): Vec3 =
        vec3(xy.x * bigY / xy.y, bigY, (1 - xy.x - xy.y) * bigY / xy.y)

    /** Solves M v' = v for a 3x3 matrix (Cramer's rule, like the Python engine). */
    fun solve3(m: Mat3, v: Vec3): Vec3 {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val inv = arrayOf(
            doubleArrayOf(e * i - f * h, c * h - b * i, b * f - c * e),
            doubleArrayOf(f * g - d * i, a * i - c * g, c * d - a * f),
            doubleArrayOf(d * h - e * g, b * g - a * h, a * e - b * d),
        )
        return DoubleArray(3) { r -> (inv[r][0] * v[0] + inv[r][1] * v[1] + inv[r][2] * v[2]) / det }
    }

    fun matvec(m: Mat3, v: Vec3): Vec3 = DoubleArray(3) { r -> m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2] }

    fun matmul(a: Mat3, b: Mat3): Mat3 =
        Array(3) { r -> DoubleArray(3) { c -> a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] } }

    fun mat(vararg rows: DoubleArray): Mat3 = arrayOf(*rows)

    fun identity(): Mat3 = mat(vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0))

    fun diag(v: Vec3): Mat3 = mat(vec3(v[0], 0.0, 0.0), vec3(0.0, v[1], 0.0), vec3(0.0, 0.0, v[2]))

    /** Columns = the XYZ of each primary, scaled so R+G+B = the white point at Y = 1. */
    fun rgbToXYZMatrix(prim: List<Point2>, white: Point2 = d65): Mat3 {
        val cols = prim.map { vec3(it.x / it.y, 1.0, (1 - it.x - it.y) / it.y) }
        val p = Array(3) { r -> DoubleArray(3) { c -> cols[c][r] } }
        val s = solve3(p, vec3(white.x / white.y, 1.0, (1 - white.x - white.y) / white.y))
        return Array(3) { r -> DoubleArray(3) { c -> p[r][c] * s[c] } }
    }

    fun inverse(m: Mat3): Mat3 {
        val cols = listOf(vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0)).map { solve3(m, it) }
        val out = Array(3) { r -> DoubleArray(3) { c -> cols[c][r] } }
        require(out.all { row -> row.all { it.isFinite() } }) { "matrix is singular" }
        return out
    }

    enum class Gamut(val primaries: List<Point2>) {
        REC709(listOf(Point2(0.640, 0.330), Point2(0.300, 0.600), Point2(0.150, 0.060))),
        P3(listOf(Point2(0.680, 0.320), Point2(0.265, 0.690), Point2(0.150, 0.060))),
        REC2020(listOf(Point2(0.708, 0.292), Point2(0.170, 0.797), Point2(0.131, 0.046))),
    }

    val m709: Mat3 = rgbToXYZMatrix(Gamut.REC709.primaries)
    val m709Inverse: Mat3 = inverse(m709)

    fun xyzToRGB709(v: Vec3): Vec3 = matvec(m709Inverse, v)

    // MARK: chromatic adaptation (for the DNG matrices)

    private val bradford: Mat3 = mat(
        vec3(0.8951, 0.2664, -0.1614),
        vec3(-0.7502, 1.7135, 0.0367),
        vec3(0.0389, -0.0685, 1.0296),
    )
    private val bradfordInverse = inverse(bradford)

    /** Bradford chromatic adaptation from one white to another (both as xy). */
    fun adaptation(from: Point2, to: Point2): Mat3 {
        val s = matvec(bradford, xyYToXYZ(from, 1.0))
        val d = matvec(bradford, xyYToXYZ(to, 1.0))
        return matmul(bradfordInverse, matmul(diag(vec3(d[0] / s[0], d[1] / s[1], d[2] / s[2])), bradford))
    }

    /** Correlated colour temperature from xy (McCamy's approximation; good to a few K near the locus). */
    fun cct(xy: Point2): Double {
        val n = (xy.x - 0.3320) / (0.1858 - xy.y)
        return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33
    }

    // MARK: perceptual colour difference

    fun xyzToLab(v: Vec3, white: Vec3): Vec3 {
        fun f(t: Double): Double =
            if (t > (6.0 / 29).pow(3)) t.pow(1.0 / 3) else t / (3 * (6.0 / 29).pow(2)) + 4.0 / 29
        val fx = f(v[0] / white[0]); val fy = f(v[1] / white[1]); val fz = f(v[2] / white[2])
        return vec3(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun deg(r: Double) = r * (180.0 / Math.PI)
    private fun rad(d: Double) = d * (Math.PI / 180.0)

    /** Python's % for floats: the result has the sign of the divisor. */
    fun pmod(a: Double, n: Double): Double {
        val r = a % n
        return if (r < 0) r + n else r
    }

    /** CIEDE2000. */
    fun de2000(lab1: Vec3, lab2: Vec3): Double {
        val l1 = lab1[0]; val a1 = lab1[1]; val b1 = lab1[2]
        val l2 = lab2[0]; val a2 = lab2[1]; val b2 = lab2[2]
        val c1 = hypot(a1, b1); val c2 = hypot(a2, b2)
        val cb = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cb.pow(7) / (cb.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1; val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1); val c2p = hypot(a2p, b2)
        val h1p = pmod(deg(atan2(b1, a1p)), 360.0)
        val h2p = pmod(deg(atan2(b2, a2p)), 360.0)
        val dLp = l2 - l1; val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180 -> h2p - h1p
            h2p - h1p > 180 -> h2p - h1p - 360
            else -> h2p - h1p + 360
        }
        val dHp = 2 * sqrt(c1p * c2p) * sin(rad(dhp / 2))
        val lbp = (l1 + l2) / 2; val cbp = (c1p + c2p) / 2
        val hbp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 - 0.17 * cos(rad(hbp - 30)) + 0.24 * cos(rad(2 * hbp)) +
            0.32 * cos(rad(3 * hbp + 6)) - 0.20 * cos(rad(4 * hbp - 63))
        val dth = 30 * exp(-((hbp - 275) / 25).pow(2))
        val rc = 2 * sqrt(cbp.pow(7) / (cbp.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lbp - 50).pow(2) / sqrt(20 + (lbp - 50).pow(2))
        val sc = 1 + 0.045 * cbp; val sh = 1 + 0.015 * cbp * t
        val rt = -sin(rad(2 * dth)) * rc
        val tl = dLp / sl; val tc = dCp / sc; val th = dHp / sh
        return sqrt(tl * tl + tc * tc + th * th + rt * tc * th)
    }

    /** White-point error only (brightness normalised away). NaN for no light. */
    fun whiteDE(v: Vec3): Double {
        val bigY = v[1]
        if (bigY <= 0) return Double.NaN
        val n = vec3(v[0] * 100 / bigY, v[1] * 100 / bigY, v[2] * 100 / bigY)
        val w = xyYToXYZ(d65, 100.0)
        return de2000(xyzToLab(n, w), vec3(100.0, 0.0, 0.0))
    }

    // MARK: chromaticity helpers

    fun upvp(xy: Point2): Point2 {
        val d = -2 * xy.x + 12 * xy.y + 3
        return Point2(4 * xy.x / d, 9 * xy.y / d)
    }

    /** Distance and angle (degrees) from D65 in u'v'. */
    fun polar(xy: Point2): Pair<Double, Double> {
        val p = upvp(xy); val w = upvp(d65)
        return hypot(p.x - w.x, p.y - w.y) to deg(atan2(p.y - w.y, p.x - w.x))
    }

    /** Angle into -180..<180. */
    fun wrap(a: Double): Double = pmod(a + 180, 360.0) - 180

    // MARK: HDR (SMPTE ST 2084, "PQ")

    private const val M1 = 2610.0 / 16384
    private const val M2 = 2523.0 / 4096 * 128
    private const val C1 = 3424.0 / 4096
    private const val C2 = 2413.0 / 4096 * 32
    private const val C3 = 2392.0 / 4096 * 32

    fun pqEncode(nits: Double): Double {
        val y = clamp(nits / 10000, 0.0, 1.0).pow(M1)
        return ((C1 + C2 * y) / (1 + C3 * y)).pow(M2)
    }

    fun pqDecode(e: Double): Double {
        val p = e.pow(1 / M2)
        return 10000 * (maxOf(p - C1, 0.0) / (C2 - C3 * p)).pow(1 / M1)
    }
}
