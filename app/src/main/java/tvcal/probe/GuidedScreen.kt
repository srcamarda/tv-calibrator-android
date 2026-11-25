package tvcal.probe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tvcal.kit.Analysis
import tvcal.kit.ColourMath
import tvcal.kit.GuidePlan
import tvcal.kit.Guided
import tvcal.kit.LiveReading
import tvcal.kit.MeasureError
import tvcal.kit.Measurements
import tvcal.kit.Meter
import tvcal.kit.Patch
import tvcal.kit.PatchKind
import tvcal.kit.Patterns
import tvcal.kit.Report
import tvcal.kit.ShownPatch
import tvcal.kit.SignalMode
import tvcal.kit.SimLiveMeter
import tvcal.kit.SimMeter
import tvcal.kit.SimTV
import tvcal.kit.Sweep
import tvcal.kit.SweepEvent
import tvcal.kit.Vec3
import tvcal.probe.ui.Badge
import tvcal.probe.ui.Eyebrow
import tvcal.probe.ui.GamutChart
import tvcal.probe.ui.GamutDot
import tvcal.probe.ui.LinkButton
import tvcal.probe.ui.PrimaryButton
import tvcal.probe.ui.SecondaryButton
import tvcal.probe.ui.Tc
import tvcal.probe.ui.TcCard
import tvcal.probe.ui.TcType
import tvcal.probe.ui.TipRow
import tvcal.probe.ui.Tone
import tvcal.probe.ui.WaitingForReading
import tvcal.probe.ui.bigSigned
import tvcal.probe.ui.fmt
import kotlin.math.abs

// The guided calibration, as in the iOS app (GuidedView.swift / Controllers.swift): set up, measure the
// TV as it is (the Quick check video, followed by its sync flashes), fix the bright and dark end of the
// white balance live, check the greys, fix the colours live, and measure again for the before and after.
// SDR on an LG for now; the demo TV runs the whole flow without a TV.

enum class GuideStep(val label: String) {
    SETUP("Set up"), BEFORE("Before"), BRIGHT("Bright end"), DARK("Dark end"), CHECK("Check"), COLOURS("Colours"), AFTER("After");

    val next: GuideStep? get() = entries.getOrNull(ordinal + 1)
}

/** The Quick check video on Slidebean's YouTube channel (upstream patterns.json). */
const val QUICK_CHECK_URL = "https://youtu.be/jGk6o8bytjY"

/** Upstream's LG list (Brands.swift), for SDR. */
private val LG_SDR_SETUP = listOf(
    "Picture mode: Filmmaker Mode (or Movie / Cinema)",
    "Energy Saving and AI Brightness: Off",
    "Gamma: BT.1886, Colour Temperature: Warm 50",
)

/** Following a pattern video. */
sealed class SweepPhase {
    object Idle : SweepPhase()
    object Waiting : SweepPhase()
    object Measuring : SweepPhase()
    object Done : SweepPhase()
    object Cancelled : SweepPhase()
    class Failed(val why: String) : SweepPhase()
}

data class PatchProgress(val id: String, val name: String, val kind: PatchKind, val bigY: Double? = null, val done: Boolean = false)

/**
 * One guided calibration: the steps, the measurements, the meters. Lives as long as the app's screen, and
 * is saved after every change ([GuideStore]), one file per meter: the camera on the real TV, the demo TV.
 */
class GuidedFlow(private val context: Context, private val manager: CameraManager) {
    val hdr = false
    val mode: SignalMode = SignalMode.fromId("sdr")!!
    val plan: GuidePlan = GuidePlan.forHDR(hdr)

    var demo by mutableStateOf(true)
        private set
    var step by mutableStateOf(GuideStep.SETUP)
        private set
    val done = mutableStateListOf<GuideStep>()
    /** Calibration sessions, oldest first: the last one is the current one. */
    val sessions = mutableStateListOf<GuideSession>()
    val before: Measurements? get() = sessions.lastOrNull()?.before
    val after: Measurements? get() = sessions.lastOrNull()?.after
    private var checkMeas by mutableStateOf<Measurements?>(null)
    var check by mutableStateOf<Report?>(null)
        private set
    val limits = mutableStateListOf<String>()
    val coloursDone = mutableStateListOf<String>()
    var colour by mutableStateOf("RED")
        private set
    private var demoSeed = 7L

    // following a video
    var phase by mutableStateOf<SweepPhase>(SweepPhase.Idle)
        private set
    var sweepFor by mutableStateOf<GuideStep?>(null)
        private set
    val progress = mutableStateListOf<PatchProgress>()
    var index by mutableIntStateOf(-1)
        private set
    var latestY by mutableStateOf<Double?>(null)
        private set
    private var job: Job? = null
    val running: Boolean get() = phase == SweepPhase.Waiting || phase == SweepPhase.Measuring

    // live readings
    var live by mutableStateOf<LiveReading?>(null)
        private set
    var liveError by mutableStateOf<String?>(null)
        private set
    var restart by mutableIntStateOf(0)

    // the demo TV, and remote presses (which redraw the controls)
    var tv by mutableStateOf(SimTV(hdr, seed = 7))
        private set
    var tvVersion by mutableIntStateOf(0)
        private set
    private val shown = ShownPatch()
    private var demoLive = SimLiveMeter(tv, shown)

    // the camera
    val raw = RawMeter(manager)
    var cameraReady by mutableStateOf(false)
    var cameraError by mutableStateOf<String?>(null)
    var cameraStatus by mutableStateOf("")
    var whiteVersion by mutableIntStateOf(0)
    val cameraPlan: Result<RawMeter.Setup>
        get() = runCatching {
            CameraFacts.meterCameras(manager).first().let { raw.plan(it, loadImported(context, it), loadWhiteRef(context, it)) }
        }

    /** 100% white of the latest round: colours are judged against it. */
    val whiteY: Double? get() = (after ?: before)?.get("W0")?.get(1)

    /** Red, green and blue of the Before round: they make the colour hints exact. */
    val basis: List<Vec3>? get() = before?.let { b -> listOf("RED", "GREEN", "BLUE").mapNotNull { b[it] }.takeIf { it.size == 3 } }

    init { load() }

    // MARK: saving

    /** Takes up the saved calibration of the current meter. */
    private fun load() {
        val s = GuideStore.load(context, demo)
        step = s.step
        done.clear(); done += s.done
        limits.clear(); limits += s.limits
        coloursDone.clear(); coloursDone += s.coloursDone
        colour = s.colour
        checkMeas = s.check
        check = s.check?.let { Analysis.analyse(it, hdr) }
        sessions.clear(); sessions += s.sessions
        phase = SweepPhase.Idle
        if (demo) {
            demoSeed = s.demoSeed
            tv = SimTV(hdr, seed = demoSeed).also { it.s.ctrl.putAll(s.demoCtrl) }
            demoLive = SimLiveMeter(tv, shown)
            tvVersion++
        }
    }

    private fun save() {
        val state = GuideState(
            step = step, done = done.toList(), limits = limits.toList(), coloursDone = coloursDone.toList(),
            colour = colour, check = checkMeas, sessions = sessions.toList(),
            demoSeed = demoSeed, demoCtrl = if (demo) tv.s.ctrl.toMap() else emptyMap(),
        )
        GuideStore.save(context, demo, state)
    }

    /** The demo TV or the camera: each has its own saved calibration. */
    fun useDemo(d: Boolean) {
        if (d == demo) return
        cancel()
        save()
        demo = d
        load()
    }

    // MARK: moving through the steps

    fun go(to: GuideStep, markDone: GuideStep? = null) {
        if (markDone != null && markDone !in done) done += markDone
        if (running) cancel()
        if (phase is SweepPhase.Failed) phase = SweepPhase.Idle
        step = to
        save()
    }

    fun markLimit(key: String) {
        if (key !in limits) limits += key
        save()
    }

    fun pickColour(c: String) {
        colour = c
        save()
    }

    fun press(key: String, delta: Int) {
        tv.apply(mapOf(key to delta))
        tvVersion++
        save()
    }

    /** A new demo TV, with other errors, to try the demo from scratch. */
    fun newDemoTV() {
        demoSeed = System.nanoTime() % 1000
        tv = SimTV(hdr, seed = demoSeed)
        demoLive = SimLiveMeter(tv, shown)
        tvVersion++
        save()
    }

    /** Start a new session: again from the Before check. The finished sessions stay in the history. */
    fun startOver() {
        cancel()
        done.clear(); done += GuideStep.SETUP
        limits.clear(); coloursDone.clear()
        colour = "RED"
        checkMeas = null; check = null
        sessions += GuideSession()
        phase = SweepPhase.Idle
        step = GuideStep.BEFORE
        save()
    }

    fun finishColour(c: String) {
        if (c !in coloursDone) coloursDone += c
        val rest = Patterns.colourIDs.filter { it !in coloursDone }
        if (rest.isEmpty()) go(GuideStep.AFTER, markDone = GuideStep.COLOURS) else { colour = rest.first(); save() }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun resetPhase() { phase = SweepPhase.Idle }

    // MARK: quick checks (before / check / after)

    fun measureQuick(scope: CoroutineScope, which: GuideStep) {
        cancel()
        job = scope.launch { runQuick(which) }
    }

    private suspend fun runQuick(which: GuideStep) {
        val seq = Patterns.quickSequence(hdr, plan.quickWithColours)
        val meter: Meter = if (demo) SimMeter(tv, seq, playAt = 3.0, realTime = 0.1, seed = System.nanoTime() % 1000)
        else {
            if (!cameraReady) { phase = SweepPhase.Failed(cameraError ?: "The camera isn't ready yet."); return }
            CameraMeter(raw)
        }
        sweepFor = which
        index = -1
        latestY = null
        progress.clear()
        progress += seq.patches.map { PatchProgress(it.id, it.name, it.kind) }
        phase = SweepPhase.Waiting
        try {
            val meas = Sweep.sweep(meter, seq) { e -> handle(e) }
            if (Sweep.outOfOrder(meas, hdr)) {
                phase = SweepPhase.Failed(MeasureError.OutOfOrder.message ?: "Out of order.")
                return
            }
            val now = System.currentTimeMillis()
            when (which) {
                GuideStep.BEFORE -> {
                    // fill a fresh session (from Start a new session), or start one
                    val last = sessions.lastOrNull()
                    if (last != null && last.before == null && last.after == null) sessions[sessions.size - 1] = last.copy(before = meas, started = now)
                    else sessions += GuideSession(started = now, before = meas)
                }
                GuideStep.AFTER -> {
                    // measuring After again: keep comparing with the same starting point
                    if (sessions.isEmpty() || sessions.last().after != null) sessions += GuideSession(started = now, before = sessions.lastOrNull()?.before)
                    sessions[sessions.size - 1] = sessions.last().copy(after = meas, finished = now)
                    if (GuideStep.AFTER !in done) done += GuideStep.AFTER
                }
                else -> { checkMeas = meas; check = Analysis.analyse(meas, hdr) }
            }
            phase = SweepPhase.Done
            save()
        } catch (e: CancellationException) {
            phase = SweepPhase.Cancelled
            throw e
        } catch (e: Exception) {
            phase = SweepPhase.Failed(e.message ?: e.toString())
        }
    }

    private fun handle(e: SweepEvent) {
        when (e) {
            is SweepEvent.Waiting -> phase = SweepPhase.Waiting
            is SweepEvent.Reading -> latestY = e.y
            is SweepEvent.Synced -> phase = SweepPhase.Measuring
            is SweepEvent.PatchStart -> index = e.index
            is SweepEvent.PatchDone -> if (e.index < progress.size) {
                progress[e.index] = progress[e.index].copy(done = true, bigY = e.xyz?.get(1))
            }
        }
    }

    // MARK: live readings (bright end, dark end, colours)

    /** Reads one pattern until cancelled. */
    suspend fun runLive(p: Patch) {
        live = null
        liveError = null
        shown.patch = p
        val rs = ArrayList<Vec3>()
        while (currentCoroutineContext().isActive) {
            val xyz = runCatching {
                if (demo) demoLive.read().xyz ?: error("no reading")
                else raw.measure().let { r -> r.xyzForward ?: r.xyzColour ?: error("no colour matrices") }
            }.getOrElse {
                if (it is CancellationException) throw it
                liveError = "The reading failed: ${it.message}"
                return
            }
            rs += xyz
            if (rs.size > 40) rs.removeAt(0)
            live = Guided.evaluate(p, rs, hdr, whiteY, basis)
        }
    }
}

// MARK: - The screen

@Composable
fun GuidedScreen(flow: GuidedFlow) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCamera(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var reopen by remember { mutableIntStateOf(0) }
    var firstStart by remember { mutableStateOf(true) }

    DisposableEffect(Unit) { onDispose { flow.cancel(); flow.raw.close(); flow.cameraReady = false } }
    flow.raw.onError = { flow.cameraError = it; flow.cameraReady = false }
    LifecycleStartEffect(Unit) {
        if (firstStart) firstStart = false else reopen++
        onStopOrDispose { flow.cancel(); flow.raw.close(); flow.cameraReady = false }
    }
    val plan = remember(flow.whiteVersion) { flow.cameraPlan }
    LaunchedEffect(flow.demo, granted, surface, reopen, flow.whiteVersion) {
        if (flow.demo || !granted) return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        val p = plan.getOrElse { flow.cameraError = "No camera on this phone can be a meter: ${it.message}"; return@LaunchedEffect }
        if (p.forward == null && p.colour == null) {
            flow.cameraError = "Camera ${p.cameraId} has no colour matrices. Import a DNG on the Measure tab first."
            return@LaunchedEffect
        }
        flow.cameraError = null
        flow.cameraStatus = "Opening camera ${p.cameraId}…"
        runCatching { flow.raw.open(p, s) }
            .onSuccess { flow.cameraReady = true; flow.cameraStatus = "Camera ${p.cameraId}, ${p.colourSource}" }
            .onFailure { flow.cameraError = "Couldn't open the camera: ${it.message}" }
    }

    // live readings on the hands-on steps
    val livePid = when (flow.step) {
        GuideStep.BRIGHT -> flow.plan.gain
        GuideStep.DARK -> flow.plan.offset
        GuideStep.COLOURS -> flow.colour
        else -> null
    }
    LaunchedEffect(livePid, flow.demo, flow.cameraReady, flow.restart, flow.tv) {
        val p = livePid?.let { Patterns.patch(flow.hdr, it) } ?: return@LaunchedEffect
        if (!flow.demo && !flow.cameraReady) return@LaunchedEffect
        flow.runLive(p)
    }

    var pinned: Pair<String, () -> Unit>? = null
    Column(Modifier.fillMaxSize().background(Tc.background)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge("Demo TV", Tc.tealBright, flow.demo) { flow.useDemo(true) }
                Badge("Camera", Tc.tealBright, !flow.demo) {
                    flow.useDemo(false)
                    if (!granted) ask.launch(Manifest.permission.CAMERA)
                }
                Spacer(Modifier.weight(1f))
                Badge("SDR", Tc.modeTint(false))
            }
            StepBar(flow)
            if (!flow.demo) {
                if (!granted) TipRow(Tone.LIMIT, "The camera is the meter, so the app needs camera access.")
                else {
                    Viewfinder(plan.getOrNull()?.previewSize?.let { it.width to it.height },
                        plan.getOrNull()?.rawSize?.let { it.height.toFloat() / it.width } ?: 0.75f,
                        onSurface = { surface = it }, onGone = { flow.raw.close(); flow.cameraReady = false; surface = null })
                    flow.cameraError?.let { TipRow(Tone.LIMIT, it) }
                        ?: Text(flow.cameraStatus, style = TcType.small.copy(color = Tc.textSecondary))
                }
            }
            androidx.compose.runtime.key(flow.tvVersion) {
                pinned = when (flow.step) {
                    GuideStep.SETUP -> SetupStep(flow, plan.getOrNull()?.colourSource?.contains("white set") == true)
                    GuideStep.BEFORE, GuideStep.AFTER, GuideStep.CHECK -> QuickStep(flow, flow.step) { flow.measureQuick(scope, flow.step) }
                    GuideStep.BRIGHT, GuideStep.DARK -> WhiteStep(flow)
                    GuideStep.COLOURS -> ColoursStep(flow)
                }
            }
        }
        Box(Modifier.fillMaxWidth().background(Tc.background.copy(alpha = 0.96f)).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            if (flow.running) SecondaryButton("Cancel", { flow.cancel() }, Modifier.fillMaxWidth())
            else pinned?.let { (title, action) -> PrimaryButton(title, action) }
        }
    }
}

/** The seven steps: done in teal with ✓, the current one in yellow with its number, the rest outlined. */
@Composable
private fun StepBar(flow: GuidedFlow) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in GuideStep.entries) {
                val now = s == flow.step
                val isDone = !now && s in flow.done
                val shape = RoundedCornerShape(8.dp)
                var m = Modifier.weight(1f).heightIn(min = 30.dp)
                m = if (now) m.background(Tc.accent, shape) else if (isDone) m.background(Tc.tealBright, shape)
                else m.background(Tc.card, shape).border(1.dp, Tc.hairline, shape)
                Box(m.clickable { flow.go(s) }, contentAlignment = Alignment.Center) {
                    Text(if (isDone) "✓" else "${s.ordinal + 1}",
                        style = TcType.captionStrong.copy(color = if (now || isDone) Tc.background else Tc.textSecondary))
                }
            }
        }
        Row {
            Text(flow.step.label, style = TcType.caption.copy(color = Tc.accent))
            Spacer(Modifier.weight(1f))
            Text("Step ${flow.step.ordinal + 1} of ${GuideStep.entries.size} · tap a step to jump", style = TcType.small.copy(color = Tc.textSecondary))
        }
    }
}

/** The file or video to put on the TV for this step. */
@Composable
private fun PlayCard(title: String, file: String, detail: String? = null) {
    TcCard(spacing = 4.dp) {
        Eyebrow(title)
        Text(file, style = TcType.bodyStrong)
        detail?.let { Text(it, style = TcType.small.copy(color = Tc.textSecondary)) }
    }
}

// MARK: 1. Set up

@Composable
private fun SetupStep(flow: GuidedFlow, whiteRefSet: Boolean): Pair<String, () -> Unit> {
    val context = LocalContext.current
    Text("Getting ready", style = TcType.stepTitle)
    TcCard(hero = true, spacing = 8.dp) {
        Text("Calibrating SDR", style = TcType.headline)
        Text("Everyday TV. The TV keeps separate settings for SDR and HDR, so start here.", style = TcType.caption.copy(color = Tc.textSecondary))
    }
    TcCard(spacing = 8.dp) {
        Text("The patterns", style = TcType.bodyStrong)
        Text("Before, Check and After follow the Quick check video: play it on the TV's YouTube app, from the very start. " +
            "The TV should show no HDR badge.", style = TcType.caption.copy(color = Tc.textSecondary))
        LinkButton("Open ‘SDR 00 - Quick check’ on YouTube", {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(QUICK_CHECK_URL)))
        }, small = true)
        Text("The adjustment steps show one still pattern at a time: copy the patterns/pack folder from the project to a " +
            "USB stick and open each picture in the TV's photo viewer.", style = TcType.caption.copy(color = Tc.textSecondary))
    }
    TcCard(spacing = 8.dp) {
        Text("Set up the LG", style = TcType.bodyStrong)
        Text("On the TV, open Picture and match these.", style = TcType.caption.copy(color = Tc.textSecondary))
        for (item in LG_SDR_SETUP) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", style = TcType.caption.copy(color = Tc.tealBright))
                Text(item, style = TcType.caption)
            }
        }
        Text("Menu names may vary a little by LG model.", style = TcType.small.copy(color = Tc.textSecondary))
    }
    TcCard(spacing = 8.dp) {
        Text("Dim the room", style = TcType.bodyStrong)
        Text("Lights off, curtains closed, and the TV on for 20–30 minutes first: screens shift as they warm up.",
            style = TcType.caption.copy(color = Tc.textSecondary))
    }
    TcCard(spacing = 8.dp) {
        Text("Place the phone", style = TcType.bodyStrong)
        Text("A hand's length (15–30 cm) from the TV, facing its middle, not touching the screen. Keep the yellow box " +
            "inside the pattern, and don't move the phone once you start.", style = TcType.caption.copy(color = Tc.textSecondary))
    }
    if (!flow.demo && whiteRefSet) {
        TipRow(Tone.LIMIT, "A camera reference is set on a Filmmaker Mode white. Measuring that same mode, its white reads on " +
            "target by definition: the greys' balance and the colours still say something, the white point doesn't.")
    }
    if (flow.demo) {
        TipRow(Tone.TODO, "Demo: a made-up TV with white-balance and colour errors. Its remote is on each step.")
        LinkButton("Try another demo TV", { flow.newDemoTV() }, small = true)
    }
    History(flow)
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    Text("TV Cal Probe ${version ?: ""}", Modifier.fillMaxWidth(), style = TcType.tiny.copy(color = Tc.textSecondary))
    return "Start: measure the TV as it is" to { flow.go(GuideStep.BEFORE, markDone = GuideStep.SETUP) }
}

// MARK: 2, 5, 7. Before, Check, After

@Composable
private fun QuickStep(flow: GuidedFlow, which: GuideStep, measure: () -> Unit): Pair<String, () -> Unit>? {
    val mine = flow.sweepFor == which
    val failed = (flow.phase as? SweepPhase.Failed)?.takeIf { mine }
    when {
        flow.running && mine -> { MeasuringCard(flow); return null }
        failed != null -> {
            Text("Is the Quick check video playing?", style = TcType.stepTitle)
            TipRow(Tone.LIMIT, failed.why)
            Text("Nothing was saved. Start again, then play the video from the very beginning.",
                style = TcType.caption.copy(color = Tc.textSecondary))
            return "Try again" to { flow.resetPhase(); measure() }
        }
    }
    val result: Measurements? = when (which) {
        GuideStep.BEFORE -> flow.before
        GuideStep.AFTER -> flow.after
        else -> null
    }
    if (which == GuideStep.CHECK) {
        val rep = flow.check
        if (rep != null) return CheckSummary(flow, rep, measure)
    } else if (result != null) {
        val rep = Analysis.analyse(result, flow.hdr)
        if (which == GuideStep.BEFORE) {
            Text("Your starting point", style = TcType.stepTitle)
            ReportSummary(rep, null)
        } else {
            Text("Before and after", style = TcType.stepTitle)
            ReportSummary(rep, flow.before?.let { Analysis.analyse(it, flow.hdr) })
            val lim = flow.limits.filter { it in Patterns.colourIDs }.map(::capital)
            if (lim.isNotEmpty()) TipRow(Tone.LIMIT, "${lim.joinToString(", ")}: as close as this TV gets (its controls were at their limit).")
        }
        LinkButton("Measure again", measure, Modifier.fillMaxWidth())
        return if (which == GuideStep.BEFORE) "Next: fix the white balance" to { flow.go(GuideStep.BRIGHT, markDone = GuideStep.BEFORE) }
        else "Start a new session" to { flow.startOver() }
    }
    // ready to measure
    Text(when (which) {
        GuideStep.BEFORE -> "Measure your starting point"
        GuideStep.AFTER -> "One final check"
        else -> "Check the greys"
    }, style = TcType.stepTitle)
    PlayCard("Play on the TV", "SDR 00 - Quick check (play from the start)",
        if (flow.demo) "Demo: the video plays by itself, ten times faster." else "About two minutes. Tap Measure first, then start the video from the very beginning.")
    if (which == GuideStep.CHECK) {
        Row {
            Spacer(Modifier.weight(1f))
            LinkButton("Next: colours", { flow.go(GuideStep.COLOURS, markDone = GuideStep.CHECK) }, small = true)
        }
    }
    return "Measure" to measure
}

/** While a video is being measured: waiting for its sync, then each patch as it's read. */
@Composable
private fun MeasuringCard(flow: GuidedFlow) {
    val waiting = flow.phase == SweepPhase.Waiting
    Text(if (waiting) "Waiting for the video" else "Measuring", style = TcType.stepTitle)
    Text(if (waiting) "Start ‘SDR 00 - Quick check’ on the TV from the very beginning. The app starts when it sees the flashes."
        else "Keep the phone still until the last patch.", style = TcType.caption.copy(color = Tc.textSecondary))
    val doneCount = flow.progress.count { it.done }
    LinearProgressIndicator(
        progress = { if (flow.progress.isEmpty()) 0f else doneCount.toFloat() / flow.progress.size },
        modifier = Modifier.fillMaxWidth(), color = Tc.tealBright, trackColor = Tc.hairline,
    )
    if (waiting) WaitingForReading("Latest reading: " + (flow.latestY?.let { fmt(it, if (it < 10) 2 else 1) } ?: "–"))
    TcCard(spacing = 6.dp) {
        for ((i, p) in flow.progress.withIndex()) {
            val now = i == flow.index && !p.done
            val name = Patterns.patch(flow.hdr, p.id)?.let(::shortName) ?: p.name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (p.done) "✓" else if (now) "•" else " ", Modifier.width(20.dp),
                    style = TcType.caption.copy(color = if (p.done) Tc.green else Tc.accent))
                Text(name, Modifier.weight(1f),
                    style = TcType.caption.copy(color = if (p.done || now) Color.White else Tc.textSecondary))
                if (p.done) Text(p.bigY?.let { fmt(it, if (it < 10) 2 else 1) } ?: "missed",
                    style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"))
            }
        }
    }
}

/** A score: the word, the dE beside it and, after calibrating, what it was before. */
@Composable
private fun ScoreTile(label: String, value: Double?, was: Double?, modifier: Modifier = Modifier, digits: Int = 1, target: String? = null) {
    TcCard(modifier, spacing = 4.dp) {
        Text(label, style = TcType.small.copy(color = Tc.textSecondary))
        if (target == null) {
            val word = Analysis.scoreWord(value)
            val tint = when {
                value == null || value.isNaN() -> Tc.textSecondary
                value < 1 -> Tc.green
                value < 3 -> Color.White
                else -> Tc.limit
            }
            Text(word.ifEmpty { "–" }, style = TcType.headline.copy(color = tint))
            Text("dE ${fmt(value, digits)}" + (was?.let { "  ·  was ${fmt(it, digits)}" } ?: ""),
                style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"))
        } else {
            Text(fmt(value, 2), style = TcType.reading(30))
            Text("target $target" + (was?.let { "  ·  was ${fmt(it, 2)}" } ?: ""),
                style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"))
        }
    }
}

/** Scores, warnings and the colour chart for one round (and the round before it). */
@Composable
private fun ReportSummary(rep: Report, before: Report?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ScoreTile("Greyscale", Analysis.greyAverage(rep, false), before?.let { Analysis.greyAverage(it, false) }, Modifier.weight(1f))
        ScoreTile("Colours", Analysis.colourAverage(rep), before?.let { Analysis.colourAverage(it) }, Modifier.weight(1f))
    }
    ScoreTile("Gamma", Analysis.gamma(rep), before?.let { Analysis.gamma(it) }, target = "2.40")
    for (w in rep.warnings) TipRow(Tone.LIMIT, w)
    if (rep.colours.isNotEmpty()) {
        TcCard(spacing = 6.dp) {
            Text("Colours", style = TcType.bodyStrong)
            GamutChart(rep.colours.map { GamutDot(it.name, ColourMath.upvp(it.xy), ColourMath.upvp(it.txy), it.de) }, false,
                Modifier.fillMaxWidth().aspectRatio(1.4f))
            Text("Rings: targets · dots: the TV", style = TcType.small.copy(color = Tc.textSecondary))
        }
    }
    GreyTable(rep, null)
}

/** Each grey: red and blue against green, and its dE. */
@Composable
private fun GreyTable(rep: Report, action: (@Composable (tvcal.kit.GreyResult) -> Unit)?) {
    val greys = rep.greys.filter { it.level >= 0.099 }
    TcCard(spacing = 0.dp) {
        Row(Modifier.padding(bottom = 4.dp)) {
            for ((h, w) in listOf("Grey" to 64, "Red" to 64, "Blue" to 64, "dE" to 48)) Text(h, Modifier.width(w.dp), style = TcType.tiny.copy(color = Tc.textSecondary))
        }
        for (g in greys) {
            Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                val st = TcType.caption.copy(fontFeatureSettings = "tnum")
                Text(g.name.replace(" GREY", "").replace("100% WHITE", "100%"), Modifier.width(64.dp), style = st)
                Text(bigSigned(g.redVsGreen * 100), Modifier.width(64.dp), style = st)
                Text(bigSigned(g.blueVsGreen * 100), Modifier.width(64.dp), style = st)
                Text(fmt(g.de, 1), Modifier.width(48.dp), style = st)
                action?.invoke(g)
            }
        }
    }
}

private fun onTarget(g: tvcal.kit.GreyResult) = abs(g.redVsGreen) <= 0.015 && abs(g.blueVsGreen) <= 0.015

@Composable
private fun CheckSummary(flow: GuidedFlow, rep: Report, measure: () -> Unit): Pair<String, () -> Unit> {
    val greys = rep.greys.filter { it.level >= 0.099 }
    val off = greys.filter { !onTarget(it) }
    val average = Analysis.greyAverage(rep, false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(when {
            !average.isFinite() -> "Here are your greys"
            average < 1 -> "On target"
            average < 2 -> "A very good result"
            average < 3 -> "A good result"
            else -> "Some greys need another pass"
        }, Modifier.weight(1f), style = TcType.stepTitle)
        Text("dE ${fmt(average, 1)}", style = TcType.captionStrong.copy(color = if (average < 3) Tc.green else Tc.limit))
    }
    TipRow(if (off.isEmpty()) Tone.OK else Tone.TODO, when {
        off.isEmpty() -> "Every grey is on target."
        else -> {
            val ends = buildList {
                if (off.any { it.level <= 0.4 }) add("dark end")
                if (off.any { it.level > 0.4 && it.level < 0.55 }) add("middle greys")
                if (off.any { it.level >= 0.55 }) add("bright end")
            }.joinToString(" and the ")
            if (off.size * 2 <= greys.size) "Most greys are on target. The $ends could be a little closer."
            else "The $ends could be closer: redo it below."
        }
    })
    GreyTable(rep) { g ->
        if (onTarget(g)) Text("✓", style = TcType.tiny.copy(color = Tc.tealBright))
        else {
            val bright = g.level >= 0.55
            LinkButton(if (bright) "Redo bright end" else "Redo dark end", { flow.go(if (bright) GuideStep.BRIGHT else GuideStep.DARK) }, small = true)
        }
    }
    LinkButton("Measure again", measure, small = true)
    return "Continue to colours" to { flow.go(GuideStep.COLOURS, markDone = GuideStep.CHECK) }
}

// MARK: 3, 4. Bright and dark end

@Composable
private fun WhiteStep(flow: GuidedFlow): Pair<String, () -> Unit> {
    val bright = flow.step == GuideStep.BRIGHT
    val pid = if (bright) flow.plan.gain else flow.plan.offset
    val patch = Patterns.patch(flow.hdr, pid)!!
    if (!flow.demo) PlayCard("Show on the TV", Patterns.packFile(flow.mode, pid) ?: pid, "From the USB stick (patterns/pack).")
    val r = flow.live?.takeIf { it.id == pid }
    WhiteBalanceStep(patch, flow.hdr, r, flow.liveError, flow.demo, flow.whiteY,
        onRestart = { flow.restart++ }, onPress = flow::press)
    LinkButton("Can't get closer", {
        flow.markLimit(if (bright) "bright" else "dark")
        flow.go(if (bright) GuideStep.DARK else GuideStep.CHECK, markDone = flow.step)
    })
    val steady = r?.steady == true
    return (if (bright) "Next: dark end" else "Next: check") to {
        flow.go(if (bright) GuideStep.DARK else GuideStep.CHECK, markDone = if (steady) flow.step else null)
    }
}

// MARK: 6. Colours

@Composable
private fun ColoursStep(flow: GuidedFlow): Pair<String, () -> Unit> {
    val cur = flow.colour
    val patch = Patterns.patch(flow.hdr, cur)!!
    if (flow.before == null) TipRow(Tone.LIMIT, "Run the Before check first, so brightness can be judged against this TV's own white.")
    if (!flow.demo) PlayCard("Show on the TV", Patterns.packFile(flow.mode, cur) ?: cur, "From the USB stick (patterns/pack).")
    val r = flow.live?.takeIf { it.id == cur }
    ColourStep(patch, flow.hdr, r, flow.liveError, flow.demo, flow.whiteY,
        onPick = { flow.pickColour(it) }, onRestart = { flow.restart++ }, onPress = flow::press, doneColours = flow.coloursDone.toSet())
    LinkButton("Can't get closer", { flow.markLimit(cur); flow.finishColour(cur) })
    val last = Patterns.colourIDs.all { it == cur || it in flow.coloursDone }
    return (if (last) "Finish colours" else "Next colour") to { flow.finishColour(cur) }
}

/** Finished calibrations, newest first: when, and the greyscale and colour scores before and after. */
@Composable
private fun History(flow: GuidedFlow) {
    val finished = flow.sessions.filter { it.after != null }.reversed()
    if (finished.isEmpty()) return
    val date = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    TcCard(spacing = 10.dp) {
        Text(if (flow.demo) "Demo calibrations" else "Your calibrations", style = TcType.bodyStrong)
        for (ss in finished) {
            val a = Analysis.analyse(ss.after!!, flow.hdr)
            val b = ss.before?.let { Analysis.analyse(it, flow.hdr) }
            fun pair(now: Double, was: Double?) = (was?.let { "${fmt(it, 1)} → " } ?: "") + fmt(now, 1)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(date.format(java.util.Date(ss.finished ?: ss.started)), style = TcType.caption)
                Text("Greyscale dE ${pair(Analysis.greyAverage(a, flow.hdr), b?.let { Analysis.greyAverage(it, flow.hdr) })}" +
                    "  ·  colours dE ${pair(Analysis.colourAverage(a), b?.let { Analysis.colourAverage(it) })}",
                    style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"))
            }
        }
    }
}
