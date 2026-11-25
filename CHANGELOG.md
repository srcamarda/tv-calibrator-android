# Changelog

Versions follow `major.minor.patch`, set in `gradle.properties` (`tvcal.version`). Build the APK with
`./gradlew apk`: it lands in `dist/` as `TV-Cal-Probe-v<version>.apk`.

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
