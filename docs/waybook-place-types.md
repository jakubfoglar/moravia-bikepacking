# Waybook — per-place-type enrichment strategy

*2026-07-17. Design/research. Grounds every claim in the shipping pipeline
(`waybook/backend/supabase/functions/discover/index.ts`, catalog version `p6a`) and the
prior docs (`waybook-monetization.md`, `waybook-google-places-discovery.md`,
`guidebook-extension-research.md`, `waybook-v1-spec.md`). Sources cited inline, all
verified July 2026. No app code changed by this doc — it is the plan the next enrichment
PR should implement.*

The thesis: Waybook meets a handful of genuinely different **kinds** of place, and today
it runs almost all of them through one resolver and one expensive escape hatch (LLM web
search, ~$0.045/place). Route each kind to the cheapest source that gives the rider what
they actually want, and the costly web-search tier shrinks to a small, well-gated
remainder. Every recommended source is free and permanently cacheable with attribution —
the same license posture as the current base (`guidebook-extension-research.md` §2.3).

---

## 0. Exactly what the pipeline does today (measured, from the code)

### Discovery — the Overpass tag set → 4 categories

`buildQuery()` (index.ts:217-237) fires **one** Overpass `around`-query over the thinned
route chain. `classify()` (index.ts:311-338) maps each element to one of four app
categories:

| Overpass tag matched | `classify()` category | hook |
|---|---|---|
| `tourism=viewpoint` | **nature** | Viewpoint |
| `man_made=tower` + `tower:type=observation` | **nature** | Observation tower |
| `natural=peak` + `name` | **nature** | Peak (· ele m) |
| `natural=waterfall` / `waterway=waterfall` | **nature** | Waterfall |
| `historic~castle\|ruins\|monastery\|fort\|citywalls\|archaeological_site` | **history** | Castle / Ruins / … |
| `historic` + `wikidata` (any historic value) | **history** | (pretty-printed value) |
| `tourism~attraction\|museum` + `wikidata` | **history** | Attraction / Museum |
| `amenity=cafe` | **cafe** | Café |
| `shop=bakery` | **cafe** | Bakery |
| `amenity=restaurant` / `pub` / `fast_food` | **food** | Restaurant / Pub / Fast food |
| `shop=convenience` | **food** | Convenience store |
| `amenity=drinking_water` | **nature** (`water:true`) | Drinking water |

`classify()` explicitly **rejects** `wayside_cross`, `wayside_shrine`, `memorial`,
`boundary_stone`, `milestone` even when `wikidata`-tagged (index.ts:325) — the "generic
historic junk" filter. Sights use the full corridor (`maxOffKm`, default 2 km, ≤5); water
≤0.5 km; cafés/food ≤0.6 km off-route (index.ts:1300). Cap `MAX_POIS = 40`, per-category
route spacing 2.5 km (1 km for water), eat/drink reserved quota 8.

**Given-points mode** (`enrichGivenPoints`, index.ts:1162) takes the route's *own*
`Symbol.POI` waypoints — already carrying the Karoo 35-value type — collapsed to the 4
categories on-device (`RouteState.kt:111 karooTypeToCategory`) and runs them through the
same resolver/enrichment, no discovery.

### Resolver tiers A–D (`enrichStage`, index.ts:840)

Only **sights** (`history`/`nature`, not water) get resolved to a Wikidata entity:

- **Tier A** — authoritative OSM tag: `wikidata=*` present (index.ts:857), or `wikipedia=*`
  that `titleToQid()` resolves (index.ts:859). Full enrichment.
- **Tier B** — no tag, but Wikidata **geosearch** (500 m, `gsradius`) + `wbgetentities`
  yields a candidate whose label similarity ≥0.75 **and** distance ≤400 m and is not a
  settlement (index.ts:904). Full enrichment.
- **Tier C** — a plausible-but-weak geosearch match: distance ≤250 m but under the
  name-similarity bar (index.ts:906). Facts only, **no story**. Non-sights with
  `opening_hours`/`cuisine` also land here (index.ts:853).
- **Tier D** — no confident match → honest plain row (name + hook + distance). Water is
  always D.

Full enrichment (A/B) pulls, all free and cached permanently per QID in
`waybook_enrichment`: Wikipedia TextExtracts intro (~2000 chars) → Czech Wikipedia
fallback → Wikivoyage lead appended → Commons P18 + Wikipedia media-list photo gallery
(≤5, 320/640 px thumbs w/ per-file credit) → **one batched Haiku 4.5 call** writes the
2–3-sentence English blurb for every A/B entity at once (~$0.0005/entity, index.ts
header). $0 fallback when the LLM is off/fails: English Wikipedia's own first sentence.

### The web-search fallback — exact mechanics and cost

`webSearchStage()` (index.ts:693) + config `WEBSEARCH` (index.ts:47):

- **Who is eligible:** named **sight**-category POIs (`history`/`nature`, not water) that
  resolved to **tier C or D** (index.ts:1095). i.e. "we found a named sight on the route
  and Wikidata/Wikipedia knows nothing usable about it."
- **Model / tool:** `claude-sonnet-5` + `web_search_20260209`, `max_uses: 3`,
  `max_tokens: 2000`, `output_config.effort: "low"` (index.ts:640). Sonnet 5 because the
  dynamic-filtering search tool doesn't run on Haiku (index.ts:38-42 comment).
- **Caps, in order:** top **`maxPerBuild: 8`** by score per build → each place searched
  **at most once ever** (even a "found nothing" result is cached as a `blurb:null` row,
  index.ts:779) → global **`dailyBudget: 40`** searched places/UTC-day circuit breaker in
  `waybook_counters` (index.ts:733), silently skips when exhausted.
- **What it sends** (index.ts:653): name, near-town, coordinates, category+hook — with a
  prompt (index.ts:523) that forbids fabrication and returns `NO_INFO` when the web has
  nothing. Text only; **no photo** (web images aren't reliably licensed).
- **Cost** (`waybook-monetization.md` §1.1): **~$0.045/searched place** (~3–6K in + 0.3–0.8K
  out tokens at Sonnet $3/$15 per MTok + 1–3 billed searches at $0.01 each).

**Quantified worst case:**

| | Searches | $ |
|---|---|---|
| Per build | 8 places × up to 3 `web_search` uses = **up to 24 billed searches** | code-capped ~**$0.36** (`waybook-monetization.md`: "$0–0.36, typ. $0.10–0.25") |
| Per UTC day (global) | 40 places × up to 3 = **up to 120 billed searches** | **40 × ~$0.045 ≈ $1.80/day ≈ $54/mo ceiling**, independent of user count |

The load-bearing asymmetry (`waybook-monetization.md` §1.1): **one web-searched place
(~$0.045) ≈ 90 Haiku paragraphs (~$0.0005).** When on, web search is ~95% of LLM spend.
It is *the* lever, and everything below aims at it.

---

## 1. Place-type taxonomy + strategy matrix

Five practical types, derived from the Overpass tag set above **and** the route's own
Karoo `Symbol.POI.Types` (the 35-value GPX-native taxonomy the waypoints already carry —
`RouteState.kt` collapses them to 4 categories today, but the *type* is the right routing
key for enrichment). The Karoo type is the cheapest possible signal — it's already on the
waypoint, no lookup — so use it to pick the strategy before spending anything.

### Type 1 — Wiki-covered landmarks *(the headline feature)*

**Karoo types:** `MONUMENT`, `SUMMIT` (named peaks), `WINERY` (estate/château), famous
`VIEWPOINT`. **OSM:** `historic=castle|monastery|fort|citywalls|archaeological_site`,
`tourism=museum|attraction` + `wikidata`, `man_made=tower`+observation with a wiki article.

- **Rider wants:** the story (why stop), a photo, ideally a few photos; for a monument you
  can enter, opening hours + admission.
- **Best free source:** **already optimal.** Tier A/B → Wikidata → Wikipedia
  intro + Wikivoyage lead → Commons gallery → Haiku blurb. ~60–80% yield a paragraph,
  ~50–80% a photo (`guidebook-extension-research.md` §6; this repo's Moravia anchor: 78%
  photos for history+nature). Multiple photos already ship (P6a gallery, ≤5).
- **Gap — opening hours / admission:** **not reliably in wiki.** Wikidata has hours
  (P3025) and admission (P2555) but coverage on landmarks is thin and stale. The reliable
  free source is the **OSM tags on the same object** — `opening_hours`, `fee`, `website` —
  which discovery already fetched but enrichment drops for sights. **Cheap win: pass
  `opening_hours`/`website`/`fee` through to the card for landmarks too, not just
  eat/drink** (today only cafés/food surface them, index.ts:1138). Zero new calls.
- **Fallback:** none needed. **Cost: $0 + ~$0.0005 blurb.** Do not touch.

### Type 2 — Named-but-no-wiki minor sights *(the cost center)*

**Karoo types:** minor `VIEWPOINT`, `SUMMIT` (unlisted hills), `GENERIC`, `PARK`,
`CONTROL`. **OSM:** `tourism=viewpoint`, `natural=peak|waterfall`, local *rozhledny*
(observation towers), small `ruins`/`chapel` with a name but no `wikidata`/`wikipedia`.

This is the **entire population of the web-search tier** — tier C/D named sights. Today
every one of them that survives the caps costs ~$0.045. The plan is a **ladder of free
tiers that must all miss before a place reaches the paid search**, in cost order:

1. **OSM tags on the object itself** (free, already fetched, no LLM). Coverage per taginfo
   2026: `description` ~3.19M objects, `inscription` 278K (verbatim — perfect for
   memorials/monuments, zero interpretation risk), `website` 4.62M, `wikimedia_commons`
   385K, `image` 421K. **Currently dropped by the pipeline for tier C/D.** Passing
   `description`/`inscription` straight to the card, verbatim, enriches a real slice of
   this tier at $0. *(Dead ends confirmed: `ref:npu` only 491 uses, `tower:height` only
   19 — do not build on them; use `ele`/`height` generically.)*
2. **Wikidata even without a Wikipedia sitelink.** OSM `wikidata=*` (4.35M objects) is
   ~2× `wikipedia=*` (2.29M) — roughly half of Wikidata-tagged OSM points have **no**
   Wikipedia article, but the *item* still carries usable statements. **Template an honest
   one-liner from statements**, gated on having at least one of: P571 inception, P1435
   heritage-designation, P2048 height, P373 Commons category (for a photo). E.g. *"Listed
   cultural monument, built 1743."* / *"Observation tower, 32 m."* Non-hallucinated,
   template-quality, **$0, no LLM.** Skip when the item is a label-only stub (common for
   bulk heritage imports — nothing to say). Wikidata is CC0.
3. **Wikivoyage listings** (best find of this research). The `{{listing}}` see/do entries
   inside destination pages are page-locked in the live API, but the community
   **[wikivoyage-listings](https://github.com/baturin/wikivoyage-listings)** project emits
   a periodically-regenerated CSV/GPX/SQL dump of **every listing in every language, with
   lat/lon** — a simple proximity join. These are editor-written descriptions of exactly
   this kind of minor local sight — **real prose, no LLM, CC-BY-SA.** Load the CZ/DACH
   subset into a Supabase table, join by coordinate. This is the one free path besides
   Wikidata-templating that yields actual sentences.
4. **Commons geosearch photo** (`list=geosearch`, `gsnamespace=6`, tight 50–150 m radius)
   — a photo from coordinates alone, even with no article. Junk rises with radius and for
   diffuse features (viewpoints); de-junk by cross-checking the file's `depicts` (P180) /
   category against the POI's QID when one exists. Free, CC per-file. Fills the "text but
   no photo" hole for this tier.
5. **NPÚ / `heritage.toolforge.org`** (CZ heritage badge, not prose). NPÚ publishes open
   data ([pamatkovykatalog.cz/openData](https://pamatkovykatalog.cz/openData),
   [geoportal.npu.cz/opendata](https://geoportal.npu.cz/opendata/), monthly, in
   data.gov.cz), joined via Wikidata **P762** (Czech cultural-heritage ID — *not* P4075).
   Wiki Loves Monuments' `heritage.toolforge.org/api` pre-joins heritage-ID ↔ Commons
   photo ↔ coordinates for 50+ countries. Gives a "protected cultural monument" badge +
   sometimes a photo, not a story.
6. **Only then** — the LLM web search, now hitting far fewer places (see §2 for the
   cheaper form of the call itself).

- **Rider wants:** one honest sentence about what it is; a photo if free.
- **Cost:** tiers 1–5 are **$0**; tier 6 is the minimized ~$0.016/place (§2). The saving is
  both *fewer places reach tier 6* and *each one is cheaper*.
- **Realistically not obtainable:** admission/hours for an unstaffed viewpoint (there are
  none); a guaranteed photo for a diffuse viewpoint. Be honest — plain row is fine.

### Type 3 — Cafés / restaurants / pubs / bakeries / wineries

**Karoo types:** `COFFEE`, `FOOD`, `BAR`, `WINERY`, `CONVENIENCE_STORE`. **OSM:**
`amenity=cafe|restaurant|pub|fast_food`, `shop=bakery`.

- **Rider wants:** is it open (hours), what kind of food (cuisine/specialities),
  phone/website, ideally a food photo.
- **Best free source — OSM, honestly mediocre.** taginfo 2026 global: `opening_hours` on
  only **~23–25%** of cafés/restaurants, `cuisine` ~30–48%, `website` ~15–20%, `phone`
  ~14–23%; `diet:*` low single digits; the legacy `contact:*` namespace is effectively
  dead (use bare `phone`/`website`). Rural CEE (Waybook's terrain) is *below* these global
  numbers (MDPI OSM-vs-Google amenity study). **Verdict: OSM is a floor, not a hours
  solution** — ~3 in 4 venues have no hours tag at all.
- **Coverage backfill — Overture Maps Places** (CDLA-Permissive-2.0, permanent cache, no
  attribution burden in v2.0; `waybook-google-places-discovery.md` §5). Confirmed 2026
  schema (`docs.overturemaps.org/schema/reference/places/place/`): names, categories,
  `websites[]`, `socials[]` (incl. Facebook/Instagram URLs), `emails[]`, `phones[]`,
  `brand`, `addresses[]`, `confidence`. **But NO opening-hours field and NO photo field** —
  the vendor roadmap lists both as *future* layers. So Overture closes the *identity /
  phone / website* gap where OSM misses it, and nothing more. Ship it for that.
- **Hours, the honest verdict:** there is **no free bulk source for European venue hours.**
  OSM (~25%) + Overture (none) is the ceiling from datasets. The only lever left is reading
  the venue's own website (§3) — worth doing where a `website` tag exists, marginal
  overall.
- **Food photos, the honest verdict:** **not shippable.** Google/Instagram/TripAdvisor/Yelp
  ToS-barred; the venue's own photos are full copyright (caching = infringement; hotlinking
  is legally live after CJEU *Renckhoff* C-161/17 and often referrer-blocked); Wikimedia
  Commons café/food coverage for small venues is ~nil (an entire capital's
  "Interiors of restaurants" category holds ~200 files). Ship a **cuisine-type icon**, not
  a photo, for eat/drink. Don't pretend otherwise.
- **Cost:** OSM $0; Overture $0 marginal (one-time regional ETL); website-read
  near-free (§3). **No LLM story for venues** — they stay facts-card (tier C), by design.

### Type 4 — Utility stops

**Karoo types:** `WATER`, `RESTROOM`, `BIKE_SHOP`, `GAS_STATION`, `CONVENIENCE_STORE`,
`LODGING`, `CAMPING`, `FERRY`, `TRANSIT_CENTER`. **OSM:** `amenity=drinking_water`, etc.

- **Rider wants:** that it exists, where, how far off-route; for water, nothing more; for a
  bike shop, phone/hours; for lodging/ferry, maybe a website.
- **Best source:** **OSM only, no enrichment.** These are plain rows by design (water is
  always tier D, index.ts:853). Pass through `opening_hours`/`phone`/`website` where the
  tag exists (bike shop, gas station) — same cheap passthrough as everywhere. No Wikidata,
  no LLM, no photo.
- **Cost: $0.** Do not enrich. (Today most of these aren't even discovered except water;
  they arrive mainly via given-points mode from the route's own waypoints.)

### Matrix summary

| Type | Rider wants | Best free source | Fallback | Photo? | Per-place cost |
|---|---|---|---|---|---|
| 1 Wiki landmark | story + photos, hours | Wikidata→Wiki→Wikivoyage→Commons + Haiku *(as-is)* + OSM hours passthrough | — | Yes, gallery | $0 + ~$0.0005 |
| 2 Minor sight | one honest sentence, photo | OSM `description`/`inscription` → Wikidata-statement template → Wikivoyage-listings → Commons geosearch → NPÚ badge | minimized LLM web search | Commons geosearch | $0, else ~$0.016 |
| 3 Café/food | hours, cuisine, contact | OSM tags → Overture backfill (contact) → venue-site read (hours) | — | No (icon) | ~$0 |
| 4 Utility | exists + distance (+ contact) | OSM only, passthrough | — | No | $0 |

---

## 2. Web-search minimization plan

Two independent multipliers: **make each call cheaper**, and **send fewer calls**.

**A. Cheaper call (verified against Anthropic docs 2026):**

1. **`maxToolUses: 3 → 1`.** For a single "what is this named place" lookup, Anthropic's
   web-search docs say simple factual queries use 1–3 searches and `max_uses` is the hard
   cap; there is no documented quality penalty for capping a single-entity lookup at 1.
   This thirds the worst-case search fee: **$0.03 → $0.01/place**, and caps billed searches
   per build at 8 (not 24) and per day at 40 (not 120).
2. **Move the call off Sonnet 5 onto Haiku 4.5.** Haiku 4.5 **does** support the basic
   `web_search_20250305` server tool (only the fancier dynamic-filtering `_20260209`
   version is Sonnet/Opus-only — which is exactly why the code is on Sonnet today,
   index.ts:38). A one-entity lookup does not need dynamic filtering. Haiku tokens are
   $1/$5 vs Sonnet's $3/$15 — ~3× cheaper on the token half. Combined with (1):
   **~$0.045 → ~$0.016/place** (~$0.01 one search + ~$0.006 Haiku tokens). **~65% cheaper
   per place.**
3. Optional: newer `web_search_20260318` supports `response_inclusion: "excluded"` to drop
   raw result blocks and cut output tokens further — marginal, and it re-requires a
   Sonnet-class model, so **(2) is the better trade** for this use.

**B. Fewer calls — the §1 Type-2 ladder runs first.** Every place resolved by OSM
`description`/`inscription`, a Wikidata-statement template, or a Wikivoyage-listings hit
never enters the web-search batch. The two prose-producing free tiers (Wikidata-templating
on the ~50% of tagged points with statements, and the Wikivoyage-listings join) plausibly
cover **30–50%** of the current tier-C/D eligible set. That directly cuts the eligible
count feeding `maxPerBuild`.

**Projected drop vs. today's numbers:**

| | Today | After A+B |
|---|---|---|
| Per searched place | ~$0.045 | ~$0.016 (−64%) |
| Eligible tier-C/D places | 100% | ~50–70% (free tiers absorb the rest) |
| **Effective web-search spend** | $54/mo ceiling | **~$12–19/mo at the same 40/day cap** (−65 to −78%) |
| Worst-case billed searches/day | 120 | 40 |

Equivalently, **hold the ~$54/mo ceiling and it now buys ~3× the coverage** — the honest
"fund the search budget" lever from `waybook-monetization.md` §4 gets 3× more effective.
Keep the daily circuit breaker exactly as-is (it's the guarantee the free product never
runs away); this just changes what a unit of budget buys.

---

## 3. "Read the venue's website" feasibility

For Type-2 minor sights and Type-3 cafés carrying an OSM/Overture `website` tag: fetch the
page, extract facts (hours, "known for kolache", what the sight is), attribute with a link.

- **Cost — genuinely cheap, and cheaper than a search.** Anthropic's `web_fetch` server
  tool has **no per-use fee** — token cost only (~2,500 tokens for a 10 kB page), and it
  runs on Haiku. The catch: `web_fetch` only fetches a URL **already in the conversation**
  — which is exactly our case, since the `website` tag *is* the URL. So a place with a
  website tag can be enriched for ~$0.003 (Haiku tokens, no $0.01 search fee) — **cheaper
  than the minimized search.** For places with a website tag, prefer `web_fetch` over
  `web_search`.
- **Free deterministic pre-step:** try parsing **schema.org JSON-LD** (`Restaurant` /
  `LocalBusiness` `openingHours`) before spending any LLM tokens. JSON-LD is on ~41–51% of
  all sites (Web Almanac 2024) and modern Wix/Squarespace templates auto-inject it. When
  present, hours come out structured, zero LLM. Measure the real hit-rate against the
  actual POI list.
- **Reliability — real risk, build a graceful skip.** No OSM-website-rot study exists;
  general link rot is ~8% dead within 3 months, ~44% at 7 years (Ahrefs), and small
  unmaintained local-business sites rot faster. Many CEE small venues use a **Facebook
  page as their website** — fetching that is against Meta ToS (actively enforced; Graph
  API Page Public Content Access is unattainable for an indie) and should be **skipped, not
  fetched**. JS-only builders that a plain fetch can't read are a further slice. Treat
  website-read as best-effort enrichment, never a dependency.
- **Legality — low risk for facts, with guardrails.** EU DSM Directive Art. 3/4 gives a TDM
  exception rebuttable only by a **machine-readable** opt-out (robots.txt / TDM-Reservation
  header — a Hamburg court, Dec 2025, held plain-text ToS opt-outs don't count). Extracted
  plain facts (hours, specialities) aren't copyrightable and a single small site isn't a
  protected database. Showing short facts + a source link, respecting robots.txt, not
  bulk-caching page bodies or media = low risk. **Photos from the site: no** (full
  copyright; caching infringes; hotlinking live after *Renckhoff*).
- **Verdict:** **Yes for facts, via `web_fetch` on Haiku, JSON-LD-first, robots-respecting,
  Facebook-URL-skipping, best-effort.** It is the one lever that produces venue hours where
  OSM and Overture can't, at near-zero marginal cost. It is **not** a photo source.

---

## 4. Future — place embeddings for recommendations *(forward sketch, not v1)*

Once each enriched place has a stable text representation, embed it once and unlock
"recommend places/detours on my next route based on what I liked." Concrete pieces:

- **What to embed:** a compact per-place document — `name · type/hook · category · one-line
  or blurb · salient OSM tags (heritage, cuisine, ele) · town`. One vector per QID / per
  ws-place key, written alongside the existing `waybook_enrichment` row (it's already the
  permanent per-entity cache — the natural home).
- **Model — cheap enough to be a non-issue.** For ~50k places (~5M tokens): OpenAI
  `text-embedding-3-small` (1536-dim, truncatable) ≈ **$0.10 total**; Voyage `voyage-4-lite`
  (Anthropic's recommended embedding vendor — there is still no first-party Anthropic
  embedding model) **$0** under its 200M-tokens/mo free tier; or **zero-external-key**
  `gte-small` (384-dim) which runs *inside* Supabase Edge Functions via
  `Supabase.ai.Session('gte-small')`, MTEB ~61 vs 3-small's ~62. Start on `gte-small` (no
  new key, no new vendor); graduate to 3-small/Voyage if quality demands.
- **Storage — pgvector on Supabase, trivial at this scale.** HNSW index (recommended
  default), store as **`halfvec`** (float16, pgvector ≥0.7) — ~50k × 512–1024-dim vectors
  ≈ 100–200 MB float32, half that as halfvec, plus a similar index. Fits the Free tier's
  500 MB; comfortable on Pro (8 GB). pgvector is free on every plan.
- **What it enables:** (a) *taste vector* — the rider taps a heart on places they liked;
  average their vectors → nearest-neighbour search among candidates in the next route's
  corridor → "you liked X, here's a similar Y 1.2 km off-route." (b) *dedupe / novelty* —
  down-rank a place too similar to ones already selected. (c) *cold-start category feel* —
  "more like castles, fewer like war memorials" as a vector nudge, complementing the
  explicit category filters already planned for Pro (`waybook-future-ideas.md`).
- **Not v1.** No rider-feedback signal exists yet, and the recommendation only pays off
  once corridors overlap across many users. Ship it after the enrichment ladder above and
  after the app has a "liked places" affordance. The embedding write is cheap to add
  earlier (backfill the cache opportunistically) so the data's ready when the feature is.

---

## 5. Prioritized recommendation — best quality-per-cent first

1. **Run the free Type-2 ladder before the paid search, and stop dropping OSM facts.**
   Pass `description`/`inscription`/`website`/`opening_hours`/`fee` through for *all* types
   (today only eat/drink surface hours; sights and utility drop them). Add the
   Wikidata-statement template (P571/P1435/P2048/P373-gated) and the **wikivoyage-listings**
   coordinate join. This is the single biggest quality-and-cost move: it enriches a real
   slice of the cost-center tier at **$0** and shrinks the web-search eligible set ~30–50%.
2. **Make the web-search call ~65% cheaper:** `maxToolUses 3→1` and move it from Sonnet 5
   to **Haiku 4.5** (basic web-search tool, sufficient for a single-entity lookup). Pure
   cost win, no quality loss for this task. Together with (1): effective web-search spend
   **~$54/mo → ~$12–19/mo**, or 3× the coverage at the same ceiling.
3. **Add the Overture Places café/food backfill** (contact/identity where OSM misses it)
   and **`web_fetch`-on-Haiku venue-site read** (JSON-LD-first) for hours — the only free
   path to venue hours. Ship a cuisine icon, not a food photo (no legal free source).
4. **Add Commons geosearch as the photo fallback** for Type-2 places with no P18/P373, tight
   radius + P180 cross-check. Closes part of the "text but no photo" hole at $0. *(Then, as
   a separate forward track, wire the embedding write into the enrichment cache so the
   recommendation feature of §4 has data waiting.)*

**Honest limits, restated:** venue opening hours are ~25% from OSM and unobtainable in bulk
otherwise; food photos are not legally shippable free at all; a diffuse viewpoint may never
resolve to a photo. The wins above are real but bounded — the design's job is to spend
$0.045 only on the shrinking remainder that genuinely needs the open web, and to be honest
about the plain rows that remain.

### Sources (verified July 2026)

- Pipeline: `waybook/backend/supabase/functions/discover/index.ts` (catalog `p6a`); `waybook/app/src/main/kotlin/cc/waybook/RouteState.kt`.
- Anthropic web_search / web_fetch tools & pricing: platform.claude.com/docs/en/agents-and-tools/tool-use/{web-search-tool,web-fetch-tool} — $10/1k searches, web_fetch no per-use fee, Haiku 4.5 supports basic web_search.
- Embeddings/pgvector: OpenAI text-embedding-3-small, Voyage voyage-4-lite (docs.voyageai.com/docs/pricing), Supabase gte-small + HNSW/halfvec (supabase.com/docs/guides/ai).
- OSM tag coverage: taginfo.openstreetmap.org (2026); Overture schema: docs.overturemaps.org/schema/reference/places/place/ + /attribution/ (CDLA-Permissive-2.0).
- Free enrichment: Wikidata P762/P571/P1435/P2048/P373; wikivoyage-listings (github.com/baturin/wikivoyage-listings); Commons API:Geosearch; NPÚ pamatkovykatalog.cz/openData, geoportal.npu.cz/opendata, heritage.toolforge.org.
- Legal: EU DSM Directive Art. 3/4 (machine-readable opt-out; Hamburg court Dec 2025); CJEU *Renckhoff* C-161/17 (hotlinking); Meta automated-collection ToS.
- Prior docs: `waybook-monetization.md`, `waybook-google-places-discovery.md`, `guidebook-extension-research.md`, `waybook-future-ideas.md`, `waybook-v1-spec.md`.
