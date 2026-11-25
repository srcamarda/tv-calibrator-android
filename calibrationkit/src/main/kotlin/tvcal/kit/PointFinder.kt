package tvcal.kit

import kotlin.math.abs
import kotlin.math.sqrt

// Point finder: TVs label their multipoint (10/20-point) controls on their own scale, which often
// doesn't match the video's grey levels (an LG C5 in HDR: the 30% control moves the 40% grey).
// The finder measures a ladder of finely spaced greys, the user nudges four controls by +10, it
// measures again, and the bumps in the difference show where each control really acts.

/** Where one TV control acts, as a grey level of the video (its own % scale). */
data class FinderPoint(
    /** The TV control's own label, e.g. 40 for the "40%" control. */
    val point: Int,
    /** "R", "B", or "?" for a point the user set by hand. */
    val channel: String,
    val level: Double,
    /** Size of the bump, %. */
    val size: Double,
)

object PointFinder {
    const val NUDGE = 10
    /** Far apart, alternating channels. */
    val nudges: List<Pair<String, Int>> = listOf("R" to 20, "B" to 40, "R" to 60, "B" to 80)

    /** The menu's step: 5 for 20-point menus, 10 for 10/11-point menus. */
    fun grid(points: Int): Int = if (points == 20 || points == 0) 5 else 10

    private class QRow(val level: Double, val r: Double, val b: Double, val bigY: Double)

    private fun q(meas: Measurements, hdr: Boolean): List<QRow> =
        Patterns.finderSequence(hdr).patches.mapNotNull { p ->
            val xyz = meas[p.id]
            val level = p.level
            if (p.kind != PatchKind.GREY || xyz == null || xyz[1] <= 0 || level == null) return@mapNotNull null
            val rgb = ColourMath.xyzToRGB709(xyz)
            if (rgb[1] <= 0) null else QRow(level * 100, rgb[0] / rgb[1], rgb[2] / rgb[1], xyz[1])
        }

    private class Row(val level: Double, val dR: Double, val dB: Double, val bigY: Double, val baseY: Double)
    private class Peak(val level: Double, val size: Double, val score: Double, val ch: String, val lean: Double)

    /**
     * Where each nudged control acts.
     *
     * The two passes are minutes apart and some TVs drift in that time, which shifts every grey the
     * same way: each channel's change is measured against its own typical (median) change, and only
     * bumps that stand out from the noise count. A higher control always acts on a brighter grey,
     * which settles which bump belongs to which control.
     */
    fun map(base: Measurements, nudged: Measurements, hdr: Boolean): List<FinderPoint> {
        val a = q(base, hdr)
        val b = q(nudged, hdr)
        val bl = HashMap<Double, QRow>()
        for (r in a) bl[pyRound(r.level, 2)] = r
        var rows = b.mapNotNull { r ->
            val o = bl[pyRound(r.level, 2)] ?: return@mapNotNull null
            Row(r.level, r.r / o.r - 1, r.b / o.b - 1, r.bigY, o.bigY)
        }
        // the TV can't get brighter than its peak: greys above it all look the same, so drop them
        val top = rows.maxOfOrNull { it.baseY } ?: 0.0
        rows = rows.filter { it.baseY < 0.97 * top }
        if (rows.size < 6) return emptyList()
        val lv = rows.map { it.level }
        // each channel against its own typical change (drift), then the two added: a nudge shows up as a
        // bump at its grey whichever channel moved
        val dd = (0..1).map { ch ->
            val d = rows.map { if (ch == 0) it.dR else it.dB }
            val med = d.sorted()[d.size / 2]
            d.map { it - med }
        }
        val d = dd[0].zip(dd[1]).map { (r, bb) -> maxOf(0.0, r) + maxOf(0.0, bb) }
        // typical noise: the smaller of the two channel changes at each grey, x2 for the two channels added
        var mad = 2 * 1.48 * dd[0].zip(dd[1]).map { (r, bb) -> minOf(abs(r), abs(bb)) }.sorted()[d.size / 2]
        if (mad == 0.0) mad = 1e-4
        val need = rows.map { maxOf(0.012, 4 * mad * sqrt(1 + 4.0 / maxOf(it.baseY, 0.5))) }   // dark greys: noisier

        val peaks = ArrayList<Peak>()
        if (d.size >= 3) {
            for (i in 1 until d.size - 1) {
                if (!(d[i] >= need[i] && d[i] >= d[i - 1] && d[i] >= d[i + 1])) continue
                // a bump on the first or last measured grey may sit beyond it: can't place it, so skipped
                var l = lv[i]
                val den = d[i - 1] - 2 * d[i] + d[i + 1]
                if (den < 0) l += clamp(0.5 * (d[i - 1] - d[i + 1]) / den, -0.5, 0.5) * (lv[i + 1] - lv[i])
                val r = maxOf(0.0, dd[0][i])
                val bb = maxOf(0.0, dd[1][i])
                val sum = r + bb
                peaks += Peak(
                    pyRound(l, 1), pyRound(d[i] * 100, 1), minOf(d[i] / need[i], 4.0),
                    if (r >= bb) "R" else "B", (r - bb) / (if (sum != 0.0) sum else 1.0),   // +1 red only, -1 blue only
                )
            }
        }
        // bumps go to controls in order (a brighter control acts on a brighter grey); among the
        // orderings, prefer more bumps explained, then the smaller shift from the labels
        val ctrls = nudges.sortedBy { it.second }
        var bestScore = -1e9
        var bestPairs: List<FinderPoint> = emptyList()
        val acc = ArrayList<FinderPoint>()
        fun walk(k: Int, pi: Int, score: Double) {
            if (k == ctrls.size) {
                if (score > bestScore) {
                    bestScore = score
                    bestPairs = acc.toList()
                }
                return
            }
            val c = ctrls[k].first
            val p = ctrls[k].second
            walk(k + 1, pi, score)                                     // no bump for this control
            for (qi in pi until peaks.size) {
                val pk = peaks[qi]
                acc += FinderPoint(p, pk.ch, pk.level, pk.size)
                // which channel moved: a clear red-only bump belongs to a red nudge
                val match = pk.lean * (if (c == "R") 1 else -1)
                walk(k + 1, qi + 1, score + 10 + pk.score - abs(pk.level - p) / 10 + 3 * match)
                acc.removeAt(acc.size - 1)
            }
        }
        walk(0, 0, 0.0)
        return bestPairs
    }

    /** Which TV control to use for a grey at level L (the video's %), from the measured points. */
    fun pointForLevel(pairs: List<FinderPoint>, level: Double, grid: Int): Int? {
        val pts = pairs.map { it.level to it.point.toDouble() }
            .sortedWith(compareBy<Pair<Double, Double>> { it.first }.thenBy { it.second })
        val first = pts.firstOrNull() ?: return null
        val l = level
        var p: Double
        if (pts.size == 1) {
            p = first.second + (l - first.first)
        } else {
            p = first.second
            for (j in 0 until pts.size - 1) {
                val (l0, p0) = pts[j]
                val (l1, p1) = pts[j + 1]
                if (l <= l1 || j + 1 == pts.size - 1) {
                    p = p0 + (p1 - p0) * (l - l0) / (if (l1 - l0 != 0.0) l1 - l0 else 1.0)
                    break
                }
            }
            if (l < first.first) {
                val (l0, p0) = pts[0]
                val (l1, p1) = pts[1]
                p = p0 + (p1 - p0) * (l - l0) / (if (l1 - l0 != 0.0) l1 - l0 else 1.0)
            }
        }
        val g = grid.toDouble()
        if (!p.isFinite()) return null
        return clamp(g * pyRound(p / g), g, 100.0).toInt()
    }

    /**
     * For each grey of the pattern pack: the TV control to use. Controls the user found by hand
     * count as measured points too, and override that grey outright.
     */
    fun suggestions(map: List<FinderPoint>, overrides: Map<String, Int>, hdr: Boolean, grid: Int): Map<String, Int> {
        val levels = LinkedHashMap<String, Double>()
        for (p in Patterns.sequence(hdr).patches) {
            if (p.kind == PatchKind.GREY || p.kind == PatchKind.WHITE) levels.putIfAbsent(p.id, p.percent)
        }
        if (map.isEmpty()) return overrides
        val mine = overrides.keys.sorted().mapNotNull { pid ->
            val l = levels[pid] ?: return@mapNotNull null
            val p = overrides[pid] ?: return@mapNotNull null
            FinderPoint(p, "?", l, 0.0)
        }
        // the user's findings win: drop measured points that disagree with them
        fun agrees(q: FinderPoint) = mine.all { m ->
            q.point != m.point && abs(q.level - m.level) > 4 && (q.point - m.point) * (q.level - m.level) > 0
        }
        val pairs = (map.filter(::agrees) + mine).sortedBy { it.level }
        val out = HashMap<String, Int>()
        for ((pid, l) in levels) pointForLevel(pairs, l, grid)?.let { out[pid] = it }
        for ((pid, p) in overrides) if (levels.containsKey(pid)) out[pid] = p
        return out
    }
}
