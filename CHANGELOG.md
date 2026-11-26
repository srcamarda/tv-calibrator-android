# Changelog

Versions follow `major.minor.patch`, set in `gradle.properties` (`tvcal.version`). Build the APK with
`./gradlew apk`: it lands in `dist/` as `TV-Cal-Probe-v<version>.apk`.

## 0.2.0

- **Your TV**: choose the brand in the Set up step (LG, Samsung, Sony, TCL, Hisense, Panasonic, Philips,
  Vizio, Toshiba, Sharp, Roku TV or Other), from upstream's brand list. It sets the picture settings to
  make, the menu paths, the white-balance names (Gain / Offset, Bias, Cutoff) and the colour menu's names.
- The colour menu type is the brand's usual (Samsung: Red / Green / Blue sliders; the rest: Hue /
  Saturation / Luminance) and can be changed, as can the multipoint menu. Red / Green / Blue slider
  menus get their own bars and the "out of reach" note, as in the iOS app.
- A gamut check after Before: colours far outside their targets the same way point to a setting.
- The TV is saved with the calibration; the Live tab follows it.

## 0.1.0

The first numbered version.

- **Calibrate**: the guided calibration, as in the iOS app. Set up, Before, Bright end, Dark end, Check,
  Colours and After, on the phone camera or a demo TV.
- **Progress is saved** after every change, and taken up on the next launch: the step you were on, the
  rounds measured, the colours done, and the demo TV's settings. The camera and the demo TV each keep
  their own calibration. Finished calibrations stay in a history (Set up step).
- **Live**: one pattern read live, with the white-balance and colour bars.
- **Camera reference**: correct the camera's colour on a white known to be D65 (Filmmaker Mode).
- **Measure** and **Camera**: RAW readings with a CSV log, DNG import, and the camera report.
- Patterns: the SDR pattern pack (`patterns/pack/`) for a USB stick; Before / Check / After use the
  Quick check video on YouTube.
