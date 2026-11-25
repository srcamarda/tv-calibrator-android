package tvcal.probe

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.isActive
import tvcal.kit.ColourMath
import tvcal.kit.Guided
import tvcal.kit.LiveReading
import tvcal.kit.Patch
import tvcal.kit.PatchKind
import tvcal.kit.Patterns
import tvcal.kit.ShownPatch
import tvcal.kit.SimLiveMeter
import tvcal.kit.SimTV
import tvcal.kit.Vec3
import java.util.Locale
import kotlin.math.abs

// Live reading of one pattern: the building block of every guided step. The TV shows one pattern,
// the meter reads it again and again, and the screen says how far off it is, which control to move
// and whether the reading has settled. "Demo" swaps the camera for a simulated TV with its own menu,
// so the whole loop can be tried without a TV.

private enum class Source { DEMO, CAMERA }

private val GREEN_OK = Color(0xFF4CAF50)
private val AMBER = Color(0xFFFFB300)
private val RED = Color(0xFFE53935)
private val BLUE = Color(0xFF42A5F5)

private fun f(x: Double, d: Int = 1) = "%.${d}f".format(Locale.ROOT, x)
private fun pct(x: Double, d: Int = 1) = "%+.${d}f%%".format(Locale.ROOT, x)

/** The patches the live view can ask for: white, greys from bright to dark, then the colours. */
private fun livePatches(hdr: Boolean): List<Patch> {
    val all = Patterns.sequence(hdr).patches.filter { it.id != "W1" && it.id != "G100" }
    val greys = all.filter { it.kind == PatchKind.GREY }.sortedByDescending { it.level ?: 0.0 }
    return all.filter { it.kind == PatchKind.WHITE } + greys + all.filter { it.kind == PatchKind.COLOUR }
}

/** Which white-balance pair moves this grey: gain at the bright end, offset at the dark end. */
private fun controlFor(p: Patch, hdr: Boolean): String {
    val plan = tvcal.kit.GuidePlan.forHDR(hdr)
    val gainLevel = Patterns.patch(hdr, plan.gain)?.level ?: 1.0
    val offLevel = Patterns.patch(hdr, plan.offset)?.level ?: 0.3
    val l = p.level ?: 1.0
    return if (abs(l - gainLevel) <= abs(l - offLevel)) "gain" else "offset"
}

/** Plain words for a grey: the biggest error first. */
private fun greyAdvice(r: LiveReading, control: String): List<String> {
    val qR = (r.qR ?: 0.0) * 100
    val qB = (r.qB ?: 0.0) * 100
    if (abs(qR) <= 1.2 && abs(qB) <= 1.2) return listOf("Red and blue are on target for this grey.")
    val out = ArrayList<String>()
    for ((name, q) in listOf("Red" to qR, "Blue" to qB).sortedByDescending { abs(it.second) }) {
        if (abs(q) <= 1.2) continue
        out += if (q > 0) "$name is ${f(q)}% high: lower $name $control."
        else "$name is ${f(-q)}% low: raise $name $control (if it's at the top, lower Green $control instead)."
    }
    return out
}

private fun colourAdvice(r: LiveReading): List<String> {
    val out = ArrayList<String>()
    val hue = r.hue ?: 0.0
    val sat = r.sat ?: 0.0
    if (abs(hue) >= 1) out += "Hue ${f(abs(hue))}° off" + (r.toward?.let { ", leaning toward ${it.lowercase()}" } ?: "") + "."
    if (abs(sat) >= 2) out += "Saturation ${pct(sat)}: " + if (sat > 0) "too saturated." else "not saturated enough."
    r.lum?.let { if (abs(it) >= 3) out += "Brightness ${pct(it)}: " + if (it > 0) "too bright." else "too dim." }
    r.rgbHint?.let { h ->
        val names = listOf("Red", "Green", "Blue")
        val moves = (0..2).filter { abs(h[it]) >= 0.02 }.sortedByDescending { abs(h[it]) }
            .map { "${if (h[it] > 0) "raise" else "lower"} ${names[it]} (${pct(h[it] * 100, 0)})" }
        if (moves.isNotEmpty()) out += "Colour-management R/G/B sliders: " + moves.joinToString(", ") +
            if (r.rgbApprox) " - sizes are estimates until red, green and blue have been measured." else "."
    }
    r.outOfReach?.let {
        out += "This colour is outside what the TV's ${it.channels.joinToString("/")} can reach; " +
            "the closest it can get is dE ${f(it.bestDE)}."
    }
    if (out.isEmpty()) out += "This colour is on target."
    return out
}

/** A bar centred on zero, with the on-target band in green. */
@Composable
private fun ErrorBar(label: String, valuePct: Double, rangePct: Double, okPct: Double, colour: Color, unit: String = "%") {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), fontSize = 13.sp)
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val w = size.width
            val h = size.height
            val mid = w / 2
            fun x(v: Double) = (mid + (v / rangePct).coerceIn(-1.0, 1.0) * mid).toFloat()
            drawRect(Color(0x33FFFFFF), Offset(0f, h * 0.35f), Size(w, h * 0.3f))
            drawRect(GREEN_OK.copy(alpha = 0.35f), Offset(x(-okPct), 0f), Size(x(okPct) - x(-okPct), h))
            drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, h), 2f)
            val vx = x(valuePct)
            drawRect(colour, Offset(minOf(mid, vx), h * 0.3f), Size(abs(vx - mid), h * 0.4f))
            drawCircle(if (abs(valuePct) <= okPct) GREEN_OK else colour, h * 0.4f, Offset(vx, h / 2))
        }
        Text("%+.1f".format(Locale.ROOT, valuePct) + unit, Modifier.width(64.dp).padding(start = 6.dp), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** +/- buttons for one simulated menu control. */
@Composable
private fun SimControl(label: String, key: String, tv: SimTV, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(48.dp), fontSize = 12.sp)
        OutlinedButton(onClick = { tv.apply(mapOf(key to -1)); onChange() }, Modifier.width(48.dp)) { Text("-") }
        Text(f(tv.s.ctrl[key] ?: 0.0, 0), Modifier.width(36.dp).padding(horizontal = 4.dp), fontSize = 12.sp)
        OutlinedButton(onClick = { tv.apply(mapOf(key to 1)); onChange() }, Modifier.width(48.dp)) { Text("+") }
    }
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

    // demo TV, one per signal mode; menu changes bump `tvVersion` so the controls redraw
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
    DisposableEffect(Unit) { onDispose { meter.release() } }
    meter.onError = { status = it; cameraReady = false }
    LifecycleStartEffect(Unit) {
        if (firstStart) firstStart = false else reopen++
        onStopOrDispose { meter.close(); cameraReady = false }
    }

    // readings of the current pattern (cleared when the pattern, the source or the mode changes)
    val readings = remember { ArrayList<Vec3>() }
    var live by remember { mutableStateOf<LiveReading?>(null) }
    var count by remember { mutableIntStateOf(0) }
    /** The brightness of 100% white, per source: colours and the mismatch check are judged against it. */
    val whiteY = remember { mutableStateMapOf<Source, Double>() }
    /** The last settled reading of each pattern: red, green and blue make the colour hints exact. */
    val settled = remember { mutableStateMapOf<String, Vec3>() }

    LaunchedEffect(patchId, source, hdr) { readings.clear(); live = null; count = 0 }
    LaunchedEffect(source, hdr) { settled.clear() }
    LaunchedEffect(hdr) { whiteY.clear() }

    val plan = remember { runCatching { CameraFacts.meterCameras(manager).first().let { meter.plan(it, loadImported(context, it)) } } }
    LaunchedEffect(source, granted, surface, reopen) {
        if (source != Source.CAMERA || !granted) return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        val p = plan.getOrElse { status = "No usable camera: ${it.message}"; return@LaunchedEffect }
        if (p.forward == null && p.colour == null) {
            status = "Camera ${p.cameraId} has no colour matrices: import a DNG on the Measure tab first."
            return@LaunchedEffect
        }
        status = "Opening camera ${p.cameraId}..."
        runCatching { meter.open(p, s) }
            .onSuccess { cameraReady = true; status = "Camera ${p.cameraId} ready (${p.colourSource})." }
            .onFailure { status = "Couldn't open the camera: ${it.message}" }
    }

    // the reading loop
    val demoMeter = remember(tv) { SimLiveMeter(tv, shown) }
    LaunchedEffect(running, source, cameraReady, patchId, hdr) {
        if (!running) return@LaunchedEffect
        if (source == Source.CAMERA && !cameraReady) return@LaunchedEffect
        if (source == Source.DEMO && whiteY[Source.DEMO] == null) {
            Patterns.patch(hdr, "W0")?.let { whiteY[Source.DEMO] = tv.render(it)[1] }
        }
        while (isActive) {
            val xyz: Vec3 = runCatching {
                if (source == Source.DEMO) demoMeter.read().xyz ?: error("no reading")
                else meter.measure().let { r -> r.xyzForward ?: r.xyzColour ?: error("no colour matrices") }
            }.getOrElse { status = "Reading failed: ${it.message}"; break }
            readings += xyz
            if (readings.size > 40) readings.removeAt(0)
            val wY = if (hdr) null else whiteY[source]
            val basis = listOf("RED", "GREEN", "BLUE").map { settled[it] }.takeIf { b -> b.all { it != null } }?.map { it!! }
            val r = Guided.evaluate(patch, readings, hdr, wY, basis)
            live = r
            count = readings.size
            if (r != null && r.mismatch == null && r.averaged >= r.need) {
                val avg = Guided.settledAverage(readings).xyz
                settled[patch.id] = avg
                if (patch.kind == PatchKind.WHITE) whiteY[source] = avg[1]
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(source == Source.DEMO, { source = Source.DEMO }, { Text("Demo TV") })
            FilterChip(source == Source.CAMERA, {
                source = Source.CAMERA
                if (!granted) ask.launch(Manifest.permission.CAMERA)
            }, { Text("Camera") })
            FilterChip(!hdr, { hdr = false }, { Text("SDR") })
            FilterChip(hdr, { hdr = true }, { Text("HDR") })
        }

        if (source == Source.CAMERA) {
            if (!granted) {
                Text("The camera needs permission.", Modifier.padding(top = 8.dp))
            } else {
                val raw = plan.getOrNull()?.rawSize
                val aspect = if (raw != null) raw.height.toFloat() / raw.width else 0.75f
                Box(Modifier.fillMaxWidth(0.55f).aspectRatio(aspect).padding(vertical = 6.dp).align(Alignment.CenterHorizontally)) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        plan.getOrNull()?.previewSize?.let { st.setDefaultBufferSize(it.width, it.height) }
                                        surface = Surface(st)
                                    }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        meter.close(); cameraReady = false; surface = null
                                        return true
                                    }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width * 0.1f
                        val h = size.height * 0.1f
                        drawRect(Color.Yellow, Offset(size.width / 2 - w / 2, size.height / 2 - h / 2), Size(w, h), style = Stroke(3f))
                    }
                }
                Text(status, fontSize = 11.sp, color = Color.Gray)
            }
        }

        // pattern picker
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { patchOpen = true }, Modifier.fillMaxWidth()) { Text("${patch.name}  ▾") }
                DropdownMenu(expanded = patchOpen, onDismissRequest = { patchOpen = false }) {
                    for (p in patches) DropdownMenuItem(text = { Text(p.name) }, onClick = { patchId = p.id; patchOpen = false })
                }
            }
            OutlinedButton(onClick = {
                val i = patches.indexOfFirst { it.id == patchId }
                patchId = patches[(i + 1) % patches.size].id
            }) { Text("Next") }
            Button(onClick = { running = !running }) { Text(if (running) "Pause" else "Read") }
        }
        if (source == Source.CAMERA) {
            Text(
                "Show this pattern on the TV (a window covering the centre of the screen), aim the yellow box at it.",
                fontSize = 11.sp, color = Color.Gray,
            )
        }

        val r = live
        if (r == null) {
            Text(if (running) "Waiting for the first reading..." else "Paused.", Modifier.padding(top = 16.dp))
        } else {
            // state line: wrong pattern / settling / steady
            val (stateText, stateColour) = when {
                r.mismatch != null -> r.mismatch!! to RED
                r.averaged < r.need -> "Settling: ${r.averaged} of ${r.need} readings" to AMBER
                r.steady -> "Steady and on target" to GREEN_OK
                else -> "Steady (${r.averaged} readings)" to Color.White
            }
            Text(stateText, Modifier.padding(top = 12.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = stateColour)

            val wY = if (hdr) null else whiteY[source]
            Text(
                buildString {
                    append("x ${f(r.xy.x, 4)}  y ${f(r.xy.y, 4)}  ")
                    if (patch.kind != PatchKind.COLOUR) append("CCT ${f(ColourMath.cct(r.xy), 0)} K  ")
                    r.de?.let { append("dE ${f(it, 2)}") }
                    appendLine()
                    append(if (source == Source.DEMO || hdr) "Y ${f(r.bigY, 2)} cd/m²" else "Y ${f(r.bigY, 4)} (camera units)")
                    if (wY != null && wY > 0 && patch.kind != PatchKind.WHITE) append(" = ${f(r.bigY / wY * 100, 2)}% of white")
                    append("   wobble ${f(r.jitter * 100, 2)}%  ($count read)")
                },
                Modifier.padding(top = 4.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )

            if (r.mismatch == null) {
                if (patch.kind != PatchKind.COLOUR) {
                    val ctl = controlFor(patch, hdr)
                    Text("White balance ($ctl)", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                    ErrorBar("Red", (r.qR ?: 0.0) * 100, 6.0, 1.2, RED)
                    ErrorBar("Blue", (r.qB ?: 0.0) * 100, 6.0, 1.2, BLUE)
                    for (line in greyAdvice(r, ctl)) Text("• $line", fontSize = 13.sp)
                    Text(
                        "Relative to green. On LG, 2-point gain is the \"high\" setting and offset the \"low\" one.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                } else {
                    Text("Colour", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                    ErrorBar("Hue", r.hue ?: 0.0, 10.0, 2.0, AMBER, "°")
                    ErrorBar("Sat", r.sat ?: 0.0, 15.0, 3.0, AMBER)
                    r.lum?.let { ErrorBar("Bright", it, 15.0, 3.0, AMBER) }
                    if (r.lum == null) Text("Read 100% white first to judge brightness and dE.", fontSize = 12.sp, color = AMBER)
                    for (line in colourAdvice(r)) Text("• $line", fontSize = 13.sp)
                }
            }
        }

        if (source == Source.DEMO) {
            // keyed on tvVersion so the values shown follow the clicks
            androidx.compose.runtime.key(tvVersion) {
                Text("Demo TV menu", Modifier.padding(top = 16.dp), fontWeight = FontWeight.Bold)
                val onChange = { tvVersion++; Unit }
                if (patch.kind != PatchKind.COLOUR) {
                    for (ch in listOf("R", "G", "B")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SimControl("$ch gain", "gain:$ch", tv, onChange)
                            SimControl("$ch off", "offset:$ch", tv, onChange)
                        }
                    }
                } else {
                    for (ch in listOf("R", "G", "B")) SimControl("$ch", "cms:${patch.id}:$ch", tv, onChange)
                    for ((k, n) in listOf("hue" to "Hue", "sat" to "Sat", "lum" to "Bright")) SimControl(n, "hsl:${patch.id}:$k", tv, onChange)
                }
                Text(
                    "A made-up TV with white-balance and colour errors. Move its controls and watch the reading follow.",
                    fontSize = 11.sp, color = Color.Gray,
                )
            }
        }
    }
}

