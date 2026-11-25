package tvcal.kit

/** The signal the TV is calibrated for. Each one has its own folder of pattern videos. */
enum class SignalMode(val id: String, val label: String, val badge: String, val packPrefix: String, val packFolder: String) {
    SDR("sdr", "SDR", "SDR", "SDR", "SDR"),
    HDR10("hdr10", "HDR10", "HDR", "HDR10", "HDR10"),
    HDR10P("hdr10p", "HDR10+", "HDR10+", "HDR10+", "HDR10+"),
    DV("dv", "Dolby Vision", "Dolby Vision", "DV", "Dolby Vision");

    val isHDR: Boolean get() = this != SDR

    companion object {
        fun fromId(id: String): SignalMode? = entries.firstOrNull { it.id == id }
    }
}

enum class PatchKind(val id: String) {
    WHITE("white"), GREY("grey"), COLOUR("colour")
}

/** One measured pattern: a centred window (10% of the screen) of a grey, white or colour. */
data class Patch(
    val id: String,
    val kind: PatchKind,
    /** SDR: signal level 0-1. HDR greys/whites: PQ code value 0-1. HDR colours: null. */
    val level: Double?,
    val name: String,
    /** SDR: R'G'B' signal 0-1. */
    val signal: Vec3? = null,
    /** HDR: linear RGB in nits, in the colour's own gamut (P3). */
    val nits: Vec3? = null,
    /** Colours: which primaries are on (1/0). */
    val rgb: Vec3? = null,
    /** Seconds on screen in the videos. */
    val hold: Double = Patterns.HOLD,
) {
    /** Grey/white level as the video's own percentage (SDR signal % or PQ %), e.g. 50 for "G50". */
    val percent: Double get() = (level ?: 0.0) * 100
}

/** A pattern video's timeline: black, three sync flashes (grey, white, grey), then the patches. */
data class PatternSequence(
    val hdr: Boolean,
    /** The sync flashes: SDR signal levels, or HDR nits. */
    val syncLow: Double,
    val syncHigh: Double,
    val patches: List<Patch>,
) {
    /** Start time of each patch, in seconds after the sync. */
    val offsets: List<Double>
        get() {
            val out = ArrayList<Double>(patches.size)
            var t = 0.0
            for (p in patches) {
                out += t
                t += p.hold
            }
            return out
        }

    /** Same order and timing rules, only some patches (the quick-check videos). */
    fun keeping(ids: List<String>): PatternSequence = copy(patches = patches.filter { it.id in ids })
}

data class ColourTarget(val xy: Point2, val yRel: Double)

object Patterns {
    /** Seconds of black before the sync. */
    const val LEAD = 10.0
    /** Low / high / low sync flashes, seconds each. */
    const val SYNC_HOLD = 6.0
    /** Seconds per measured patch. */
    const val HOLD = 7.0
    /** HDR colours: very saturated light can make a meter integrate for several seconds. */
    const val HDR_COLOUR_HOLD = 12.0
    /** Point-finder greys. */
    const val FINDER_HOLD = 5.0

    val colours: List<Pair<String, Vec3>> = listOf(
        "RED" to vec3(1.0, 0.0, 0.0), "GREEN" to vec3(0.0, 1.0, 0.0), "BLUE" to vec3(0.0, 0.0, 1.0),
        "CYAN" to vec3(0.0, 1.0, 1.0), "MAGENTA" to vec3(1.0, 0.0, 1.0), "YELLOW" to vec3(1.0, 1.0, 0.0),
    )
    val colourIDs = listOf("RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW")
    val hueOrder = listOf("RED", "YELLOW", "GREEN", "CYAN", "BLUE", "MAGENTA")
    /** The order colour menus list them in. */
    val menuOrder = listOf("RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA")

    private fun f0(x: Double) = fmtFixed(x, 0)
    private fun white() = vec3(1.0, 1.0, 1.0)
    private fun nits100() = vec3(100.0, 100.0, 100.0)

    /** The full automatic-round sequence (the long tvcal_auto video). */
    fun sequence(hdr: Boolean): PatternSequence {
        val pats = ArrayList<Patch>()
        if (!hdr) {
            pats += Patch("W0", PatchKind.WHITE, 1.0, "100% WHITE", signal = white())
            for (p in 5..100 step 5) {
                val l = p / 100.0
                pats += Patch("G$p", PatchKind.GREY, l, "$p% GREY", signal = vec3(l, l, l))
            }
            for ((n, c) in colours) {
                pats += Patch(n, PatchKind.COLOUR, 0.75, "$n 75%", signal = c * 0.75, rgb = c)
            }
            pats += Patch("W1", PatchKind.WHITE, 1.0, "100% WHITE (drift check)", signal = white())
            return PatternSequence(false, 0.30, 1.0, pats)
        }
        pats += Patch("W0", PatchKind.WHITE, ColourMath.pqEncode(100.0), "100 nit WHITE", nits = nits100())
        for (p in 25..75 step 5) {
            val n = ColourMath.pqDecode(p / 100.0)
            pats += Patch("G$p", PatchKind.GREY, p / 100.0, "PQ $p% GREY (${f0(n)} nits)", nits = vec3(n, n, n))
        }
        for ((n, c) in colours) {
            pats += Patch(n, PatchKind.COLOUR, null, "P3 $n", nits = c * 100.0, rgb = c, hold = HDR_COLOUR_HOLD)
        }
        pats += Patch("W1", PatchKind.WHITE, ColourMath.pqEncode(100.0), "100 nit WHITE (drift check)", nits = nits100())
        return PatternSequence(true, 25.0, 200.0, pats)
    }

    fun patch(hdr: Boolean, id: String): Patch? = sequence(hdr).patches.firstOrNull { it.id == id }

    /** Length of the automatic-round video in seconds. */
    fun videoLength(hdr: Boolean): Int = (LEAD + 3 * SYNC_HOLD).toInt() + sequence(hdr).patches.sumOf { it.hold }.toInt()

    /** The point-finder video: the same sync, then finely spaced greys (5 s each). */
    fun finderSequence(hdr: Boolean): PatternSequence {
        val base = sequence(hdr)
        val pats = arrayListOf(
            if (hdr) Patch("FW", PatchKind.WHITE, ColourMath.pqEncode(100.0), "WHITE", nits = nits100(), hold = FINDER_HOLD)
            else Patch("FW", PatchKind.WHITE, 1.0, "WHITE", signal = white(), hold = FINDER_HOLD),
        )
        if (!hdr) {
            for (p in 10..100 step 5) {
                val l = p / 100.0
                pats += Patch("F$p", PatchKind.GREY, l, "$p% GREY", signal = vec3(l, l, l), hold = FINDER_HOLD)
            }
        } else {
            for (i in 0 until 25) {
                val pq = 20 + 2.5 * i
                val n = ColourMath.pqDecode(pq / 100)
                pats += Patch(
                    "F${(pq * 10).toInt()}", PatchKind.GREY, pq / 100, "PQ ${fmtG(pq)}% GREY (${f0(n)} nits)",
                    nits = vec3(n, n, n), hold = FINDER_HOLD,
                )
            }
        }
        return base.copy(patches = pats)
    }

    /** The patches in the one-to-two-minute quick-check videos. */
    fun quickIDs(hdr: Boolean): List<String> =
        if (hdr) listOf("W0", "G30", "G40", "G50", "G60", "G70", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW")
        else listOf("W0", "G20", "G40", "G60", "G80", "G100", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW")

    fun quickSequence(hdr: Boolean, ids: List<String>? = null): PatternSequence = sequence(hdr).keeping(ids ?: quickIDs(hdr))

    /** The pattern pack on the USB stick: (patch id or "QUICK", file label), in file order. */
    fun packFiles(hdr: Boolean): List<Pair<String, String>> =
        if (!hdr) listOf(
            "QUICK" to "00 - Quick check (play from the start)", "W0" to "01 - White (bright end)",
            "G30" to "02 - Grey 30 (dark end)", "G50" to "03 - Grey 50 (middle)", "G20" to "04 - Grey 20",
            "G40" to "05 - Grey 40", "G60" to "06 - Grey 60", "G80" to "07 - Grey 80", "RED" to "08 - Red",
            "GREEN" to "09 - Green", "BLUE" to "10 - Blue", "CYAN" to "11 - Cyan", "MAGENTA" to "12 - Magenta",
            "YELLOW" to "13 - Yellow",
        ) else listOf(
            "QUICK" to "00 - Quick check (play from the start)", "G65" to "01 - Grey 65 (bright end)",
            "G35" to "02 - Grey 35 (dark end)", "G50" to "03 - Grey 50 (middle)", "G40" to "04 - Grey 40",
            "G60" to "05 - Grey 60", "G70" to "06 - Grey 70", "RED" to "07 - Red", "GREEN" to "08 - Green",
            "BLUE" to "09 - Blue", "CYAN" to "10 - Cyan", "MAGENTA" to "11 - Magenta", "YELLOW" to "12 - Yellow",
        )

    /** File name (without .mp4) of the pattern video for a patch, e.g. "SDR 02 - Grey 30 (dark end)". */
    fun packFile(mode: SignalMode, id: String): String? =
        packFiles(mode.isHDR).firstOrNull { it.first == id }?.let { "${mode.packPrefix} ${it.second}" }

    /** The point-finder video for a mode, e.g. "SDR 00b - Point finder (play from the start)". */
    fun finderFile(mode: SignalMode): String = "${mode.packPrefix} 00b - Point finder (play from the start)"

    /** The long automatic-round video. */
    fun autoFile(hdr: Boolean): String = if (hdr) "tvcal_auto_HDR" else "tvcal_auto_SDR"

    /** Colour targets: chromaticity and brightness relative to white (Rec.709 for SDR, P3 for HDR). */
    fun colourTargets(hdr: Boolean): Map<String, ColourTarget> {
        val m = ColourMath.rgbToXYZMatrix((if (hdr) ColourMath.Gamut.P3 else ColourMath.Gamut.REC709).primaries)
        return colours.associate { (n, c) ->
            val v = ColourMath.matvec(m, c)
            n to ColourTarget(ColourMath.xyzToXY(v), v[1])
        }
    }
}
