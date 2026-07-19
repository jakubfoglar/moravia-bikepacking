# Waybook — cyclist-favorite cafés and iconic cycling places

*2026-07-18. Design/research. Builds on `waybook-google-places-discovery.md` (Google/Mapy
killed on caching grounds; Overture flagged as café-coverage candidate),
`waybook-coverage-photos.md` (Flickr killed on caching grounds; the cache-legality test
this doc reuses), `waybook-place-types.md` (café tag coverage baseline, the OSM-tag-prior
scoring hook), and `waybook-ranking.md` (the fame formula — Wikidata sitelinks + Wikipedia
pageviews — and why it's 0 for cafés/lesser places). Grounded in the shipping pipeline
`waybook/backend/supabase/functions/discover/index.ts`. All ToS/license claims verified
live 2026-07-18, cited inline. No app code changed by this doc.*

**The constraint, restated:** Waybook fetches once at build time and caches forever on the
device. A signal is usable only if its *terms*, not just its license, permit permanent
storage. Google Places, Mapy.com, and Flickr are already out on exactly this test — this
doc applies the same test to eight more candidates and finds the same pattern hits five of
them: **rich, tempting live data (ratings, popularity, curated tips) sits behind ToS that
forbid caching it**, full stop, regardless of price or a generous free tier.

---

## Question A — café quality / cyclist-favorite / good-vibes signal

**Verdict up front: no external source gives Waybook a legally cacheable popularity or
vibe signal for cafés.** Every candidate that actually carries one (Strava, Komoot, live
Foursquare, Yelp, Tripadvisor) is caching-barred or has no legal ingestion path at all. The
two open POI datasets that *are* cacheable (Overture, FSQ OS Places) are coverage
backfills only — confirmed, schema-level, to carry **zero** rating/popularity/visit
fields. The one real, free, legally clean quality proxy is **OSM tag richness**, which the
pipeline already fetches and currently throws away for cafés. Ship that.

### 1. Candidate table — cache-legality first

| Source | Cache-legality verdict (basis) | Carries a quality/popularity signal? | Coverage rural CEE |
|---|---|---|---|
| **Overture Maps Places** | ✅ cacheable — CDLA-Permissive-2.0 | **No.** `confidence` is existence-certainty, not quality (see §1.1) | Global, Meta-sourced; unverified at CZ granularity |
| **FSQ OS Places** | ✅ cacheable — Apache-2.0 | **No.** Schema is pure venue metadata (see §1.2) | 100M+ POIs / 200+ countries; unverified at CZ granularity |
| **OSM tag richness** | ✅ cacheable — ODbL, ours already | **Proxy only** — care/amenity signal, not taste (see §1.3) | Whatever OSM has; already Waybook's base layer |
| **Strava** (segments/heatmap) | ❌ **OUT** — API Policy §5.5 "No Persistent Index"; §6.2 caps any cache at 7 days; §5.4 bars even *aggregated/de-identified* analytics use | Would be excellent (ride-through frequency) but unreachable | n/a |
| **Komoot Highlights** | ❌ **OUT** — no public API exists (partner-only, `komoot.com/b2b/api`); ToS bars exporting/distributing tour data beyond the app's own export function | Would be excellent (user-tagged cyclist tips) but no ingestion path, live or cached | n/a |
| **Foursquare Places API** (live, commercial) | ❌ **OUT** — AUP: "Extract or store any Foursquare Services for the purpose of creating or enhancing a database…"; bars "systematic query… to obtain all or substantially all data for a locality" | Yes, live only (ratings/tips) — unusable for an offline-preload product | n/a |
| **Yelp Fusion** | ❌ **OUT** — API ToS: cache/store cap of 24 h, exception only for business IDs | Yes, live only | Weak — Yelp is US-centric |
| **Tripadvisor Content API** | ❌ **OUT** — Caching Policy: only `location_id` cacheable indefinitely; ratings bars/reviews/everything else must be served live from Tripadvisor URLs | Yes, live only | Weak in rural areas — TA indexes tourist-facing venues |
| **Wikidata / Wikivoyage** | ✅ cacheable (already used) | Near-empty for CEE cafés — confirmed by prior research, not re-litigated here | n/a |

### 1.1 Overture Maps Places — coverage backfill, not a quality signal

Confirmed directly against the live schema (`docs.overturemaps.org/schema/reference/places/place/`,
fetched 2026-07-18): fields are `id`, `geometry`, `operating_status`, `confidence`,
`categories`/`basic_category`/`taxonomy`, `websites[]`, `socials[]`, `emails[]`,
`phones[]`, `brand`, `addresses[]`, `names`. **`confidence` is "a score between 0 and 1
indicating how confident we are that the place exists"** — an existence-match score, the
same shape as a geocoder's match confidence, not a quality or popularity rating. There is
no field for rating, popularity, visit frequency, or reviews. License CDLA-Permissive-2.0
(confirmed in `waybook-google-places-discovery.md`) — permanent caching fine.

**What it's for:** the café/food coverage backfill already recommended in
`waybook-google-places-discovery.md` §5 — closing the "OSM has nothing here" gap, nothing
more. `categories`/`basic_category` distinguishes e.g. a specialty coffee shop from a
generic snack bar in its taxonomy, which is a *weak* categorical proxy (see §1.3) but nothing
beyond what a good `cuisine` tag already gives.

### 1.2 FSQ OS Places — same conclusion, confirmed at the field level

Fetched directly (`docs.foursquare.com/data-products/docs/places-os-data-schema`,
`opensource.foursquare.com/os-places/`, 2026-07-18). The **20+ core attributes** are:
`fsq_place_id`, `name`, `latitude`/`longitude`, `address`, `locality`, `region`,
`postcode`, `country`, `admin_region`, `post_town`, `po_box`, `date_created`,
`date_refreshed`, `date_closed`, `tel`, `website`, `email`, `facebook_id`, `instagram`,
`twitter`, `fsq_category_ids`, `fsq_category_labels`, `placemaker_url`,
`unresolved_flags`. **No rating, popularity, check-in, or tip field exists** — Foursquare
stripped those out of the open release; they live only in the paid live Places API (which
carries its own caching prohibitions, not evaluated here since it's a commercial product
outside a hobby OSS budget). License **Apache-2.0** (confirmed, incl. the file-level
`Copyright 2024 Foursquare Labs, Inc.` notice) — permanent caching, redistribution, and
derived use all clean. Distribution: bulk Parquet via S3 / Hugging Face
(`foursquare/fsq-os-places`) / AWS Registry of Open Data — same DuckDB-over-Parquet
ingestion shape already planned for Overture.

**Verdict:** legally the cleanest possible source (Apache-2.0, no attribution burden even),
and a genuine second coverage-backfill option alongside Overture. Not a quality signal.
Country-level CEE coverage numbers were not published anywhere findable — before relying
on it, run the same kind of spot-check spike `waybook-coverage-photos.md` §6 used for
Commons/Mapillary: query the CZ bbox against the anchor route's known café list and count
hits. Given Overture already ingests FSQ as one of its four source providers, the two
datasets substantially overlap for coverage purposes — **pick one, not both**; Overture is
the better pick since it also merges Meta/Microsoft/AllThePlaces sources for wider net
coverage in one file format Waybook is already planning to ingest.

### 1.3 OSM tag richness — the only real, free, legally clean quality proxy

taginfo counts fetched live 2026-07-18. Base: **`amenity=cafe` = 640,737 objects
worldwide.** Co-occurrence with quality-adjacent tags:

| Tag | Co-occurs with `amenity=cafe` | Reading |
|---|---|---|
| `outdoor_seating=yes` | 12.7% (81,525) | cared-for, has a terrace — good cyclist-stop signal |
| `cuisine=coffee_shop` | 15.9% (101,553) | specialty-coffee self-identification, vs. bare `amenity=cafe` |
| `opening_hours` | 24.8% (159,190) | actively maintained listing |
| `website` | 14.6% (93,546) | real, findable business (also enables the venue-website enrichment already in the pipeline, `waybook-place-types.md` §2.2/owner-priority) |
| `internet_access` (any) | 10.2% (65,389); `=wlan` 6.8% (43,444) | amenity for a touring stop |
| `indoor_seating` | 6.4% (40,857) | thinner but useful where present |
| `wheelchair` | 10.4% (66,897) | tagging diligence proxy, minor |
| `brand` present | 10.9% (69,498) | **negative** for "local favorite" — usually a chain (Costa, McCafé, Starbucks) |

**Cyclist-specific tags** (global counts, not café-scoped — these attach to any amenity, but
are rare and strong when they do land on a café): `service:bicycle:pump` 23,548,
`service:bicycle:repair` 19,847, `service:bicycle:retail` 13,387. Low absolute numbers
mean this fires rarely, but when a café *does* carry `service:bicycle:pump=yes` or sits on
a `route=bicycle` relation as a named waypoint, that is a direct, human-placed "this place
serves cyclists" signal — the closest thing to Komoot Highlights that's actually legal to
use.

**Proposed scoring bump** (deterministic, additive, capped — same shape as the existing
`fameStage` cap logic in `waybook-ranking.md`):

```
tagBonus =   0.3  if outdoor_seating=yes
           + 0.2  if cuisine=coffee_shop  (specialty signal, vs generic amenity=cafe)
           + 0.2  if internet_access is set
           + 0.1  if website is set        (also unlocks venue-website enrichment)
           + 0.5  if any service:bicycle:* tag is present
           + 0.2  if node/way is a member of a named route=bicycle relation
           − 0.2  if brand is set to a known non-local chain
capped to [0, 1.0], added to the existing OSM-tag prior at classify()/score assignment
```

**Honest limits, stated plainly:** this measures *care and equipment*, not taste. A
diligently-tagged café is more likely to be a real, functioning, cyclist-aware place than a
bare `amenity=cafe` node — but tag richness cannot tell you the coffee is good. The
`service:bicycle:*` tags are the one sub-signal that is literally what was asked for
("cyclists love it") rather than a proxy for it, and they should be weighted accordingly
(the +0.5, the single biggest term). Frame this as "surfacing places that try", not
"surfacing places that are beloved" — the latter genuinely has no free, legal data source
in 2026.

### 1.4 Recommendation — Question A

1. **Ship the OSM tag-richness bump now.** Zero new API calls — Overpass already returns
   every tag on every café element (`buildQuery`); today `classify()` only reads
   `amenity=cafe` and throws the rest away for scoring. This is a same-day change to the
   scoring function at `index.ts:2045`.
2. **Add Overture Places (not FSQ OS Places too — pick one) as the café/food coverage
   backfill**, exactly per `waybook-google-places-discovery.md` §5 — this is a coverage
   fix, not a quality fix, and was already decided; this doc just confirms it carries no
   popularity field so the tag-richness bonus won't apply to Overture-sourced entries
   (they'll default to the un-bumped baseline unless their `categories`/`basic_category`
   value maps to a specialty tier).
3. **Do not build against Strava, Komoot, live Foursquare, Yelp, or Tripadvisor** — real
   signal, no legal path for an offline-cached product. Revisit only if any of them ships
   a bulk-licensed open dataset (none do today).
4. **Cost/effort:** $0, ~half a day (scoring function only; no new discovery query, no new
   secrets, no new stage).

---

## Question B — iconic cycling places (cols, climbs, cyclist landmarks)

**Verdict up front: the existing fame score already nails the world-famous climbs — it's
the *cyclist-famous-but-not-Wikipedia-famous* tier that's invisible, and there is no open
dataset that fixes it. The pragmatic, and only clean, answer is a small hand-authored seed
list (Wikipedia's highest-paved-roads tables as the backbone + Grand Tour climb history)
cross-matched to OSM, shipped as a flat score bonus.** The three obvious scrape targets
(cyclingcols, climbfinder, salite) are all explicitly closed by their own terms —
confirmed directly against each site's robots.txt/ToS, not assumed.

### 2.1 What fame already covers — measured, not assumed

Pulled the live Wikimedia pageviews API (same endpoint `fameStage` uses,
`wikimedia.org/api/rest_v1/metrics/pageviews/per-article`, en.wikipedia, 12 months
trailing 2026-07-18) for a spread of climbs:

| Climb | 12-mo en.wikipedia views | `fame` (formula: `min(3.0, 0.8·log10(views+1))`) | Already covered? |
|---|---|---|---|
| Mont Ventoux | 131,107 | **3.0 (capped)** | yes — comfortably |
| Stelvio Pass | 103,323 | **3.0 (capped)** | yes — comfortably |
| Alpe d'Huez | 65,843 | **3.0 (capped)** | yes — comfortably |
| Sella Pass | 9,492 | 2.4 | yes — solid |
| Colle delle Finestre | 5,783 | 2.3 | marginal — just above `MIN_SIGHT_SCORE 2.2` after `prior` |
| **Passo di Giau** | **196** | **1.84** | **no** — under threshold, likely dropped |

Passo di Giau is not obscure to a cyclist — it's a repeat Giro d'Italia summit finish and
one of the most photographed, most-revered climbs in the Dolomites, ranked top-tier on
every cycling-climb site. Its English Wikipedia article gets **196 views/year** — the fame
formula (correctly, for its purpose) can't distinguish it from a random 200-view stub. This
is the precise, quantified shape of the gap: **the fame signal tracks general/tourist
renown, not cycling subculture renown**, and there is no free dataset that tracks the
latter directly (Strava/Komoot would, and both are caching-barred — see §2.3).

**Conclusion:** don't touch the fame formula for the top tier — it already works. The gap
is narrow (cyclist-iconic, tourist-obscure climbs) and needs a targeted patch, not a new
general-purpose signal.

### 2.2 OSM tags for cols/passes — a real, underused discoverable category

taginfo counts, fetched live 2026-07-18:

- **`mountain_pass=yes`: 37,571 objects worldwide** (37,361 nodes). Marks the literal apex
  point of a road/path/railway crossing a ridge — the correct tag for "here is a rideable
  pass."
- **`natural=saddle`: 79,243 objects worldwide** (79,176 nodes). The topographic feature
  itself — broader and more numerous, but many saddles carry no road at all (hiking-only
  cols), so it over-includes for a cycling app on its own.
- Per the OSM wiki (`Key:mountain_pass`): a single saddle can have several
  `mountain_pass=yes` nodes (one per way crossing it), and the tagged point isn't always
  exactly the topographic saddle point — so discovery should query **both** tags and
  de-duplicate by proximity + name, the same pattern the pipeline already uses for
  Wikidata Tier B matching (`enrichStage`, distance + `similarity()`).
- **Cycling-route context is a free bonus signal already sitting in OSM**: a pass that is
  a via-point of a named `route=bicycle` relation (a EuroVelo leg, a national numbered
  cycle route) has been curated as cyclist-relevant by the OSM cycling-route mapping
  community — a legitimate, free, zero-cost score bump, same shape as the café
  `service:bicycle:*` bonus in §1.3.
- **This slots into the existing "nature" category, not a new one.** `classify()`
  (`waybook-place-types.md` §0) already maps `tourism=viewpoint`, `natural=peak`,
  `natural=waterfall` to `nature`; adding `mountain_pass=yes` / (`natural=saddle` +
  named + road-adjacent) with hook `"Pass"`/`"Col"` and an `ele` readout is a same-shaped,
  same-file change.

### 2.3 Strava segments / Komoot Highlights, through the cache-legality lens (repeat)

Same verdicts as §1's table, restated for the climbs use case because the *product fit*
argument is even stronger here and the *legal* answer is unchanged:

- **Strava**: segment "times ridden"/KOM/heatmap density would be an excellent
  cyclist-fame signal for a col — but API Policy §5.5 ("No Persistent Index") and §5.4
  (bars even *aggregated, de-identified* analytics use of Strava Data) block not just
  caching the raw data but deriving and storing an aggregate popularity score from it.
  There is no live-only workaround either, since Waybook's whole architecture is
  preload-then-offline — a Karoo on a Moravian back road has no connectivity to make a
  live Strava call mid-ride. **OUT, same as §1.**
- **Komoot Highlights**: exactly the right content shape (user-tagged points with tips,
  frequently sitting on or near famous climbs) and exactly no legal ingestion path — no
  public API, ToS bars redistributing tour/highlight data beyond Komoot's own export
  button. **OUT, same as §1.**

### 2.4 The scrape targets — cyclingcols.com, climbfinder.com, salite.ch

Checked each site's actual robots.txt and terms directly (fetched 2026-07-18), not
inferred:

| Site | robots.txt | ToS | Verdict |
|---|---|---|---|
| **cyclingcols.com** | Ships a `Content-Signal:` block (an emerging robots.txt extension) declaring `search=yes, ai-train=no, use=reference` — but the file's *final* rule group is a blanket `User-agent: * / Disallow: /`, closing crawling outright. Both blocks are preceded by an explicit statement that they are asserted as **"express reservations of rights under Article 4 of [EU DSM Directive] 2019/790"** — i.e. a *machine-readable TDM opt-out*, the exact form the Hamburg court (cited already in `waybook-place-types.md` §3) held is what makes a TDM reservation legally binding, unlike a plain-text ToS claim. | n/a — robots.txt alone settles it | ❌ **OUT** — one of the cleanest, most explicit "no" signals found in this whole research thread |
| **climbfinder.com** | Permissive for generic crawlers (only `/dashboard/` disallowed; AI-specific bots individually blocked, generic UA not touched) | Explicit: *"not permitted to copy, distribute, or make public information from the Services… except for personal use."* | ❌ **OUT** — robots.txt permissiveness is irrelevant once the ToS bars redistribution; a hobby project shipping a derived open POI list *is* "making public" |
| **salite.ch** | No robots.txt (404 — silent, not a grant) | Footer: *"Copyright © 1999-2026 salite.ch — Tutti i diritti riservati"* (all rights reserved), no visible open-data statement or API | ❌ **OUT** — default copyright + likely EU sui generis database right on a 25-year, 12,000+-climb curated compilation |

**None of the three offers a public API or an open license.** This category has no legal
shortcut — the only content there is worth having (curated difficulty ratings, per-climb
essays, "why this col matters") is precisely the proprietary, hand-curated value these
sites sell, and it stays theirs.

### 2.5 A curated open seed list — the pragmatic answer, confirmed feasible

Two ingredients, both legally clean, combined into one small table:

1. **Wikipedia's "List of highest paved roads in Europe" and "…by country"**
   (en.wikipedia.org) — editor-maintained tables of ~100+ major European passes with
   country, elevation, and notes on which Grand Tours have used them, already gated at
   ≥2,000 m / ≥1 km so it's pre-filtered to genuinely significant climbs. **CC BY-SA
   4.0**, the identical license posture as every other Wikipedia/Wikivoyage table Waybook
   already ingests (attribute, cache forever). Directly machine-extractable via the
   Wikipedia API or a dump.
2. **A hand-authored addition list** the operator writes once: every recurring Hors
   Catégorie / Category-1 Grand Tour summit finish (Passo di Giau included), sourced from
   **facts** — climb name, country, coordinates, which race/year used it — pulled from
   primary results (official Grand Tour sites, Wikipedia race-stage articles) or an
   open-licensed race-results dataset (e.g. `jenslemb/cyclingdata` /
   `thomascamminady/LeTourDataSet` on GitHub — verify each repo's stated license before
   use; these are results/stage facts, not cyclingcols-style curated essays, so the
   copyright exposure is categorically different even before checking). **Not** copied
   prose or difficulty scores from cyclingcols/climbfinder/salite — names, years, and
   coordinates are facts, uncopyrightable, and obtainable independently. Add regional CEE
   passes the operator already knows firsthand (Šumava/Krkonoše/Beskydy) for future
   Waybook routes outside the Alps.

**Mechanics:** a static seed table (name, country, lat/lon, optional QID) shipped in the
repo or a small Supabase table, ~100–150 rows, refreshed rarely (cols don't get built or
demolished — no scheduled scrape needed, unlike the monthly Wikivoyage-listings job).
Proximity + name-match it against `mountain_pass=yes`/`natural=saddle` OSM candidates
during discovery (reuse the existing distance + `similarity()` gate). A match gets a **flat
score bonus (e.g. +2.0)** large enough to clear `MIN_SIGHT_SCORE` regardless of what the
pageview-based fame formula says — directly patching the Passo di Giau-shaped hole,
independent of Wikipedia's opinion of the place.

### 2.6 Recommendation — Question B

1. **Add `mountain_pass=yes` / (`natural=saddle` + named) to the Overpass discovery query
   and `classify()`** as a first-class `nature` subtype (hook `"Pass"`) — a same-shaped,
   same-file change to existing code, $0, no new dependency.
2. **Build the curated open seed list** (Wikipedia highest-paved-roads tables + a
   hand-authored Grand Tour HC/Cat-1 climb list, cross-matched to OSM/Wikidata) as a flat
   score bonus that bypasses the pageview-fame gate. This is the one piece of real
   editorial work in this whole doc, and it's the right place to spend it — a col's fame
   doesn't decay or need refreshing the way a café's hours do.
3. **Do not build against Strava or Komoot** (no legal path, see §2.3) and **do not scrape
   cyclingcols/climbfinder/salite** (all three explicitly closed, see §2.4) — including as
   a one-time manual "just eyeball their list for names" move for anything beyond public
   facts (name/location/elevation, which are freely available from OSM/Wikipedia anyway).
4. **Cost/effort:** ~half a day to author and verify a ~100–150-entry seed list + a small
   proximity-join stage; $0 ongoing (no scheduled refresh, no new API secret).

---

## How this slots into the pipeline

All server-side in `waybook/backend/supabase/functions/discover/index.ts`, following the
same stage shapes the ranking and place-types docs already established:

1. **Café tag-richness bump** — extend the OSM-tag-prior scoring function
   (`waybook-ranking.md` calls it out at `index.ts:2045`) with the §1.3 `tagBonus` formula.
   No new discovery query: `buildQuery()`'s Overpass `around` call already returns full
   tags for every `amenity=cafe` element; the bonus reads tags `classify()` currently
   ignores past matching `amenity=cafe` itself.
2. **Overture Places café/food backfill** — exactly the ETL already scoped in
   `waybook-google-places-discovery.md` §5 (bulk GeoParquet via DuckDB, CZ/AT/SK extract,
   merged as a second candidate source pre-classify). This doc adds only the caveat that
   backfilled entries get no tag-richness bonus unless their `categories` value maps to a
   specialty tier — worth a small `OVERTURE_CATEGORY_BONUS` lookup table, deferred to the
   implementation PR.
3. **Pass/col discovery** — add `mountain_pass=yes` and `natural=saddle`+`name` to the
   Overpass tag set in `buildQuery()`; extend `classify()`'s `nature` branch with hook
   `"Pass"`, `ele`-based subtitle (reusing the existing peak `· ele m` pattern).
4. **Seed-list matcher** — new small stage, structurally identical to the Wikidata Tier B
   matcher in `enrichStage` (distance gate + `similarity()` name match) but against the
   static seed table instead of a live Wikidata geosearch; on match, add the flat score
   bonus and (optionally) mark the entity for guaranteed inclusion even under
   `MAX_POIS`/per-category spacing caps, the way tier-A sights already get priority.
5. **Neither addition needs a new Supabase secret or an LLM call** — bump
   `CATALOG_VERSION` when shipped, same convention as every prior enrichment PR.

Suggested order: **café tag-richness bump first** (smallest, zero new data, immediate
quality lift on the existing café population) → **pass/col OSM discovery** (opens a new
discoverable category) → **seed-list authoring + matcher** (the one task needing real
human time) → **Overture backfill** (already-scoped work from a prior doc, sequence it
whenever that PR lands).

---

### Sources (verified 2026-07-18)

- Pipeline: `waybook/backend/supabase/functions/discover/index.ts` — `buildQuery()`,
  `classify()`, `enrichStage`, prior-score assignment (`waybook-ranking.md` cites
  `index.ts:2045`), `fameStage` formula and `MIN_SIGHT_SCORE` (`waybook-ranking.md`).
- Overture Places schema — docs.overturemaps.org/schema/reference/places/place/ (fields,
  `confidence` definition); license CDLA-Permissive-2.0 per
  `waybook-google-places-discovery.md` §5.
- FSQ OS Places — docs.foursquare.com/data-products/docs/places-os-data-schema (full field
  list); opensource.foursquare.com/os-places/ (Apache-2.0, 100M+/200+ countries,
  distribution channels); huggingface.co/datasets/foursquare/fsq-os-places.
- Strava API Policy — strava.com/legal/api_policy (June 2026): §5.4 (bars aggregated/
  de-identified analytics use), §5.5 ("No Persistent Index"), §6.2 (7-day cache cap), §6.4.
- Komoot — support.komoot.com/hc/en-us/articles/… (no public API, partner-only
  `komoot.com/b2b/api`); komoot.com/terms-of-service (export/distribution ban).
- Foursquare Places API AUP — foursquare.com/legal/enterprise/places-api/aup/ ("extract or
  store… for the purpose of creating or enhancing a database"; anti-bulk-query clause);
  docs.foursquare.com/developer/reference/usage-guidelines-personalization-apis.
- Yelp API Terms of Use — terms.yelp.com/developers/api_terms/20250113_en_us/ (24-hour cache
  cap, business-ID exception).
- Tripadvisor Content API Caching Policy — tripadvisor-content-api.readme.io/reference/
  caching-policy (`location_id` only cacheable indefinitely).
- OSM taginfo (live counts, fetched 2026-07-18) — taginfo.openstreetmap.org: `amenity=cafe`
  (640,737), `outdoor_seating`/`cuisine`/`website`/`internet_access`/`opening_hours`
  co-occurrence with `amenity=cafe`, `service:bicycle:pump`/`repair`/`retail`,
  `mountain_pass=yes` (37,571), `natural=saddle` (79,243).
- OSM wiki — wiki.openstreetmap.org/wiki/Key:mountain_pass (multiple `mountain_pass=yes`
  nodes per saddle; tagged point vs. topographic saddle point).
- Wikimedia pageviews API — wikimedia.org/api/rest_v1/metrics/pageviews/per-article,
  en.wikipedia, 12 months trailing 2026-07-18: Mont Ventoux 131,107; Stelvio Pass 103,323;
  Alpe d'Huez 65,843; Sella Pass 9,492; Colle delle Finestre 5,783; Passo di Giau 196.
- cyclingcols.com/robots.txt — `Content-Signal: search=yes,ai-train=no,use=reference`
  preamble invoking EU DSM Directive 2019/790 Art. 4; trailing blanket
  `User-agent: * / Disallow: /`.
- climbfinder.com/robots.txt and climbfinder.com/en/terms — permissive crawl rules,
  restrictive ToS ("not permitted to copy, distribute, or make public information…
  except for personal use").
- salite.ch — robots.txt absent (404); site footer copyright notice, no open-data/API
  statement found.
- Wikipedia — "List of highest paved roads in Europe" and "…by country" (CC BY-SA 4.0).
- Grand Tour open datasets (license unverified, facts-only use proposed) —
  github.com/jenslemb/cyclingdata, github.com/thomascamminady/LeTourDataSet.
- Legal background carried over — Hamburg court Dec 2025 (machine-readable TDM opt-out
  standard), cited in `waybook-place-types.md` §3.
- Prior docs: `waybook-google-places-discovery.md`, `waybook-coverage-photos.md`,
  `waybook-place-types.md`, `waybook-ranking.md`, `waybook-monetization.md`.
