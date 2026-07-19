# Waybook — ranking & relevance: "is this place actually worth showing?"

*2026-07-18. Design/research. Grounds every claim in the shipping pipeline
(`waybook/backend/supabase/functions/discover/index.ts`, catalog version `p7`) and the
prior docs (`waybook-place-types.md`, `waybook-v1-spec.md`,
`guidebook-extension-research.md`). All API claims verified live from this machine,
July 2026 (endpoints called, headers read, real Moravian POIs used for calibration). No
app code changed by this doc — it is the plan the ranking PR should implement.*

The problem, in the owner's words: *"we do need to somehow rank it and not show
irrelevant places."* Overpass returns a 12th-century castle and a nameless
`tourism=attraction` as equals. Today's score (index.ts:2045) is a thin OSM-tag prior —
`base(category) + 1.5·wikidata + 0.5·wikipedia + 1.0·heritage − 1.2·offKm` — computed
**before** any Wikidata data exists, and there is **no relevance cutoff at all**: every
classified, named candidate that survives spacing competes for the 40 slots, so on a
sight-sparse route the junk gets in. The fix is a **two-stage score** (OSM prior → free
fame signal) plus an **explicit drop threshold**, using data the pipeline already half
fetches.

---

## 0. What the pipeline gives us to work with (measured, from the code)

- **Selection happens before enrichment.** `buildCatalog()` scores candidates from OSM
  tags only (step 5, index.ts:2045), greedy-selects 40 with per-category spacing
  (step 7), *then* `enrichStage()` resolves entities and — crucially — already
  batch-fetches `sitelinks` for every QID via `wbEntities()` (index.ts:1005,
  `props=labels|sitelinks|claims`). **The strongest free fame signal is already being
  downloaded — one stage too late to influence ranking.**
- The permanent per-entity cache `waybook_enrichment` already has a `sitelinks` column
  (index.ts:1422). Fame data cached there is free forever after first contact.
- The classify() whitelist (index.ts:374) is itself the first relevance filter — benches,
  substations, wayside crosses never enter. The problem population is *named but boring*:
  minor `tourism=attraction`/`viewpoint`/`historic` nodes with no wiki presence.
- Given-points mode (`enrichGivenPoints`) ranks nothing — the rider picked those
  waypoints. **Ranking must never drop a given point**; it may only order them.

## 1. The signals — what checked out and what didn't

### 1.1 Wikidata sitelink count — free, already fetched, the workhorse ✓

Number of language editions with an article ≈ global notability. Comes back in the same
`wbgetentities` batch the resolver already makes (50 ids/call, no key, no extra quota).
Calibration on real corridor-class entities (queried live 2026-07-18):

| Entity | Class | Sitelinks |
|---|---|---|
| Lednice Castle (Q370990) | top-tier landmark | 13 |
| Buchlov (Q105310) | major regional castle | 13 |
| Velehrad (Q1004655) | pilgrimage site/town | 27 |
| Brdo lookout tower, Chřiby (Q903418) | worthwhile minor sight | 6 |
| Rozhledna Travičná, tiny chapels | local minor sight | **no QID at all** |

Bands: 1–4 = local-only notability, 5–14 = regional, 15+ = international. Coarse but
honest, and $0. Caveat: sitelink counts saturate low for CEE (Buchlov = Lednice = 13);
they separate junk from real, not good from great — pageviews do that.

### 1.2 Wikipedia Pageviews API — free, no key, CC0, verified live ✓

`GET https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/{project}/all-access/user/{title}/monthly/{YYYYMMDD00}/{YYYYMMDD00}`
— called live from this machine, returns monthly view counts per article. Facts verified:

- **License: CC0.** "All Analytics datasets are available under the Creative Commons CC0
  dedication" (dumps.wikimedia.org/legal.html) — cache forever, no attribution needed.
- **Rate limit: 100 req/s anonymous** per IP (2026 Wikimedia API rate-limit policy;
  429 + `Retry-After` on excess). A build needs at most a few dozen calls — irrelevant.
- **`agent=user`** filters out bots/spiders; 12-month window smooths news spikes and
  seasonality (a rozhledna's views triple in summer).
- Calibration, 12-month sums (Jul 2025–Jun 2026): Eiffel Tower **1,554,215** (enwiki);
  Buchlov **9,359** (cswiki) + 1,395 (enwiki); Lednice 2,350 (enwiki); Brdo lookout
  **1,596** (cswiki). Note the CEE lesson: **the local-language wiki carries the
  signal** — Buchlov is 7× bigger on cswiki than enwiki. Fetch views for the local
  sitelink, not just enwiki.
- Mapping OSM POI → article is the resolver's existing job: the `wikipedia=lang:Title`
  tag directly names the article; otherwise QID → `sitelinks` → title. No new matching
  logic.

Cost: **1–2 HTTP calls per *new* entity** (en + local wiki), cached permanently. A place
enriched once never calls again — same economics as the rest of the enrichment cache.

### 1.3 Precomputed bulk ranks — QRank and Nominatim importance ✗ (rejected, documented)

Both were verified as the "one download, no per-POI calls" alternative:

- **QRank** (qrank.toolforge.org, CC0): `Entity,QRank` CSV of pageview-aggregated
  Wikidata ranks. Verified by HEAD + partial download: 105 MB gz, ~25M rows — but
  **`Last-Modified: 16 Mar 2024`**. The dataset is a two-year-stale snapshot; the
  toolforge job looks dead. Popularity of castles barely drifts, so it *would* still
  work, but betting a core signal on an unmaintained artifact is how pipelines rot.
- **Nominatim wikimedia-importance** (nominatim.org/data, from
  osm-search/wikipedia-wikidata): link-graph importance 0–1 with Wikidata IDs, monthly
  builds (2025-11 seen), 17M rows, ~334 MB gz. Alive and good — but it's a **334 MB
  monthly ETL into a ~2 GB Postgres table** to serve lookups the live Pageviews API
  answers in one cached call.

**Verdict: don't bulk-import.** The per-entity live fetch + permanent cache costs ~1
call per entity *ever* and is always fresh. Revisit Nominatim's file only if Wikimedia
API dependence ever becomes a problem (it's the drop-in replacement: same QID key).

### 1.4 Wikivoyage membership — ✗ as a ranking signal (coverage ~zero here)

Tested live: en.wikivoyage full-text search for "Buchlov" — a landmark castle — returns
**0 hits**; GeoData `list=geosearch` with `gsprimary=secondary` at the castle's exact
coordinates returns nothing (listing coordinates aren't indexed). The
`wikivoyage-listings` dumps (baturin) recommended in `waybook-place-types.md` §Type-2
are **dead — both mirrors 404 as of 2026-07-18** (the GitHub tool still runs, but
self-hosting a dump pipeline for a ranking signal is out of proportion). What survives:
`sitelinks.enwikivoyage` arrives free in the existing batch — a place with its *own*
Wikivoyage article is genuinely notable. Keep that as a small bonus; drop everything
else. *(This also corrects the place-types doc: the wikivoyage-listings join planned
there needs a self-hosted run of the tool, not a download.)*

### 1.5 OSM tags — the only signal for the no-QID tail ✓

Roughly half the classified sights have no `wikidata`/`wikipedia` tag, and (verified
above) minor rozhledny/chapels often have **no Wikidata item at all** — no fame signal
will ever exist for them. For this tail the OSM object itself must rank:

| Tag | Why it signals "real" | Weight direction |
|---|---|---|
| `wikidata=` / `wikipedia=` | someone linked it to the knowledge graph | strong + (and unlocks stage 2) |
| `heritage=` / `heritage:operator=` | state-listed monument | strong + |
| `description=` / `inscription=` | a mapper wrote prose about it | modest + (already harvested by P7) |
| `website=` | maintained presence | modest + |
| `name` present | baseline (unnamed already dropped) | gate |
| `tourism=attraction` *without* wikidata | OSM's junk drawer — playgrounds, minigolf | **penalty** |
| `ele=` on peaks | a 340 m "peak" in flatland vs 1,300 m | small + scaled |

## 2. The recommended scoring model

### 2.1 Two stages, one number

**Stage 1 — OSM prior** (at classify time, no network; replaces index.ts:2045):

```
prior = base(type)                                   // table below
      + 1.0·heritage + 0.5·hasWikiTag                // wikidata= or wikipedia=
      + 0.3·hasDescription + 0.2·hasWebsite          // description/inscription | website
      − 0.8·genericAttraction                        // tourism=attraction without wikidata
      − 1.2·offKm                                    // unchanged detour penalty
```

`base(type)` — per-hook, replacing the flat per-category base (category weights kept as
the default for unlisted hooks so eat/water passes need no retune):

| base | hooks |
|---|---|
| 3.4 | Castle, Monastery, Ruins, Fort |
| 3.0 | Museum, Archaeological site, City walls, City gate, Manor, other `historic` (cat default) |
| 3.0 | Observation tower, Waterfall |
| 2.8 | Viewpoint, Peak (+0.4 if `ele` ≥ 500) (nature cat default) |
| 2.4 | Attraction, Historic church, Chapel, Monument |
| 2.2 | Drinking water (unchanged) |
| 1.6 / 1.4 | Café/Bakery / Restaurant/Pub/Fast food/Convenience (unchanged) |

Given-points mode maps the Karoo 35-type taxonomy the same way (MONUMENT/SUMMIT→3.0,
VIEWPOINT→2.8, WINERY→2.4, GENERIC/CONTROL→2.0, utility types→listed, never dropped).

**Stage 2 — fame** (new `fameStage()`, runs between dedupe and greedy selection):

```
fame = min(3.0, 0.8 · log10(views12mo + 1))          // views known (article exists)
     = min(2.0, 0.5 · sitelinks)                     // QID but no article/views yet
     = 0                                             // no QID — the honest tail
bonus: +0.3 if sitelinks.enwikivoyage exists
score = prior + fame
```

Calibrated on the live numbers: Buchlov (10.7K views/yr) → fame 3.0 (capped); Brdo
lookout (1.6K) → 2.6; a cs-stub with 150 views/yr → 1.7; QID-only with 3 sitelinks →
1.5; no QID → 0. A fameless on-route viewpoint (2.8 − 0.1) still comfortably beats a
famous castle 2.5 km off (3.4 + 3.0 − 3.0), which is right for a *route* guide — but the
castle now reliably beats the nameless attraction next to it, which is the point.

### 2.2 The relevance cutoff — actually dropping noise

After scoring, **drop sight-category candidates with `score < 2.2`** (`MIN_SIGHT_SCORE`
config const). Water and eat/drink are exempt (they're utility, governed by their own
radius/quota; a café is never "noise" to a hungry rider). Given points are exempt.
Worked examples:

| Candidate | Arithmetic | Result |
|---|---|---|
| Named `tourism=attraction`, no wikidata, 1.4 km off | 2.4 − 0.8 − 1.68 = −0.08 | **dropped** (the classic junk) |
| Chapel, no QID, on-route (0.1 km) | 2.4 − 0.12 = 2.28 | kept (barely — correct: it's a real waypoint) |
| Same chapel 0.5 km off | 2.4 − 0.6 = 1.80 | **dropped** — not worth a detour with nothing to say |
| Viewpoint, no wiki, on-route | 2.8 − 0.12 = 2.68 | kept (fameless viewpoints are still the ride's texture) |
| Buchlov 2 km off | 3.4 + 1.5 + 3.0 − 2.4 = 5.5 | kept, ranks first |

The cutoff runs *before* the greedy fill, so freed slots go to real places further down
the route instead of spacing-diluted noise. On dense corridors nothing visible changes
(junk lost the slot race anyway); on sparse corridors the list gets shorter instead of
padded — which is the honest behavior. Ship `MIN_SIGHT_SCORE` as a tunable const and
expect one round of adjustment after the first real routes.

### 2.3 Ordering beyond selection

The device list currently orders by `routeKm` (right for a ride) — keep it. But ship
`score` in `toPoiJson` (rounded, one decimal): it lets the device offer "best first"
sorting later, feeds the web-search stage's existing top-`maxPerBuild` pick
(index.ts:1327) with a *much* better ordering (fameless-but-typed sights now correctly
outrank tagged-but-boring ones for the paid search), and makes ranking debuggable from
a catalog JSON.

## 3. How this slots into the pipeline

```
step 5  classify → prior score            (edit: new base table + tag terms, index.ts:2045)
step 6  dedupe                            (unchanged)
step 6b NEW fameStage(cands):
          qids = cands with wikidata tag (+ wikipedia-tag titleToQid resolves)
          hit waybook_enrichment first    (sitelinks + NEW views12mo columns — $0, warm path)
          misses: ONE wbEntities batch    (existing helper, 50/call → 1–3 calls)
                  + per-new-entity pageviews fetch (en + local wiki, parallel, ≤2 calls each)
          score += fame;  persist views12mo/views_at to waybook_enrichment
step 7  DROP score < MIN_SIGHT_SCORE, then greedy select    (3-line change)
step 8  enrichStage                       (pass the already-fetched entities map through —
                                           saves the duplicate wbEntities call it makes today)
```

- **Schema:** add `views12mo int` + `views_at timestamptz` to `waybook_enrichment`
  (`sitelinks` already exists). No new table.
- **Geosearch-resolved entities (tier B/C)** get their QID only inside `enrichStage` —
  after selection. Accepted: their fame lands in the cache and sharpens the *next* build
  through that corridor. Don't move geosearch earlier; it's per-candidate latency for a
  second-order gain.
- **Cost per build: $0.** No LLM involvement anywhere in ranking.
- **Latency:** cold corridor ≈ +1–3 s (one wbEntities batch + parallel pageview fetches
  at worst ~40 entities × 2 calls, far under the 100 req/s limit; `USER_AGENT` already
  set). Warm corridor ≈ +0 (single indexed cache read). Against a 30–60 s build with a
  live progress UI, invisible — add a `"ranking"` progress step anyway.
- **Failure mode:** `fameStage` wrapped like every other stage — on any throw, fame = 0
  for everyone and the cutoff still runs on the prior alone. Ranking degradation must
  never fail a build.

## 4. Honest limits

- **The no-QID tail ranks blind.** A genuinely lovely unnamed-in-any-wiki lookout scores
  exactly like a dull one of the same type and distance. No free signal exists there;
  the type prior + the cutoff's on-route bias is the best available, and the web-search
  tier remains their story path. Do not pretend otherwise with LLM "interestingness"
  scoring — it would cost more than the web search it feeds.
- **Sitelinks/pageviews measure fame, not beauty.** A grim war memorial can out-view a
  gorgeous minor château. Acceptable: fame correlates with "has a story", and the story
  is the product.
- **Pageview localization is heuristic.** "en + the wikipedia-tag language, else cswiki"
  matches the current enrichment order; a French route will under-count until the local
  language follows the route's country (small follow-up, same endpoint).
- **The cutoff trades recall for precision by design.** A 0.5 km-off chapel someone
  loves will vanish. That's what the `Na trase` tab (rider's own waypoints, never
  dropped) is for.

## 5. Decision summary

1. **Signals:** OSM-tag prior (refined per-hook base + heritage/description/website
   terms + `attraction` penalty) → Wikidata **sitelinks** (already fetched, moved before
   selection) → **Wikipedia pageviews 12-mo** via the free CC0 per-article API, cached
   permanently per entity. Wikivoyage only as the free `enwikivoyage`-sitelink bonus.
2. **Combine:** `score = prior + min(3.0, 0.8·log10(views12mo+1)) − 1.2·offKm` (sitelink
   fallback when no article; 0 when no QID).
3. **Cut:** drop sights below **2.2**; never drop water/eat-drink/given points.
4. **Rejected:** QRank (stale since 2024-03), Nominatim importance ETL (334 MB/mo for
   what a cached API call does), Wikivoyage listings dumps (mirrors dead), any paid or
   LLM-based ranking (fame is free).
5. **Cost/latency:** $0/build; +1–3 s cold, ~0 warm.

### Sources (verified 2026-07-18, live calls from this machine)

- Pipeline: `waybook/backend/supabase/functions/discover/index.ts` (`p7`) — score
  index.ts:2045, selection :2088, `wbEntities` :1001, `waybook_enrichment` :1415.
- Pageviews API: wikimedia.org/api/rest_v1/metrics/pageviews/per-article (called live;
  Buchlov/Brdo/Eiffel numbers above); rate limits api.wikimedia.org/wiki/Rate_limits
  (100 req/s anon, 2026); license dumps.wikimedia.org/legal.html (Analytics = CC0).
- QRank: qrank.toolforge.org (CC0; HEAD: 105,533,721 bytes, Last-Modified 2024-03-16;
  format `Entity,QRank` from partial download); github.com/brawer/wikidata-qrank.
- Nominatim importance: nominatim.org/2024/08/07/wikimedia-file.html,
  github.com/osm-search/wikipedia-wikidata (17M rows, ~334 MB, monthly, QID column).
- Wikivoyage: en.wikivoyage.org API `list=search` + `list=geosearch&gsprimary=secondary`
  (both empty for Buchlov); wikivoyage-listings.toolforge.org + baturin.org mirrors 404.
- Calibration entities: Q370990, Q105310, Q1004655, Q903418 via wbgetentities
  (sitelinks) + per-article pageviews, 2025-07→2026-07.
- Prior docs: `waybook-place-types.md`, `waybook-v1-spec.md`,
  `guidebook-extension-research.md`.
