# Skyform Weather — notes for Claude sessions

Android weather app (Kotlin, Compose, no Play services), public at github.com/rockenrooster/skyform. Data:
- the National Weather Service by default (`Nws.kt`: api.weather.gov forecasts, alerts, and the observation of the
  nearest station, citizen (CWOP) ones included: the state's station list, kept 30 days; the hours start from its
  reading, the forecast's miss fading out over 6 hours; US only);
- Open-Meteo (`OpenMeteo` in `Model.kt`) filling the NWS's gaps (UV, sun times, rain and snow amounts, feels-like,
  clouds, pressure, gusts, yesterday's hours, 15-minute precipitation, days 8-14) and covering places outside the US,
  with a choice of model (`OpenMeteo.models`); its air-quality API (US AQI, pollutants, pollen) and place search;
- RainViewer's radar (zoom 7 at most, free) over OpenStreetMap tiles darkened on the phone (`ui/Radar.kt`; CARTO's
  tiles now need a key). OSM's tile policy: our User-Agent names the app, tiles go through the HTTP cache, the credit
  is shown;
- the moon's phase and times and the sun's twilight computed on the phone (`Astro.kt`, SunCalc's formulas);
- Android's LocationManager and Geocoder.

Screens: pages per place (`ui/Home.kt`: hero with the change since yesterday, then the cards in the owner's order,
`Card` in `Repo.kt`), the day sheet (`ui/DaySheet.kt`: a tapped day's hours as charts and its facts), Places and
Settings (`ui/Sheets.kt`). Widgets (`widget/Widget.kt`): the clock-and-forecast widget (the owner's screenshot; charts
run to 3 dp from the edges, "Updated" time in small text), "now" (2x2) and "week" (4x2). Notifications (`Notify.kt`):
temperature in the status bar, NWS alerts, rain or snow starting soon, today's forecast; each off until turned on.
In-app updater: ported from Trueform (`..\Trueform\docs\in-app-updater.md`); release pipeline
`.github/workflows/release.yml` (every push to main publishes `build-<commit count>` with `skyform.apk`), signing key
`keys\` (made by `scripts\release-key.ps1`; never read, print or commit it).

## Rules (the owner's, shared with Trueform and Raveform)

- Builds and tests on at most 4 CPU cores: `scripts\build.ps1` (dot-sources `scripts\env.ps1`). Never `gradlew --stop`.
- Judge smoothness with `scripts\build.ps1 -Config staging` (R8, not debuggable, profileable, installs over debug), never
  the debug build: Compose is several times slower when debuggable.
- The owner's bar: swipes at 120 Hz with p95 frame time under 8.33 ms and headroom, from a fresh launch too. Measure with
  `scripts\swipe-test.ps1 -Device op12r [-Cold]` (holds the window at 120 Hz via the `hz120` extra; reports frames in
  motion separately from the sky's 30 fps frames). 2026-11-24 (before the radar, air and day-sheet batch), in motion
  p95: warmed up 7.2 ms; cold 6.7-7.0 ms once Android has compiled the app (`cmd package compile -m speed-profile -f
  dev.skyform.weather.debug`), 7.7 ms straight after an install. Find slow frames with `atrace -a
  dev.skyform.weather.debug` and `scripts\trace-top.py` (the `traced("SF …")` sections name the cards).
- The owner's other bar: as light on battery, CPU, GPU and memory as possible. What keeps it so (keep it so):
  - The sky has its own half-resolution cached layer, animates at 30 fps only for 30 s after a touch, scroll or return
    (`Motion` in `ui/Theme.kt`; asleep it requests no frames: 0 frames in 20 s idle), never in battery saver or when
    "Moving sky" is off, freezes while anything scrolls, and crossfades between skies.
  - Pages are plain columns, all kept composed, each on its own render node; nothing animates inside a cached layer;
    state that changes on a swipe is read only inside small `Scoped {}` blocks; `Forecast` is `@Immutable`; long label
    lists carry one merged semantics description.
  - The radar loads only while its card is on screen and the app is in front, shows the latest picture, fetches the
    past two hours only when played, lets go of them off screen, and keeps pictures in a 10 MB cache, one bitmap per
    frame (radar at 1 px per dp, the map in RGB_565).
  - One background job (`RefreshJob`, ~30 min, network required, batched by Android) exists only while a widget is
    placed or a notification is on; widgets never wake the phone (`updatePeriodMillis` 0).
  - Forecast and update requests bypass the HTTP cache (`useCaches = false` in `UrlHttp`); tiles use it.
- **Push only when the owner asks.** A push to `main` publishes a release that installed copies offer as an update.
- Never read, print, copy or commit anything under `keys/`. The repository is public: no device serials, no owner
  location or address anywhere in it (test fixtures use public cities).
- Shared phones: every adb command has `-s <serial>`; nothing device-wide; check the foreground app first; lock files in
  `D:\Stuff\DevFolder\Android_Games\Raveform\captures\locks` (`Enter-TfDeviceLock`); soaks 15 minutes at most.
  The owner reads Claude on the 12R: if anything but the launcher or Skyform is in front, do not start or tap. When a
  test is done, always press Home (`input keyevent KEYCODE_HOME`) so the phone is left on the home screen.
  Development uses `dev.skyform.weather.debug`.
- PC runs are silent and off screen (an emulator: `-no-window -no-audio`, `-read-only` for another project's AVD), and an
  emulator is shut down (`adb -s <serial> emu kill`) as soon as it is not in use. For now (the owner, 2026-11-25) tests
  run on emulators, not the phones. The read-only tablet AVD `RandallLamp_Tab_API35`, set to 1080x2400 at 420 dpi, with
  places seeded through `run-as` into `shared_prefs/skyform.xml` (debug build), is a good smoke test of screens,
  notifications, the job and widgets.
- UI: human readable, space-efficient; clear high-contrast hover/pressed states (`Modifier.tap` in `ui/Theme.kt`).
- Never send the owner's own location or address to outside services in tests: use a public city.
