# Waybook — v1 design spec

**A free Hammerhead Karoo extension that turns any loaded route into an illustrated guide.** For whatever route you're navigating, Waybook builds a catalog of interesting places along it — each with a photo, a short LLM-written paragraph, and practical facts (hours, category, detour). Browse them as a list, see them as labelled markers on the native map, and navigate to any of them. Fully offline once built.

Grounded in `docs/guidebook-extension-research.md` (the feasibility research — legal/cost/coverage; read it for the deep matching rules, confidence tiers, cited 2026 pricing, and cost model). This spec is the *build blueprint* and decisions.

## Decisions (locked in brainstorm 2026-07-17)

| Decision | Choice |
|---|---|
| Repo | **Fresh public repo `waybook`** — copy-adapt the reusable core from the trip extension; trip repo frozen after Aug 1. |
| Name | **Waybook** (waybook.cc). |
| Primary feature | **Discovery-first.** Tab `Tipy` = POIs discovered along the route (Overpass); tab `Na trase` = the route's own waypoints, shown only when present. |
| Paragraphs | **LLM-written**, operator-funded, permanent cache + spend cap. Benchmark **Gemini Flash vs Claude Haiku vs Opus** for cost/quality before locking the model (the call is model-agnostic). |
| Map | Suggested POIs as **labelled typed markers** on the native map (`Symbol.POI`, toggled by the Layers button). **"Navigovat sem"** in the detail via `LaunchPinDrop`. |
| Data source | Free open stack only — OSM/Overpass + Wikidata/Wikipedia/Wikivoyage/Commons. Commercial maps (Google, Mapy) are legally closed to an offline cache — see research §Google/Mapy. |
| Offline | Preload the whole catalog + photos at home on WiFi; Companion-link fallback (≤100 KB/req) on the road; cached in `filesDir` forever after. |
| Timeline | **Full v1 before the trip (~2 weeks)**, dogfooded on the ride. Aggressive; sequenced to de-risk (below). |
| Licence | Code **MIT**; data attributed (OSM ODbL, Wikipedia/Wikivoyage CC BY-SA, Commons per-file). |
| Monetisation | Free. Unobtrusive Ko-fi / GitHub Sponsors link only. No accounts, no billing in v1. |

## Verified SDK capabilities (karoo-ext 1.1.9)

- **Read the loaded route:** `OnNavigationState.NavigatingRoute` → `routePolyline` (Google-encoded, precision 5), `routeDistance`, `pois: List<Symbol.POI>` (name, type from the 35-value taxonomy, lat/lng, `distancesAlongRoute`), `reversed`, `name`. `OnGlobalPOIs` → the rider's saved POIs.
- **Map markers:** `startMap(Emitter<MapEffect>)` (gated by `mapLayer="true"` → native Layers toggle) + `ShowSymbols(List<Symbol>)`. `Symbol.POI(id, lat, lng, poiType, name, …)` = stock typed icon **with a name label**; `Symbol.Icon(id, lat, lng, @DrawableRes, orientation)` = custom drawable. **No tap event — display-only.**
- **Navigate to a point:** `LaunchPinDrop(Symbol.POI)` — drops a pin at the POI and opens the Karoo's native navigate-to-point flow. (Confirm on-device whether it auto-starts nav or drops-for-confirm.) `ShowMapPage(true)` switches to the map.
- **Cannot** modify/append to the loaded route (no SDK effect). Navigate-to is the substitute.
- **HTTP:** system-service HTTP over the Companion link with **no device WiFi**, ≤100 KB/req (gzip manually). On real WiFi, `HttpURLConnection` is uncapped (the updater already pulls an 11 MB APK that way).

## Architecture

```
Karoo (Waybook extension)                Supabase (waybook backend)         Internet
─────────────────────────                ──────────────────────────         ────────
OnNavigationState → NavigatingRoute
  polyline + route POIs + route hash
  cached catalog in filesDir? ─yes→ render offline (never needs network again)
  no ↓  [Build catalog — auto on WiFi at home, or a button]
  POST /catalog {polyline, routePois} ─→ corridor_hash cache hit? → return catalog
                                          miss: build (waitUntil):
                                            Overpass corridor query      ──→ overpass-api.de
                                            resolve + enrich (per-entity cache) ─→ wikidata/wikipedia/wikivoyage/commons
                                            rank + select ~30–40
                                            LLM paragraph (cached per entity) ─→ Gemini/Claude
                                            write catalog JSON
  GET /catalog/{hash}  (gzip)        ←──  catalog (~20–40 KB)
  atomic write filesDir → list + map markers
  per POI card shown: GET thumbnail  ─────────────────────────────→ upload.wikimedia.org (direct, 0 server egress)
```

- **Two catalogs per route:** `Tipy` (corridor discovery) and `Na trase` (the route's own POIs, if any) — same enrichment pipeline, different candidate source.
- **Caching:** device (filesDir, LRU); server catalog-by-corridor-hash; server **permanent per-entity enrichment cache** (a castle enriched once, forever, for every future route past it — this is what keeps "free" sustainable).
- **Offline / link-drop:** built catalog is fully offline. Mid-build link drop → the *server* keeps building (async job); the device polls/retries; worst case the catalog arrives 40 km in. Photo fetch failure → category-tile fallback (already built).

## The enrichment pipeline (per candidate)

1. **Candidates.** `Tipy`: Overpass corridor query — simplify polyline (Douglas-Peucker ~150 m), one `around`-query over the coordinate chain for the tags that yielded good POIs in the trip set (viewpoints, observation towers/rozhledny, castles/ruins/monasteries, peaks, waterfalls, `wikidata`-tagged attractions, cafés/pubs/bakeries, water). `out center` + a hard node budget. `Na trase`: the route's `Symbol.POI`s directly (category already known).
2. **Resolve** the candidate to a real entity (research §Matching): search radius scaled by type, score on name + distance + type-consistency → confidence tier A–D. Tiers decide render: A/B = photo + paragraph; C = name + category + facts; D = drop or list-only. **Never fabricate.**
3. **Enrich** (cached permanently per Wikidata QID / OSM id): Wikidata (P18 image, sitelink count = fame), Wikipedia/**Wikivoyage** summary (paragraph source), Commons `iiurlwidth=320` thumbnail + artist/licence.
4. **Paragraph:** one batched LLM call per catalog — compress each enriched extract to ≤2 sentences in the rider's language. Model TBD by benchmark. No-LLM fallback = Wikipedia first sentences (kept as the $0 safety path).
5. **Rank + select** ~30–40 (research §Ranking): base(category) + fame + has-image + heritage − off-route km; greedy with per-category spacing so an old town doesn't eat every slot; utility POIs (water/food in the near ring) always kept.

## Device app (reuse ~70%)

**Reuse from the trip extension (copy-adapt):** the tiered catalog **field** (`Render.card` tiny/medium/full), the **detail** Activity (type-aware), the **list** Activity (becomes two-tab), `Poi`/`PoiRepository` (repointed from baked assets → `filesDir` catalog), `OpeningHours.kt`, `RouteMath.kt`, the HTTP client (`TripPoster` → `WaybookApi`), `UpdateManager` (CI self-update), the Karoo design system, the map-overlay code (adapt the train-pin → POI markers).

**New:**
- **Route detection + build-state machine** (field + list): `NO_ROUTE → NEW_ROUTE (tap/auto Build) → BUILDING (progress) → READY → ERROR`. Never black.
- **Two-tab list** (`Tipy` / `Na trase`), each row → detail.
- **Map layer:** `Symbol.POI` markers for the catalog, Layers toggle.
- **Detail additions:** "Navigovat sem" (`LaunchPinDrop`), attribution line (OSM / Wikipedia / photo credit).
- **Preload + offline cache** in `filesDir` (atomic write-then-rename; never a half-catalog), **photo fetch + LRU cache** direct from Commons.
- **Settings:** build trigger (auto-on-WiFi toggle, default off), locale, attribution/about, Ko-fi link.

**Drop entirely:** baked POIs, Train Catcher, Day Overview, Radar, Ride Narrator, follow-website, Sketch, the day concept.

## Backend

New Supabase project `waybook` (same stack as the trip's `ingest`): Postgres + Storage-free (photos hotlink) + edge functions. Tables: `catalogs(corridor_hash pk, kind, body jsonb, built_at)`, `enrichment(entity_id pk, extract, image_url, image_attr, sitelinks, fetched_at)` (permanent), `quota(device_uuid, day, count)`. Async build via `EdgeRuntime.waitUntil`. Abuse gate: static app token + per-device daily cap + **global daily circuit-breaker** (cold-build count + LLM-spend cap → serve cache-only when tripped). Overpass politeness: descriptive User-Agent + contact, honour `Retry-After`, 1 build in-flight per mirror, rotate mirrors; self-host only if it grows past ~500 builds/day.

## Cost & model (from research)

LLM is the only real bill. With the permanent cache: ~$12/mo at 100 users → ~$69 at 1k on Opus; ~$35/mo at 10k on Haiku+batch. No-LLM tier ≈ $0 (Supabase floor only). **v1 action:** benchmark Gemini Flash / Claude Haiku / Opus on ~20 representative POIs for cost-per-good-paragraph, pick the cheapest that clears the quality bar.

## Coverage — the real risk (design around it)

Photo + paragraph only reliably fire on **landmarks** (~70–80% paragraph, ~50–80% photo). Cafés / water / rest stops — the *majority* of discovered POIs — are ~0% paragraph, ~0% photo. This repo's own set: 48% photos overall, 78% landmarks, 0% cafés. So Waybook is deliberately **two-register**: *illustrated* for sights, *cleanly listed* (name · category · hours · detour) for stops. Never fabricate; a listed stop is a complete card.

## Build order (de-risked for the 2-week goal)

Front-loads the genuinely new/unknown parts so an end-to-end thin slice works early and there's always something to dogfood.

- **P0 (d1):** New `waybook` repo; copy core, strip trip stuff; building + CI + self-updater; skeleton extension installs.
- **P1 (d2–3):** Route reading — subscribe to `NavigatingRoute`, extract polyline + POIs; raw list "N points, X km". Proves route→device.
- **P2 (d4–6):** Backend thin slice — edge fn: polyline → one Overpass corridor query → raw POI JSON; device fetches (WiFi) → `Tipy` list of un-enriched points. Proves route→server→catalog→device end-to-end.
- **P3 (d7–9):** Enrichment — resolver + confidence tiers + Wikidata/Wikipedia/Wikivoyage/Commons + LLM paragraph (benchmark model). Headline feature lands: photos + paragraphs.
- **P4 (d10–11):** Map markers (`Symbol.POI` + Layers) + "Navigovat sem" (`LaunchPinDrop`) + detail wired to enriched data + attribution.
- **P5 (d12–13):** Preload/offline cache (filesDir, atomic), photo LRU cache, Companion fallback, build-state machine polish, `Na trase` tab.
- **P6 (d14):** Polish, README/MIT/attribution, awesome-karoo prep, and **dogfood prep** — verify it works on the actual trip route.

**Biggest risks:** P3 coverage/quality and Overpass reliability (mitigate: two-register design, aggressive caching, mirror rotation). If the timeline slips, P4/P5 features degrade gracefully — a working `Tipy` list with photos (through P3) is already dogfoodable; map/navigate/offline are additive.

## Open items to confirm before build

- Model benchmark result (Gemini Flash vs Haiku vs Opus).
- `LaunchPinDrop` exact on-device behaviour (auto-nav vs drop-and-confirm).
- Overpass tag list final tuning (reuse the trip's `build_map.py`/`gen_assets.py` learnings).
