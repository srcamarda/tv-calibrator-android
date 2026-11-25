package tvcal.kit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// The same checks as upstream's CalibrationKit tests, against the same fixtures (recorded from the
// Mac app's Python engine by upstream's tools/make_fixtures.py): the port must answer exactly alike.

object Fixture {
    fun load(name: String): JsonElement {
        val text = Fixture::class.java.getResource("/fixtures/$name.json")?.readText() ?: error("missing fixture $name")
        return Json.parseToJsonElement(text)
    }
}

fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonElement?.arr(): List<JsonElement> = this as? JsonArray ?: emptyList()
fun JsonElement?.num(): Double? = if (this == null || this is JsonNull) null else (this as? JsonPrimitive)?.doubleOrNull
fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else (this as? JsonPrimitive)?.content
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
fun JsonElement?.v3(): Vec3 = this.arr().let { vec3(it[0].num()!!, it[1].num()!!, it[2].num()!!) }
fun JsonElement?.pt(): Point2 = this.arr().let { Point2(it[0].num()!!, it[1].num()!!) }

/** Decodes a measurements object as upstream's history files store it. */
fun JsonElement?.measurements(): Measurements {
    val m = Measurements()
    for ((k, v) in this.obj()) {
        if (k == "_missed") {
            for ((id, e) in v.obj()) {
                val o = e.obj()
                m.missed[id] = MissedPatch(o["name"].str()!!, o["readings_s"].arr().map { it.num()!! }, o["hold_s"].num()!!)
            }
        } else if (v is JsonArray && v.size == 3) {
            m[k] = v.v3()
        }
    }
    return m
}

/** Equal within a relative tolerance (and an absolute one near zero). null == null; NaN matches null. */
fun assertClose(a: Double?, b: Double?, tol: Double = 1e-6, msg: String = "") {
    val x = a?.takeUnless { it.isNaN() }
    val y = b?.takeUnless { it.isNaN() }
    if (x == null && y == null) return
    if (x == null || y == null) fail("$msg: $x vs $y")
    assertTrue(abs(x - y) <= tol * maxOf(1.0, abs(x), abs(y)), "$msg: $x vs $y")
}

fun assertClose(a: Vec3, b: Vec3, tol: Double = 1e-6, msg: String = "") {
    for (i in 0..2) assertClose(a[i], b[i], tol, "$msg[$i]")
}

fun assertClose(a: Point2, b: Point2, tol: Double = 1e-6, msg: String = "") {
    assertClose(a.x, b.x, tol, "$msg.x")
    assertClose(a.y, b.y, tol, "$msg.y")
}

class MathsTests {
    private val f = Fixture.load("maths").obj()

    @Test
    fun conversionsMatchPython() {
        val w = ColourMath.xyYToXYZ(ColourMath.d65, 100.0)
        for ((i, item) in f["xyz"].arr().withIndex()) {
            val c = item.obj()
            val xyz = c["XYZ"].v3()
            val xy = ColourMath.xyzToXY(xyz)
            assertClose(xy, c["xy"].pt(), 1e-9, "xy $i")
            assertClose(ColourMath.xyzToLab(xyz, w), c["lab"].v3(), 1e-9, "lab $i")
            assertClose(ColourMath.whiteDE(xyz), c["white_de"].num(), 1e-9, "white dE $i")
            assertClose(ColourMath.upvp(xy), c["upvp"].pt(), 1e-9, "u'v' $i")
            val pol = ColourMath.polar(xy)
            val want = c["polar"].arr()
            assertClose(pol.first, want[0].num(), 1e-9, "polar r $i")
            assertClose(pol.second, want[1].num(), 1e-9, "polar angle $i")
            assertClose(ColourMath.xyzToRGB709(xyz), c["rgb709"].v3(), 1e-9, "rgb709 $i")
        }
    }

    @Test
    fun de2000MatchesPython() {
        for ((i, item) in f["de2000"].arr().withIndex()) {
            val c = item.obj()
            assertClose(ColourMath.de2000(c["a"].v3(), c["b"].v3()), c["de"].num(), 1e-9, "dE2000 $i")
        }
    }

    @Test
    fun pqAndMatrices() {
        for (item in f["pq"].arr()) {
            val c = item.obj()
            c["nits"].num()?.let { assertClose(ColourMath.pqEncode(it), c["enc"].num(), 1e-9, "PQ encode $it") }
            c["enc_in"].num()?.let { assertClose(ColourMath.pqDecode(it), c["nits_out"].num(), 1e-9, "PQ decode $it") }
        }
        for (item in f["wrap"].arr()) {
            val c = item.arr()
            assertClose(ColourMath.wrap(c[0].num()!!), c[1].num(), 1e-12, "wrap")
        }
        val m709 = f["m709"].arr().map { it.v3() }
        val inv = f["m709inv"].arr().map { it.v3() }
        val p3 = f["p3"].arr().map { it.v3() }
        val myP3 = ColourMath.rgbToXYZMatrix(ColourMath.Gamut.P3.primaries)
        for (r in 0..2) {
            assertClose(ColourMath.m709[r], m709[r], 1e-12, "M709")
            assertClose(ColourMath.m709Inverse[r], inv[r], 1e-12, "M709 inverse")
            assertClose(myP3[r], p3[r], 1e-12, "P3")
        }
    }

    @Test
    fun pythonRounding() {
        assertEquals(2.0, pyRound(2.5))
        assertEquals(4.0, pyRound(3.5))
        assertEquals(-2.0, pyRound(-2.5))
        assertEquals(0.1, pyRound(0.15, 1))
        assertEquals("22.5", fmtG(22.5))
        assertEquals("20", fmtG(20.0))
    }
}

class PatternTests {
    private fun comparePatches(mine: List<Patch>, want: List<JsonElement>, label: String) {
        assertEquals(want.size, mine.size, "$label count")
        for ((p, item) in mine.zip(want)) {
            val w = item.obj()
            assertEquals(w["id"].str(), p.id, label)
            assertEquals(w["name"].str(), p.name, "$label ${p.id} name")
            assertEquals(w["kind"].str(), p.kind.id, "$label ${p.id} kind")
            assertClose(p.level, w["level"].num(), 1e-12, "$label ${p.id} level")
            assertClose(p.hold, w["hold"].num() ?: Patterns.HOLD, 1e-12, "$label ${p.id} hold")
            w["sig"]?.let { assertClose(p.signal ?: vec3(-1.0, -1.0, -1.0), it.v3(), 1e-12, "$label ${p.id} signal") }
            w["nits"]?.let { assertClose(p.nits ?: vec3(-1.0, -1.0, -1.0), it.v3(), 1e-9, "$label ${p.id} nits") }
        }
    }

    @Test
    fun sequencesMatchTheVideos() {
        val f = Fixture.load("sequences").obj()
        for (hdr in listOf(false, true)) {
            val w = f[if (hdr) "hdr" else "sdr"].obj()
            val seq = Patterns.sequence(hdr)
            comparePatches(seq.patches, w["patches"].arr(), if (hdr) "HDR" else "SDR")
            assertEquals(w["offsets"].arr().map { it.num() }, seq.offsets)
            assertEquals(w["video_length"].num()!!.toInt(), Patterns.videoLength(hdr))
            val finder = Patterns.finderSequence(hdr)
            comparePatches(finder.patches, w["finder"].arr(), if (hdr) "HDR finder" else "SDR finder")
            assertEquals(w["finder_offsets"].arr().map { it.num() }, finder.offsets)
            assertEquals(w["quick_ids"].arr().map { it.str() }, Patterns.quickIDs(hdr))
            assertEquals(w["quick_offsets"].arr().map { it.num() }, Patterns.quickSequence(hdr).offsets)
            val sync = w["sync"].obj()
            val key = if (hdr) "nits" else "sig"
            assertEquals(sync["low"].obj()[key].v3()[0], seq.syncLow)
            assertEquals(sync["high"].obj()[key].v3()[0], seq.syncHigh)
            val targets = Patterns.colourTargets(hdr)
            for ((name, item) in w["targets"].obj()) {
                val t = item.obj()
                assertClose(targets.getValue(name).xy, t["xy"].pt(), 1e-12, "target $name")
                assertClose(targets.getValue(name).yRel, t["Yrel"].num(), 1e-12, "target $name Y")
            }
        }
        for ((mode, files) in f["pack_files"].obj()) {
            val m = SignalMode.fromId(mode)!!
            for ((pid, name) in files.obj()) assertEquals(name.str(), Patterns.packFile(m, pid))
        }
    }
}

class AnalysisTests {
    @Test
    fun analyseMatchesPython() {
        for (item in Fixture.load("analyse").arr()) {
            val c = item.obj()
            val label = c["label"].str() ?: "?"
            val hdr = c["hdr"].bool() ?: false
            val rep = Analysis.analyse(c["meas"].measurements(), hdr)
            val want = c["report"].obj()
            val greys = want["greys"].arr()
            assertEquals(greys.size, rep.greys.size, "$label greys")
            for ((g, wi) in rep.greys.zip(greys)) {
                val w = wi.obj()
                assertEquals(w["id"].str(), g.id, label)
                assertClose(g.bigY, w["Y"].num(), 1e-9, "$label ${g.id} Y")
                assertClose(g.targetY, w["targetY"].num(), 1e-9, "$label ${g.id} target")
                assertClose(g.bal, w["bal"].v3(), 1e-9, "$label ${g.id} balance")
                assertClose(g.de, w["de"].num(), 1e-9, "$label ${g.id} dE")
                assertEquals(w["clipped"].bool(), g.clipped, "$label ${g.id} clipped")
            }
            val cols = want["colours"].arr()
            assertEquals(cols.size, rep.colours.size, "$label colours")
            for ((col, wi) in rep.colours.zip(cols)) {
                val w = wi.obj()
                assertEquals(w["name"].str(), col.name, label)
                assertClose(col.sat, w["sat"].num(), 1e-9, "$label ${col.name} sat")
                assertClose(col.hue, w["hue"].num(), 1e-9, "$label ${col.name} hue")
                assertClose(col.lum, w["lum"].num(), 1e-9, "$label ${col.name} lum")
                assertClose(col.de, w["de"].num(), 1e-9, "$label ${col.name} dE")
                assertClose(col.target, w["target"].v3(), 1e-9, "$label ${col.name} target")
            }
            assertEquals(want["warnings"].arr().size, rep.warnings.size, "$label warnings: ${rep.warnings}")
            assertClose(rep.drift, want["drift"].num(), 1e-9, "$label drift")
            assertClose(rep.peak, want["peak"].num(), 1e-9, "$label peak")
            assertClose(Analysis.greyAverage(rep, hdr), want["grey_avg"].num(), 1e-9, "$label grey avg")
            assertClose(Analysis.greyMax(rep, hdr), want["grey_max"].num(), 1e-9, "$label grey max")
            assertClose(Analysis.colourAverage(rep), want["colour_avg"].num(), 1e-9, "$label colour avg")
        }
    }
}

class GuidedTests {
    private val f = Fixture.load("guided").obj()

    @Test
    fun settledAverageMatchesPython() {
        for ((i, item) in f["settled"].arr().withIndex()) {
            val c = item.obj()
            val out = Guided.settledAverage(c["readings"].arr().map { it.v3() })
            assertClose(out.xyz, c["xyz"].v3(), 1e-9, "settled $i XYZ")
            assertEquals(c["n"].num()!!.toInt(), out.count, "settled $i count")
            assertClose(out.wobble, c["wobble"].num(), 1e-9, "settled $i wobble")
        }
    }

    @Test
    fun liveReadingMatchesPython() {
        for ((i, item) in f["live"].arr().withIndex()) {
            val c = item.obj()
            val hdr = c["hdr"].bool() ?: false
            val p = Patterns.patch(hdr, c["patch_id"].str()!!)!!
            val basis = c["basis"].takeUnless { it == null || it is JsonNull }?.arr()?.map { it.v3() }
            val r = Guided.liveReading(p, c["XYZ"].v3(), hdr, c["white_Y"].num(), basis)
            val w = c["out"].obj()
            val label = "live $i ${p.id}"
            assertClose(r.uv, w["uv"].pt(), 1e-9, label)
            assertEquals(w["ok"].bool(), r.ok, "$label ok")
            assertClose(r.de, w["de"].num(), 1e-9, "$label dE")
            w["q"]?.obj()?.let { q ->
                assertClose(r.qR, q["R"].num(), 1e-9, "$label qR")
                assertClose(r.qB, q["B"].num(), 1e-9, "$label qB")
            }
            if (p.kind == PatchKind.COLOUR) {
                assertClose(r.hue, w["hue"].num(), 1e-9, "$label hue")
                assertClose(r.sat, w["sat"].num(), 1e-9, "$label sat")
                assertClose(r.lum, w["lum"].num(), 1e-9, "$label lum")
                assertEquals(w["toward"].str(), r.toward, "$label toward")
                assertEquals(w["rgb_approx"].bool() ?: false, r.rgbApprox, "$label approx")
                val h = w["rgb_hint"]
                if (h != null && h !is JsonNull) assertClose(r.rgbHint ?: vec3(Double.NaN, Double.NaN, Double.NaN), h.v3(), 1e-9, "$label hint")
                else assertNull(r.rgbHint, "$label hint")
                val o = w["out_of_reach"] as? JsonObject
                if (o != null) {
                    assertEquals<List<String?>?>(o["channels"].arr().map { it.str() }, r.outOfReach?.channels, "$label out of reach")
                    assertClose(r.outOfReach?.bestDE, o["best_de"].num(), 1e-9, "$label best dE")
                } else {
                    assertNull(r.outOfReach, "$label out of reach")
                }
            }
        }
    }

    @Test
    fun mismatchMatchesPython() {
        for ((i, item) in f["mismatch"].arr().withIndex()) {
            val c = item.obj()
            val hdr = c["hdr"].bool() ?: false
            val p = Patterns.patch(hdr, c["patch_id"].str()!!)!!
            val got = Guided.mismatch(p, c["XYZ"].v3(), hdr, c["white_Y"].num())
            val want = c["out"].str()
            assertEquals(want == null, got == null, "mismatch $i ${p.id}: $got vs $want")
        }
    }

    @Test
    fun liveAnalysisMatchesPython() {
        for (item in f["live_analysis"].arr()) {
            val c = item.obj()
            val r = LiveAnalysis.of(c["XYZ"].v3(), c["hdr"].bool() ?: false)
            val w = c["out"].obj()
            assertEquals(w["kind"].str(), if (r.kind == LiveAnalysis.Kind.WHITE) "white" else "colour")
            if (r.kind == LiveAnalysis.Kind.WHITE) {
                assertEquals(w["advice"].str(), r.advice)
                assertClose(r.de, w["de"].num(), 1e-9)
            } else {
                assertEquals(w["name"].str(), r.name)
                assertClose(r.hue, w["hue"].num(), 1e-9)
                assertClose(r.sat, w["sat"].num(), 1e-9)
            }
        }
    }
}

class PointFinderTests {
    private val f = Fixture.load("finder").obj()

    @Test
    fun finderMapMatchesPython() {
        for ((i, item) in f["cases"].arr().withIndex()) {
            val c = item.obj()
            val hdr = c["hdr"].bool() ?: false
            val grid = c["grid"].num()!!.toInt()
            val map = PointFinder.map(c["base"].measurements(), c["nudged"].measurements(), hdr)
            val want = c["map"].arr().map { it.obj() }
            assertEquals(want.size, map.size, "finder $i points")
            for ((m, w) in map.zip(want)) {
                assertEquals(w["point"].num()!!.toInt(), m.point, "finder $i")
                assertEquals(w["channel"].str(), m.channel, "finder $i channel")
                assertClose(m.level, w["level"].num(), 1e-9, "finder $i level")
                assertClose(m.size, w["size"].num(), 1e-9, "finder $i size")
            }
            val sugg = PointFinder.suggestions(map, emptyMap(), hdr, grid)
            for ((pid, v) in c["suggest"].obj()) assertEquals(v.num()?.toInt(), sugg[pid], "finder $i suggestion $pid")
        }
    }

    @Test
    fun pointForLevelMatchesPython() {
        for (item in f["point_for_level"].arr()) {
            val c = item.obj()
            val pairs = c["pairs"].arr().map {
                val d = it.obj()
                FinderPoint(d["point"].num()!!.toInt(), "R", d["level"].num()!!, 1.0)
            }
            val got = PointFinder.pointForLevel(pairs, c["L"].num()!!, c["grid"].num()!!.toInt())
            assertEquals(c["out"].num()?.toInt(), got, "L ${c["L"]} grid ${c["grid"]}")
        }
    }

    @Test
    fun overridesWin() {
        val map = listOf(FinderPoint(20, "R", 30.0, 6.0), FinderPoint(40, "B", 50.0, 4.0))
        assertEquals(45, PointFinder.suggestions(map, mapOf("G50" to 45), false, 5)["G50"])
    }
}

class SweepTests {
    @Test
    fun syncIsFoundAndEveryPatchMeasured() = runBlocking {
        for (hdr in listOf(false, true)) {
            val tv = SimTV(hdr, seed = 3)
            val seq = Patterns.quickSequence(hdr)
            val meas = Sweep.sweep(SimMeter(tv, seq, playAt = 7.0), seq)
            for (p in seq.patches) {
                val m = meas[p.id]
                assertNotNull(m, "HDR $hdr: ${p.id} missing")
                assertClose(m[1], tv.render(p)[1], 0.03, "HDR $hdr: ${p.id} brightness")
            }
            assertFalse(Sweep.outOfOrder(meas, hdr))
        }
    }

    @Test
    fun noSyncWithoutAVideo() = runBlocking {
        val meter = SimMeter(SimTV(false), PatternSequence(false, 0.3, 1.0, emptyList()), playAt = 1e9)
        assertFailsWith<MeasureError.NoSync> { Sweep.findSync(meter, timeout = 60.0) }
        Unit
    }

    @Test
    fun simulatedRoundAnalyses() = runBlocking {
        // A full SDR round through the simulator: the analysis sees a TV close to, but not on, target.
        val tv = SimTV(false, seed = 11)
        val seq = Patterns.sequence(false)
        val rep = Analysis.analyse(Sweep.sweep(SimMeter(tv, seq), seq), false)
        // 5% grey can be too dark for the simulated meter in its 7 s (reported as missed), as on a real TV
        assertTrue(rep.greys.size >= 19, "greys ${rep.greys.size}")
        assertEquals(6, rep.colours.size)
        val gamma = Analysis.gamma(rep)!!
        assertTrue(gamma in 2.2..2.6, "gamma $gamma")
        assertTrue(Analysis.greyAverage(rep, false) > 0.3, "the demo TV starts off target")
    }
}
