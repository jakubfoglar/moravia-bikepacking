# karoo-ext SDK notes & gotchas

Hard-won reference for the Trip Companion extension. Source of truth: the `karoo-ext` library
(`io.hammerhead.karooext.*`). Reference extensions we studied: hammerheadnav/karoo-ext (sample),
timklge/karoo-headwind, karoo-routegraph, karoo-tilehunting, yrkan/eiradar (radar, MIT).

## What an extension is
An APK whose **Service** (`extends KarooExtension(extensionId, version)`) the Karoo binds to via
intent action `io.hammerhead.karooext.KAROO_EXTENSION` + meta-data `EXTENSION_INFO` → `@xml/extension_info`.
It's a plugin, not a launchable app: it contributes **data fields** (and optionally a map layer,
alerts, FIT effects) that the Karoo renders inside its own ride UI. `extensionId` must not contain `.`.

## Data fields (graphical)
- Subclass `DataTypeImpl(extension, typeId)`; override `startView(context, config: ViewConfig, emitter: ViewEmitter)`.
- Emit views with `emitter.updateView(remoteViews)` — **≤1 Hz** (faster emits are dropped).
- `ViewConfig.viewSize` = Pair(widthPx, heightPx); `.preview` true in page-editor; `.alignment`, `.textSize`.
- Register in `extension_info.xml` (`<DataType graphical="true" typeId=…/>`) **and** the `types` list.
- **RemoteViews only permits**: FrameLayout, LinearLayout, RelativeLayout, GridLayout + TextView,
  ImageView, Button, ImageButton, ProgressBar, Chronometer, ViewFlipper, AdapterViewFlipper, ViewStub.
  **A bare `<View>` is NOT allowed → whole view fails to inflate → black field.** Use a 1dp TextView.
  No ScrollView / no scrolling overflow text. Dynamic bg colour via `rv.setInt(id,"setBackgroundColor",c)`;
  keep rounded shapes by `setInt(id,"setBackgroundResource",R.drawable.pill_x)`.
- In-field taps: `rv.setOnClickPendingIntent(id, PendingIntent.getBroadcast(...))` → a BroadcastReceiver
  (see `NavReceiver`). `FLAG_IMMUTABLE` required.
- Draw arbitrary graphics (e.g. the minimap) into a `Bitmap` via `Canvas` → `rv.setImageViewBitmap`.

## Streaming ride data
- `streamDataFlow` is **not** provided — define it (see `KarooExt.kt`) via
  `addConsumer(OnStreamState.StartStreaming(typeId)) { it.state }` wrapped in `callbackFlow`.
- Types: `DataType.Type.SPEED / AVERAGE_SPEED / …`. Speed values are **m/s** → ×3.6 for km/h.
- Location: `karooSystem.addConsumer<OnLocationChanged> { it.lat, it.lng }` (auto params).
- Loaded route: `OnNavigationState.NavigatingRoute` exposes `routePolyline` (Google-encoded, precision 5),
  `routeDistance` (m), `routeElevationPolyline`. (We currently key off bundled per-day tracks instead.)
- `KarooSystemService(context).connect { connected -> … }`; `removeConsumer(id)`; `disconnect()`.

## Map layer
- `extension_info.xml` `mapLayer="true"` → the extension appears as a **toggle in the Karoo's native
  Layers button**; the Karoo calls `startMap(emitter: Emitter<MapEffect>)` when enabled.
- Effects: `ShowPolyline(id, encodedPolyline, @ColorInt color, width)` — colour supports **alpha**;
  `ShowSymbols(List<Symbol>)` / `HideSymbols(ids)`. `Symbol.Icon(id, lat, lng, @DrawableRes iconRes, orientation)`
  = **custom drawable**; `Symbol.POI(id, lat, lng, poiType, name, …)` = stock typed icon + label.
- One extension = one native layer toggle (all its overlays toggle together).
- Caveat: no event for the viewable area when the map is unlocked; `OnMapZoomLevel` exists.

## FIT recording
- Override `startFit(emitter: Emitter<FitEffect>)`. Define `DeveloperField(fieldDefNum: Short,
  fitBaseTypeId: Short, name, units)` — `fitBaseTypeId` 132 = uint16, 2 = uint8, 136 = float32.
- Emit `WriteDeveloperDataIdMesg(0)` once, then `WriteToRecordMesg(listOf(FieldValue(field, value)))`
  per record. Also `WriteToSessionMesg`, `WriteEventMesg`. (Pattern from eiRadar.)

## Radar
- `DataType.Type.RADAR` stream → `StreamState.Streaming.dataPoint.values: Map<String, Double>` with
  `DataType.Field.RADAR_THREAT_LEVEL` + `RADAR_TARGET_1..8_RANGE` (metres). **No per-target speed** →
  estimate closing speed from range-over-time (Δrange/Δt). A "pass" ≈ a target that came within a
  threshold and then vanished. See `RadarEngine.kt` (adapted from eiRadar, MIT). Needs a paired ANT+ radar.

## Toolchain facts
- Only JDK: `/opt/homebrew/opt/openjdk@17`. Android SDK at `~/Library/Android/sdk` (build-tools 34/36,
  platforms **34 & 36 only** — use `compileSdk 34`). Gradle 8.7, AGP 8.6.1, Kotlin 2.0.0.
- CI: `.github/workflows/build.yml` (ubuntu, temurin 17, setup-android, `sdkmanager platforms;android-34`).
