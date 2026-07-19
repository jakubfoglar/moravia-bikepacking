# Waybook — place-selection & quality strategy (the synthesis)

*2026-07-18. Strategy/research. This is the step-back doc: it sits **above**
`waybook-ranking.md`, `waybook-place-types.md`, `waybook-coverage-photos.md`,
`waybook-cyclist-places.md`, `waybook-google-places-discovery.md`, `waybook-monetization.md`,
`waybook-model-cost-optimization.md`, `waybook-future-ideas.md`, `waybook-web-app.md` and the
5-route cold-run test (`ranking-quality-report.md`) — it does not repeat their settled
analysis, it **cites and extends** them into one coherent answer to the central question:*

> **How should Waybook decide which places to surface along ANY route, worldwide, and make
> them genuinely high-quality and trustworthy — across every place type and route type,
> offline, legally, and cheaply?**

Grounded in the shipping pipeline `waybook/backend/supabase/functions/discover/index.ts`
(catalog `p8a`) and the P9 rating sink (`functions/rate/`, `waybook_ratings`). All new
source claims verified live 2026-07-18, cited inline; the prior docs' verifications are
taken as settled and referenced, not re-run.

---

## 0. The one strategic insight (read this if nothing else)

**The search for new *data sources* has hit diminishing returns and a legal wall. The
quality lever now is signal, not sources — and the trip is the one chance to harvest the
labels that unlock it.**

Six prior docs went source-hunting and converged on the same short list: the entire
cache-legal universe for an offline product is **OSM + Wikidata/Wikipedia/Wikivoyage/Commons
+ Mapillary + Overture/FSQ**. Everything with a *real popularity or taste signal* — Google,
Mapy, Strava, Komoot, Foursquare-live, Yelp, Tripadvisor, Flickr — is barred by caching ToS,
not licence (`waybook-cyclist-places.md` §1, `waybook-coverage-photos.md` §1,
`waybook-google-places-discovery.md`). That verdict is stable and this doc adds two more
"looks great, isn't" entries (OpenTripMap, GeoNames, §2) without moving it. **Stop looking
for a source that ranks the tail; there isn't one.**

What *is* underexploited:

1. **Signal already in hand, thrown away.** Cross-source agreement, image-existence,
   tag-richness, and corridor-relative density are all computable from data the pipeline
   already fetches, and none of them feed ranking today. This is where the fame=0 tail gets
   solved — §4.
2. **The feedback loop just went live and has zero data.** `waybook_ratings` (P9) captures a
   👍/👎 per place *with the score/prior/fame/off_km it shipped with* — the first ground
   truth Waybook will ever have. The current `RELEVANCE_CUTOFF = 0.0` is a deliberate
   placeholder waiting for it (index.ts:100). **The trip is calibration season.** Everything
   pre-trip should maximize the quantity and usefulness of the labels harvested; everything
   post-trip should exploit them — §7.
3. **The product has no floor guarantee.** On Prague→Karlštejn the marquee castle *vanished*
   (a truncation bug, now noted) — the system can silently omit the one place a rider would
   be furious to miss. A relevance strategy needs a **guaranteed-coverage backstop** (seed
   lists, §8) independent of whatever the formula does.

So the strategy is three moves, in order: **squeeze more signal from owned data → close the
rating loop → guarantee the icons.** New sources are a distant fourth.

---

## 1. What makes a place "worth stopping for" — and why one formula is wrong

The current model is a single scalar: `score = prior(OSM tags) + fame(pageviews) − 1.2·offKm`,
one relevance cutoff for all sights (`waybook-ranking.md` §2). The 5-route test
(`ranking-quality-report.md`) shows this is *right for famous landmarks and wrong for
everything else* — and the reason is that "worth stopping for" is not one quantity.

**Worth-stopping decomposes into four independent axes, and different place types live on
different axes:**

| Axis | What it measures | Best free signal | Which types it ranks |
|---|---|---|---|
| **Notability** | would a stranger have heard of it | pageviews/sitelinks (fame) | landmarks — *already solved* |
| **Intrinsic appeal** | is it nice to be at, famous or not | type prior + elevation/prominence + image-existence + tag-richness | viewpoints, nature, minor sights — *the gap* |
| **Utility** | do I need it right now | category + distance + open-now | water, food, café, bike shop — *governed by radius/quota, not score* |
| **Effort** | what does the detour cost | off-route km + climb to reach | all types, as a penalty |

The single-formula error is collapsing **Notability** and **Intrinsic appeal** into one
"fame" term. A gorgeous fameless viewpoint (Intrinsic high, Notability zero) and a dull
tourist-tagged node (both zero) get the same score, so they clump and the cutoff can't
separate them (`ranking-quality-report.md` finding 5). **The fix is not a better single
number — it's per-axis scoring with a per-category cutoff**, calibrated from the rating data
(§4, §7).

**Rider intent is the second missing input.** A sightseeing tourist and a rider chasing a
train (the origin extension's whole premise) want different catalogs from the *same* route.
v1 has no intent control; the honest v1 answer is a **sensible default + the `Na trase` tab**
(the rider's own waypoints, never dropped). But intent maps cleanly onto the axes above:
"fast day" = raise the off-route penalty + utility weight, drop the cutoff floor;
"sightseeing" = the opposite. That is exactly the **category-filter / radius Pro lever**
already scoped (`waybook-future-ideas.md`, `waybook-monetization.md` §4) — so intent-awareness
is a *packaging* decision, not new research. Ship one good default now; expose the axes as
preferences later.

---

## 2. The cache-legal data-source landscape (complete, honest)

Every source Waybook could draw on, with the cache-legality verdict that is the gate for an
offline product. **The test is the *terms*, not the licence** — this is exactly where Google,
Mapy and Flickr died. New verdicts this doc adds are marked ⊕; the rest consolidate prior
docs (cited).

| Source | Cache-legality | Basis | Quality/popularity signal? | Verdict |
|---|---|---|---|---|
| **OSM / Overpass** | ✅ cacheable | ODbL — attribute + share-alike derived DB | Tag-richness *proxy* only (care, not taste) | **Base layer** — `waybook-cyclist-places.md` §1.3 |
| **Wikidata** | ✅ cacheable | CC0 | Sitelinks = notability; statements for facts | **Core** — `waybook-ranking.md` §1.1 |
| **Wikipedia pageviews** | ✅ cacheable | CC0 (Analytics) | **The** notability signal, local-lang essential | **Core** — `waybook-ranking.md` §1.2 |
| **Wikipedia/Wikivoyage text** | ✅ cacheable | CC BY-SA | Prose (blurb source), not a rank signal | **Core** (+ listings join, §4) |
| **Wikimedia Commons** | ✅ cacheable | per-file CC | *Image-existence* = latent appeal signal (§4) | **Core** — geosearch shipping p8 |
| **Mapillary** | ✅ cacheable | CC BY-SA 4.0; Terms §11 permits download-and-serve w/ logo+link | Coverage, not quality | **Add** (street-view rung) — `waybook-coverage-photos.md` §1.2 |
| **Overture Places** | ✅ cacheable | CDLA-Permissive-2.0 | `confidence` = existence, **not** quality | **Add** (café/food coverage backfill) — `waybook-google-places-discovery.md` §5 |
| **FSQ OS Places** | ✅ cacheable | Apache-2.0 | None (ratings stripped from OS release) | Redundant w/ Overture — pick one — `waybook-cyclist-places.md` §1.2 |
| ⊕ **OpenTripMap** | ⚠️ data ODbL / **API paywalled** | dev.opentripmap.org — data ODbL, but access is $19/mo or rate-limited RapidAPI | Its `rate` 1–7 field is **derived from wikidata/wikipedia presence** — signals Waybook *already computes itself* | **Skip** — a re-packaging of our own inputs behind a paywall; also semi-dormant. §2.1 |
| ⊕ **GeoNames** | ✅ cacheable | CC BY 4.0, bulk dumps | **Population** per settlement + feature codes; no per-POI quality | **Minor** — town-tiebreak only, §2.2 |
| **Google Places** | ❌ out | ToS bars caching + non-Google-map use + derived content | Rich, live only | Dead — `waybook-google-places-discovery.md` |
| **Mapy.com** | ❌ out | ToS (offline cache) | — | Dead (prior) |
| **Strava** segments/heat | ❌ out | API Policy §5.4/5.5 — no persistent index, no aggregated derivation | *Ideal* cyclist-fame signal, unreachable | Dead — `waybook-cyclist-places.md` §2.3 |
| **Komoot Highlights** | ❌ out | no public API; ToS bars redistribution | *Ideal* cyclist-tip signal, no ingestion path | Dead — `waybook-cyclist-places.md` §1 |
| **Foursquare (live) / Yelp / Tripadvisor** | ❌ out | caching caps (24 h / location_id-only) | Ratings, live only | Dead — `waybook-cyclist-places.md` §1 |
| **Flickr** | ❌ out | API ToS 24 h cache cap | CC photos, wrong channel | Dead — `waybook-coverage-photos.md` §1.4 |
| **cyclingcols / climbfinder / salite** | ❌ out | robots/ToS (DSM Art. 4 opt-out, all-rights-reserved) | Curated climb value, all closed | Dead — `waybook-cyclist-places.md` §2.4 |

**The shape of the table is the point:** the ✅ column is *complete* — there is no eighth
cacheable source with a taste signal hiding somewhere. Every ❌ is a caching-terms death, not
a licence one, and none will change without those companies choosing to open a bulk dataset
(none do). **Design for the ✅ set being the permanent universe.**

### 2.1 OpenTripMap — the tempting redundancy (⊕ verified 2026-07-18)

OpenTripMap looks like the missing popularity source: a POI API with a `rate` field (0–7,
`h`-prefixed for historic). Two reasons it isn't worth adding: (a) **the access terms** — the
underlying data is ODbL (cacheable), but the API is a paid product ($19/mo, or RapidAPI with
per-call limits), so there's no free bulk path the way Overture/FSQ give; and (b) **the signal
is circular** — OpenTripMap's `rate` is a heuristic over *exactly* wikidata-presence,
wikipedia-presence and OSM tags, which Waybook's `prior` + `fame` already compute directly and
more freshly. Paying a subscription to import a coarser version of a number we already derive
is strictly worse. The project also looks semi-maintained (docs static for years). **Skip** —
and note it as the cautionary pattern: a "POI quality API" that is just OSM+Wiki re-scored is
not new signal.

### 2.2 GeoNames — cache-legal, but a settlement signal, not a sight signal (⊕)

CC BY 4.0, bulk tab-delimited dumps (`cities500/1000/5000.zip`, `allCountries.zip`), ~12 M
features with **population** and 645 feature codes. Cache-legal (attribution only). What it
buys Waybook: a free **population number per town/village**, usable as a tiebreak for *which
settlements* to surface on a rural route ("you'll pass through Buchlovice, pop. 900") and to
sanity-rank the "near town" already computed. What it does *not* buy: any per-POI sight
quality — it has no notion of a castle being worth stopping for. **Minor, deferred**: load a
country subset only if/when Waybook surfaces settlements as first-class stops; not a fame=0
fix.

---

## 3. The core unsolved problem, precisely stated

From the test (`ranking-quality-report.md` finding 5) and the owner's brief: **the fame=0
tail.** Fame (pageviews + sitelinks) is 0 for ~half of all classified sights and for *nearly
all* cafés, viewpoints, nature and minor places — they have no Wikidata item at all, so no
notability signal can ever exist (`waybook-ranking.md` §1.5). Those places rank on the OSM
`prior` alone, which is a flat per-type base + a few tag bonuses, so **within a type they are
indistinguishable and they clump** (Mt Tam's three sub-peaks, all sitelinks=1 → fame 0.5).

This is not a source problem (no source ranks them — §2). It is a **feature-engineering + a
labels problem**. §4 is the features; §7 is the labels.

---

## 4. Signals for the fame=0 tail — concrete, weightable, all $0

Five signals, all computable from data the pipeline already fetches or fetches one call more.
None is a taste oracle; together they meaningfully separate "someone cared about this place"
from "an OSM node exists here." Ship them as additive terms on `prior`, then let the ratings
set the weights (§7) — do **not** hand-tune past a first guess.

**4.1 Cross-source agreement (the strongest free tail signal, and it's free-in-hand).**
A real, worth-seeing minor place tends to exist in *several* open datasets at once; a lone OSM
node that appears nowhere else is more likely noise. Waybook already touches all of these per
candidate:

- has a Wikidata QID (even with no article) — already known at classify
- Commons geosearch returns a photo at its coordinates — **already fetched in p8**
  (`GEOPHOTO`, index.ts:116) but used only for *display*, never for *ranking*
- present in Overture/FSQ (once the backfill lands)
- OSM tag-richness above the bare minimum (`description`/`website`/`heritage`/`wikimedia_commons`)

Define `agreement = count of independent sources that know this place` and add a modest,
capped term (e.g. `+0.4·min(agreement, 3)`). This is the single highest-leverage tail fix:
**it reuses fetches already made, it's honest (agreement is real, not invented), and it
directly de-clumps** — of two identical-type viewpoints, the one that's also photographed on
Commons and tagged with a description outranks the bare node. **Decision: build. Effort S
(re-use existing fetch results in the score). Impact high.**

**4.2 Image-existence as a latent-appeal proxy.** Someone bothered to photograph it → it's
more likely worth seeing. The p8 Commons-geosearch hit/miss and the OSM `image=`/
`wikimedia_commons=` presence are *already computed for the photo ladder* — reuse the boolean
as a small ranking bonus (`+0.3 if any photo source resolved`). Especially strong for
viewpoints (a geotagged photo at a viewpoint *is* the view — `waybook-coverage-photos.md`
§1.1). Nearly free; folds into 4.1's agreement count. **Decision: build with 4.1.**

**4.3 Elevation & prominence for the nature tail.** Viewpoints and peaks are the biggest
fameless category and OSM `ele` is already parsed (`Peak · N m`, +0.4 at ≥500 m, index.ts:108).
Extend: scale the bonus continuously with `ele`, and — *research-more* — add topographic
**prominence** (how much a peak stands above its surroundings), the real "is this a viewpoint
or a bump" signal. Prominence isn't in OSM broadly; Wikidata P2660 has thin coverage; a
DEM-derived value (SRTM/Copernicus, open) is a heavier lift. **Flag: research-more, low
priority** — `ele`-scaling is the cheap 80%; prominence is a nicety.

**4.4 Tag-richness, per category (already designed, ship it).** The café bonus formula
(`outdoor_seating`, `cuisine=coffee_shop`, `internet_access`, `service:bicycle:*` +0.5,
`route=bicycle` membership) is fully specified in `waybook-cyclist-places.md` §1.3 and reads
tags Overpass already returns — `classify()` throws them away today (index.ts:497). Generalize
the idea: a "mapper effort" score (count of meaningful tags) is a weak-but-real care proxy for
*any* type. **Decision: build (café bonus is spec-complete; the general version is a follow-up).**

**4.5 The cyclist-relevance signals (the one thing literally asked-for, not proxied).**
`service:bicycle:*` tags and `route=bicycle` relation membership are direct human "this serves
cyclists" statements — the closest legal thing to Komoot Highlights
(`waybook-cyclist-places.md` §1.3, §2.2). Plus **`mountain_pass=yes` / `natural=saddle`** as a
first-class discoverable nature subtype (§5 of that doc). **Decision: build** — new Overpass
tags + classify branch, same-file change.

**What stays honestly unrankable:** a genuinely lovely, un-photographed, un-tagged,
un-wiki'd lookout scores like a dull one of the same type. No free signal exists there
(`waybook-ranking.md` §4). The on-route bias + the type prior + the `Na trase` escape hatch
are the honest best; do not paper over it with fabricated importance.

---

## 5. Taxonomy — refine, don't rebuild

Today: 4 app categories (history / nature / cafe / food) with per-hook base weights
(index.ts:104). That's the right *granularity for the rider's mental model* and the right
*ranking key* (per-hook base already exists). Two refinements, both cheap:

1. **Split "nature" — it's overloaded.** It currently holds viewpoints, towers, peaks,
   waterfalls, *and* drinking water (a utility). Water already carries a `water:true` flag and
   its own radius/quota, so it's functionally separate — but conceptually the taxonomy should
   name a **`scenic`** axis (viewpoint/tower/peak/waterfall/pass) distinct from **`utility`**
   (water/toilet/bike-shop). This matters because the *cutoff and the rating calibration are
   per-category* (§7) — lumping a viewpoint and a water tap under "nature" muddies the label
   data. **Decision: build (a relabel + carry `subtype`), low effort.**
2. **Add the missing discoverable types** riders expect and OSM has: `mountain_pass`/`saddle`
   (§4.5), and — *research-more* — natural bathing/spring, notable bridges/viaducts, windmills,
   and the utility types the given-points mode already sees (bike shop, toilet, shelter) but
   discovery never queries. Each is a `buildQuery` + `classify` line.

Do **not** build a deep hierarchical ontology or map the full 35-value Karoo taxonomy into
discovery — the given-points path already handles the Karoo types on-device
(`waybook-place-types.md` §0), and a bigger taxonomy is more surface to calibrate against the
same thin label pool. Finer types earn their place only once the ratings show a category is
being systematically mis-served.

---

## 6. Route-type / context awareness — normalize, don't branch

The test surfaced the real problem: **the same absolute score means different things on
different corridors.** City-edge routes pull dense off-theme urban clusters (Prague's museums,
the Sex Machines Museum at 38k views — *real* popularity, wrong context,
`ranking-quality-report.md` finding 6); rural routes are sparse and the cutoff over-prunes
(Moravia loses 38% at cutoff 2.0). A fixed cutoff can't serve both.

**The fix is corridor-relative normalization, not a route-type classifier.** Rather than
detect "urban vs rural" (brittle), measure the corridor's own POI density and rank/cut
*relative to it*:

- **Adaptive cutoff / target count.** Instead of a hard `score < CUTOFF` drop, target a
  **density per route-km** (e.g. aim for ~1 sight per 3–5 km) and take the top-N by score
  within each segment. Sparse routes keep their thin texture; dense routes tighten
  automatically. This also fixes the "sparse route gets padded with junk" vs "dense route
  drops good places" tension in one mechanism.
- **Endpoint declustering.** A city at the start/finish dumps its whole tourist set into the
  first/last few km. Cap the share of the catalog any single 5-km segment can occupy (a
  spatial spacing already exists per-category — extend it to a global per-segment quota). This
  is the same lesson as `waybook-ranking.md`'s "de-collide in screen space" but applied to
  selection.
- **Keep it a knob, not a brain.** One tunable (`target sights/km`), calibrated from ratings,
  beats a route-type classifier that would itself need labels to train.

**Decision: build the adaptive per-segment target** (replaces the flat cutoff; the rating data
sets the target). **Impact high** — it's the mechanism that makes a *single* product work on
both the Loire and a Moravian back road.

---

## 7. The rating feedback loop — the highest-value asset, exploited

P9 shipped the loop: each 👍/👎 lands in `waybook_ratings` with `score, prior, fame, off_km,
route_hash, wikidata, name` and is **append-only** (the time series is the point —
`20260723_waybook_ratings.sql`). Today there is ~no data. Here's how to turn it into
calibration, in the order the data volume unlocks each:

**7.1 (First 10s of votes) Per-category cutoffs, read straight off the histogram.** Plot 👍/👎
rate vs `score`, **split by category**. The threshold where downvotes start dominating *is* the
per-category cutoff — replacing the single `RELEVANCE_CUTOFF = 0.0` placeholder with
`{history: x, nature: y, scenic: z, cafe: …}`. The doc already predicts these will differ
(~2.0 defensible on dense, aggressive on sparse — `ranking-quality-report.md` finding 7). This
is the cheapest, highest-value use and needs only dozens of labels. **Do first, post-trip.**

**7.2 (Hundreds of votes) Learned weights via logistic regression.** The rating row carries
the *decomposed* score (`prior`, `fame`, `off_km`) precisely so you can regress 👍/👎 on the
components and recover the weights that actually predict rider approval — is `−1.2·offKm` too
harsh for landmarks (the test says yes, finding 4)? Is `fame` over- or under-weighted per
category? A logistic regression on a few hundred labels answers it empirically instead of by
the current hand-picked constants. Keep it interpretable (a linear model the owner can read),
not a black box — trust is the product. **Research-more until the label count justifies it;
the schema is already right for it.**

**7.3 (Ongoing) Active learning — spend labels where they're worth most.** The cutoff is set
deliberately **lenient** now (index.ts:100) *so that marginal places get shown and rated* —
that is active learning by design (show the uncertain ones to get labels on them). Formalize
it: keep surfacing a small fraction of *near-threshold* candidates even after the cutoff
tightens, so the boundary stays calibrated as the corpus and the world drift. Flag these
internally so their downvotes don't over-penalize (they're exploration, not the product's
best foot forward).

**7.4 Guard against the loop's failure modes** (state them now so they're not learned the hard
way, à la the CLAUDE.md "lessons"):
- **Selection bias:** you only get labels on places you *showed*. The regression can't learn
  that a place you always drop was actually good — hence 7.3's exploration and §8's seed lists
  as an outside check.
- **Popularity ≠ approval:** a rider may 👎 a famous-but-crowded landmark they didn't want on a
  fast day. That's *intent* (§1), not a ranking error — segment the analysis by any intent
  signal you have (route length, pace) before concluding fame is mis-weighted.
- **Per-device taste:** `device_id` is on every row — enough to spot a single opinionated
  rider skewing a thin dataset, not enough (yet) for personalization. Aggregate, but watch
  distinct-device counts (the web-app doc found *2 devices* total today).

**The trip's job:** ride a real route with the lenient cutoff, rate liberally, and come home
with the first few dozen labels that turn 7.1 from a guess into a number. Pre-trip, the only
ranking work that matters is *not breaking* the label harvest (§9).

---

## 8. Novel angles the prior docs missed

**8.1 LLM-as-judge for interestingness — now affordable, use it as a re-ranker.**
`waybook-ranking.md` §4 rejected LLM interestingness scoring as "more expensive than the web
search it feeds" — but that costed it at *Sonnet web-search* rates. The model-cost doc since
validated **Gemini flash-lite at $0.25/$1.50 per MTok** for the batched paragraph pass
(`waybook-model-cost-optimization.md` §2). A *single batched judge call* over the ~60-candidate
shortlist — name + hook + salient tags + town, ~40 tokens each → ~2.5k in / ~0.6k out — costs
**~$0.0015 per build**, cheaper than one web-searched place. That changes the verdict.

The honest framing that makes it safe: **it re-ranks real candidates, it never invents.** The
judge picks among places OSM already found — "of these 60, which 40 would a curious cyclist
most want to know about, and flag any that are dull/off-theme." It's choosing, not asserting
facts, so it carries none of the fabrication risk that gates the web-search step. It's the one
tool that can read the fame=0 tail's *tags* the way a human would ("Baroque chapel with a
described fresco" > "attraction"). **Flag: research-more** — validate on the anchor route that
it agrees with the eventual rating data before trusting it; it's the natural thing to
cross-check *against* 7.1's histogram. High potential, needs the labels to prove it.

**8.2 Embeddings for de-duplication and "more like this."** Covered as a forward track
(`waybook-place-types.md` §4, `waybook-web-app.md` §3) — the strategic point to add: the first
payoff isn't recommendation (needs a big corpus + overlap), it's **novelty penalty in
selection** — down-rank a candidate too similar to one already picked, so a route past five
near-identical wayside chapels shows one and moves on. That's a same-catalog operation
(no cross-user corpus needed) and directly attacks clumping from a different angle than
spacing. `gte-small` in-edge is $0 (`waybook-model-cost-optimization.md` §6). **Flag:
research-more, after the corpus write is wired.**

**8.3 Seasonal & time relevance.** A viewpoint at sunset, a waterfall in spring melt, a
Christmas market, a café that's shut Mondays — relevance has a time axis Waybook ignores. Cheap
pieces: use OSM `opening_hours` to **de-emphasize (never hide) a place closed at the rider's
ETA** (ETA is derivable from route position + a nominal pace); tag obviously-seasonal types.
**Flag: research-more, low priority** — a nicety, but a differentiator no free dataset
competitor offers.

**8.4 Detour-worth-it scoring — make Effort real.** Today off-route is a flat `−1.2·offKm`.
Effort is not linear: 500 m flat ≠ 500 m up a 12% ramp, and *what you get* should scale the
tolerance (a rider will climb for Buchlov, not for a bench). Combine the **climb to reach**
(from the route's own elevation vs the POI's `ele`) with the appeal axis: `tolerated_detour =
f(appeal)`. The test found the flat penalty flips landmarks (finding 4). **Decision: build the
fame-capped off-route penalty** (already a test recommendation); **research-more** on the
climb-aware version.

**8.5 Guaranteed-icon seed lists — the product's floor.** Independent of any formula: a small
hand-curated seed table (world-famous climbs + must-see landmarks) proximity-matched to the
corridor, granted a flat score bonus + **exemption from spacing/cutoff/truncation**, so the
one place a rider would be furious to miss can never silently vanish (the Karlštejn failure,
`ranking-quality-report.md` finding 2; the Passo di Giau gap, `waybook-cyclist-places.md`
§2.5). This is the cheapest possible insurance against catastrophic omission and the right
place to spend the project's one bit of editorial effort. **Decision: build** — cols seed from
Wikipedia's highest-paved-roads tables + a hand list; landmarks seed can grow from the ratings
(anything with high fame + consistent 👍 becomes a seed). ~half a day, $0 ongoing.

**8.6 Community curation — via OSM/Wikidata, not a parallel DB.** The open-data-native
contribution path already exists (`waybook-web-app.md` §5): a "spot a mistake? fix it in OSM /
Wikidata" link per place, and Waybook re-syncs. Better than a proprietary write path, and it
improves the base for everyone. The one Waybook-native curation signal worth keeping private is
the rating data itself (§7). **Decision: the footer link; no parallel database.**

---

## 9. Prioritized roadmap

Two horizons. **Pre-trip is calibration season** — its *only* job is to harvest good labels;
resist building ranking cleverness that can't yet be validated. Post-trip exploits the labels.
Effort: S ≈ hours, M ≈ a day or two, L ≈ a week+. Impact and type (**build** = decide now /
**research** = validate first) flagged.

### Before the trip — protect and maximize the label harvest

| # | Item | Effort | Impact | Type |
|---|---|---|---|---|
| 1 | **Fix relation truncation** (split queries so historic/wikidata sights aren't evicted by café budget) — *already done in p8a* (LANDMARK/SIGHT/STOP budgets, index.ts:71); **confirm Karlštejn survives** | S | Critical | build ✓ |
| 2 | **Fame-exempt spacing + capped off-route penalty for landmarks** — *already in p8a* (`FAME_KEEP`, index.ts:81); verify on the anchor route | S | High | build ✓ |
| 3 | **Keep the cutoff lenient (0.0)** so marginal places get shown and rated — *already set* (index.ts:100). Do **not** tighten pre-trip | — | High | build ✓ |
| 4 | **Ship the café tag-richness bonus + `service:bicycle:*`/`route=bicycle`** (spec-complete, `waybook-cyclist-places.md` §1.3) so café ratings carry a *varied* prior to calibrate against | S | Med | build |
| 5 | **Add `mountain_pass`/`saddle` discovery** (§4.5) — opens the category before you ride anything hilly | S | Med | build |
| 6 | **Seed-list backstop for icons** (§8.5) — the anti-embarrassment floor, before dogfooding on a real route | M | High | build |
| 7 | **Verify the rating round-trips end-to-end** (device → `rate` → `waybook_ratings` with score/prior/fame populated) — a dropped field here wastes the whole trip's labels | S | Critical | build |

### After the trip — exploit the labels, phased

| # | Item | Effort | Impact | Type |
|---|---|---|---|---|
| 8 | **Per-category cutoffs from the vote histogram** (§7.1) — the payoff of calibration season | S | High | build |
| 9 | **Cross-source agreement + image-existence terms** (§4.1–4.2) — reuse fetches already made; the biggest fame=0 fix | S–M | High | build |
| 10 | **Adaptive per-segment target density + endpoint declustering** (§6) — makes one product work urban *and* rural | M | High | build |
| 11 | **Split taxonomy (scenic vs utility), carry `subtype`** (§5) — cleaner per-category calibration | S | Med | build |
| 12 | **Logistic-regression weight calibration** on decomposed scores (§7.2) | M | Med–High | research→build when labels suffice |
| 13 | **LLM-as-judge re-ranker on the shortlist**, cross-checked vs the histogram (§8.1) | M | High-if-it-holds | research |
| 14 | **Overture café/food coverage backfill** (already scoped) — coverage, sequence when convenient | M | Med | build |
| 15 | **Embedding novelty-penalty in selection** (§8.2); then "more like this" | M | Med | research |
| 16 | **Climb-aware detour-worth + ETA/opening-hours time relevance** (§8.3–8.4) | M | Med | research |
| 17 | **Prominence for the nature tail** (§4.3) — DEM lift, nice-to-have | L | Low–Med | research |

**Sequencing logic:** pre-trip items are all either already-shipped confirmations or
same-file additions that *vary the prior* so the ratings have signal to separate (a café that
always scored 1.6 teaches nothing; one that scores 1.6–2.6 by tag-richness teaches the weight).
Post-trip, items 8–9 are the two that turn the trip's labels into product quality at the lowest
effort; 10 is the structural fix that generalizes Waybook past its home region; everything else
waits on either label volume (12, 13) or corpus size (15).

---

## 10. Honest trade-offs & what stays impossible

- **No source ranks the fameless tail** — the ✅ table (§2) is the whole universe and none of
  it carries taste. §4's signals are proxies for *care and corroboration*, not beauty; the
  ceiling is "surfaces places that someone bothered with," not "surfaces beloved places."
- **The rating loop can only judge what it shows** (§7.4) — seed lists and exploration are the
  outside check, but a place systematically never-surfaced is invisible to calibration
  forever. Lenience now is the hedge.
- **LLM-as-judge is a re-ranker, never a fact source** — the moment it's asked to *assert*
  rather than *choose*, it re-enters the fabrication-risk zone the web-search step is fenced
  against. Keep it on the selection side of the two-register line.
- **Intent-awareness is deferred, not free** — the axes (§1) make it a packaging job, but v1
  ships one default; a rider on a fast day still gets the sightseeing catalog until the Pro
  filters exist.
- **The whole effort serves hundreds-to-low-thousands of riders** (`waybook-monetization.md`
  §2.3) — calibrate the *build* effort to that. The pre-trip list is hours; the post-trip list
  is opportunistic. This doc argues for **more signal from owned data + one good calibration
  cycle**, precisely because that's the high-return-per-hour path for a one-person project, and
  chasing new sources is not.

---

### Sources (new verifications 2026-07-18; prior-doc verifications cited, not re-run)

- Pipeline: `waybook/backend/supabase/functions/discover/index.ts` (`p8a`) — buildQuery :367,
  classify :477, scoring/prior :2745 + HOOK_BASE :104, fameStage :1588, cutoff/selection
  :2770–2820, budgets :71, FAME_KEEP :81, RELEVANCE_CUTOFF :100, GEOPHOTO :116, MAPILLARY :125.
- Rating loop: `functions/rate/index.ts`; `migrations/20260723_waybook_ratings.sql`
  (append-only, carries score/prior/fame/off_km/route_hash); `waybook_passes` snapshot
  (`20260724`).
- ⊕ OpenTripMap — dev.opentripmap.org/product (data ODbL; paid/RapidAPI access; `rate` field
  derived from wikidata/wikipedia/OSM presence).
- ⊕ GeoNames — geonames.org/export (CC BY 4.0; bulk dumps; population + 645 feature codes);
  wiki.creativecommons.org/wiki/GeoNames.
- Prior docs (settled verifications): `waybook-ranking.md` (fame formula, QRank/Nominatim
  rejected, cutoff), `waybook-place-types.md` (Type ladder, OSM tag coverage, Overture),
  `waybook-coverage-photos.md` (Commons geosearch, Mapillary §11, Flickr/Openverse out),
  `waybook-cyclist-places.md` (café tag-richness, cols seed, Strava/Komoot/scrape targets out),
  `waybook-google-places-discovery.md` (Google out, Overture backfill),
  `waybook-monetization.md` (cost ceiling, market size), `waybook-model-cost-optimization.md`
  (Gemini flash-lite economics, gte-small), `waybook-web-app.md` (corpus reality, OSM
  contribution path), `ranking-quality-report.md` (5-route cold test: truncation bug, spacing
  collapse, off-route flip, fame=0 tail, cutoff calibration).
