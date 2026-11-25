package tvcal.probe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tvcal.kit.ColourMath
import tvcal.probe.ui.Tc
import tvcal.probe.ui.TcTheme
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import tvcal.kit.ByteSource
import tvcal.kit.DngColourData
import tvcal.kit.DngColourText
import tvcal.kit.DngFile
import tvcal.kit.DngInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone sits on a stand measuring; don't let the screen sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            TcTheme {
                Scaffold(containerColor = Tc.background) { pad -> App(Modifier.padding(pad)) }
            }
        }
    }
}

@Composable
private fun App(modifier: Modifier) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    // the guided calibration outlives a trip to the other tabs
    val flow = remember { GuidedFlow(context, context.getSystemService(CameraManager::class.java)) }
    DisposableEffect(Unit) { onDispose { flow.raw.release() } }
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Tc.background) {
            for ((i, name) in listOf("Calibrate", "Live", "Measure", "Camera").withIndex()) {
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name, maxLines = 1) },
                    selectedContentColor = Color.White, unselectedContentColor = Tc.textSecondary)
            }
        }
        when (tab) {
            0 -> GuidedScreen(flow)
            1 -> LiveScreen()
            2 -> MeasureScreen()
            else -> ReportScreen()
        }
    }
}

/** The app's folder on shared storage (reachable with adb), or its private one if that can't be written. */
internal fun outDir(context: Context, name: String): File {
    val shared = context.getExternalFilesDir(null)?.let { File(it, name).apply { mkdirs() } }
    if (shared != null && shared.canWrite()) return shared
    return File(context.filesDir, name).apply { mkdirs() }
}

internal fun hasCamera(context: Context) =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Colour matrices imported from a DNG, per camera id. */
internal fun importedFile(context: Context, cameraId: String) = File(outDir(context, "colour"), "camera-$cameraId.txt")

/** The camera RGB of a white the user said is D65, per camera id (see [RawMeter.plan]). */
internal fun whiteRefFile(context: Context, cameraId: String) = File(outDir(context, "colour"), "white-$cameraId.txt")

internal fun loadWhiteRef(context: Context, cameraId: String): DoubleArray? = runCatching {
    whiteRefFile(context, cameraId).takeIf { it.exists() }?.readText()?.trim()?.split(" ")?.map { it.toDouble() }
        ?.takeIf { it.size == 3 }?.toDoubleArray()
}.getOrNull()

internal fun saveWhiteRef(context: Context, cameraId: String, rgb: DoubleArray) =
    whiteRefFile(context, cameraId).writeText(rgb.joinToString(" ") { it.toString() })

internal fun loadImported(context: Context, cameraId: String): Pair<DngColourData, String>? =
    importedFile(context, cameraId).takeIf { it.exists() }
        ?.let { runCatching { DngColourText.read(it.readText()) }.getOrNull() }

/** Reads a DNG's colour tags from a picked document, without loading the whole file. */
private fun readDng(context: Context, uri: Uri): DngInfo {
    val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "can't open the file" }
    return pfd.use {
        FileInputStream(it.fileDescriptor).channel.use { ch ->
            DngFile.read(ByteSource { offset, length ->
                val buf = ByteBuffer.allocate(length)
                var pos = offset
                while (buf.hasRemaining()) {
                    val n = ch.read(buf, pos)
                    if (n <= 0) break
                    pos += n
                }
                buf.array().copyOf(buf.position())
            })
        }
    }
}

private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

private fun share(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, subject))
}

@Composable
private fun ReportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var asked by remember { mutableStateOf(false) }
    // Without camera permission Android hides the colour matrices and focus range, so ask first.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) else asked = true }
    LaunchedEffect(granted, asked) {
        if (!asked) return@LaunchedEffect
        report = withContext(Dispatchers.Default) {
            runCatching { CameraReport.build(context.getSystemService(CameraManager::class.java), granted) }
                .getOrElse { "Couldn't read the cameras: $it" }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { share(context, "TV Cal Probe camera report", report) }, enabled = report.isNotEmpty()) {
                Text("Share")
            }
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val f = File(outDir(context, "reports"), "camera-report-${stamp()}.txt")
                    f.writeText(report)
                    saved = f.absolutePath
                }
            }, enabled = report.isNotEmpty()) { Text("Save file") }
        }
        if (saved.isNotEmpty()) Text("Saved: $saved", fontSize = 12.sp)
        Text(
            report.ifEmpty { "Reading the cameras..." },
            Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
}

/** The patterns in tools/make_window_patterns.py, in order: label and what's on screen. */
private val PATCHES = listOf(
    "W100" to "white, 255", "G75" to "grey, 191", "G50" to "grey, 128", "G25" to "grey, 64",
    "G10" to "grey, 26", "R" to "red, 255,0,0", "G" to "green, 0,255,0", "B" to "blue, 0,0,255",
)

/** A white reference: what a 100% white patch read, per conversion, and optionally its real luminance. */
private class WhiteRef(val yForward: Double?, val yColour: Double?, val gPerSecond: Double, val nits: Double?)

@Composable
private fun MeasureScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Column(Modifier.padding(16.dp)) {
            Text("The camera is the meter, so the app needs camera access.")
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 8.dp)) { Text("Allow camera") }
        }
        return
    }

    val manager = remember { context.getSystemService(CameraManager::class.java) }
    val cameras = remember { runCatching { CameraFacts.meterCameras(manager) }.getOrDefault(emptyList()) }
    if (cameras.isEmpty()) {
        Text("No camera on this phone offers RAW with manual exposure. See the camera report.", Modifier.padding(16.dp))
        return
    }
    val meter = remember { RawMeter(manager) }
    val scope = rememberCoroutineScope()
    var cameraId by remember { mutableStateOf(cameras.first()) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var status by remember { mutableStateOf("Starting the camera...") }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(PATCHES.first().first) }
    var patchOpen by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var firstStart by remember { mutableStateOf(true) }
    var nitsText by remember { mutableStateOf("") }
    var whiteRef by remember { mutableStateOf<WhiteRef?>(null) }
    var last by remember { mutableStateOf<Reading?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var reopen by remember { mutableIntStateOf(0) }
    var importedVersion by remember { mutableIntStateOf(0) }

    val plan = remember(cameraId, importedVersion) {
        runCatching { meter.plan(cameraId, loadImported(context, cameraId), loadWhiteRef(context, cameraId)) }
    }
    val pickDng = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                val info = withContext(Dispatchers.IO) { readDng(context, uri) }
                val name = listOfNotNull(info.make, info.cameraModel ?: info.model).joinToString(" ").ifEmpty { "DNG" }
                withContext(Dispatchers.IO) {
                    importedFile(context, cameraId).writeText(DngColourText.write(info.colour, name))
                }
                importedVersion++
                reopen++
                "Imported colour matrices from $name for camera $cameraId" +
                    if (info.colour.forwardMatrix1 == null) " (no forward matrix: colour-matrix method only)" else ""
            }.getOrElse { "Couldn't read that DNG: ${it.message}" }
        }
    }
    DisposableEffect(Unit) { onDispose { meter.release() } }
    meter.onError = { status = it; ready = false }

    // Android takes the camera away when the app goes to the background: let it go on stop and open
    // it again on start.
    LifecycleStartEffect(Unit) {
        if (firstStart) firstStart = false else reopen++
        onStopOrDispose {
            meter.close()
            ready = false
            status = "Camera paused while the app was in the background."
        }
    }

    LaunchedEffect(cameraId, surface, reopen, importedVersion) {
        val s = surface ?: return@LaunchedEffect
        val p = plan.getOrElse { status = it.message ?: it.toString(); return@LaunchedEffect }
        status = "Opening camera $cameraId..."
        ready = false
        runCatching { meter.open(p, s) }
            .onSuccess {
                ready = true
                status = "Camera $cameraId: RAW ${p.rawSize.width}x${p.rawSize.height}, ISO ${p.iso}, " +
                    "focus ${p.focusTarget} D${if (p.minFocus > 0f) "" else " (closest unknown)"}, ${p.cfa}, " +
                    "colour: ${p.colourSource}"
            }
            .onFailure { status = "Couldn't open the camera: ${it.message}" }
    }

    fun log(r: Reading) {
        val f = File(outDir(context, "readings"), "readings-v2.csv")
        if (!f.exists()) {
            f.writeText(
                "time,label,camera,physical,colour_source,exposure_ns,iso,focus,fill,clipped,uneven,black,white," +
                    "raw_r,raw_g,raw_b,r_per_s,g_per_s,b_per_s,X_fm,Y_fm,Z_fm,X_cm,Y_cm,Z_cm,notes\n"
            )
        }
        fun xyz(v: DoubleArray?) = v?.map { "%.6g".format(Locale.ROOT, it) } ?: listOf("", "", "")
        val cells = listOf(
            stamp(), label, cameraId, r.physicalCamera ?: "", meter.setup?.colourSource?.replace(",", " ") ?: "",
            r.exposureNs, r.iso, r.focus ?: "", "%.4f".format(Locale.ROOT, r.stats.fill), r.stats.clipped,
            "%.4f".format(Locale.ROOT, r.stats.uneven), r.black.joinToString("/"), r.white,
            *r.stats.mean.map { "%.3f".format(Locale.ROOT, it) }.toTypedArray(),
            *r.rgbPerSecond.map { "%.3f".format(Locale.ROOT, it) }.toTypedArray(),
            *xyz(r.xyzForward).toTypedArray(),
            *xyz(r.xyzColour).toTypedArray(),
            r.notes.joinToString("; ").replace(",", " "),
        )
        f.appendText(cells.joinToString(",") + "\n")
    }

    fun describe(rs: List<Reading>): String = buildString {
        val r = rs.last()
        val ref = whiteRef
        fun line(name: String, xyz: List<DoubleArray>, refY: Double?) {
            val xy = xyz.map { ColourMath.xyzToXY(it) }
            val mx = xy.map { it.x }.average()
            val my = xy.map { it.y }.average()
            val y = xyz.map { it[1] }.average()
            append("$name  x %.4f  y %.4f".format(mx, my))
            if (xy.size > 1) {
                val sx = Math.sqrt(xy.map { (it.x - mx) * (it.x - mx) }.average())
                val sy = Math.sqrt(xy.map { (it.y - my) * (it.y - my) }.average())
                val sY = Math.sqrt(xyz.map { (it[1] - y) * (it[1] - y) }.average())
                append("  (sd %.4f / %.4f, Y %.2f%%)".format(sx, sy, if (y > 0) sY / y * 100 else 0.0))
            }
            appendLine()
            val mean = doubleArrayOf(xyz.map { it[0] }.average(), y, xyz.map { it[2] }.average())
            append("    CCT %.0f K, dE from D65 %.2f".format(ColourMath.cct(ColourMath.xyzToXY(mean)), ColourMath.whiteDE(mean)))
            if (refY != null && refY > 0) {
                append(", Y %.2f%% of white".format(y / refY * 100))
                ref?.nits?.let { append(" = %.2f cd/m2".format(it * y / refY)) }
            }
            appendLine()
        }
        appendLine("[$label] ${rs.size} reading(s)")
        val fm = rs.mapNotNull { it.xyzForward }
        val cm = rs.mapNotNull { it.xyzColour }
        if (fm.size == rs.size) line("Forward matrix:", fm, ref?.yForward)
        if (cm.size == rs.size) line("Colour matrix: ", cm, ref?.yColour)
        // Camera-space ratios work without any matrix: enough to check readings repeat.
        val rg = rs.map { it.rgbPerSecond[0] / it.rgbPerSecond[1] }
        val bg = rs.map { it.rgbPerSecond[2] / it.rgbPerSecond[1] }
        val g = rs.map { it.rgbPerSecond[1] }.average()
        fun sd(v: List<Double>) = Math.sqrt(v.map { (it - v.average()) * (it - v.average()) }.average())
        append("Camera R/G %.4f  B/G %.4f".format(rg.average(), bg.average()))
        if (rs.size > 1) append("  (sd %.4f / %.4f)".format(sd(rg), sd(bg)))
        ref?.let { if (it.gPerSecond > 0) append(", G %.2f%% of white".format(g / it.gPerSecond * 100)) }
        appendLine()
        r.physicalCamera?.let { appendLine("physical camera $it, focus ${r.focus} D") }
        appendLine(
            "exposure %.3f ms, ISO %d, fill %.0f%%, uneven %.1f%%, %d photo(s)"
                .format(r.exposureNs / 1e6, r.iso, r.stats.fill * 100, r.stats.uneven * 100, r.attempts)
        )
        appendLine("raw RGB %s  black %s  white %d".format(
            r.stats.mean.joinToString("/") { "%.1f".format(it) }, r.black.joinToString("/") { "%.0f".format(it) }, r.white))
        if (r.notes.isNotEmpty()) appendLine("! " + r.notes.joinToString("; "))
    }

    fun measure(times: Int) {
        if (busy) return
        busy = true
        scope.launch {
            val rs = mutableListOf<Reading>()
            var logError: String? = null
            runCatching {
                repeat(times) {
                    status = "Measuring ${it + 1}/$times..."
                    val r = meter.measure()
                    rs += r
                    // A log that can't be written mustn't stop the readings.
                    withContext(Dispatchers.IO) { runCatching { log(r) } }.onFailure { e -> logError = e.message }
                }
            }.onFailure { status = "Reading failed: ${it.message}" }
                .onSuccess {
                    status = logError?.let { "Done, but the log couldn't be written: $it" }
                        ?: "Done. Logged to Android/data/.../files/readings/readings-v2.csv"
                }
            if (rs.isNotEmpty()) {
                last = rs.last()
                output = describe(rs) + "\n" + output
            }
            // After a full x5 series, move on to the next patch in the sequence.
            if (times > 1 && rs.size == times) {
                val i = PATCHES.indexOfFirst { it.first == label }
                if (i >= 0 && i + 1 < PATCHES.size) {
                    label = PATCHES[i + 1].first
                    status += " Next: $label."
                }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Box {
            OutlinedButton(onClick = { pickerOpen = true }) {
                Text(plan.getOrNull()?.let { CameraFacts.title(cameraId, it.chars) } ?: "Camera $cameraId", fontSize = 12.sp)
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                for (id in cameras) {
                    DropdownMenuItem(
                        text = { Text(CameraFacts.title(id, manager.getCameraCharacteristics(id)), fontSize = 12.sp) },
                        onClick = { pickerOpen = false; cameraId = id },
                    )
                }
            }
        }
        val raw = plan.getOrNull()?.rawSize
        // Portrait: the sensor's long side runs down the screen.
        val aspect = if (raw != null) raw.height.toFloat() / raw.width else 0.75f
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).padding(vertical = 8.dp)) {
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
                                meter.close()
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // The box the reading averages: the centre 10% of the frame's width and height.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.1f
                val h = size.height * 0.1f
                drawRect(
                    Tc.accent, topLeft = Offset(size.width / 2 - w / 2, size.height / 2 - h / 2),
                    size = androidx.compose.ui.geometry.Size(w, h), style = Stroke(3f),
                )
            }
        }
        Text(status, fontSize = 12.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { patchOpen = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Patch: $label  ▾")
                }
                DropdownMenu(expanded = patchOpen, onDismissRequest = { patchOpen = false }) {
                    for ((name, what) in PATCHES) {
                        DropdownMenuItem(
                            text = { Text("$name  ($what)") },
                            onClick = { label = name; patchOpen = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                nitsText, { nitsText = it }, Modifier.weight(1f), label = { Text("White cd/m2 (optional)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { measure(1) }, enabled = !busy && ready) { Text("Measure") }
            Button(onClick = { measure(5) }, enabled = !busy && ready) { Text("x5") }
            OutlinedButton(onClick = {
                last?.let {
                    whiteRef = WhiteRef(it.xyzForward?.get(1), it.xyzColour?.get(1), it.rgbPerSecond[1], nitsText.replace(',', '.').toDoubleOrNull())
                    status = "Last reading is now the 100% white reference."
                }
            }, enabled = last != null) { Text("Set as white") }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reopen++ }) { Text("Restart camera") }
            OutlinedButton(onClick = {
                val f = File(outDir(context, "readings"), "readings-v2.csv")
                if (f.exists()) share(context, "TV Cal Probe readings", f.readText())
            }) { Text("Share log") }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDng.launch(arrayOf("image/x-adobe-dng", "image/*", "application/octet-stream")) }) {
                Text("Import DNG matrices")
            }
            if (loadImported(context, cameraId) != null) {
                OutlinedButton(onClick = {
                    importedFile(context, cameraId).delete()
                    importedVersion++
                }) { Text("Forget DNG") }
            }
        }
        Text(
            "Readings use the camera's own colour matrices or a DNG's (no colorimeter profile): approximate, best for " +
                "greys and the white point.",
            Modifier.padding(top = 8.dp), fontSize = 11.sp, color = Tc.textSecondary,
        )
        Text(output, Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
