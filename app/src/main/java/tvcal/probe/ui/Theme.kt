package tvcal.probe.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tvcal.kit.ColourMath
import tvcal.kit.Point2
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The design system of the upstream iOS app (DesignSystem.swift / Components.swift, from its Figma file
// "Calibrator"), in Compose. Same tokens, same parts, so the two apps look like one product. Type is the
// system font (Roboto here, SF Pro there); live readings use tabular figures in place of SF Pro Rounded.

// MARK: colour

object Tc {
    /** Screens: near-black with a teal cast. */
    val background = Color(0xFF05171A)
    /** Hero cards and illustration panels. */
    val surface = Color(0xFF0D3B42)
    /** Links, icons, eyebrows, the SDR format. */
    val tealBright = Color(0xFF40B8B0)
    /** The one main action per screen, the current step, the HDR10 format. */
    val accent = Color(0xFFFFD133)
    val accentPressed = Color(0xFFDAB025)
    /** On target, done. */
    val green = Color(0xFF4DCC73)
    /** At the TV's limit, warnings. */
    val limit = Color(0xFFFFB36B)
    val red = Color(0xFFF2544D)
    val blue = Color(0xFF548CFA)
    val hdr10Plus = Color(0xFF9E85FA)
    val dolbyVision = Color(0xFFED73BF)
    /** Card fill: white 6%. */
    val card = Color.White.copy(alpha = 0.06f)
    /** Hairline borders and empty tracks: white 9%. */
    val hairline = Color.White.copy(alpha = 0.09f)
    val textSecondary = Color.White.copy(alpha = 0.6f)
    /** The camera preview's empty fill. */
    val previewFill = Color(0xFF30383A)

    private val swatches = mapOf(
        "RED" to Color(0.90f, 0.27f, 0.24f), "GREEN" to Color(0.24f, 0.78f, 0.44f),
        "BLUE" to Color(0.25f, 0.47f, 0.91f), "CYAN" to Color(0.25f, 0.82f, 0.85f),
        "MAGENTA" to Color(0.84f, 0.36f, 0.82f), "YELLOW" to Color(0.91f, 0.85f, 0.29f),
    )

    fun swatch(id: String): Color = swatches[id.uppercase()] ?: Color(0.8f, 0.8f, 0.8f)

    /** The chart's colour for each patch. */
    fun chart(id: String): Color = when (id.uppercase()) {
        "RED" -> red
        "GREEN" -> green
        "BLUE" -> blue
        "YELLOW" -> accent
        "MAGENTA" -> dolbyVision
        else -> swatch(id)
    }

    /** SDR teal, HDR10 yellow: each format has its own colour. */
    fun modeTint(hdr: Boolean): Color = if (hdr) accent else tealBright
}

// MARK: type

object TcType {
    private fun s(size: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
        TextStyle(fontSize = size.sp, fontWeight = weight, letterSpacing = tracking.sp)

    /** 28: screen headlines. */
    val display = s(28, tracking = -0.6)
    /** 22 semibold: a step's title ("Adjust Red and Blue Gain"). */
    val stepTitle = s(22, FontWeight.SemiBold, -0.4)
    /** 18 semibold: section headings. */
    val section = s(18, FontWeight.SemiBold)
    /** 17 semibold: emphasised titles. */
    val headline = s(17, FontWeight.SemiBold)
    /** 16 semibold: the name beside a live reading. */
    val label = s(16, FontWeight.SemiBold)
    /** 15: body text; semibold for names and links. */
    val body = s(15)
    val bodyStrong = s(15, FontWeight.SemiBold)
    /** 13: secondary text; semibold for eyebrows and small links. */
    val caption = s(13)
    val captionStrong = s(13, FontWeight.SemiBold)
    /** 12: small labels; bold for format badges. */
    val small = s(12)
    val badge = s(12, FontWeight.Bold)
    /** 11 and 10: scale labels, chart labels. */
    val tiny = s(11)
    val micro = s(10)

    /** Live readings, readable from across the room. */
    fun reading(size: Int = 40) =
        TextStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp,
            fontFeatureSettings = "tnum")
}

/** Text styles carry no colour: they take the component's (white on screens, dark on yellow).
 * Material's slots filled from the tokens, so stock components (tabs, menus, fields) match. */
@Composable
fun TcTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Tc.accent, onPrimary = Tc.background,
        secondary = Tc.tealBright, onSecondary = Tc.background,
        tertiary = Tc.tealBright,
        background = Tc.background, onBackground = Color.White,
        surface = Tc.background, onSurface = Color.White,
        surfaceVariant = Tc.surface, onSurfaceVariant = Tc.textSecondary,
        surfaceContainer = Tc.surface, surfaceContainerHigh = Tc.surface, surfaceContainerLow = Tc.background,
        outline = Tc.tealBright, outlineVariant = Tc.hairline,
        secondaryContainer = Tc.surface, onSecondaryContainer = Color.White,
        error = Tc.red,
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = TcType.body, bodyMedium = TcType.caption,
        labelLarge = TcType.body, titleSmall = TcType.bodyStrong), content = content)
}

// MARK: numbers

fun fmt(v: Double?, digits: Int = 1): String =
    if (v == null || !v.isFinite()) "–" else "%.${digits}f".format(Locale.ROOT, v)

/** A big signed reading with a true minus sign: "+3.2%", "−2.1". One decimal below 10, none above. */
fun bigSigned(v: Double?, suffix: String = ""): String {
    if (v == null || !v.isFinite()) return "–"
    val digits = if (abs(v) < 9.95) 1 else 0
    return "%+.${digits}f".format(Locale.ROOT, v).replace("-", "−") + suffix
}

// MARK: buttons

/** The main action: a yellow capsule, dark label, at least 52 dp tall. One per screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick, modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp), enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tc.accent, contentColor = Tc.background,
            disabledContainerColor = Tc.accent.copy(alpha = 0.2f), disabledContentColor = Tc.textSecondary,
        ),
    ) { Text(text, style = TcType.body) }
}

/** A secondary action: a teal-outlined capsule. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.defaultMinSize(minHeight = 52.dp), enabled = enabled, shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) Tc.tealBright else Tc.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Tc.textSecondary),
    ) { Text(text, style = TcType.body) }
}

/** A text link: teal, semibold. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    TextButton(onClick, modifier.defaultMinSize(minHeight = 44.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text(text, style = (if (small) TcType.captionStrong else TcType.bodyStrong).copy(color = Tc.tealBright))
    }
}

/** A − / + key on the demo remote. */
@Composable
fun RemoteKey(symbol: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.width(48.dp).height(44.dp).semantics { contentDescription = description },
        shape = CircleShape, border = BorderStroke(1.dp, Tc.tealBright), contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Tc.surface, contentColor = Tc.tealBright),
    ) { Text(symbol, style = TcType.stepTitle.copy(fontWeight = FontWeight.Normal)) }
}

// MARK: surfaces

/** A card: white 6% (or the teal surface for a hero card), hairline border, 18 dp corners, 16 dp padding. */
@Composable
fun TcCard(modifier: Modifier = Modifier, hero: Boolean = false, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().background(if (hero) Tc.surface else Tc.card, shape).border(1.dp, Tc.hairline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content,
    )
}

/** A small line above a title, in teal. */
@Composable
fun Eyebrow(text: String, colour: Color = Tc.tealBright) = Text(text, style = TcType.small.copy(color = colour))

/** "SDR" / "HDR10" style badge; selectable when given an onClick. */
@Composable
fun Badge(text: String, tint: Color, selected: Boolean = true, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier.defaultMinSize(minHeight = 32.dp)
    m = if (selected) m.background(tint.copy(alpha = 0.13f), shape) else m.border(1.dp, Tc.hairline, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = TcType.badge.copy(color = if (selected) tint else Tc.textSecondary))
    }
}

enum class Tone { TODO, OK, LIMIT }

/** One tip: to do (teal bulb), on target (green check) or at the TV's limit (orange minus). */
@Composable
fun TipRow(tone: Tone, text: String) {
    val colour = when (tone) { Tone.OK -> Tc.green; Tone.LIMIT -> Tc.limit; Tone.TODO -> Tc.tealBright }
    Row(
        Modifier.fillMaxWidth().background(colour.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(20.dp)) { tipIcon(tone, colour) }
        Text(text, Modifier.weight(1f), style = TcType.caption.copy(color = if (tone == Tone.TODO) Color.White else colour))
    }
}

private fun DrawScope.tipIcon(tone: Tone, c: Color) {
    val r = size.minDimension / 2 - 1.5f
    val ctr = center
    val w = 1.5.dp.toPx()
    when (tone) {
        Tone.OK -> {
            drawCircle(c, r, ctr, style = Stroke(w))
            val p = Path().apply {
                moveTo(ctr.x - r * 0.45f, ctr.y + r * 0.02f)
                lineTo(ctr.x - r * 0.1f, ctr.y + r * 0.35f)
                lineTo(ctr.x + r * 0.48f, ctr.y - r * 0.32f)
            }
            drawPath(p, c, style = Stroke(w, cap = StrokeCap.Round))
        }
        Tone.LIMIT -> {
            drawCircle(c, r, ctr, style = Stroke(w))
            drawLine(c, Offset(ctr.x - r * 0.45f, ctr.y), Offset(ctr.x + r * 0.45f, ctr.y), w, StrokeCap.Round)
        }
        Tone.TODO -> {   // a light bulb
            val bulb = r * 0.62f
            val bc = Offset(ctr.x, ctr.y - r * 0.22f)
            drawCircle(c, bulb, bc, style = Stroke(w))
            drawLine(c, Offset(ctr.x - bulb * 0.5f, ctr.y + r * 0.62f), Offset(ctr.x + bulb * 0.5f, ctr.y + r * 0.62f), w, StrokeCap.Round)
            drawLine(c, Offset(ctr.x - bulb * 0.35f, ctr.y + r * 0.9f), Offset(ctr.x + bulb * 0.35f, ctr.y + r * 0.9f), w, StrokeCap.Round)
        }
    }
}

/** Shown until the first reading arrives. */
@Composable
fun WaitingForReading(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Tc.tealBright, strokeWidth = 2.dp)
        Text(text, style = TcType.caption.copy(color = Tc.textSecondary))
    }
}

// MARK: live readings

/**
 * One channel or control: its name and a big reading, over a centred track. Left of the white target line
 * is "too little", right is "too much"; the green zone is the tolerance. Within tolerance the reading turns
 * green with a tick and the bar shrinks to a green pip on the line.
 */
@Composable
fun BalanceBar(
    label: String,
    value: Double?,
    colour: Color,
    fullScale: Double = 0.05,
    tolerance: Double = 0.012,
    valueText: String? = null,
    readingSize: Int = 38,
    caption: String? = null,
) {
    val v = value?.takeIf { it.isFinite() }
    val ok = v != null && abs(v) <= tolerance
    val reading = valueText ?: bigSigned(v?.let { it * 100 }, "%")
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "$label: ${if (ok) "on target" else reading}" },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = TcType.label, maxLines = 1)
            val tint = if (ok) Tc.green else Color.White
            if (ok) Text("✓", Modifier.padding(end = 6.dp), style = TcType.reading((readingSize * 0.62).toInt()).copy(color = tint))
            Text(reading, style = TcType.reading(readingSize).copy(color = tint), maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val w = size.width
            val h = size.height
            val mid = w / 2
            val off = (((v ?: 0.0) / fullScale).coerceIn(-1.0, 1.0) * mid).toFloat()
            val tol = min(max((tolerance / fullScale).toFloat() * mid, 6.dp.toPx()), mid)
            val corner = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = corner)
            drawRect(Tc.green.copy(alpha = 0.16f), Offset(mid - tol, 0f), Size(tol * 2, h))
            if (v != null) {
                val len = if (ok) 8.dp.toPx() else max(abs(off), 4.dp.toPx())
                val x = if (ok) mid - len / 2 else if (off < 0) mid - len else mid
                drawRoundRect(if (ok) Tc.green else colour, Offset(x, h / 2 - 5.dp.toPx()), Size(len, 10.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            }
            drawRect(Color.White, Offset(mid - 1.dp.toPx(), 0f), Size(2.dp.toPx(), h))
        }
        if (caption != null && !ok && v != null) Text(caption, style = TcType.small.copy(color = Tc.textSecondary))
    }
}

/** "Too little · On target · Too much" under a set of balance bars. */
@Composable
fun BalanceScaleLabels() {
    Row(Modifier.fillMaxWidth()) {
        Text("Too little", style = TcType.tiny.copy(color = Tc.textSecondary))
        Spacer(Modifier.weight(1f))
        Text("On target", style = TcType.tiny.copy(color = Tc.green))
        Spacer(Modifier.weight(1f))
        Text("Too much", style = TcType.tiny.copy(color = Tc.textSecondary))
    }
}

/**
 * The app icon's colours as a wheel around white: each hue lies the way that colour lies from the white
 * point (red toward the red primary, cyan straight across from it...).
 */
private fun hueWheel(corners: List<Offset>, white: Offset): Brush {
    if (corners.size != 3) return Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    val red = Color(1f, 0.10f, 0.10f); val green = Color(0.13f, 1f, 0.24f); val blue = Color(0.23f, 0.17f, 1f)
    val cyan = Color(0f, 0.88f, 1f); val magenta = Color(1f, 0.06f, 0.48f); val yellow = Color(1f, 0.93f, 0f)
    val twoPi = 2 * PI
    fun angle(p: Offset): Double { val a = atan2((p.y - white.y).toDouble(), (p.x - white.x).toDouble()); return if (a < 0) a + twoPi else a }
    fun across(a: Double) = if (a + PI >= twoPi) a - PI else a + PI
    val r = angle(corners[0]); val g = angle(corners[1]); val b = angle(corners[2])
    val hues = listOf(r to red, across(r) to cyan, g to green, across(g) to magenta, b to blue, across(b) to yellow)
        .sortedBy { it.first }
    // Compose's sweep starts at 3 o'clock, clockwise, like these screen angles: close the wheel at 0 / 1
    val first = hues.first(); val last = hues.last()
    val gap = first.first + twoPi - last.first
    val at0 = lerp(last.second, first.second, ((twoPi - last.first) / gap).toFloat())
    val stops = buildList {
        add(0f to at0)
        hues.forEach { add((it.first / twoPi).toFloat() to it.second) }
        add(1f to at0)
    }.toTypedArray()
    return Brush.sweepGradient(*stops, center = white)
}

private fun circlePath(c: Offset, r: Float) = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

/**
 * The white point against D65, in u'v'. The teal inner ring is the target zone (about dE 2); the dot is
 * the TV's white: yellow while off, green with a tick once inside.
 */
@Composable
fun WhiteTarget(uv: Point2, d65: Point2, ok: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.semantics { contentDescription = if (ok) "White is on target" else "White is off target" }) {
        val inner = 0.0045
        val outer = inner * 130 / 64.24
        val side = size.minDimension
        val c = center
        val outerR = side / 2 * (130f / 146f)
        val sc = outerR / outer.toFloat()
        val innerR = inner.toFloat() * sc

        // between the rings, a faint tint of the colour that lies each way from white
        val primaries = ColourMath.Gamut.REC709.primaries.map { ColourMath.upvp(it) }.map {
            Offset(c.x + (it.x - d65.x).toFloat() * sc, c.y - (it.y - d65.y).toFloat() * sc)
        }
        val band = Path().apply {
            addOval(Rect(c.x - outerR + 2, c.y - outerR + 2, c.x + outerR - 2, c.y + outerR - 2))
            addOval(Rect(c.x - innerR - 2, c.y - innerR - 2, c.x + innerR + 2, c.y + innerR + 2))
            fillType = PathFillType.EvenOdd
        }
        drawPath(band, hueWheel(primaries, c), alpha = 0.24f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.18f), Color.Transparent),
            c, innerR * 1.5f), innerR * 1.5f, c)
        drawCircle(Color.White.copy(alpha = 0.2f), outerR, c, style = Stroke(1.dp.toPx()))
        drawCircle(Tc.tealBright, innerR, c, style = Stroke(1.5.dp.toPx()))
        val arm = 10.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.6f), Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.6f), Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 1.dp.toPx())

        // the white, kept inside the outer ring so it never leaves the picture
        var dx = (uv.x - d65.x).toFloat() * sc
        var dy = -(uv.y - d65.y).toFloat() * sc
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > outerR && dist > 0) { dx *= outerR / dist; dy *= outerR / dist }
        val m = Offset(c.x + dx, c.y + dy)
        val dotR = max(5.dp.toPx(), side * 5 / 146)
        if (ok) drawCircle(Brush.radialGradient(listOf(Tc.green.copy(alpha = 0.85f), Color.Transparent), m, dotR * 2.6f), dotR * 2.6f, m)
        drawCircle(if (ok) Tc.green else Tc.accent, dotR, m)
        if (ok) {
            val t = Offset(m.x + dotR + 4.dp.toPx(), m.y - 2.dp.toPx())
            val u = 1.dp.toPx()
            drawPath(Path().apply { moveTo(t.x, t.y); lineTo(t.x + 3 * u, t.y + 3 * u); lineTo(t.x + 9 * u, t.y - 4 * u) },
                Tc.green, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        }
        val label = measurer.measure("D65", TcType.micro.copy(color = Tc.textSecondary))
        drawText(label, topLeft = Offset(c.x - label.size.width / 2f, size.height - label.size.height - 1.dp.toPx()))
    }
}

/** One colour on the gamut chart: the TV's reading and its target, in u'v'. */
class GamutDot(val id: String, val uv: Point2, val target: Point2, val de: Double?) {
    val onTarget: Boolean
        get() = de?.takeIf { it.isFinite() }?.let { it < 3 }
            ?: (sqrt((uv.x - target.x) * (uv.x - target.x) + (uv.y - target.y) * (uv.y - target.y)) < 0.004)
}

/**
 * The colours in CIE 1976 u'v', over the Rec.709 (SDR) or P3 (HDR) triangle: rings are the targets, dots are
 * the TV, a line joins each pair, and a green tick marks a colour that's on target.
 */
@Composable
fun GamutChart(dots: List<GamutDot>, hdr: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val active = if (dots.size == 1) dots.first().id else null
    Canvas(modifier.semantics {
        contentDescription = dots.singleOrNull()?.let { "Colour chart: ${it.id.lowercase()} is ${if (it.onTarget) "on" else "off"} target" }
            ?: "Colour chart"
    }) {
        val gamut = (if (hdr) ColourMath.Gamut.P3 else ColourMath.Gamut.REC709).primaries.map { ColourMath.upvp(it) }
        val fit = gamut + dots.map { it.target }
        val uMin = fit.minOf { it.x }; val uMax = fit.maxOf { it.x }
        val vMin = fit.minOf { it.y }; val vMax = fit.maxOf { it.y }
        val pad = 16.dp.toPx()
        // one scale for both axes, so the triangle keeps its true shape
        val sc = min((size.width - pad * 2) / max(uMax - uMin, 0.01).toFloat(), (size.height - pad * 2) / max(vMax - vMin, 0.01).toFloat())
        val ox = (size.width - (uMax - uMin).toFloat() * sc) / 2
        val oy = (size.height - (vMax - vMin).toFloat() * sc) / 2
        fun p(q: Point2) = Offset(ox + (q.x - uMin).toFloat() * sc, size.height - oy - (q.y - vMin).toFloat() * sc)
        fun inside(q: Offset) = Offset(q.x.coerceIn(6f, size.width - 6f), q.y.coerceIn(6f, size.height - 6f))
        fun hit(v: Offset, w: Offset, a: Offset, b: Offset): Offset {
            val d1 = w - v; val d2 = b - a
            val den = d1.x * d2.y - d1.y * d2.x
            if (abs(den) < 1e-6f) return w
            val t = ((a.x - v.x) * d2.y - (a.y - v.y) * d2.x) / den
            return Offset(v.x + d1.x * t, v.y + d1.y * t)
        }

        val corners = gamut.map { p(it) }
        val tri = Path().apply { moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        val white = p(ColourMath.upvp(ColourMath.d65))
        drawPath(tri, hueWheel(corners, white), alpha = if (active == null) 0.3f else 0.2f)
        drawPath(tri, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), white, size.minDimension * 0.3f),
            alpha = if (active == null) 0.3f else 0.2f)
        for (i in 0..2) {
            drawLine(Color.White.copy(alpha = 0.13f), corners[i], hit(corners[i], white, corners[(i + 1) % 3], corners[(i + 2) % 3]), 1.dp.toPx())
        }
        drawPath(tri, Color.White.copy(alpha = 0.45f), style = Stroke(1.dp.toPx()))
        val label = measurer.measure(if (hdr) "P3 (HDR)" else "Rec. 709 (SDR)", TcType.micro.copy(color = Tc.textSecondary))
        drawText(label, topLeft = Offset(size.width - label.size.width - 2f, size.height - label.size.height - 2f))

        for (d in dots.filter { it.id != active } + dots.filter { it.id == active }) {
            val col = Tc.chart(d.id)
            val isActive = d.id == active
            val dim = active != null && !isActive
            val t = p(d.target)
            val m = inside(p(d.uv))
            val ringR = (if (isActive) 9 else 8).dp.toPx()
            if (d.onTarget && !dim) {
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.8f), Color.Transparent), t, ringR + 9.dp.toPx()), ringR + 9.dp.toPx(), t)
                drawCircle(col.copy(alpha = 0.2f), ringR, t)
            }
            drawCircle(col.copy(alpha = if (dim) 0.45f else 0.9f), ringR, t, style = Stroke((if (isActive) 2.0 else 1.2).dp.toPx()))
            drawLine(col.copy(alpha = if (dim) 0.5f else 1f), t, m, (if (isActive) 1.5 else 1.0).dp.toPx())
            drawCircle(col.copy(alpha = if (dim) 0.65f else 1f), (if (isActive) 5 else 4).dp.toPx(), m)
            if (d.onTarget) {
                val k = Offset(t.x + ringR + 3.dp.toPx(), t.y - 3.dp.toPx())
                val u = 1.dp.toPx()
                drawPath(Path().apply { moveTo(k.x, k.y); lineTo(k.x + 2.5f * u, k.y + 2.5f * u); lineTo(k.x + 7.5f * u, k.y - 3 * u) },
                    Tc.green, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/** One of the six colours: yellow ring when it's the one on screen, a dot in its own colour otherwise. */
@Composable
fun ColourChip(id: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, done: Boolean = false) {
    val c = Tc.swatch(id)
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(32.dp)) {
            val r = size.minDimension / 2
            drawCircle(if (selected) c.copy(alpha = 0.15f) else Tc.card, r)
            drawCircle(if (selected) Tc.accent else c, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            if (done) {
                val u = r / 16f
                drawPath(Path().apply { moveTo(center.x - 5 * u, center.y); lineTo(center.x - 1.5f * u, center.y + 4 * u); lineTo(center.x + 6 * u, center.y - 4.5f * u) },
                    c, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            } else drawCircle(c, 2.5.dp.toPx())
        }
        Text(id.lowercase().replaceFirstChar { it.uppercase() }, style = TcType.micro.copy(color = if (selected) Color.White else Tc.textSecondary), maxLines = 1)
    }
}
