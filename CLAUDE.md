# CLAUDE.md — Moravia Bikepacking

Personal 2-day bikepacking trip (**Břeclav → Velehrad → Otrokovice**, ~265 km) with three deliverables:
1. A researched **POI review map** (`bikepacking-map.html`) — open in a browser.
2. A custom **Hammerhead Karoo extension** (`extension/`) — "Trip Companion".
3. A live **follow website** (`web/`) — Supabase + Vercel, captions written live by Fable.

Trip dates: **Day 1 = Fri 2026-07-31** (Břeclav→Velehrad, ~140 km), **Day 2 = Sat 2026-08-01** (Velehrad→Otrokovice, ~124 km). Train home: Otrokovice→Praha, **15:51** (backup **17:51**).

## Repo layout
| Path | What |
|---|---|
| `bikepacking-map.html` | Interactive review map (generated). |
| `pois.json` / `pois_enriched.json` | POI dataset (enriched adds distance-to-track + day). |
| `build_map.py` | Regenerates the review map from `pois.json` + the GPX. |
| `gen_assets.py` | Builds the extension's offline assets (≤5 km POIs, tracks, photos). Run after editing `pois.json`. |
| `gen_web.py` | Builds `web/site/ride.json` (route + POIs for the follow site). Run after editing `pois.json`. |
| `web/site/` | The follow website (static; deployed to Vercel). `index.html` public feed, `post.html` phone camera page. |
| `web/supabase/functions/ingest/` | Edge function: takes a post, gets a Czech caption from Fable, stores it. |
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

## The follow website
Public page showing where I am, the day's drawn route (hairline SVG, **no map tiles** — deliberate), the day's POIs as tappable dots, and a reverse-chron feed. Swiss-minimal, single-column, light-only by choice.

**Pipeline:** Karoo (`RideNarrator`) sends *facts only* → Supabase edge function `ingest` → **Fable 5** writes the Czech line (vision: it comments on the actual photo/drawing) → stored → the page polls every 30 s.

- Supabase project `moravia-ride` (`nqtvcxztuuoywznxrxga`, eu-central-1). Tables `posts`, `ride_state`; public bucket `media`.
- **Secrets live in Supabase only** (`ANTHROPIC_API_KEY`, `INGEST_SECRET`) — never in this repo. The Karoo's copy of the secret is typed into the app once and kept in prefs. The phone page reads it from the URL fragment (`/post#<secret>`), which browsers never send to a server.
- The Supabase **publishable** key in `index.html` is public by design: RLS grants anon `select` only; every write goes through the edge function under the service role.
- Auto-post events: `start`, `quarter` (25/50/75 %), `poi` (<150 m), `stop` (>10 min still), `cars` milestones, `train` verdict change, `finish`. Floor of **10 min** between auto-posts; `start`/`finish`/`train` bypass it.
- Fable can refuse (safety classifiers) → the function falls back to a fixed quip so the feed never shows a blank. Cost ≈ $0.02/photo post, well under $1 for the trip.
- **The system prompt carries a glossary of the fact keys.** Without it Fable misreads our vocabulary — it read the `amber` train verdict as "caught the train comfortably" when it means "you'll make it only if you push". Add a glossary line whenever a new fact key or event is introduced.

### Phone notifications (Telegram)
When `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID` are set as Supabase secrets, `ingest` mirrors each caption to Telegram → phone → **Karoo shows it via the Companion app's notification relay**, so I know on the bars when a post landed. Text only (a photo upload would be a second slow round trip; the picture lives on the site). Optional `SITE_URL` appends a link on media posts. A Telegram failure is logged and swallowed — it must never fail the post. All three unset ⇒ the feature simply no-ops.

## Lessons learned (things that bit us — don't repeat)
- **`OnHttpResponse.MAX_REQUEST_SIZE` is exactly 100_000 bytes** and Karoo HTTP goes over the *Companion* link — so ride events and finger-sketches post without WiFi, but photos can't (they come from the phone instead). `waitForConnection = true` queues rather than drops.
- **A public Supabase bucket needs no SELECT policy.** Adding one doesn't enable image URLs (those already work) — it only lets anyone *list* every file in the bucket. Don't add it back.
- **Map labels must be de-collided in screen space, not route-km space.** Day 2 is a loop: towns 60 km apart on the route sit on top of each other on the map.
- **RemoteViews allows only specific widgets.** A bare `<View>` broke inflation → **black field**. Use a 1dp `TextView` for dividers. No `ScrollView`/scrolling in fields (that's why the POI card is glance-only with a separate Reading pane).
- **`streamDataFlow` is NOT in the SDK** — extensions define it themselves (see `KarooExt.kt`).
- **`karoo-ext` is on GitHub Packages (needs a token) — use jitpack instead**: `com.github.hammerheadnav:karoo-ext` (anonymous). Classes stay `io.hammerhead.karooext.*`.
- **Committed `debug.keystore`** so every CI build shares a signature → in-app updates install over each other. Never regenerate it.
- **Third-party internet needs real WiFi/hotspot, NOT the Bluetooth Companion tether** (self-updater fails otherwise). Surface network errors, don't swallow them.
- **`updateView` is throttled to ~1 Hz.** Radar target *speed* is not exposed by the SDK — derive it from range deltas.
- Errors self-report on-field via a minimal safe layout — fields must **never go black**. Logs live in the app (screenshot to report).

## Conventions
- **Document every big change in this file as part of the change** — new subsystem, new field, new external dependency, or anything learned the hard way (→ "Lessons learned"). Not a follow-up task; part of the same commit.
- End commit messages with the `Co-Authored-By: Claude Opus 4.8 (1M context)` trailer.
- Keep `design-preview.html` in sync when adding/altering a field.
- When adding a data field: layout in `res/layout/`, render in `Render.kt`, a `DataTypeImpl` class, register in `res/xml/extension_info.xml` **and** the `types` list in `TripCompanionExtension.kt`, add strings.
