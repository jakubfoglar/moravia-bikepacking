# Guidebook Extension — Feasibility Study

**Question:** split the curated single-trip POI catalog out of this repo into a *free, general-purpose, standalone Karoo extension* — "a guidebook for the route you already made." When any rider loads a route, the extension builds a POI catalog: for each POI a **short interesting paragraph + a photo** (the headline feature) plus practical facts (category, hours, distance/detour). Offline-first: built at home on WiFi, works with zero connectivity on the ride.

Research date: **July 2026.** All pricing/ToS claims below are from current sources, cited inline. This is a decision-support document, not an implementation.

---

## 1. Executive verdict

**Viable — build it on the open stack (OSM/Overpass + Wikidata/Wikipedia + Wikimedia Commons + Wikivoyage), server-side, cached forever, free.** Every leg of the intended design is legal and $0-marginal on that stack. The two commercial options a user would reach for first — Google Maps and Mapy.com — are **both eliminated for the offline-cache use case by their Terms of Service**, not by price (details and exact clauses in §2). The established repo pattern (Supabase edge function → LLM captions → Postgres/Storage) already matches the recommended architecture; you are removing the two hard/expensive halves (corridor discovery and 400→45 ranking), not adding anything structurally new.

**The single biggest risk is content coverage, not cost or law.** The headline feature — *photo + paragraph* — only reliably fires on **landmark** POIs (castles, monuments, viewpoints, monasteries): ~70–80% get a paragraph, ~50–80% a photo. For **amenity** POIs (cafés, water taps, the majority of a real ride's waypoints) the paragraph rate is near-0% and the photo rate is **effectively 0%**. This is not a fixable bug; it is the shape of the free data. This repo's own hand-curated 93-POI Moravia set proves it: **48% photos overall, 78% for landmarks, 0% for cafés/food** (`pois_enriched.json`, verified). The product must be designed around this — set expectations in the UI, degrade gracefully, and never fabricate — or riders will feel the guide is "half empty." That risk is a **go**, provided the MVP is scoped as a *landmark* guide that also lists amenities, not a guide that promises a photo of every café.

**Go / no-go: GO**, on the free open stack, landmark-first, with the honest MVP scope in §9.

---

## 2. Google vs Mapy.com vs open — the verdict

### 2.1 Google Maps Platform — NO (killed by ToS, cite the clause)

Pricing is actually *fine* for this volume — the $200/mo flat credit was retired March 2025 and replaced with **per-SKU monthly free allowances** (10,000/mo Essentials, 5,000/mo Pro, 1,000/mo Enterprise per SKU); Place Details (Essentials) is $5.00/1,000 with 10,000 free, Place Photos (Enterprise) $7.00/1,000 with 1,000 free. At 10–40 waypoints/route this sits inside the free allowances. Sources: `developers.google.com/maps/billing-and-pricing/pricing`, `mapsplatform.google.com/pricing/`.

**Price is irrelevant because the ToS forbid the entire design.** From the Google Maps Platform Terms (`cloud.google.com/maps-platform/terms`), §3.2.3 "Restrictions Against Misusing the Services":

- **§3.2.3(a) No Scraping** — *"Customer will not export, extract, or otherwise scrape Google Maps Content for use outside the Services. For example, Customer will not: (i) pre-fetch, index, store, reshare, or rehost Google Maps Content outside the services; … (iii) copy and save business names, addresses, or user reviews …"*
- **§3.2.3(b) No Caching (the killing clause)** — *"Customer will not cache Google Maps Content except as expressly permitted under the Maps Service Specific Terms."*

The Service Specific Terms (`cloud.google.com/maps-platform/terms/maps-service-terms`) grant only two narrow caching permissions:
- **§A.3 Google ID Caching** — `place_id` may be cached **indefinitely**.
- **§B.14.3 (Places)** — latitude/longitude may be cached **for up to 30 consecutive calendar days**, then must be deleted.

Everything else — name, opening hours, description, **photos**, reviews — has **no caching permission**, so §3.2.3(b) prohibits persisting it. A shared per-place server cache serving other users is doubly forbidden: the one persistent-cache carve-out (Android Geocoder, §3.3.2) requires data be *"logically isolated to the specific End User … and must not be used across multiple End Users."* Place Photos may not be downloaded/re-hosted, only proxied live. The 2025 AI products (Grounding with Google Maps, GA Oct 2025; Places Insights) **do not change this** — their terms still forbid caching except place ID and review ID.

**Verdict:** Google is unusable for a free, offline-first, cache-forever, shared-cache guide. The *only* legal Google path is **BYO-API-key, live-only**: a rider brings their own key, text/photos are fetched per session and never persisted or shared, and you may keep `place_id` forever / lat-lng ≤30 days. That defeats "offline-first" and adds a headless-device key-management burden (see §7). Not recommended as a core dependency.

### 2.2 Mapy.com (Seznam) — NO for the cache; YES only as a live component

Mapy.com (formerly Mapy.cz) has a real public REST API at `developer.mapy.com` (docs `api.mapy.com/v1/docs/`): forward + reverse **geocoding**, suggest/autocomplete, raster + vector **tiles**, **static maps**, **routing** + matrix, **elevation**, **static panorama** (street-level spherical photos), timezones. Free tier is generous — **250,000 credits/month** free (Basic), or **10,000,000/month** on the Extended tariff if you display the Mapy.com logo; overage 1.60 CZK per 1,000 credits (~$0.07). A geocode costs 4 credits, so 250k free ≈ 62,500 geocodes/month. Sources: `developer.mapy.com/`, `.../pricing/`.

**Two hard blockers, though:**
1. **No POI place-details / place-photos / description API exists at all.** The rich consumer-app POI database (hours, photos, descriptions — the thing that makes Mapy.cz great) is **not exposed to developers**. Geocoding can return `poi`-type hits but only name/label/coords/type — no hours, no photos, no text. The only imagery API is street-level *panorama*, not POI marketing photos.
2. **The ToS forbid caching and offline use.** `developer.mapy.com/terms-and-conditions/` §4.6.2 explicitly prohibits *"storing or caching individual tiles or API function results"* and *"…indexing, sharing, pre-caching, storing, or exporting"* any output data. An offline-first, indefinitely-cached, shared-between-users store is precisely what it bans. Attribution is mandatory (§4.4, §4.6.7).

**Verdict:** Mapy.com cannot be the persistent POI datastore (no content API + no-store ToS). It is **excellent as an optional live component** — reverse geocoding is strong in Czechia (RÚIAN registry), and panorama/tiles are nice — called at runtime with WiFi, attribution shown, nothing persisted. Reserve it for an optional "live Czech reverse-geocode / panorama" nicety, not the backbone. Its free tier is irrelevant to the core design because the core design is exactly what §4.6.2 prohibits.

### 2.3 The open stack — YES (the free base)

Every source below permits **permanent server-side caching + redistribution with attribution** — the exact opposite of Google/Mapy. This is the recommended base for the whole guide.

| Source | Role | Cost / limits (2026) | Caching / license | Cite |
|---|---|---|---|---|
| **Overpass API** (overpass-api.de) | resolve a coord+type → OSM object | ~10k req/day, <1GB/day soft caps; 429 + fair-queue; self-host/paid mirror (kumi.systems) for heavy use | permanent cache OK; **ODbL** (see note) | `dev.overpass-api.de/overpass-doc/en/preface/commons.html` |
| **Nominatim** (OSM) | reverse-geocode fallback | max 1 req/s, bulk throttled; **results MUST be cached** | ODbL, attribution | `operations.osmfoundation.org/policies/nominatim/` |
| **Photon** (komoot) | reverse-geocode fallback | Apache-2.0; public demo tolerated; self-host easy | free | `github.com/komoot/photon` |
| **Geoapify / LocationIQ** | managed geocoding if you don't self-host | Geoapify 3,000/day, **caching allowed**; LocationIQ 5,000/day, 2 req/s, caching allowed | OSM-derived, attribution | `apidocs.geoapify.com/docs/geocoding/caching/`, `locationiq.com` |
| **Wikidata** | P18 image + short description; the identity anchor | free REST; 2026 limits ~100 req/s anon / 500 authed, target bulk scrapers only | CC0 data / CC BY-SA text | `api.wikimedia.org/wiki/Rate_limits` |
| **Wikipedia REST** page-summary | the factual **paragraph** | `/api/rest_v1/page/summary/{title}`, free | **CC BY-SA** — must show article-link attribution | `mediawiki.org/wiki/Wikimedia_REST_API` |
| **Wikimedia Commons** | the **photo** (hotlink thumbnail) | hotlink `Special:FilePath?width=` from device; discouraged-but-allowed with proper User-Agent | per-file license — must show author + license | `commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia/technical` |
| **Wikivoyage** | town/destination descriptions | MediaWiki API, 142k articles | CC BY-SA | `en.wikivoyage.org/.../How_to_re-use_Wikivoyage_guides` |
| **Foursquare OS Places / Overture Places** | amenity backfill (cafés/food OSM misses) | free **bulk parquet dumps**; FSQ Apache-2.0, Overture CDLA-Permissive-2.0 | permanent use OK | `opensource.foursquare.com/os-places/`, `docs.overturemaps.org/attribution/` |

**ODbL note (important, not scary):** an enriched POI database built from OSM is a *Derivative Database* — if you ever **distribute the database** it must stay ODbL/share-alike. But rendering it into the app UI (text cards, a map) is a **Produced Work**, which you may license freely, provided you (a) attribute OpenStreetMap and (b) offer the underlying derived data on request. You do not have to open-source your app. (`osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ`.)

**"Use X for Y" recommendation:**
- **Base guide (free, offline, cache-forever): the open stack.** OSM/Overpass for identity, Wikidata→Wikipedia for the paragraph, Commons for the photo, Wikivoyage for town context, FSQ/Overture to backfill amenities.
- **Mapy.com:** optional *live* Czech reverse-geocode / panorama only; never as the cache.
- **Google:** only if a rider brings their own key and accepts live-only, no-offline. Do not make it a core dependency.

---

## 3. End-to-end architecture

Two paths, one backend. The device stays dumb (100KB request cap over the Bluetooth Companion link makes heavy on-device work impossible per `docs/karoo-ext-notes.md` and the verified SDK facts); all resolution + enrichment is server-side and permanently cached.

```
   KAROO (extension)                       BACKEND (Supabase)                        FREE SOURCES
   ─────────────────                       ─────────────────                         ────────────
   OnNavigationState.NavigatingRoute.pois  ┌── edge fn: /build-catalog ──────────┐
   → [{name,type(35-enum),lat,lng,          │  1. hash route-corridor + POI keys  │
      distanceAlongRoute}]                  │  2. per-ENTITY cache lookup ────────┼─▶ Postgres (permanent
        │  (2–4 KB gzipped request)         │     hit → reuse forever             │    per-entity cache)
        ▼                                   │     miss → resolve + enrich:        │
  ┌─ HOME, on WiFi ─────────────┐           │       • Overpass point query (r by  ├─▶ Overpass
  │ plain HttpURLConnection,    │           │         type) → match/score entity  ├─▶ Nominatim/Photon
  │ NO 100KB cap (app already   │◀──────────┤       • follow OSM wikidata tag →   ├─▶ Wikidata (P18 + desc)
  │ pulls 11MB APKs this way)   │  catalog  │         Wikipedia REST summary      ├─▶ Wikipedia REST
  │ → download whole catalog    │  JSON     │       • Commons thumbnail URL       │   (paragraph)
  │ → fetch photos DIRECT from  │           │       • FSQ/Overture amenity backfl ├─▶ FSQ/Overture dumps
  │   Wikimedia CDN, resize     │           │       • (opt) LLM writes paragraph  ├─▶ Claude Opus 4.8
  │   ~480px, store on device   │           │         FROM retrieved facts only   │   (the only $ line)
  └─────────────────────────────┘           │  3. store entity rows forever       │
                                            │  4. return catalog = view over rows │
  ┌─ ON THE ROAD, Companion ────┐           └─────────────────────────────────────┘
  │ system HTTP, 100KB/req cap,  │◀───────────  lazy per-POI fetch: text-first,
  │ Accept-Encoding: gzip +      │              one thumbnail (~25–60KB gz) per call
  │ manual gunzip                │
  └──────────────────────────────┘
```

**Home-WiFi preload path (primary):** the app already downloads an 11MB APK over real WiFi with plain `HttpURLConnection` and no cap. Use the same path to pull the *entire* catalog (text + Wikimedia photo URLs) in one shot, then have the **device** fetch each photo **directly from the Wikimedia CDN** (zero server egress), downscale to ~480px for the 480×800 screen, and store text + images locally. After this, the ride is fully offline.

**On-road Companion fallback (secondary, v1.1):** if the rider didn't preload, fall back to the Karoo system-service HTTP over the phone's Bluetooth Companion link — but that caps at **100,000 bytes per request *and* response**; send `Accept-Encoding: gzip` and gunzip manually (the Karoo doesn't auto-decompress). Budget ≈ 100KB compressed ≈ 400–600KB JSON. So on-road fetches are **text-first, one thumbnail at a time** (a single ~25–60KB compressed photo fits; a whole photo catalog does not).

**Caching layers:**
1. **Per-entity permanent cache** (Postgres): the atomic unit. A castle enriched once is enriched *forever, for every future route past it.* Key = Wikidata QID → else OSM type+id → else `geohash7 + normalized-name + karoo-type` (see §5). This is what makes marginal cost collapse toward zero at scale.
2. **Route-corridor catalog** = a *view* over entity rows keyed by the hash of the sorted entity keys — two riders past the same landmarks share the same catalog. The per-route cost is only the subset of *new, never-seen* entities.
3. **Device cache**: the downloaded catalog + downscaled photos, offline for the ride.
4. **Volatile-field TTL**: opening hours re-queried from OSM only if >90 days stale at build time (cheap, no LLM).

**Offline behavior:** once preloaded, the reading pane and per-POI cards render entirely from device storage. RemoteViews data fields stay glance-only (no scrolling — a bare `<View>` breaks inflation → black field, per lessons learned); the full paragraph + photo live in the detail Activity (a full Activity has no RemoteViews limits). Missing content degrades to category-icon + km, never blank, never fabricated.

---

## 4. Cost model

**The only real bill is the LLM paragraph writer.** Everything else — Overpass, Nominatim/Photon, Wikidata, Wikipedia, Wikivoyage, Commons photos (hotlinked straight from the Wikimedia CDN to the device = zero server egress), FSQ/Overture bulk dumps — is free and cache-forever. Supabase runs free-to-cheap at hobby scale.

### 4.1 Marginal cost per catalog (cold build), LLM tier

LLM = **Claude Opus 4.8: $5.00 / 1M input, $25.00 / 1M output** (verified via the claude-api skill's current model table). Batch API halves both; Haiku 4.5 is $1/$5.

Per landmark paragraph, the model receives only **retrieved facts** (Wikipedia extract + OSM tags, ~300 input tokens; the shared instruction prefix is prompt-cached) and writes a ~130-token paragraph:

- input 300 × $5/1M = $0.0015
- output 130 × $25/1M = $0.00325
- **≈ $0.0048 per paragraph** (Opus 4.8, standard).

A typical catalog of ~25 POIs has ~10–14 landmark entities that qualify for a paragraph (§7 hit rates). Say 12:

| Build | Per-paragraph | ×12 landmarks | + shared-prompt overhead | **Per catalog (cold)** |
|---|---|---|---|---|
| **Opus 4.8, standard** | $0.0048 | $0.058 | ~$0.01 | **≈ $0.07** ← matches the CLAUDE.md figure |
| **Opus 4.8, Batch API (−50%)** | $0.0024 | $0.029 | ~$0.005 | **≈ $0.035** |
| **Haiku 4.5, standard** | $0.0010 | $0.012 | ~$0.003 | **≈ $0.015** |
| **No-LLM tier** (Wikipedia paragraph verbatim + OSM facts) | — | — | — | **≈ $0.00** |

**Crucial:** cost is *per new entity ever enriched*, not per catalog. A catalog that is 100% cache hits costs **$0**. The $0.07 is a fully-cold build; real cold cost = (new landmark entities) × $0.0048.

### 4.2 Monthly cost at scale

Assumptions: each active user builds ~3 routes/month; ~12 landmark entities/route qualify for a paragraph. The **cold fraction** (entities not already in the global cache) falls as the user base grows and corridors overlap — Europe's landmark set is finite and popular cycling regions converge.

| Users | Routes/mo | Landmark enrichments/mo | Cold fraction | New paragraphs/mo | **LLM cost — Opus std** | **Opus Batch** | **Haiku Batch** | No-LLM tier |
|---|---|---|---|---|---|---|---|---|
| 100 | 300 | 3,600 | ~70% | ~2,520 | **~$12/mo** | ~$6 | ~$1.3 | **$0** |
| 1,000 | 3,000 | 36,000 | ~40% | ~14,400 | **~$69/mo** | ~$35 | ~$7 | **$0** |
| 10,000 | 30,000 | 360,000 | ~20% | ~72,000 | **~$346/mo** | ~$173 | ~$35 | **$0** |

**Reading this:** even the worst case (10k users, Opus, standard pricing) is **~$350/mo** and drops to **~$35/mo** on Haiku+Batch. The **no-LLM tier is genuinely ~$0** — Wikipedia's page-summary paragraph is already a clean factual paragraph; serving it verbatim (with CC BY-SA attribution) plus OSM tags costs nothing but a server. Infra floor: Supabase **free tier** (500MB DB / 5GB egress) covers hundreds of users; **~$25/mo Supabase Pro** covers the DB at 10k-user scale (the cache is tiny — text + URLs; photos never touch your egress). **So the free product is free, and the premium LLM tier is the only thing that ever bills — a few dollars to a few hundred dollars a month, entirely proportional to how good you want the prose.**

---

## 5. The gotcha solutions (concrete rules)

### 5.1 Matching a slightly-off waypoint to the real entity

Route waypoints are dropped loosely or snapped to the road, so the coordinate is often off. Standard conflation (per the OSM↔Wikidata matcher / Edward Betts' `osm-wikidata`): **candidate score = w_name·name_sim + w_dist·(1 − d/r) + w_type·type_match**, top match above threshold wins. Concrete rules:

- **Search radius by Karoo type** (features differ in size + placement looseness):
  - SUMMIT / VIEWPOINT / MONUMENT / CAUTION / CONTROL: **500 m** (big or loosely dropped from the map)
  - COFFEE / FOOD / BAR / WINERY / LODGING / BIKE_SHOP / GAS_STATION: **250 m** (road-snap offsets)
  - WATER / RESTROOM: **150 m**
  - GENERIC / unnamed: **100–150 m** (tight — nothing to disambiguate on)
- **Name similarity:** normalize (strip diacritics, lowercase, drop type-stopwords like *hrad / café / restaurace / kostel*), token-set fuzzy ratio. Karoo names are user-typed, so accept ≥ 0.55.
- **Type consistency:** map the Karoo 35-value taxonomy → OSM tag classes (COFFEE→`amenity=cafe`; WATER→`amenity=drinking_water`|`natural=spring`; MONUMENT→`historic=*`|`tourism=attraction`; SUMMIT→`natural=peak`; …). **Reject** a candidate whose type strongly contradicts the waypoint (a COFFEE waypoint matching a church) *unless* name_sim > 0.85.
- **Wikidata link:** prefer the matched OSM object's own `wikidata=*` tag (authoritative) → else Wikidata radius search + label match. **Never** attach a Wikipedia article on distance alone — require name agreement, or you get the castle's paragraph on the pub next door.

### 5.2 Nonexistent places — degrade, never fabricate

A COFFEE waypoint where no café exists in OSM, or a generic un-named waypoint, must degrade gracefully. **Fabrication is prevented structurally, not by prompting:** the LLM only ever receives *retrieved facts*; if the retrieved facts fall below a threshold, **there is no LLM call at all**. The model cannot invent a castle's history because it is never invoked for a POI that resolved to nothing.

### 5.3 Confidence tiers (what each renders)

| Tier | Condition | Renders |
|---|---|---|
| **A** | entity matched + paragraph + photo | Full card: photo, LLM/Wikipedia paragraph, hours, category, km-along-route, detour |
| **B** | entity matched, but no photo OR no paragraph | Card from OSM tags only (hours, website, cuisine) + one factual line from tags; category **icon** instead of photo |
| **C** | low-score match, or nothing found for an amenity-type waypoint | "Your waypoint" card: name **as typed**, category, km-along-route, detour, explicit *"no verified info for this stop."* Nothing invented. LLM never sees tier-C POIs. |
| **D** | unnamed / generic waypoint | List row only: category icon + km |

Given the hit rates in §7, expect most **landmarks → A/B** and most **amenities → C/D**. That is the honest product.

### 5.4 De-duplication and the shared cache key

Entity key precedence: **Wikidata QID** (if known) → **OSM type+id** → **`geohash(7)` (~150 m) + normalized name + Karoo type**. Two riders whose routes pass the same landmark resolve to the same key → the landmark is enriched exactly once, ever. Re-enrichment TTL applies only to volatile fields (opening hours: refresh if >90 days at build time — a cheap OSM re-query, no LLM).

---

## 6. Hit-rate reality (the load-bearing honesty)

Split every waypoint into **landmark** vs **amenity**. Research + this repo's own data agree closely:

| Signal | Landmarks (castle/monument/viewpoint/monastery) | Amenities (café/water/bench) |
|---|---|---|
| Resolves to an OSM object | ~90–100% | variable; good for town cafés, patchy for taps → ~85–95% named overall |
| Yields a Wikidata/Wikipedia **paragraph** | ~60–80% | **near-0%** (a village café has no article) |
| Yields a usable **photo** | ~50–80% | **~0%** |

This repo's 93-POI Moravia anchor: **48% photos overall, 78% for landmarks (history+nature), 0% for cafés/food** — matching the research exactly. Expect this shape everywhere in Europe: rich for sights, sparse for utility stops. FSQ/Overture bulk dumps add café *identity/category/hours* coverage that OSM misses, but they do **not** supply the marketing photo or the interesting paragraph.

**Design consequence:** the headline "photo + paragraph" is a **landmark feature.** Sell it as such. For amenities the guide is a categorized, distance-aware list — still useful, but not illustrated.

---

## 7. Monetization — ranked, with a realistic number

The Karoo ecosystem norm is **free**. timklge — the most prolific Karoo dev and maintainer of `awesome-karoo` — ships pure MIT/free with **no Sponsor/Ko-fi/Patreon** button at all (`github.com/timklge`). Hammerhead's Extension Library (launched ~March 2025) is a **curated free library, not a paid store** — no in-app billing, no revenue share (`support.hammerhead.io/.../FAQ-Hammerhead-Extensions`, DC Rainmaker 2025-03). **There is no evidence any Karoo extension earns meaningful revenue.** The adjacent Garmin Connect IQ market (also no in-store paid apps) monetizes via *external* unlocks — dynamicWatch/dwMap charges **$9.99/yr or $29.99 lifetime** through its own account system (`dynamic.watch/premium`); MyBikeTraffic charges for the *server* analytics, not the on-device datafield. Both are described by their authors as coffee-money, not income.

Given a tiny addressable market (Karoo owners worldwide ≈ tens of thousands; realistic users hundreds to low thousands), near-zero cached marginal cost, and goodwill as the real asset:

| Rank | Model | Fit for this case | Realistic € / yr |
|---|---|---|---|
| **1. Free + tip jar** (Ko-fi one-time link + GitHub Sponsors) | **Do this.** 0% platform fees (GitHub Sponsors; Ko-fi 0% on one-time donations), no EU-VAT headache, zero friction, zero goodwill risk, matches ecosystem norms | **€50–300/yr**, front-loaded at launch |
| 2. Freemium hosted tier via **Lemon Squeezy** | Only if usage crosses ~1,000 users and you want "real" money. LS is full **merchant-of-record** — it handles EU OSS/VAT for you (critical for a Czech solo dev) and issues license keys. Charge €10–15/yr for the *good-LLM prose / extra corridors*; keep a no-LLM tier free forever | ~€120–450/yr @ 500 users; ~€500–1,800/yr @ 2,000 users (2–5% convert), −~5% fees |
| 3. One-time license unlock (Gumroad or LS) | Clean "lifetime unlock" (dwMap's $29.99 model). Gumroad is flat 10% but **not** full EU-VAT MoR in 2026 — use LS if you want VAT handled | similar to #2 |
| 4. **BYO-API-key — AVOID** | Highest support burden (Obsidian AI-plugin forums are full of "my key won't work"), worst UX on a headless bike computer with no keyboard, undermines "it just works." Only defensible if you refuse to run any server | — |

**Recommendation:** ship **free**, add an **unobtrusive Ko-fi + GitHub Sponsors** link, keep the **no-LLM tier free forever**, and hold a **Lemon Squeezy freemium tier** (the *server-generated good prose* is the paid part) in reserve for if usage ever crosses ~1,000 users. Do not build billing for a market that will realistically pay in three figures. Sources: `lemonsqueezy.com/blog/2026-update` (Stripe-owned, MoR), `docs.github.com/.../github-sponsors` (0% fee), `dynamic.watch/premium`.

---

## 8. MVP scope and go/no-go

**In (v1):**
- Ingest `OnNavigationState.NavigatingRoute.pois` → resolve + enrich known waypoints server-side (§3, §5).
- Open-stack enrichment only (OSM/Overpass + Wikidata/Wikipedia + Commons + Wikivoyage; FSQ/Overture amenity backfill).
- **Home-WiFi preload** path + offline catalog + reading-pane Activity + tier A–D rendering.
- **No-LLM free tier** (Wikipedia paragraph verbatim + OSM facts) as the default; optional LLM prose behind a toggle.
- Attribution screen (OSM / CC BY-SA article links / per-photo author+license) — legally required, ship it in v1.
- Reuse the existing self-updater / CI / feature-toggle / Black Box infrastructure.

**Out (defer):**
- On-road Companion lazy-fetch → **v1.1** (preload-only for v1).
- Google BYO-key, Mapy.com live reverse-geocode/panorama → optional later, never core.
- Corridor auto-discovery, 400→45 ranking → **explicitly gone** (the whole point — the rider already picked the POIs).
- Payments beyond a tip-jar link → only if usage crosses ~1,000 users.
- Languages beyond EN + CS → later.

**Biggest risk to manage in the UI:** amenity POIs are mostly tier B/C — the photo+paragraph headline only fires on landmarks. Set that expectation explicitly ("illustrated for sights, listed for stops") so the guide reads as *honest*, not *empty*.

**GO** — free, open-stack, landmark-first, offline-first via home preload, no-LLM tier free and LLM prose as the cheap upsell. The law is clear (Google/Mapy out for caching, open stack in), the cost is near-zero, and the only genuine risk (coverage) is a design/expectations problem, not a blocker.

---

### Sources (all accessed July 2026)
Google: `developers.google.com/maps/billing-and-pricing/pricing`; `cloud.google.com/maps-platform/terms` (§3.2.3(a)/(b)); `cloud.google.com/maps-platform/terms/maps-service-terms` (§A.3, §B.14.1–3). Mapy.com: `developer.mapy.com/`, `.../pricing/`, `.../terms-and-conditions/` (§4.6.2). Open stack: `dev.overpass-api.de/overpass-doc/en/preface/commons.html`; `operations.osmfoundation.org/policies/nominatim/`; `github.com/komoot/photon`; `apidocs.geoapify.com/docs/geocoding/caching/`; `locationiq.com`; `api.wikimedia.org/wiki/Rate_limits`; `mediawiki.org/wiki/Wikimedia_REST_API`; `commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia/technical`; `en.wikivoyage.org/wiki/Wikivoyage:How_to_re-use_Wikivoyage_guides`; `opensource.foursquare.com/os-places/`; `docs.overturemaps.org/attribution/`; `osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ`; `github.com/EdwardBetts/osm-wikidata`. Monetization: `github.com/timklge`; `support.hammerhead.io/.../FAQ-Hammerhead-Extensions`; `dcrainmaker.com/2025/03/...`; `dynamic.watch/premium`; `mybiketraffic.com`; `lemonsqueezy.com/blog/2026-update`; `docs.github.com/.../github-sponsors`. LLM pricing: Claude Opus 4.8 $5/$25 per 1M (claude-api skill, cached 2026-06-24).
