# TV Calibrator for Android (probe)

An early Android port of [TV Calibrator](https://github.com/slidebean/tv-calibrator) by Slidebean,
licensed under [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/): free and
non-commercial. This is a derived work, with its own name and icon.

Upstream turns an iPhone's camera into a light meter using a **camera profile** for each model,
trained against a colorimeter. No Android profile exists yet, so this port starts with the camera's
**own colour matrices** (the DNG forward and colour matrices that Camera2 reports). That gives an
approximate camera RGB -> XYZ conversion: fair for greys and the white point, less accurate for
saturated colours, with luminance only relative until anchored. Profiles trained against a
colorimeter (or against an iPhone that already has one) can replace it later.

## What's here

| Module | What it is |
|---|---|
| `calibrationkit/` | Pure Kotlin, no Android: a port of upstream's `CalibrationKit` (colour maths, pattern timelines, video sync and sweep, analysis, guided live readings, point finder, simulated TV) plus the DNG-matrix conversion and the RAW centre-box analysis. Tested against upstream's fixtures (recorded from the Mac app's Python engine, copied under `src/test/resources/fixtures`). Upstream's `AutoCalibration` isn't ported: the app doesn't use it. |
| `app/` | **TV Cal Probe**. *Calibrate*: the guided calibration as in upstream (Set up → Before → Bright end → Dark end → Check → Colours → After), on the camera or a built-in demo TV. *Live*: one pattern read live. *Measure* and *Camera*: RAW measuring and the camera report, for diagnosis. Screens use upstream's design system, ported in `app/.../ui/Theme.kt`. |

### Camera report

This lists every camera, including the physical ones behind a logical camera. For each, it shows:

- hardware level and capabilities
- RAW sizes and colour filter layout
- ISO and exposure ranges, focus, apertures
- black and white levels
- a meter checklist
- the camera RGB -> XYZ matrices built from the sensor's colour data

Share it or save it (`Android/data/br.camarda.tvcal.probe/files/reports/`). The app asks for camera
permission first: without it, Android hides the colour matrices and the focus range.

If a phone doesn't report colour matrices, **Import DNG matrices** (Measure tab) reads them from a
DNG taken with the phone's own camera app (Pro mode, RAW, main 1x camera). They're the same for every
phone of a model. Without any matrices, the app still shows camera R/G and B/G ratios, which are
enough to check that readings repeat.

### Measure

This locks the camera as upstream does: RAW, focus at its closest, lowest ISO, noise reduction and
stabilisation off. Only the shutter time changes; it searches for an exposure that isn't clipped or
too dark, and divides the reading by it. The app averages the centre 10% box (yellow) and shows x, y,
CCT and dE from D65 for both conversions. **x5** gives the spread of five readings. **Set as white**
makes the last reading the 100% reference; enter its cd/m² (e.g. from a review of the same TV model)
to get absolute luminance. Every reading is appended to
`Android/data/br.camarda.tvcal.probe/files/readings/readings-v2.csv`.

## Building

You need JDK 25 and the Android SDK with platform 37.

```sh
./gradlew :calibrationkit:test      # engine tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

## Patterns for the TV

- **Before, Check and After** follow upstream's Quick check video, with its sync flashes: play
  [SDR 00 - Quick check](https://youtu.be/jGk6o8bytjY) on the TV's YouTube app, from the start.
- **The adjustment steps** show one still pattern at a time: copy `patterns/pack/` to a USB stick and open
  the pictures in the TV's photo viewer. They're named like upstream's pattern videos and use the same
  window (10% of the screen). Made by `tools/make_pattern_pack.py`.

## Next steps

1. Run the guided flow with the camera on a real TV (first: Galaxy S25+ and an LG), and check the
   Quick check video syncs with the RAW meter's pace.
2. Add upstream's other brands, HDR10 and fine-tuning.
3. Real camera profiles: a Lab mode that pairs with upstream's `mac-lab` tool, with a colorimeter or
   a profiled iPhone as the reference.
