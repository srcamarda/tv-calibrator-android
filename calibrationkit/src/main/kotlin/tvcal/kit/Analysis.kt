package tvcal.kit

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** One grey step of a round. */
data class GreyResult(
    val id: String,
    val level: Double,
    val name: String,
    val xyz: Vec3,
    val bigY: Double,
    val targetY: Double,
    /** R, G, B relative to brightness: (1, 1, 1) is exactly on D65. */
    val bal: Vec3,
    val rgb: Vec3,
    /** White-point error (dE2000, brightness ignored). */
    val de: Double,
    /** HDR: above what this TV can reach, so left out of corrections. */
    val clipped: Boolean,
) {
    /** Red and blue relative to green, minus 1 (0 = on target). What the white-balance bars show. */
    val redVsGreen: Double get() = if (bal[1] > 0) bal[0] / bal[1] - 1 else 0.0
    val blueVsGreen: Double get() = if (bal[1] > 0) bal[2] / bal[1] - 1 else 0.0
}

enum class HSLAxis { HUE, SAT, LUM }

/** One colour of a round. */
data class ColourResult(
    val name: String,
    val xyz: Vec3,
    val target: Vec3,
    val xy: Point2,
    val txy: Point2,
    /** Saturation error, %. */
    val sat: Double,
    /** Hue error, degrees (u'v' angle around D65). */
    val hue: Double,
    /** Brightness error, %. */
    val lum: Double,
    val de: Double,
) {
    val id: String get() = name

    fun value(axis: HSLAxis): Double = when (axis) {
        HSLAxis.HUE -> hue
        HSLAxis.SAT -> sat
        HSLAxis.LUM -> lum
    }
}

/** The analysis of one set of measurements. */
class Report {
    val greys = ArrayList<GreyResult>()
    val colours = ArrayList<ColourResult>()
    var white: Vec3? = null
    val warnings = ArrayList<String>()
    /** Brightness change of white from the start of the run to the end, %. */
    var drift: Double? = null
    /** HDR: the TV's highest measured brightness, when some greys are above it. */
    var peak: Double? = null
}

object Analysis {
    /** The SDR colour patches are 75% signal; their target brightness is relative to white. */
    fun colourLevel(hdr: Boolean): Double = if (hdr) 1.0 else 0.75.pow(2.4)

    fun analyse(meas: Measurements, hdr: Boolean): Report {
        val seq = Patterns.sequence(hdr)
        val rep = Report()
        val w = meas["W0"]
        if (w == null) {
            rep.warnings += "No 100% white reading - can't analyse this round."
            return rep
        }
        rep.white = w
        val yw = w[1]
        meas["W1"]?.let { w1 ->
            val drift = (w1[1] / yw - 1) * 100
            rep.drift = drift
            if (abs(drift) > 3) {
                rep.warnings += "Brightness drifted ${String.format(java.util.Locale.ROOT, "%+.1f", drift)}% during the run " +
                    "(TV not warmed up, or auto-brightness/energy saving still on)."
            }
        }
        // missed patches, in the video's order
        for (p in seq.patches) {
            val m = meas.missed[p.id] ?: continue
            val took = m.readings.maxOrNull() ?: 0.0
            rep.warnings += "${m.name}: no reading. " + if (took != 0.0)
                "The camera's readings took up to ${fmtFixed(took, 1)}s on it, against ${fmtG(m.hold)}s on screen."
            else "The camera didn't return anything while it was on screen."
        }
        var prevY = 0.0
        for (p in seq.patches) {
            if (p.kind != PatchKind.GREY) continue
            val xyz = meas[p.id] ?: continue
            val level = p.level ?: continue
            val rgb = ColourMath.xyzToRGB709(xyz)
            val y = xyz[1]
            if (y < prevY * 0.95 && !hdr) {
                rep.warnings += "${p.name} is darker than the step before it - timing may be off, " +
                    "or the TV is clipping/tone-mapping."
            }
            prevY = y
            val tgt = if (hdr) ColourMath.pqDecode(level) else yw * level.pow(2.4)
            val clipped = hdr && tgt > 200 && y < 0.85 * tgt
            rep.greys += GreyResult(p.id, level, p.name, xyz, y, tgt, rgb / y, rgb, ColourMath.whiteDE(xyz), clipped)
        }
        val clip = rep.greys.filter { it.clipped }
        if (clip.isNotEmpty()) {
            val peak = rep.greys.maxOf { it.bigY }
            rep.peak = peak
            val names = clip.joinToString(", ") { it.name.split(" (")[0] }
            rep.warnings += "The TV tops out around ${fmtFixed(peak, 0)} cd/m² - steps above that " +
                "($names) are left out of the corrections."
        }
        val tg = Patterns.colourTargets(hdr)
        // HDR: judge colours against the TV's own 100-nit white, so an overall HDR brightness
        // boost (reported separately as PQ tracking) doesn't swamp the colour scores.
        val whiteY = if (hdr) (if (yw > 0) yw else 100.0) else yw
        val lvl = colourLevel(hdr)
        val wn = ColourMath.xyYToXYZ(ColourMath.d65, whiteY)
        for ((n, _) in Patterns.colours) {
            val xyz = meas[n] ?: continue
            val t = tg[n] ?: continue
            val expY = whiteY * t.yRel * lvl
            val target = ColourMath.xyYToXYZ(t.xy, expY)
            val xy = ColourMath.xyzToXY(xyz)
            val m = ColourMath.polar(xy)
            val tp = ColourMath.polar(t.xy)
            rep.colours += ColourResult(
                name = n, xyz = xyz, target = target, xy = xy, txy = t.xy,
                sat = (m.first / tp.first - 1) * 100, hue = ColourMath.wrap(m.second - tp.second),
                lum = (xyz[1] / expY - 1) * 100,
                de = ColourMath.de2000(ColourMath.xyzToLab(xyz, wn), ColourMath.xyzToLab(target, wn)),
            )
        }
        return rep
    }

    // MARK: summaries

    private fun usableGreys(rep: Report, hdr: Boolean) = rep.greys.filter { (hdr || it.level >= 0.099) && !it.clipped }

    /** Average greyscale dE (SDR: 10% and up). NaN if nothing to average. */
    fun greyAverage(rep: Report, hdr: Boolean): Double {
        val g = usableGreys(rep, hdr).map { it.de }
        return if (g.isEmpty()) Double.NaN else g.sum() / g.size
    }

    fun greyMax(rep: Report, hdr: Boolean): Double = usableGreys(rep, hdr).maxOfOrNull { it.de } ?: Double.NaN

    fun colourAverage(rep: Report): Double {
        val c = rep.colours.map { it.de }
        return if (c.isEmpty()) 0.0 else c.sum() / c.size
    }

    /** SDR: average gamma between 10% and 90%. */
    fun gamma(rep: Report): Double? {
        val w = rep.white ?: return null
        val gs = rep.greys.filter { it.level >= 0.1 && it.level <= 0.9 && it.bigY > 0 }
            .map { ln(it.bigY / w[1]) / ln(it.level) }
        return if (gs.isEmpty()) null else gs.sum() / gs.size
    }

    /** HDR: how far brightness is from the PQ target below 300 nits, % on average. */
    fun pqTracking(rep: Report): Double? {
        val trk = rep.greys.filter { it.targetY < 300 && it.targetY > 0 }.map { it.bigY / it.targetY - 1 }
        return if (trk.isEmpty()) null else 100 * trk.sum() / trk.size
    }

    /** "Excellent", "Very good"... for a dE. */
    fun scoreWord(de: Double?): String {
        if (de == null || de.isNaN()) return ""
        return when {
            de < 1 -> "Excellent"
            de < 2 -> "Very good"
            de < 3 -> "Good"
            de < 5 -> "Noticeably off"
            else -> "Clearly off"
        }
    }

    /** Which neighbouring colour a hue error leans toward. */
    fun hueToward(name: String, hue: Double, hdr: Boolean): String? {
        val i = Patterns.hueOrder.indexOf(name)
        if (abs(hue) < 1 || i < 0) return null
        val tg = Patterns.colourTargets(hdr)
        val me = tg[name] ?: return null
        val at = ColourMath.polar(me.xy).second
        var toward: String? = null
        for (cand in listOf(Patterns.hueOrder[(i + 1) % 6], Patterns.hueOrder[(i + 5) % 6])) {
            val c = tg[cand] ?: continue
            if ((ColourMath.wrap(ColourMath.polar(c.xy).second - at) > 0) == (hue > 0)) toward = cand
        }
        return toward
    }
}
