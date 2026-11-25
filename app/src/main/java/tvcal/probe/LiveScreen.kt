package tvcal.probe

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.isActive
import tvcal.kit.ColourMath
import tvcal.kit.GuidePlan
import tvcal.kit.Guided
import tvcal.kit.LiveReading
import tvcal.kit.Patch
import tvcal.kit.PatchKind
import tvcal.kit.Patterns
import tvcal.kit.ShownPatch
import tvcal.kit.SimLiveMeter
import tvcal.kit.SimTV
import tvcal.kit.Vec3
import tvcal.probe.ui.Badge
import tvcal.probe.ui.BalanceBar
import tvcal.probe.ui.BalanceScaleLabels
import tvcal.probe.ui.ColourChip
import tvcal.probe.ui.Eyebrow
import tvcal.probe.ui.GamutChart
import tvcal.probe.ui.GamutDot
import tvcal.probe.ui.LinkButton
import tvcal.probe.ui.PrimaryButton
import tvcal.probe.ui.RemoteKey
import tvcal.probe.ui.Tc
import tvcal.probe.ui.TcCard
import tvcal.probe.ui.TcType
import tvcal.probe.ui.TipRow
import tvcal.probe.ui.Tone
import tvcal.probe.ui.WaitingForReading
import tvcal.probe.ui.WhiteTarget
import tvcal.probe.ui.bigSigned
import tvcal.probe.ui.fmt

// Live reading of one pattern, laid out like the iOS app's guided steps (LiveSteps.swift): the white
// balance step for white and greys, the colours step for the six colours. The TV shows one pattern, the
// meter reads it again and again, and the bars say which control to move. "Demo" swaps the camera for a
// simulated TV with its own remote, so the loop can be tried without a TV.

private enum class Source { DEMO, CAMERA }

/** The TV's menu names. LG only for now (the first supported TV); upstream's Brands.swift has the rest. */
private object Lg {
    const val GAIN = "Gain"
    const val OFFSET = "Offset"
    const val WHITE_BALANCE = "Picture › Advanced Settings › Colour › White Balance (set it to 2 Point)"
    const val COLOUR_MENU = "Picture › Advanced Settings › Colour › Colour Management System"
    const val HUE = "Tint"
    const val SATURATION = "Saturation"
    const val BRIGHTNESS = "Luminance"
}

private fun capital(id: String) = id.lowercase().replaceFirstChar { it.uppercase() }

/** The patches the live view can ask for: white, greys from bright to dark, then the colours. */
private fun livePatches(hdr: Boolean): List<Patch> {
    val all = Patterns.sequence(hdr).patches.filter { it.id != "W1" && it.id != "G100" }
    val greys = all.filter { it.kind == PatchKind.GREY }.sortedByDescending { it.level ?: 0.0 }
    return all.filter { it.kind == PatchKind.WHITE } + greys + all.filter { it.kind == PatchKind.COLOUR }
}

/** "50% grey", "100% white", "Red 75%". */
private fun shortName(p: Patch): String =
    if (p.kind == PatchKind.COLOUR) capital(p.name)
    else p.name.replace("% GREY", "% grey").replace("100% WHITE", "100% white").replace("100 nit WHITE", "100 nit white")

/** Which white-balance pair moves this grey: gain at the bright end, offset at the dark end. */
private fun isGain(p: Patch, hdr: Boolean): Boolean {
    val plan = GuidePlan.forHDR(hdr)
    val gain = Patterns.patch(hdr, plan.gain)?.level ?: 1.0
    val off = Patterns.patch(hdr, plan.offset)?.level ?: 0.3
    val l = p.level ?: 1.0
    return kotlin.math.abs(l - gain) <= kotlin.math.abs(l - off)
}

@Composable
fun LiveScreen() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(Source.DEMO) }
    var hdr by remember { mutableStateOf(false) }
    val patches = remember(hdr) { livePatches(hdr) }
    var patchId by remember(hdr) { mutableStateOf(patches.first().id) }
    val patch = patches.first { it.id == patchId }
    var patchOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(true) }

    // demo TV, one per signal mode; remote presses bump `tvVersion` so the screen follows
    val tv = remember(hdr) { SimTV(hdr) }
    var tvVersion by remember { mutableIntStateOf(0) }
    val shown = remember { ShownPatch() }
    shown.patch = patch

    // camera
    var granted by remember { mutableStateOf(hasCamera(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val manager = remember { context.getSystemService(CameraManager::class.java) }
    val meter = remember { RawMeter(manager) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var reopen by remember { mutableIntStateOf(0) }
    var firstStart by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { meter.release() } }
    meter.onError = { error = it; cameraReady = false }
    LifecycleStartEffect(Unit) {
        if (firstStart) firstStart = false else reopen++
        onStopOrDispose { meter.close(); cameraReady = false }
    }

    // readings of the current pattern (cleared when the pattern, the source or the mode changes)
    val readings = remember { ArrayList<Vec3>() }
    var live by remember { mutableStateOf<LiveReading?>(null) }
    var restart by remember { mutableIntStateOf(0) }
    /** The brightness of 100% white, per source: colours and the mismatch check are judged against it. */
    val whiteY = remember { mutableStateMapOf<Source, Double>() }
    /** The last settled reading of each pattern: red, green and blue make the colour hints exact. */
    val settled = remember { mutableStateMapOf<String, Vec3>() }

    LaunchedEffect(patchId, source, hdr, restart) { readings.clear(); live = null; error = null }
    LaunchedEffect(source, hdr) { settled.clear() }
    LaunchedEffect(hdr) { whiteY.clear() }

    val plan = remember { runCatching { CameraFacts.meterCameras(manager).first().let { meter.plan(it, loadImported(context, it)) } } }
    LaunchedEffect(source, granted, surface, reopen) {
        if (source != Source.CAMERA || !granted) return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        val p = plan.getOrElse { error = "No camera on this phone can be a meter: ${it.message}"; return@LaunchedEffect }
        if (p.forward == null && p.colour == null) {
            error = "Camera ${p.cameraId} has no colour matrices. Import a DNG on the Measure tab first."
            return@LaunchedEffect
        }
        status = "Opening camera ${p.cameraId}…"
        runCatching { meter.open(p, s) }
            .onSuccess { cameraReady = true; status = "Camera ${p.cameraId}, ${p.colourSource}" }
            .onFailure { error = "Couldn't open the camera: ${it.message}" }
    }

    // the reading loop
    val demoMeter = remember(tv) { SimLiveMeter(tv, shown) }
    LaunchedEffect(running, source, cameraReady, patchId, hdr, restart) {
        if (!running) return@LaunchedEffect
        if (source == Source.CAMERA && !cameraReady) return@LaunchedEffect
        if (source == Source.DEMO && whiteY[Source.DEMO] == null) {
            Patterns.patch(hdr, "W0")?.let { whiteY[Source.DEMO] = tv.render(it)[1] }
        }
        while (isActive) {
            val xyz: Vec3 = runCatching {
                if (source == Source.DEMO) demoMeter.read().xyz ?: error("no reading")
                else meter.measure().let { r -> r.xyzForward ?: r.xyzColour ?: error("no colour matrices") }
            }.getOrElse { error = "The reading failed: ${it.message}"; break }
            readings += xyz
            if (readings.size > 40) readings.removeAt(0)
            val wY = if (hdr) null else whiteY[source]
            val basis = listOf("RED", "GREEN", "BLUE").mapNotNull { settled[it] }.takeIf { it.size == 3 }
            val r = Guided.evaluate(patch, readings, hdr, wY, basis)
            live = r
            if (r != null && r.mismatch == null && r.averaged >= r.need) {
                val avg = Guided.settledAverage(readings).xyz
                settled[patch.id] = avg
                if (patch.kind == PatchKind.WHITE) whiteY[source] = avg[1]
            }
        }
    }

    val isColour = patch.kind == PatchKind.COLOUR
    val i = patches.indexOfFirst { it.id == patchId }
    val next = patches[(i + 1) % patches.size]
    val nextTitle = when {
        isColour && next.kind == PatchKind.COLOUR -> "Next colour"
        isColour -> "Back to white"
        next.kind == PatchKind.COLOUR -> "Next: colours"
        else -> "Next: ${shortName(next)}"
    }

    Column(Modifier.fillMaxSize().background(Tc.background)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // where the readings come from, and the format
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge("Demo TV", Tc.tealBright, source == Source.DEMO) { source = Source.DEMO }
                Badge("Camera", Tc.tealBright, source == Source.CAMERA) {
                    source = Source.CAMERA
                    if (!granted) ask.launch(Manifest.permission.CAMERA)
                }
                Spacer(Modifier.weight(1f))
                Badge("SDR", Tc.modeTint(false), !hdr) { hdr = false }
                Badge("HDR10", Tc.modeTint(true), hdr) { hdr = true }
            }

            if (source == Source.CAMERA) {
                if (!granted) TipRow(Tone.LIMIT, "The camera is the meter, so the app needs camera access.")
                else Viewfinder(plan.getOrNull()?.previewSize?.let { it.width to it.height },
                    plan.getOrNull()?.rawSize?.let { it.height.toFloat() / it.width } ?: 0.75f,
                    onSurface = { surface = it }, onGone = { meter.close(); cameraReady = false; surface = null })
                if (status.isNotEmpty()) Text(status, style = TcType.small.copy(color = Tc.textSecondary))
            }

            // the pattern on screen
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LinkButton("Pattern: ${shortName(patch)}  ▾", { patchOpen = true })
                    DropdownMenu(expanded = patchOpen, onDismissRequest = { patchOpen = false }) {
                        for (p in patches) DropdownMenuItem(text = { Text(shortName(p), style = TcType.body) },
                            onClick = { patchId = p.id; patchOpen = false })
                    }
                }
                LinkButton(if (running) "Pause" else "Resume", { running = !running }, small = true)
            }

            androidx.compose.runtime.key(tvVersion) {
                if (isColour) ColourStep(patch, hdr, live, error, source == Source.DEMO, whiteY[source] != null || hdr,
                    onPick = { patchId = it }, onRestart = { restart++ }, onPress = { k, d -> tv.apply(mapOf(k to d)); tvVersion++ })
                else WhiteBalanceStep(patch, hdr, live, error, source == Source.DEMO,
                    onRestart = { restart++ }, onPress = { k, d -> tv.apply(mapOf(k to d)); tvVersion++ })
            }
        }
        // the one main action, pinned above the navigation bar
        Box(Modifier.fillMaxWidth().background(Tc.background.copy(alpha = 0.96f)).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            PrimaryButton(nextTitle, { patchId = if (isColour && next.kind != PatchKind.COLOUR) patches.first().id else next.id })
        }
    }
}

/** The camera preview, with the box the reading averages (the centre 10%). */
@Composable
private fun Viewfinder(bufferSize: Pair<Int, Int>?, aspect: Float, onSurface: (Surface) -> Unit, onGone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp)).background(Tc.previewFill),
            contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxHeight().aspectRatio(aspect)) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    bufferSize?.let { st.setDefaultBufferSize(it.first, it.second) }
                                    onSurface(Surface(st))
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { onGone(); return true }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width * 0.1f
                    val h = size.height * 0.1f
                    drawRect(Tc.accent, Offset(size.width / 2 - w / 2, size.height / 2 - h / 2), Size(w, h), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text("Blurry on purpose: it averages out the TV's pixels. Keep the yellow box on the pattern.",
            style = TcType.small.copy(color = Tc.textSecondary))
    }
}

/** Demo TVs: what the readings are, in place of the pattern to show. */
@Composable
private fun DemoNote() {
    TcCard(hero = true, spacing = 4.dp) {
        Text("Demo · simulated readings", style = TcType.captionStrong.copy(color = Tc.accent))
        Text("Try the remote buttons below.", style = TcType.small.copy(color = Tc.textSecondary))
    }
}

/** Demo TVs only: − / + buttons that stand in for the TV's menus. */
@Composable
private fun DemoRemote(controls: List<Pair<String, String>>, onPress: (String, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, key) in controls) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = TcType.body)
                RemoteKey("−", "$label down") { onPress(key, -1) }
                RemoteKey("+", "$label up") { onPress(key, 1) }
            }
        }
    }
}

/** "This TV's readings wobble…" and "Average of 5 readings · latest 42.1 cd/m²   Restart". */
@Composable
private fun AverageLine(r: LiveReading, nits: Boolean, onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (r.jitter >= 0.004) {
            Text("This TV's readings wobble about ±${fmt(r.jitter * 100)}%, so the bars show an average.",
                style = TcType.small.copy(color = Tc.textSecondary))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val unit = if (nits) " cd/m²" else " (camera units)"
            Text(
                "Average of ${r.averaged} reading${if (r.averaged == 1) "" else "s"}" +
                    (if (r.averaged < r.need) " (needs ${r.need} to call it steady)" else "") +
                    " · latest ${fmt(r.latestY, if (r.latestY < 10) 2 else 1)}$unit",
                Modifier.weight(1f), style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"),
            )
            LinkButton("Restart", onRestart, small = true)
        }
    }
}

/** An error, the wait for the first reading, or the wrong pattern. True when there's nothing to show. */
@Composable
private fun ReadingProblems(error: String?, r: LiveReading?, demo: Boolean): Boolean {
    error?.let { TipRow(Tone.LIMIT, it) }
    if (r == null) {
        if (error == null) WaitingForReading(
            if (demo) "Reading the demo TV…" else "Show the pattern on the TV: readings appear as soon as the camera sees it."
        )
        return true
    }
    r.mismatch?.let {
        TipRow(Tone.LIMIT, "$it Is the right pattern showing, with the camera pointed at the centre?")
        return true
    }
    return false
}

// MARK: white and greys

@Composable
private fun WhiteBalanceStep(
    patch: Patch, hdr: Boolean, r: LiveReading?, error: String?, demo: Boolean,
    onRestart: () -> Unit, onPress: (String, Int) -> Unit,
) {
    val gain = isGain(patch, hdr)
    val control = if (gain) Lg.GAIN else Lg.OFFSET
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(shortName(patch).replaceFirstChar { it.uppercase() })
        Text("Adjust Red and Blue $control", style = TcType.stepTitle)
        Text("Until both bars sit on the line. Leave Green alone.", style = TcType.caption.copy(color = Tc.textSecondary))
    }
    val valid = r?.takeIf { it.mismatch == null }
    if (valid != null) BigWhiteTarget(valid)
    if (demo) DemoNote()
    if (!ReadingProblems(error, r, demo) && valid != null) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BalanceBar("Red", valid.qR, Tc.red)
            BalanceBar("Blue", valid.qB, Tc.blue)
            BalanceScaleLabels()
        }
    }
    if (demo) DemoRemote(listOf("Red $control" to (if (gain) "gain:R" else "offset:R"), "Blue $control" to (if (gain) "gain:B" else "offset:B")), onPress)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TV menu: ${Lg.WHITE_BALANCE}", style = TcType.small.copy(color = Tc.textSecondary))
        valid?.let { AverageLine(it, demo || hdr, onRestart) }
    }
}

/**
 * The white-point target, as big as the screen allows: where the grey is now (the dot) against D65 (the
 * centre). The main thing to watch while adjusting.
 */
@Composable
private fun BigWhiteTarget(r: LiveReading) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        WhiteTarget(r.uv, r.d65uv, r.ok, Modifier.size(150.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (r.steady) "On target" else if (r.ok) "In the ring. Hold still…" else "Bring the dot into the ring",
                style = TcType.bodyStrong.copy(color = if (r.ok) Tc.green else Color.White),
            )
            Text("dE ${fmt(r.de, 1)} from D65 · ${fmt(ColourMath.cct(r.xy), 0)} K",
                style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"))
        }
    }
}

// MARK: colours

@Composable
private fun ColourStep(
    patch: Patch, hdr: Boolean, r: LiveReading?, error: String?, demo: Boolean, haveWhite: Boolean,
    onPick: (String) -> Unit, onRestart: () -> Unit, onPress: (String, Int) -> Unit,
) {
    val name = capital(patch.id)
    val colour = Tc.swatch(patch.id)
    if (demo) DemoNote()
    Text("Bring $name onto its ring", style = TcType.stepTitle)
    Row(Modifier.fillMaxWidth()) {
        for (c in Patterns.colourIDs) ColourChip(c, c == patch.id, { onPick(c) }, Modifier.weight(1f))
    }
    if (!haveWhite) TipRow(Tone.LIMIT, "Read 100% white first, so brightness can be judged against this TV's own white.")
    val valid = r?.takeIf { it.id == patch.id }
    if (!ReadingProblems(error, valid, demo) && valid != null) {
        valid.tuv?.let { t ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GamutChart(listOf(GamutDot(patch.id, valid.uv, t, valid.de)), hdr,
                    Modifier.heightIn(max = 220.dp).aspectRatio(1.4f))
                Text("Ring: target · dot: now · dE ${fmt(valid.de, 1)}",
                    style = TcType.small.copy(color = Tc.textSecondary, fontFeatureSettings = "tnum"))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BalanceBar(Lg.HUE, valid.hue, colour, fullScale = 10.0, tolerance = 2.0, valueText = bigSigned(valid.hue), readingSize = 30,
                caption = "Leans toward ${valid.toward?.let(::capital) ?: "a neighbour"}: move ${Lg.HUE} a click and keep going the way that shrinks the bar")
            BalanceBar(Lg.SATURATION, valid.sat, colour, fullScale = 15.0, tolerance = 3.0, valueText = bigSigned(valid.sat), readingSize = 30,
                caption = if ((valid.sat ?: 0.0) > 0) "Too strong: lower ${Lg.SATURATION}" else "Too weak: raise ${Lg.SATURATION}")
            BalanceBar(Lg.BRIGHTNESS, valid.lum, colour, fullScale = 25.0, tolerance = 5.0, valueText = bigSigned(valid.lum), readingSize = 30,
                caption = if ((valid.lum ?: 0.0) > 0) "Too bright: lower ${Lg.BRIGHTNESS}" else "Too dim: raise ${Lg.BRIGHTNESS}")
            BalanceScaleLabels()
        }
        AverageLine(valid, demo || hdr, onRestart)
        when {
            valid.steady -> TipRow(Tone.OK, "$name is on target. Nice.")
            valid.ok -> TipRow(Tone.OK, "Close enough - holding steady…")
            else -> {}
        }
    }
    if (demo) DemoRemote(listOf(
        "$name ${Lg.HUE}" to "hsl:${patch.id}:hue", "$name ${Lg.SATURATION}" to "hsl:${patch.id}:sat",
        "$name ${Lg.BRIGHTNESS}" to "hsl:${patch.id}:lum",
    ), onPress)
    Text("TV menu: ${Lg.COLOUR_MENU} › $name", style = TcType.small.copy(color = Tc.textSecondary))
}
