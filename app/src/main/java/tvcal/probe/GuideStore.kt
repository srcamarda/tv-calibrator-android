package tvcal.probe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tvcal.kit.Measurements
import tvcal.kit.MissedPatch
import java.io.File

// Saved guided calibrations, one file per meter (the camera on the real TV, the demo TV), like upstream's
// TVStore keeps one guide state per TV and format. Plain JSON, readable with adb:
// Android/data/br.camarda.tvcal.probe/files/guided/camera.json

/** One calibration session: the Before round, and the After round once finished. Times in ms since 1970. */
data class GuideSession(
    val started: Long = System.currentTimeMillis(),
    val before: Measurements? = null,
    val after: Measurements? = null,
    val finished: Long? = null,
)

/** Everything a guided calibration needs to carry on where it was left. */
data class GuideState(
    val step: GuideStep = GuideStep.SETUP,
    val done: List<GuideStep> = emptyList(),
    /** Steps and colours at the TV's limit ("bright", "dark", "RED"...). */
    val limits: List<String> = emptyList(),
    val coloursDone: List<String> = emptyList(),
    val colour: String = "RED",
    /** The latest Check round. */
    val check: Measurements? = null,
    /** Oldest first; the last one is the current session. */
    val sessions: List<GuideSession> = emptyList(),
    /** The demo TV: its seed and the menu changes made on it. */
    val demoSeed: Long = 7,
    val demoCtrl: Map<String, Double> = emptyMap(),
)

object GuideStore {
    private const val FORMAT = 1

    private fun file(context: Context, demo: Boolean) = File(outDir(context, "guided"), if (demo) "demo.json" else "camera.json")

    fun load(context: Context, demo: Boolean): GuideState = runCatching {
        val f = file(context, demo)
        if (!f.exists()) GuideState() else decode(JSONObject(f.readText()))
    }.getOrElse { GuideState() }

    /** Writes the whole state (small: a few rounds of ~13 readings). A failed write keeps the old file. */
    fun save(context: Context, demo: Boolean, state: GuideState) {
        runCatching {
            val f = file(context, demo)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encode(state).toString(1))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun encode(s: GuideState): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("step", s.step.name)
        put("done", JSONArray(s.done.map { it.name }))
        put("limits", JSONArray(s.limits))
        put("coloursDone", JSONArray(s.coloursDone))
        put("colour", s.colour)
        s.check?.let { put("check", encode(it)) }
        put("sessions", JSONArray(s.sessions.map { ss ->
            JSONObject().apply {
                put("started", ss.started)
                ss.before?.let { put("before", encode(it)) }
                ss.after?.let { put("after", encode(it)) }
                ss.finished?.let { put("finished", it) }
            }
        }))
        put("demoSeed", s.demoSeed)
        put("demoCtrl", JSONObject(s.demoCtrl))
    }

    fun decode(o: JSONObject): GuideState {
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        fun step(name: String) = GuideStep.entries.firstOrNull { it.name == name }
        val sessions = o.optJSONArray("sessions")?.let { a ->
            List(a.length()) { i ->
                val ss = a.getJSONObject(i)
                GuideSession(
                    started = ss.optLong("started"),
                    before = ss.optJSONObject("before")?.let(::decodeMeasurements),
                    after = ss.optJSONObject("after")?.let(::decodeMeasurements),
                    finished = if (ss.has("finished")) ss.getLong("finished") else null,
                )
            }
        } ?: emptyList()
        val ctrl = o.optJSONObject("demoCtrl")?.let { c -> c.keys().asSequence().associateWith { c.getDouble(it) } } ?: emptyMap()
        return GuideState(
            step = step(o.optString("step")) ?: GuideStep.SETUP,
            done = strings("done").mapNotNull(::step),
            limits = strings("limits"),
            coloursDone = strings("coloursDone"),
            colour = o.optString("colour", "RED"),
            check = o.optJSONObject("check")?.let(::decodeMeasurements),
            sessions = sessions,
            demoSeed = o.optLong("demoSeed", 7),
            demoCtrl = ctrl,
        )
    }

    /** Upstream's shape: {"W0": [X, Y, Z], ..., "_missed": {"G5": {"name", "readings", "hold"}}}. */
    fun encode(m: Measurements): JSONObject = JSONObject().apply {
        for ((id, v) in m.values) put(id, JSONArray(v.toList()))
        if (m.missed.isNotEmpty()) {
            put("_missed", JSONObject().apply {
                for ((id, mp) in m.missed) put(id, JSONObject().apply {
                    put("name", mp.name)
                    put("readings", JSONArray(mp.readings))
                    put("hold", mp.hold)
                })
            })
        }
    }

    fun decodeMeasurements(o: JSONObject): Measurements {
        val m = Measurements()
        for (id in o.keys()) {
            if (id == "_missed") continue
            val a = o.optJSONArray(id) ?: continue
            if (a.length() == 3) m[id] = DoubleArray(3) { a.getDouble(it) }
        }
        o.optJSONObject("_missed")?.let { mo ->
            for (id in mo.keys()) {
                val x = mo.getJSONObject(id)
                val r = x.optJSONArray("readings")
                m.missed[id] = MissedPatch(x.optString("name", id), r?.let { a -> List(a.length()) { a.getDouble(it) } } ?: emptyList(), x.optDouble("hold"))
            }
        }
        return m
    }
}
