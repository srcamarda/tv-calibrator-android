package tvcal.kit

import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// A made-up TV and meter, used by the tests and by the app's demo mode (so the app can be tried
// without a TV). Ported from upstream's Simulation.swift (itself from the Python engine's SimTV / SimMeter).

/** Small, seedable random numbers (SplitMix64), so a demo TV is the same every time. */
class SeededRandom(seed: Long) {
    private var state: Long = seed + -0x61c8864680b583ebL   // 0x9E3779B97F4A7C15

    fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L      // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L      // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** 0 ..< 1 */
    fun unit(): Double = (next() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun uniform(a: Double, b: Double): Double = a + (b - a) * unit()

    fun gauss(mu: Double, sigma: Double): Double {
        val u1 = maxOf(unit(), 1e-12)
        val u2 = unit()
        return mu + sigma * sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)
    }
}

/**
 * A TV with white-balance, gamma and colour errors that responds to menu changes roughly the way a
 * real one does (not exactly like the engine's model).
 */
class SimTV(val hdr: Boolean, state: State? = null, seed: Long = 7) {
    class State(
        var peak: Double,
        var gainErr: DoubleArray,
        var offErr: DoubleArray,
        var gamma: DoubleArray,
        var colErr: MutableMap<String, DoubleArray>,
        var ctrl: MutableMap<String, Double>,
        /** Each multipoint control acts this far (0-1) above its label. */
        var p20Shift: Double,
    )

    val s: State
    private val kTrue = 0.0075
    private val gainStep = doubleArrayOf(0.0045, 0.0040, 0.0055)
    private val offStep = doubleArrayOf(0.0030, 0.0035, 0.0110)        // blue offset ~3x stronger, like the Micro RGB
    private val p20Step = doubleArrayOf(0.0070, 0.0050, 0.0040)
    /** Hue/sat/lum menus in the demo: degrees, % and % per click. */
    private val hslStep = mapOf("hue" to 1.2, "sat" to 0.9, "lum" to 1.1)

    init {
        s = state ?: run {
            val rnd = SeededRandom(seed)
            val col = LinkedHashMap<String, DoubleArray>()
            for ((n, _) in Patterns.colours) {
                col[n] = doubleArrayOf(rnd.uniform(-0.06, 0.06), rnd.uniform(-0.06, 0.06), rnd.uniform(-0.06, 0.06))
            }
            State(
                peak = if (hdr) 700.0 else 160.0,
                gainErr = doubleArrayOf(rnd.uniform(-0.06, 0.0), rnd.uniform(-0.02, 0.0), rnd.uniform(-0.06, 0.0)),
                offErr = doubleArrayOf(rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03)),
                gamma = doubleArrayOf(
                    2.4 + rnd.uniform(-0.12, 0.12), 2.4 + rnd.uniform(-0.12, 0.12), 2.4 + rnd.uniform(-0.12, 0.12)
                ),
                colErr = col, ctrl = LinkedHashMap(), p20Shift = 0.0,
            )
        }
    }

    /** Menu changes, in clicks. Menus stop at +/-50. */
    fun apply(changes: Map<String, Int>) {
        for ((k, v) in changes) s.ctrl[k] = clamp((s.ctrl[k] ?: 0.0) + v, -50.0, 50.0)
    }

    /**
     * Power limiter: if the brightest channel at full white would exceed the panel's max, everything
     * is scaled down (so raising all gains together does nothing).
     */
    private fun limit(): Double {
        val m = (0..2).maxOf { chanRaw(it, 1.0, null) }
        return if (m > 1) 1 / m else 1.0
    }

    internal fun chan(c: Int, level: Double, keyLevel: Double? = null): Double = chanRaw(c, level, keyLevel) * limit()

    private fun chanRaw(c: Int, level: Double, keyLevel: Double?): Double {
        val ch = listOf("R", "G", "B")[c]
        val kl = keyLevel ?: level
        val g = s.ctrl["gain:$ch"] ?: 0.0
        val o = s.ctrl["offset:$ch"] ?: 0.0
        // multipoint: tent interpolation between 5% points, spilling into neighbours
        var p = 0.0
        for ((key, v) in s.ctrl) {
            if (!key.startsWith("p20:$ch:")) continue
            val parts = key.split(":")
            val lvInt = parts.getOrNull(2)?.toDoubleOrNull()
            if (parts.size != 3 || lvInt == null) continue
            val lv = lvInt / 100
            p += v * maxOf(0.0, 1 - abs(kl - (lv + s.p20Shift)) / 0.09)
        }
        val base = level.pow(s.gamma[c])
        var v = base * (1 + s.gainErr[c] + g * gainStep[c]) * (1 + p * p20Step[c])
        v += (s.offErr[c] + o * offStep[c]) * 0.02 * (1 - level).pow(2) * (1 - exp(-30 * level))
        return maxOf(v, 0.0)
    }

    /** What a meter on the centre of the screen sees for a patch. */
    fun render(patch: Patch): Vec3 {
        val peak = s.peak
        if (patch.kind == PatchKind.WHITE || patch.kind == PatchKind.GREY) {
            val rgb = DoubleArray(3)
            val nits = patch.nits?.get(0)
            if (hdr && nits != null) {
                val lvl = if (nits < peak) (nits / peak).pow(1 / 2.4) else 1.0
                for (c in 0..2) rgb[c] = minOf(chan(c, minOf(lvl, 1.0), patch.level), 1.02) * peak
            } else {
                for (c in 0..2) rgb[c] = chan(c, patch.level ?: 0.0) * peak
            }
            return ColourMath.matvec(ColourMath.m709, rgb)
        }
        // colour: native primaries a bit wider than the target, plus per-colour errors + menu sliders
        val name = patch.id
        val target = patch.rgb ?: vec3(0.0, 0.0, 0.0)
        val lvl = if (hdr) (100 / peak).pow(1 / 2.4) else 0.75
        val mix = DoubleArray(3)
        for ((j, ch) in listOf("R", "G", "B").withIndex()) {
            val cms = (s.ctrl["cms:$name:$ch"] ?: 0.0) * kTrue
            mix[j] = maxOf(0.0, target[j] + (s.colErr[name]?.get(j) ?: 0.0) + cms) * chan(j, lvl) * peak
        }
        val native = if (hdr) ColourMath.Gamut.P3.primaries
        else listOf(Point2(0.655, 0.325), Point2(0.290, 0.615), Point2(0.150, 0.058))
        var xyz = ColourMath.matvec(ColourMath.rgbToXYZMatrix(native), mix)
        // demo extra: hue / saturation / brightness sliders move the colour around D65
        val h = s.ctrl["hsl:$name:hue"] ?: 0.0
        val sa = s.ctrl["hsl:$name:sat"] ?: 0.0
        val lu = s.ctrl["hsl:$name:lum"] ?: 0.0
        if (h != 0.0 || sa != 0.0 || lu != 0.0) {
            val xy = ColourMath.xyzToXY(xyz)
            val w = ColourMath.d65
            val ang = h * hslStep.getValue("hue") * Math.PI / 180
            val scale = 1 + sa * hslStep.getValue("sat") / 100
            val dx = (xy.x - w.x) * scale
            val dy = (xy.y - w.y) * scale
            val nx = w.x + dx * cos(ang) - dy * sin(ang)
            val ny = w.y + dx * sin(ang) + dy * cos(ang)
            xyz = ColourMath.xyYToXYZ(Point2(nx, ny), xyz[1] * (1 + lu * hslStep.getValue("lum") / 100))
        }
        return xyz
    }

    /** A sync flash at a drive level (SDR signal, or HDR nits converted to this TV's drive). */
    internal fun renderSync(value: Double): Vec3 {
        val lvl = if (hdr) (value / s.peak).pow(1 / 2.4) else value
        return ColourMath.matvec(ColourMath.m709, vec3(chan(0, lvl) * s.peak, chan(1, lvl) * s.peak, chan(2, lvl) * s.peak))
    }
}

/**
 * Virtual clock + a pattern video playing + a SimTV. Runs much faster than real time unless
 * `realTime` is set (0.1: ten times faster than real, so progress is watchable).
 */
class SimMeter(
    private val tv: SimTV,
    private val seq: PatternSequence,
    private val playAt: Double = 3.0,
    private val realTime: Double = 0.0,
    seed: Long = 1,
) : Meter {
    private var t = 0.0
    private val rnd = SeededRandom(seed)

    override val integration: Double get() = 0.25
    override fun now(): Double = t

    override suspend fun sleep(seconds: Double) {
        if (seconds > 0) {
            t += seconds
            if (realTime > 0) MeterClock.sleep(seconds * realTime) else yield()
        }
    }

    private sealed class Showing {
        object Nothing : Showing()
        class Sync(val level: Double) : Showing()
        class Shown(val patch: Patch) : Showing()
    }

    private fun showing(time: Double): Showing {
        var v = time - playAt
        if (v < Patterns.LEAD) return Showing.Nothing
        v -= Patterns.LEAD
        if (v < 3 * Patterns.SYNC_HOLD) {
            val which = (v / Patterns.SYNC_HOLD).toInt()
            return Showing.Sync(if (which == 1) seq.syncHigh else seq.syncLow)
        }
        v -= 3 * Patterns.SYNC_HOLD
        for ((p, off) in seq.patches.zip(seq.offsets)) {
            if (off <= v && v < off + p.hold) return Showing.Shown(p)
        }
        return Showing.Nothing
    }

    private fun render(s: Showing): Vec3 = when (s) {
        is Showing.Nothing -> vec3(0.03, 0.03, 0.035)
        is Showing.Sync -> tv.renderSync(s.level)
        is Showing.Shown -> tv.render(s.patch)
    }

    override suspend fun read(): MeterReading {
        val t0 = t
        val bigY = render(showing(t0 + 0.3))[1]
        val dur = 0.9 + 0.25 * rnd.unit() + (if (bigY < 5) 1.6 / maxOf(bigY, 0.05).pow(0.5) else 0.0)
        t += dur
        if (realTime > 0) MeterClock.sleep(dur * realTime) else yield()
        // a real meter integrates over a short window, so readings across a change are mixed
        val win = 0.5
        var sum = vec3(0.0, 0.0, 0.0)
        for (k in 0 until 5) sum = sum + render(showing(t - win + win * (k + 0.5) / 5))
        val common = 1 + rnd.gauss(0.0, 0.003)                             // brightness noise
        val xyz = sum / 5.0 * common * (1 + rnd.gauss(0.0, 0.0006))       // small colour noise
        return MeterReading(t0, t, xyz)
    }
}

/** The pattern the app is currently asking for, readable from any thread (the demo meter uses it). */
class ShownPatch {
    @Volatile var patch: Patch? = null
}

/**
 * Demo mode for the guided steps: reads whichever patch the app is asking for (as if the right file
 * were playing), in real time.
 */
class SimLiveMeter(private val tv: SimTV, private val shown: ShownPatch) : Meter {
    private val rnd = SeededRandom(99)

    override val integration: Double get() = 0.1
    override fun now(): Double = MeterClock.now()
    override suspend fun sleep(seconds: Double) = MeterClock.sleep(seconds)

    override suspend fun read(): MeterReading {
        val t0 = now()
        val p = shown.patch
        val xyz = (p?.let { tv.render(it) } ?: vec3(0.03, 0.03, 0.035)) * (1 + rnd.gauss(0.0, 0.002))
        xyz[0] *= 1 + rnd.gauss(0.0, 0.0015)
        xyz[2] *= 1 + rnd.gauss(0.0, 0.0015)
        MeterClock.sleep(if (xyz[1] > 5) 0.35 else 0.8)
        return MeterReading(t0, now(), xyz)
    }
}
