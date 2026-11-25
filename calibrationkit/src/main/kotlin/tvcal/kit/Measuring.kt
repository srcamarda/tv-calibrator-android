package tvcal.kit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// MARK: - Measurements

/** A patch that got no reading (too dark for the meter, or the meter too slow). */
data class MissedPatch(
    val name: String,
    /** How long each reading attempt took, in seconds. */
    val readings: List<Double>,
    /** Seconds the patch was on screen. */
    val hold: Double,
)

/**
 * One round's readings: XYZ per patch id. Upstream stores it like the Mac app's history files:
 * {"W0": [X, Y, Z], "G5": null, ..., "_missed": {...}}.
 */
class Measurements(
    val values: MutableMap<String, Vec3> = LinkedHashMap(),
    val missed: MutableMap<String, MissedPatch> = LinkedHashMap(),
) {
    operator fun get(id: String): Vec3? = values[id]
    operator fun set(id: String, v: Vec3?) {
        if (v == null) values.remove(id) else values[id] = v
    }
}

// MARK: - Meters

/** One reading: when it started and ended (on the meter's own clock, seconds) and what it saw. */
class MeterReading(val start: Double, val end: Double, val xyz: Vec3?)

/** Anything that measures the TV: the phone camera, or a simulation. */
interface Meter {
    /** Seconds on the meter's clock (monotonic). Simulations use a virtual clock. */
    fun now(): Double
    suspend fun sleep(seconds: Double)
    suspend fun read(): MeterReading
    /** How long before a reading's end the light it reports was captured (the sync uses this). */
    val integration: Double
}

/** Real-time clock helpers for meters that measure real light. */
object MeterClock {
    fun now(): Double = System.nanoTime() / 1e9
    suspend fun sleep(seconds: Double) {
        if (seconds > 0) delay((seconds * 1000).toLong())
    }
}

sealed class MeasureError(message: String) : Exception(message) {
    object NoSync : MeasureError(
        "No sync flashes seen. Check the camera is pointed at the centre of the screen and the video is playing from the start."
    )
    object OutOfOrder : MeasureError(
        "The greys came in out of order, so the camera and the video got out of step. Start the measurement, then play the video from the very beginning."
    )
}

/** Progress while a video is being measured. */
sealed class SweepEvent {
    object Waiting : SweepEvent()
    data class Reading(val y: Double?) : SweepEvent()
    object Synced : SweepEvent()
    data class PatchStart(val index: Int, val id: String, val name: String) : SweepEvent()
    data class PatchDone(val index: Int, val id: String, val name: String, val xyz: Vec3?) : SweepEvent()
}

// MARK: - Following a pattern video

object Sweep {
    internal fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    internal fun neutral(v: Vec3, limit: Double = 0.05): Boolean =
        v[1] > 0 && ColourMath.polar(ColourMath.xyzToXY(v)).first < limit

    private class Run(val ys: MutableList<Double>, val ts: MutableList<Double>, val xyz: MutableList<Vec3>)

    /**
     * Waits for the sync and returns the time (meter clock) the patch sequence starts.
     *
     * The video goes: black, grey, WHITE, grey, then the first (bright) patch. This looks for
     * bright -> grey (~6 s) -> bright, all three neutral (not a colour). Readings that straddle a
     * change are skipped, and anything shown before PLAY is ignored.
     */
    suspend fun findSync(meter: Meter, timeout: Double = 240.0, onEvent: ((SweepEvent) -> Unit)? = null): Double {
        onEvent?.invoke(SweepEvent.Waiting)
        val tStart = meter.now()
        val runs = ArrayList<Run>()
        while (meter.now() - tStart < timeout) {
            currentCoroutineContext().ensureActive()
            val r = meter.read()
            val xyz = r.xyz
            if (xyz == null) {
                onEvent?.invoke(SweepEvent.Reading(null))
                continue
            }
            val t = r.end - meter.integration
            val y = maxOf(xyz[1], 1e-4)
            onEvent?.invoke(SweepEvent.Reading(xyz[1]))
            val last = runs.lastOrNull()
            if (last != null && 1 / 1.6 < y / median(last.ys) && y / median(last.ys) < 1.6) {
                last.ys += y
                last.ts += t
                last.xyz += xyz
            } else {
                runs += Run(mutableListOf(y), mutableListOf(t), mutableListOf(xyz))
            }
            while (runs.size > 10) runs.removeAt(0)
            // drop single-reading runs that sit between their neighbours (a reading caught mid-change)
            val clean = ArrayList<Run>()
            for ((i, run) in runs.withIndex()) {
                if (i > 0 && i < runs.size - 1 && run.ys.size == 1) {
                    val a = median(runs[i - 1].ys)
                    val b = run.ys[0]
                    val c = median(runs[i + 1].ys)
                    if (minOf(a, c) < b && b < maxOf(a, c)) continue
                }
                clean += run
            }
            if (clean.size < 3) continue
            val h1r = clean[clean.size - 3]
            val lr = clean[clean.size - 2]
            val h2r = clean[clean.size - 1]
            val h1 = median(h1r.ys)
            val l = median(lr.ys)
            val h2 = median(h2r.ys)
            // the sync's grey is a mid grey, not black: bright/grey ratio ~4-20, never ~1000
            if (!(2.0 <= h1 / l && h1 / l <= 40 && 1.6 <= h2 / l && h2 / l <= 40 && 0.3 < h1 / h2 && h1 / h2 < 3.5)) continue
            // all three must be greys/whites, not colour patches
            if (!listOf(h1r, lr, h2r).all { neutral(it.xyz[it.xyz.size / 2]) }) continue
            // The grey started between the last bright reading and the first grey one, and ended
            // between the last grey reading and the first bright one: work with ranges.
            val fallLo = h1r.ts.last()
            val fallHi = lr.ts.first()
            val riseLo = lr.ts.last()
            val riseHi = h2r.ts.first()
            val tol = 0.6
            if (!(riseLo - fallHi - tol <= Patterns.SYNC_HOLD && Patterns.SYNC_HOLD <= riseHi - fallLo + tol)) continue
            val lo = maxOf(riseLo, fallLo + Patterns.SYNC_HOLD)
            val hi = minOf(riseHi, fallHi + Patterns.SYNC_HOLD)
            val est = if (lo <= hi) (lo + hi) / 2 else (riseLo + riseHi) / 2
            onEvent?.invoke(SweepEvent.Synced)
            return est
        }
        throw MeasureError.NoSync
    }

    /** Measures every patch of a pattern video, following its timeline from the sync. */
    suspend fun sweep(meter: Meter, sequence: PatternSequence, onEvent: ((SweepEvent) -> Unit)? = null): Measurements {
        val tSeq = findSync(meter, onEvent = onEvent)
        val offsets = sequence.offsets
        val meas = Measurements()
        for ((i, p) in sequence.patches.withIndex()) {
            val start = tSeq + offsets[i]
            val end = start + p.hold
            onEvent?.invoke(SweepEvent.PatchStart(i, p.id, p.name))
            while (meter.now() < start + 1.2) {
                currentCoroutineContext().ensureActive()
                meter.sleep(minOf(0.25, start + 1.2 - meter.now()))
            }
            val vals = ArrayList<Vec3>()
            val tries = ArrayList<Double>()
            while (meter.now() < end - 1.0) {
                currentCoroutineContext().ensureActive()
                val r = meter.read()
                tries += pyRound(r.end - r.start, 1)
                onEvent?.invoke(SweepEvent.Reading(r.xyz?.get(1)))
                val xyz = r.xyz
                if (xyz == null || r.start < start + 0.5) continue
                if (r.end - meter.integration <= end - 0.15) vals += xyz
                // bright patches: one more reading if there's clearly time
                if (r.end + (r.end - r.start) > end - 0.6) break
            }
            if (vals.isEmpty()) {
                meas.missed[p.id] = MissedPatch(p.name, tries, p.hold)
            } else {
                meas[p.id] = vals.reduce { a, b -> a + b } / vals.size.toDouble()
            }
            onEvent?.invoke(SweepEvent.PatchDone(i, p.id, p.name, meas[p.id]))
        }
        return meas
    }

    /** Readings in the wrong order mean the video and the meter got out of step. */
    fun outOfOrder(meas: Measurements, hdr: Boolean): Boolean {
        val ys = Patterns.sequence(hdr).patches.filter { it.kind == PatchKind.GREY }.mapNotNull { meas[it.id]?.get(1) }
        return ys.zipWithNext().any { (a, b) -> b < a * 0.8 }
    }
}
