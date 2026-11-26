package tvcal.probe

import tvcal.kit.Report
import tvcal.kit.SignalMode

// Menu names and setup advice per TV brand, ported from upstream's Brands.swift. Menu names vary a little
// between models and years, so the app always says "usually".

/** The kind of colour menu a TV has. */
enum class ColourMenu(val label: String) {
    RGB_SLIDERS("Red / Green / Blue sliders"),
    HSL("Hue / Saturation / Luminance"),
    OFF("None"),
}

/** Multipoint white balance menus. */
enum class Multipoint(val points: Int, val label: String) {
    TWENTY(20, "20 points (5%, 10% … 100%)"),
    TEN(11, "10 points (10%, 20% … 100%)"),
    NONE(0, "None (2-point only)"),
}

enum class Brand(val label: String) {
    LG("LG"), SAMSUNG("Samsung"), SONY("Sony"), TCL("TCL"), HISENSE("Hisense"), PANASONIC("Panasonic"),
    PHILIPS("Philips"), VIZIO("Vizio"), TOSHIBA("Toshiba"), SHARP("Sharp"), ROKU("Roku TV"), OTHER("Other");

    /** "LG", or "TV" for Other: for sentences like "Set up the LG". */
    val shortName: String get() = if (this == OTHER) "TV" else label

    /** What the brand calls the bright-end and dark-end white-balance controls. */
    val gainName: String get() = "Gain"
    val offsetName: String
        get() = when (this) {
            SONY -> "Bias"
            PANASONIC -> "Cutoff"
            else -> "Offset"
        }

    val whiteBalancePath: String
        get() = when (this) {
            LG -> "Picture › Advanced Settings › Colour › White Balance (set it to 2 Point)"
            SAMSUNG -> "Picture › Expert Settings › White Balance (2 Point)"
            SONY -> "Picture › Advanced settings › Colour › White balance"
            TCL -> "Picture › Advanced (or Expert) settings › White balance"
            HISENSE -> "Picture › Picture Mode Settings › White Balance"
            PANASONIC -> "Picture › Advanced Settings › White Balance (2p)"
            PHILIPS -> "Picture › Advanced › Colour › Colour temperature › Custom"
            VIZIO -> "Picture › Picture Mode Edit › Color Calibration › Color Temperature"
            TOSHIBA -> "Picture › Advanced settings › White balance"
            SHARP -> "Picture › Expert settings › White balance"
            ROKU -> "★ button › Advanced picture settings › Expert settings › White balance"
            OTHER -> "the White Balance menu (often under Picture › Advanced or Expert settings)"
        }

    val colourMenuPath: String
        get() = when (this) {
            LG -> "Picture › Advanced Settings › Colour › Colour Management System"
            SAMSUNG -> "Picture › Expert Settings › Color Space Settings › Custom"
            SONY -> "Picture › Advanced settings › Colour › Advanced colour adjustment"
            TCL -> "Picture › Advanced settings › Colour tuner"
            HISENSE -> "Picture › Picture Mode Settings › Color Tuner"
            PANASONIC -> "Picture › Advanced Settings › Colour Management"
            PHILIPS -> "Picture › Advanced › Colour › Colour control"
            VIZIO -> "Picture › Picture Mode Edit › Color Calibration › Color Tuner"
            TOSHIBA -> "Picture › Advanced settings › Colour tuner"
            SHARP -> "Picture › Expert settings › Colour tuner"
            ROKU -> "★ button › Advanced picture settings › Expert settings › Color tuning"
            OTHER -> "the colour management menu (often called Colour Tuner, CMS or Colour Space)"
        }

    /** The usual colour menu type for the brand (the user confirms it). */
    val colourMenu: ColourMenu get() = if (this == SAMSUNG) ColourMenu.RGB_SLIDERS else ColourMenu.HSL

    /** The usual multipoint white balance for the brand (the user confirms it). */
    val multipoint: Multipoint
        get() = when (this) {
            LG, SAMSUNG, HISENSE -> Multipoint.TWENTY
            SONY, PANASONIC, VIZIO, TCL -> Multipoint.TEN
            PHILIPS, TOSHIBA, SHARP, ROKU, OTHER -> Multipoint.NONE
        }

    /** What the colour menu's three controls are called: LG says Tint and Luminance, Sony Lightness. */
    val cmsHue: String get() = if (this == LG) "Tint" else "Hue"
    val cmsSaturation: String get() = "Saturation"
    val cmsBrightness: String
        get() = when (this) {
            SONY -> "Lightness"
            VIZIO -> "Brightness"
            else -> "Luminance"
        }

    /** The calibrated picture mode, per format. */
    fun pictureMode(mode: SignalMode): String {
        val dv = mode.id == "dv"
        val sdr = mode.id == "sdr"
        return when {
            this == SAMSUNG && dv -> "Samsung TVs don't support Dolby Vision: calibrate HDR10 or HDR10+ instead"
            this == LG && dv -> "Dolby Vision Filmmaker Mode (or Cinema Home)"
            this == SONY && !dv -> "Professional (or Custom)"
            this == VIZIO && dv -> "Dolby Vision Dark"
            this == VIZIO -> "Calibrated Dark"
            this == ROKU -> if (dv) "Dolby Vision Dark" else "Movie"
            this == PANASONIC && dv -> "Dolby Vision Dark (or Dolby Vision Cinema)"
            this == HISENSE && sdr -> "Filmmaker Mode (or Theater Night)"
            this == SHARP && sdr -> "Movie (or Filmmaker Mode)"
            dv -> "Dolby Vision Dark (or Dolby Vision Cinema)"
            else -> "Filmmaker Mode (or Movie / Cinema)"
        }
    }

    /** Picture settings to make before measuring (after the picture mode). */
    fun extras(mode: SignalMode): List<String> {
        val sdr = mode.id == "sdr"
        val hdr = mode.isHDR
        val items = ArrayList<String>()
        when (this) {
            LG -> {
                items += "Energy Saving and AI Brightness: Off"
                if (sdr) items += "Gamma: BT.1886, Colour Temperature: Warm 50"
                if (hdr) items += "Dynamic Tone Mapping: Off"
            }
            SAMSUNG -> {
                items += "Energy saving, Adaptive Brightness and Contrast Enhancer: Off"
                if (sdr) items += "Colour Tone: Warm2, Gamma: BT.1886"
                if (hdr) items += "HDR Tone Mapping: Static, ST.2084: 0"
                items += "Readings jumping around? Turn off Brightness Optimisation and set Local Dimming to Low"
            }
            SONY -> {
                items += "Light sensor, Live colour and Advanced contrast enhancer: Off"
                if (sdr) items += "Colour temperature: Expert 1"
                if (hdr) items += "HDR tone mapping: Off"
            }
            TCL, HISENSE, TOSHIBA, SHARP -> {
                items += "Dynamic contrast, Eco / energy saving and the light sensor: Off"
                if (sdr) items += "Colour temperature: Warm (or Low)"
                if (hdr) items += "Any dynamic tone mapping: Off"
                if (this == HISENSE || this == TCL) items += "Local dimming: Low or Medium"
            }
            PANASONIC -> {
                items += "Ambient sensor and Dynamic Contrast: Off"
                if (sdr) items += "Colour Temperature: Warm2"
                if (hdr) items += "HDR Brightness Enhancer / Dynamic HDR Effect: Off"
            }
            PHILIPS -> {
                items += "Ambilight, the light sensor and Dynamic contrast: Off"
                if (sdr) items += "Colour temperature: Warm"
                if (hdr) items += "HDR Plus / tone mapping: Off or Standard"
            }
            VIZIO -> {
                items += "Auto Brightness Control: Off, Contrast Control: Off"
                if (sdr) items += "Color Temperature: Normal (Vizio's is the film standard)"
            }
            ROKU -> {
                items += "Dynamic contrast and Action smoothing: Off"
                if (sdr) items += "Color temperature: Warm"
            }
            OTHER -> {
                items += "Energy saving, light sensors and contrast boosters: Off"
                if (sdr) items += "Colour temperature: Warm"
                if (hdr) items += "Dynamic tone mapping: Off"
            }
        }
        if (mode.id == "hdr10p") items += "HDR10+ mode: the plain setting, not Adaptive / Dynamic"
        return items
    }

    /** Everything to set before measuring, as one list (picture mode first). */
    fun setup(mode: SignalMode): List<String> = listOf("Picture mode: ${pictureMode(mode)}") + extras(mode)

    /** Where the TV's colour gamut setting is, and what it should be. */
    val gamutSetting: String
        get() = when (this) {
            LG -> "Picture › Advanced Settings › Colour › Colour Gamut is Auto Detect"
            SAMSUNG -> "Picture › Expert Settings › Color Space is Auto"
            SONY -> "Live Colour is Off"
            else -> "the Colour Gamut (or Colour Space) setting is Auto"
        }

    companion object {
        fun fromName(name: String?): Brand? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Colours far outside their targets, all the same way, mean a setting (the TV's colour gamut, or the
 * player's output) rather than something the colour sliders can fix.
 */
object GamutCheck {
    fun note(report: Report, mode: SignalMode, brand: Brand): String? {
        val primaries = report.colours.filter { it.name.uppercase() in listOf("RED", "GREEN", "BLUE") }
        val over = primaries.count { it.sat > 10 || it.lum > 40 }
        val under = primaries.count { it.sat < -12 }
        if (over < 2 && under < 2) return null
        val how = if (over >= 2) "stronger" else "weaker"
        return "Your TV is showing $how colours than ${mode.label} uses. That's usually a setting, not something " +
            "the colour sliders can fix. Check that the picture mode is Filmmaker or Cinema and ${brand.gamutSetting}. " +
            "Playing from a streaming box? Set it to match the video's dynamic range, so the TV gets the video as it is."
    }
}
