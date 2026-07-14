# Moravia Bikepacking — trip POIs + Karoo extension

A two-day bikepacking trip **Břeclav → Velehrad → Otrokovice** (~265 km), with a researched
POI catalog and a custom Hammerhead Karoo extension to see those POIs on the device.

## Parts

| Path | What it is |
|------|-----------|
| `bikepacking-map.html` | Interactive review map (open in a browser) — 93 POIs across 4 categories with photos, opening hours, filters, and distance-from-track. |
| `pois.json` / `pois_enriched.json` | The researched POI dataset (enriched adds distance-to-track + day). |
| `build_map.py` | Regenerates the review map from `pois.json` + the GPX tracks. |
| `gen_assets.py` | Builds the extension's offline asset bundle (≤5 km POIs, combined track, photos). |
| `export-30.gpx` / `export-29.gpx` | The route: Day 1 (Břeclav→Velehrad), Day 2 (Velehrad→Otrokovice). |
| `extension/` | The Karoo extension (Android/Kotlin, `karoo-ext`). See `extension/PLAN.md`. |

## The Karoo extension — "Trip Companion"

Two data fields you add to a ride page:
- **POI Catalog** (full-page): photo card, nearest-first, along-route distance, prev/next paging.
- **Next POI** (compact): the nearest POI ahead.

79 POIs + photos + the 264 km track are bundled in the APK, so it works offline.

### Update it over WiFi (no laptop)
Every push to `main` triggers GitHub Actions to build the APK and publish a **Release**.
On the Karoo: open **Trip Companion → Check for update** → it downloads + installs the newest build.
(The Karoo just needs internet — hotel WiFi or a phone hotspot.)

### Build / install locally
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd extension && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # Karoo in USB/wireless debug
```

## Remote iteration workflow (on the trip, phone-only)
1. Test on the Karoo, message Claude with feedback from your phone.
2. Claude fixes → pushes to `main`.
3. GitHub Actions builds + publishes a Release automatically.
4. Karoo → Trip Companion → **Check for update** → installs the new build.

Design decisions and status live in `extension/PLAN.md`.
