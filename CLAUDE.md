# CLAUDE.md — Moravia Bikepacking

Personal 2-day bikepacking trip (**Břeclav → Velehrad → Otrokovice**, ~265 km) with two deliverables:
1. A researched **POI review map** (`bikepacking-map.html`) — open in a browser.
2. A custom **Hammerhead Karoo extension** (`extension/`) — "Trip Companion".

Trip dates: **Day 1 = Fri 2026-07-31** (Břeclav→Velehrad, ~140 km), **Day 2 = Sat 2026-08-01** (Velehrad→Otrokovice, ~124 km). Train home: Otrokovice→Praha, **15:51** (backup **17:51**).

## Repo layout
| Path | What |
|---|---|
| `bikepacking-map.html` | Interactive review map (generated). |
| `pois.json` / `pois_enriched.json` | POI dataset (enriched adds distance-to-track + day). |
| `build_map.py` | Regenerates the review map from `pois.json` + the GPX. |
| `gen_assets.py` | Builds the extension's offline assets (≤5 km POIs, tracks, photos). Run after editing `pois.json`. |
| `export-30.gpx` / `export-29.gpx` | Day 1 / Day 2 routes. |
| `extension/` | The Karoo extension (Android/Kotlin). See `extension/PLAN.md`. |
| `design-preview.html` | Living design gallery of every field/screen (keep updated). |
| `docs/karoo-ext-notes.md` | **Karoo SDK gotchas & API notes — read before touching the extension.** |

## The Karoo extension
Kotlin, plain **RemoteViews** (no Compose/Glance/Hilt), `karoo-ext` via **jitpack** (no GitHub token). Fields: **POI Catalog**, **Next POI**, **Train Catcher**, **Day Overview**, **Radar Traffic**; a native-map **train-deadline pin**; an in-app **self-updater**, **feature toggles**, and a **Black Box** (in-app logs + on-field error surfacing).

### Build / update commands
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17           # only JDK on this machine
export ANDROID_HOME=$HOME/Library/Android/sdk
cd extension && ./gradlew :app:assembleDebug            # compileSdk 34 (platforms 34/36 installed, NOT 35)
```
Do **not** commit `extension/app/build/`, `extension/local.properties`, `.superpowers/`, `osm_food.json` (see `.gitignore`).

### OTA / CI (the whole point of this project)
Every push to `main` that touches `extension/**` → **GitHub Actions builds the APK and publishes a Release** (`v<run_number>`, versionCode = run number). The in-app **Check for update** button pulls the newest Release over WiFi. On-trip loop: *user messages Claude from phone → Claude pushes → CI builds → user taps Check for update on the Karoo.* No laptop/cable after the first install.

## Lessons learned (things that bit us — don't repeat)
- **RemoteViews allows only specific widgets.** A bare `<View>` broke inflation → **black field**. Use a 1dp `TextView` for dividers. No `ScrollView`/scrolling in fields (that's why the POI card is glance-only with a separate Reading pane).
- **`streamDataFlow` is NOT in the SDK** — extensions define it themselves (see `KarooExt.kt`).
- **`karoo-ext` is on GitHub Packages (needs a token) — use jitpack instead**: `com.github.hammerheadnav:karoo-ext` (anonymous). Classes stay `io.hammerhead.karooext.*`.
- **Committed `debug.keystore`** so every CI build shares a signature → in-app updates install over each other. Never regenerate it.
- **Third-party internet needs real WiFi/hotspot, NOT the Bluetooth Companion tether** (self-updater fails otherwise). Surface network errors, don't swallow them.
- **`updateView` is throttled to ~1 Hz.** Radar target *speed* is not exposed by the SDK — derive it from range deltas.
- Errors self-report on-field via a minimal safe layout — fields must **never go black**. Logs live in the app (screenshot to report).

## Conventions
- End commit messages with the `Co-Authored-By: Claude Opus 4.8 (1M context)` trailer.
- Keep `design-preview.html` in sync when adding/altering a field.
- When adding a data field: layout in `res/layout/`, render in `Render.kt`, a `DataTypeImpl` class, register in `res/xml/extension_info.xml` **and** the `types` list in `TripCompanionExtension.kt`, add strings.
