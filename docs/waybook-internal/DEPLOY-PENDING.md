# Pending manual setup — P8 (ranking + photos) & ratings/detour

Everything built and verified in the working tree but **not yet applied/deployed**, because
the DB migrations are applied by hand against the shared Supabase project. Nothing here
contains secrets. Project: **`moravia-ride`** (ref `nqtvcxztuuoywznxrxga`, eu-central-1).

> Deploy edge functions with **`--no-verify-jwt`** — the Karoo authenticates with a token
> header, not a JWT. Without the flag the device gets 401s.

## Order
Migrations **before** the function deploys (the functions read/write the new columns/table).
Device APK is safe to ship any time — it degrades quietly if the backend isn't updated yet.

## 1. Migration — P8 fame columns
File: `backend/supabase/migrations/20260722_waybook_p8.sql`. Two idempotent, additive columns
on `waybook_enrichment` (the permanent per-entity Wikipedia-pageviews cache):
```sql
alter table public.waybook_enrichment add column if not exists views12mo int;
alter table public.waybook_enrichment add column if not exists views_at timestamptz;
```
Until applied, every `fameStage` hits an unknown-column path and ranking degrades to
prior-only (no crash, but the whole point of P8 is off).

## 2. Migration — ratings table
File: `backend/supabase/migrations/20260723_waybook_ratings.sql`. New `waybook_ratings`
table (append-only — every vote/clear is a new row; latest per (device_id, place) wins at
analysis time), deny-all RLS like every other `waybook_` table, indexes on
`(wikidata, created_at desc)` and `(created_at desc)`. Apply the file as-is.

## 3. Deploy the edge functions
```bash
cd waybook/backend/supabase
supabase functions deploy discover --no-verify-jwt   # P8 ranking + photo coverage
supabase functions deploy rate     --no-verify-jwt   # NEW — receives "Good pick?" votes
```

## 4. Secrets
- **`WAYBOOK_TOKEN`** — the `rate` function checks the device's `x-waybook-token` against
  this. It's the same shared token the other functions already use, so it should already be
  set; **verify** it exists and matches what the device sends (`supabase secrets list`).
- **`MAPILLARY_TOKEN`** — *optional*. Enables the café/water street-view photo rung. Unset ⇒
  that rung logs once and no-ops (nothing else breaks). Create a free Mapillary client token,
  then `supabase secrets set MAPILLARY_TOKEN=…`.

## 5. Ship the device APK
Push to `main` (touching `app/**`) → CI builds the APK → Release → on the Karoo, **Check for
update**. This carries the "Good pick?" 👍/👎 rating UI and the `Navigate here · +~X km`
detour estimate.

## 6. Smoke test (after 1–4)
Build a fresh corridor on the Karoo (or via the preview tool once it exists) and expect:
- a `ranking` progress step,
- `score` / `prior` / `fame` present on every POI,
- `cutoff: dropped …` only for clearly negative-scoring junk (the cutoff is deliberately
  lenient — `RELEVANCE_CUTOFF = 0.0` — for trip calibration; raise it post-trip from the
  rating data),
- with `MAPILLARY_TOKEN` set: a `street_photo` on some cafés, its URL working on the **second**
  fetch (the cache-hit serve is what refreshes the expiring thumbnail URL).

## 7. One-time effects
- `CATALOG_VERSION` `p7 → p8a`: the first build after deploy rebuilds catalogs from scratch
  (the corridor cache is salted by the version). Expected, one-time.

## 7b. Ranking fixes folded into `discover` (from the 5-route quality test)
Two fixes verified against the live harness (see `scratchpad/ranking-quality-report.md`),
already in the `discover` changes above — no extra deploy step, just redeploy `discover`:
- **Relation-truncation bug.** Overpass emits nodes→ways→relations and `out center N`
  truncates the tail, so a dense (city-edge) corridor dropped castle/abbey **relations** —
  Karlštejn Castle vanished on the Prague→Karlštejn test. Fixed by splitting the query into
  3 sets (`.landmarks` castles/monasteries · `.sights` · `.stops`) each with its own budget;
  the rare marquee class can't be starved by city museum density. Karlštejn now returns as
  the #1 pick (score 8, fame 3).
- **Fame-exempt spacing.** Route spacing kept only the top-scoring POI in a cluster and could
  drop the *more* famous of two neighbours (Dürnstein castle vs abbey; Clos Lucé vs Amboise).
  Now a POI with `fame ≥ 2.5` is never spacing-dropped — distinct icons close together both
  show, while low-fame duplicates are still suppressed.

---

# Pending — P8.1 ranking-calibration map (debug mode, preview tool, passes)

## 8. Migration — recorded passes table (DEFERRED — apply whenever)
File: `backend/supabase/migrations/20260724_waybook_passes.sql`. New `waybook_passes`
table (one row per non-cached discover build: every ranked candidate with its
score/prior/fame/offKm + picked/dropped verdict as `candidates jsonb`), deny-all RLS
(enabled, zero policies) like every other `waybook_` table, indexes on
`(route_hash, created_at desc)` and `(created_at desc)`. Apply the file as-is.

**Safe order-independence:** the updated `discover` can deploy BEFORE this migration —
the pass insert runs fire-and-forget (`EdgeRuntime.waitUntil`), and a missing table is a
logged, swallowed error (`pass snapshot insert failed (ignored)`). Discovery behaves
identically either way; passes just aren't recorded until the table exists.

## 9. Redeploy `discover` (debug mode + pass recording)
```bash
cd waybook/backend/supabase
supabase functions deploy discover --no-verify-jwt
```
Nothing else to configure: debug mode is gated by the same `x-waybook-token` the function
already checks (add `?debug=1` or body `{ "debug": true }`), short-circuits before
enrichment (no LLM/photo cost), and bypasses the corridor cache so scores are always
fresh. The normal device path is byte-identical.

## 10. Preview tool (works as soon as 9 is deployed — no DB needed)
```bash
export WAYBOOK_DISCOVER_URL="https://nqtvcxztuuoywznxrxga.supabase.co/functions/v1/discover"
export WAYBOOK_TOKEN="<the shared x-waybook-token value>"
node waybook/tools/preview-discover.mjs "bikepacking/export-30.gpx" --open
```
Parses the GPX → encodes the precision-5 polyline discover expects → calls debug mode →
writes a self-contained `discover-preview.html` (Leaflet from unpkg CDN — vendor the two
files locally if offline use matters). Picked = colored pins, dropped = grey; popups show
`score = prior + fame`, off-route m and the drop reason; the **cutoff slider** re-greys
sights client-side instantly (utility water/eat/drink always kept, mirroring the server
rule) with a live kept/dropped count — drag until junk starts to find the real cutoff.

## 11. /admin Passes page
Deployed with the next site deploy (no config). Shows a friendly "table not migrated"
state until 8 is applied; after that, every non-cached build records a pass and the page
lists them (newest first) with the same picked-vs-greyed map + score table per pass.

## 12. Smoke test (after 8 + 9)
- `node tools/preview-discover.mjs <gpx>` end-to-end → an HTML map with candidates.
- Trigger one normal (non-debug, non-cached) build → a row lands in `waybook_passes`
  and `/admin/passes` renders it.
- A debug call must NOT create a pass row or a catalog/usage row.

---

# Pending — P8b pre-trip ranking prep (café signals, cols, seed backstop)

Implements the three pre-trip "build now" items from `docs/waybook-place-strategy.md`
(private bikepacking repo, roadmap items 4-6). **No new migration** — everything is
compute-only or a bundled file; ships with the next `discover` deploy.

## 13. Redeploy `discover`
```bash
cd waybook/backend/supabase
supabase functions deploy discover --no-verify-jwt
```
Ships together:
- **Café/food tag-richness + cyclist-signal bonus** — `service:bicycle:*` (+0.5, strongest),
  outdoor_seating/cuisine=coffee_shop/internet_access/website/opening_hours (small, capped),
  a known chain `brand` (small penalty). Compute-only, reads tags Overpass already returns.
- **`mountain_pass`/named `natural=saddle` discovery** — a new `nature` hook `"Pass"`
  (`HOOK_BASE` 2.9). Two extra Overpass clauses in the existing `.sights` set; no new query.
- **Seed-list icon backstop** — `functions/discover/seeds.json` (bundled with the function,
  deploys automatically, no DB table) is proximity/QID-matched into every build and a match
  is exempt from `RELEVANCE_CUTOFF` and spacing suppression, selected first up to `MAX_POIS`.
- `CATALOG_VERSION` `p8a` → `p8b`: the first build after deploy rebuilds catalogs from
  scratch (the corridor cache is salted by the version). Expected, one-time.

## 14. Smoke test
- A route known to pass near a seeded climb/landmark (e.g. the Moravia anchor route past
  Karlštejn-class Czech icons, or any corridor near a seeded col) → the debug body
  (`?debug=1`) shows that candidate with `seeded: true` and `selected: true`, even if its
  `prior`/`fame` alone would have scored under `RELEVANCE_CUTOFF` or lost a spacing clash.
- A well-tagged café (outdoor seating, `cuisine=coffee_shop`, a `service:bicycle:*` tag)
  outscores a bare `amenity=cafe` node nearby — compare `prior` in the debug body.
- A named `mountain_pass=yes`/`natural=saddle` node on the route appears with hook `"Pass"`.

---

# Pending — Live Info (Google Places, per-tap accessory) — the ONLY place Waybook calls Google

Design: `docs/design/live-info-ux.md` + `docs/design/live-info-backend.md`. A per-tap detail-screen
button pulling fresh hours/rating/reviews for ONE place, shown transiently + attributed, then
discarded. Offline catalog is untouched. Compliance verified against Google's June-2026 terms.

## 15. ⚠ COMPLIANCE ORDERING — read before anything else
Two hard gates, in this order, or the feature is non-compliant:
1. **Put the OFFICIAL Google wordmark in the app BEFORE the backend goes live.** The device ships
   with a grey PLACEHOLDER (`res/drawable/google_on_white.xml`, loudly commented). Replace it with
   Google's official attribution asset (light + dark; from Google's brand/attribution resources —
   do NOT hand-draw it), then build/ship that APK. Only *after* that APK is out may the `live`
   backend be deployed with a key — otherwise a shipped APK would render Google reviews under a
   placeholder wordmark = non-compliant. (Until the key is live, the button just returns "not
   available", so the current placeholder is never shown — safe to ship now.)
2. Google content must stay ONLY on the detail screen; never render it on the native map layer.

## 16. Migration
File: `backend/supabase/migrations/20260725_waybook_live.sql`. Creates `waybook_places`
(place_id-only cache — NO content columns; that absence is the compliance guarantee) and
`waybook_live_usage` (counts/status only) + a rollup view, both deny-all RLS. Apply as-is.

## 17. Deploy + secret
```bash
cd waybook/backend/supabase
supabase functions deploy live --no-verify-jwt        # device uses x-waybook-token, not a JWT
```
- **New secret `GOOGLE_MAPS_API_KEY`** — create a Google Cloud key, restrict it to **Places API
  (New)**, enable **billing** (every tap that reaches Place Details costs real money — the SKU
  with reviews is the priciest tier), set a quota/alert as a backstop under the breaker
  (`LIVE.dailyBudget=200/day` global, `perDeviceDaily=25`). Unset ⇒ every call returns
  `status:"error"` gracefully.
- Reviews are only shown if Google returns author attribution + `googleMapsUri` (the server drops
  any review missing them); the device shows avatar + name + text + "View on Google Maps" + a
  "Top reviews from Google" ordering notice — all compliance requirements.

## 18. Smoke test (after 15–17)
- Tap "Check live info" on a real café/landmark → the Live card shows fresh hours + rating + ≤2
  reviews, each with an avatar, author, and a working "View on Google Maps" link; Google wordmark
  present; "as of HH:MM" stamped.
- Confirm `waybook_places` has a row with a `place_id` but NO hours/reviews columns; `waybook_live_usage`
  counts the call; a second tap on the same place resolves from cache (no Text Search).
- A place with no Google match → "Couldn't find this place on Google" (no retry); breaker exhausted
  → "resting for today".
- **Follow-up (not a blocker):** `place_id` is currently held in-memory per session, not persisted to
  the on-device catalog — so each app restart re-resolves once. Wiring `place_id` into catalog-disk
  persistence later saves that one call per place per session.
