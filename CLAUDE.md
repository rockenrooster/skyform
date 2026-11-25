# Skyform Weather — notes for Claude sessions

Android weather app (Kotlin, Compose, no Play services). Data: the National Weather Service by default (`Nws.kt`:
api.weather.gov forecasts, station observation, alerts; US only), Open-Meteo filling its gaps (UV, sun times, rain
amounts, days 8-14) and for places outside the US (`OpenMeteo` in `Model.kt`; also place search); Android's
LocationManager and Geocoder. Home-screen widget: `widget/Widget.kt` (RemoteViews with TextClock + a chart bitmap drawn
by `WidgetArt`, the same code as the preview in Settings). In-app updater: ported from Trueform
(`..\Trueform\docs\in-app-updater.md`); release pipeline `.github/workflows/release.yml` (tag `build-<commit count>`,
asset `skyform.apk`), signing key via `scripts\release-key.ps1`.

## Rules (the owner's, shared with Trueform and Raveform)

- Builds and tests on at most 4 CPU cores: `scripts\build.ps1` (dot-sources `scripts\env.ps1`). Never `gradlew --stop`.
- Judge smoothness with `scripts\build.ps1 -Config staging` (R8, not debuggable, profileable, installs over debug), never
  the debug build: Compose is several times slower when debuggable.
- The owner's bar: swipes at 120 Hz with p95 frame time under 8.33 ms and headroom. Measure with
  `scripts\swipe-test.ps1 -Device op12r` (holds the window at 120 Hz via the `hz120` extra; reports frames in motion
  separately from the sky's 30 fps idle frames; `-Cold` stops the app first and measures the first swipes). 2026-11-24,
  in motion p95: warmed up 7.2 ms; cold launch 6.7-7.0 ms once Android has compiled the app (`cmd package compile -m
  speed-profile -f dev.skyform.weather.debug`, what its idle job does), 7.7 ms straight after an install. Find slow frames
  with `atrace -a dev.skyform.weather.debug` and `scripts\trace-top.py` (the `traced("SF …")` sections name the cards).
- What kept frames fast (keep it so): the sky has its own half-resolution cached layer, animates at 30 fps, freezes
  while anything scrolls and crossfades between skies; pages are plain columns, all kept composed, each on its own
  render node; nothing animates inside a cached layer (the pull spinner only on a pull); state that changes on a swipe
  (current or settled page) is read only inside small `Scoped {}` blocks; `Forecast` is `@Immutable` so unchanged
  pages skip; long label lists carry one merged semantics description.
- **Push only when the owner asks.** A push to `main` publishes a release.
- Never read, print, copy or commit anything under `keys/`.
- Shared phones: every adb command has `-s <serial>`; nothing device-wide; check the foreground app first; lock files in
  `D:\Stuff\DevFolder\Android_Games\Raveform\captures\locks` (`Enter-TfDeviceLock`); soaks 15 minutes at most.
  The owner reads Claude on the 12R: if anything but the launcher or Skyform is in front, do not start or tap. When a
  test is done, always press Home (`input keyevent KEYCODE_HOME`) so the phone is left on the home screen.
  Development uses `dev.skyform.weather.debug`.
- PC runs are silent and off screen (an emulator: `-no-window -no-audio`, `-read-only` for another project's AVD).
- UI: human readable, space-efficient; clear high-contrast hover/pressed states (`Modifier.tap` in `ui/Theme.kt`).
- Never send the owner's own location or address to outside services in tests: use a public city.
