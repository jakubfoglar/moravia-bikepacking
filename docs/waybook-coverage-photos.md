# Waybook — coverage & photo sourcing: filling the gaps in the headline feature

*2026-07-18. Design/research. Grounds every claim in the shipping pipeline
(`waybook/backend/supabase/functions/discover/index.ts`, catalog version `p7`) and the
prior docs (`waybook-place-types.md`, `waybook-v1-spec.md`,
`waybook-google-places-discovery.md`, `guidebook-extension-research.md`). All API and
licensing claims verified against the live documents July 2026, cited inline. No app code
changed by this doc — it is the plan the next coverage PR should implement.*

**The question:** photo + paragraph fire on ~78% of landmarks and ~0% of cafés, water
stops, and minor local places (this repo's Moravia anchor: 48% photos overall). The
two-register design is right — *illustrated sights* vs *listed stops* — but the listed
register is visually bare and many genuinely interesting minor places fall through
because they have no Commons image. What raises coverage legally, offline-cacheably,
and for ~$0?

**The verdict, up front:** two photo sources survive the licensing test and are worth
adding — **Wikimedia Commons geosearch** (we are *not* using it today; only Wikidata's
geosearch runs) and **Mapillary** (CC BY-SA 4.0, download-and-rehost explicitly
contemplated by its own terms — the only legal path to a photo of a café). One text
source is worth adding for the listed register: the **Wikivoyage listings join**
(editor-written one-liners with coordinates, CC BY-SA). **Flickr and Openverse are
out** — Flickr not on license grounds but because its API ToS caps caching at ~24 hours
(the same "content fine, channel forbidden" pattern that killed Google and Mapy), and
Openverse has no geographic search at all. KartaView is legal but pointless here.
Everything recommended is $0 in API fees; total build cost stays where it is.

---

## 0. Where coverage stands today (measured, from the code)

The `p7` pipeline already ships a photo ladder and a facts ladder; know them before
adding rungs.

**Photos (sights, tier A/B):** Wikidata **P18** → **Wikipedia media-list** gallery (≤5,
`GALLERY_MAX`, junk-name filter, index.ts:1076–1090) → batched Commons `imageinfo`
thumbs at 320/640 px with per-file credit (index.ts:1036). **P7 added** the OSM
`wikimedia_commons=File:…` tag as a $0 photo for *unresolved* sights (index.ts:1780–1790,
tag parsed at :2071). Photos are **hotlinked from `upload.wikimedia.org`** — zero server
egress, device LRU-caches bytes at preload time.

**Text:** Wikipedia intro + Wikivoyage *lead* (the article lead only, not listings) →
one batched Haiku blurb for A/B; P7 added OSM `description`/`inscription` verbatim, the
Wikidata statement-template fact line, and the guarded venue-website read; tier-W web
search (Sonnet 5, capped) mops up named tier-C/D sights.

**The two live gaps, precisely:**
1. **Photo for anything that doesn't resolve to a wiki entity** — cafés, water, minor
   viewpoints, small chapels. Today: nothing, unless the OSM object happens to carry
   `wikimedia_commons=` (385K objects globally — a sliver).
2. **A sentence for listed stops** beyond raw tags — today only the ~15–20% of venues
   with a working `website` tag get a `venue_note`.

---

## 1. Photo sources — the licensing test first

An image source is useful only if its content can be **stored permanently for offline
use**. That test, applied to every candidate:

| Source | Geo query | Content license | Permanent offline cache | Verdict |
|---|---|---|---|---|
| Commons geosearch | ✅ `list=geosearch` radius | CC per-file (mostly BY/BY-SA) | ✅ same as today's Commons photos | **ADD** |
| Mapillary | ✅ bbox / radius API | **CC BY-SA 4.0** (Terms §3(b)) | ✅ **explicitly contemplated** — "downloading individual images and serving them from your own servers" is allowed with logo+link attribution (Terms §11) | **ADD** |
| KartaView | ✅ bbox API | CC BY-SA 4.0 | ✅ | legal but **SKIP** (coverage) |
| Flickr (CC filter) | ✅ lat/lon/radius | CC per-photo | ❌ **API ToS: no caching beyond a "reasonable period"; developer guide pins it at 24 h** | **OUT** |
| Openverse | ❌ **no geo filter exists** | CC per-item | (moot) | **OUT** |
| OSM `image=` tag | n/a (on the object) | unknown per-URL | only when the URL is wikimedia-hosted | **normalize, else skip** |
| Google / Mapy | — | — | ❌ ToS (prior docs) | out, already decided |

### 1.1 Wikimedia Commons geosearch — the free rung we forgot

Grep the pipeline: the only `list=geosearch` call goes to **wikidata.org**
(index.ts:983–987, entity resolution). We have **never queried Commons itself by
coordinate**. Commons `action=query&generator=geosearch&ggscoord=lat|lon&ggsradius=…&ggsnamespace=6`
returns *files* (namespace 6) geotagged near a point — photos that exist on Commons but
are linked to no Wikidata item and no article. Then the **same** batched `imageinfo`
call we already have (`thumbsFor`, index.ts:1036) yields thumbs + per-file credit.

- **Who it helps:** tier-C/D minor sights — the small chapel, the rozhledna, the weir —
  and *viewpoints especially*: a photo geotagged at a viewpoint is usually the view,
  i.e. exactly the right picture. Some cafés in photographed town squares too.
- **Junk control (the real work):** radius **by type** — 75 m for buildings/monuments,
  150 m for viewpoints/peaks; reuse the existing `NOT_A_PHOTO` filename filter
  (index.ts:1076); prefer files whose SDC `depicts` (P180) or categories mention the
  POI's name/QID when one exists; take max 1–2 files. Accept that a town-square photo
  attached to the wrong café is worse than no photo — when several distinct subjects
  fall in the radius, take nothing.
- **License:** identical posture to every photo we already ship — per-file CC license
  from `extmetadata`, credit line on the card. Files on Commons are by definition
  freely licensed. **$0, hotlinkable, cache-forever.**

### 1.2 Mapillary — the only legal photo of a café (the big unlock)

Meta-owned, still open. Verified July 2026 against `mapillary.com/terms` and
`/developerpolicy`:

- **License:** imagery is **CC BY-SA 4.0** (Terms §3(b): "Your use of any User Content
  provided by other users is subject to the Creative Commons Share Alike license").
- **Caching is not a gray area:** Terms §11 — *"If you are downloading individual
  images and serving them from your own servers, you must attribute the image(s) by
  visibly displaying the Mapillary logo and linking back to the Mapillary homepage or
  corresponding Mapillary image page."* Their own terms describe our exact use and name
  its price: **logo + link**. This is the mirror image of Google/Mapy, where the ToS
  forbids the architecture regardless of license.
- **Developer-policy fit:** apps must "materially supplement" Mapillary, not replicate
  it — a route POI guide plainly qualifies. Only real prohibition to honor: no
  re-identification/unblurring (§5) — we never would.
- **API v4** (`graph.mapillary.com`, free token from the dashboard): query
  `/images?bbox=…&fields=id,thumb_256_url,thumb_1024_url,computed_geometry,compass_angle,captured_at,is_pano,creator`.
  A dedicated radius search (`lat`,`lng`,`radius`) exists but is capped at 50 m — use a
  ~±120 m **bbox** instead. Rate limits are a non-issue at our scale: 60,000/min entity,
  10,000/min search.
- **The one gotcha — thumb URLs expire.** v4 thumbnail URLs carry a TTL. So: cache the
  **image ID** permanently in `waybook_enrichment`, and resolve fresh thumb URLs **at
  catalog-serve time** (one batched entity call, `image_ids=` supports batching); the
  device downloads bytes minutes later during preload and keeps them forever in its LRU
  — legal per §11 + CC BY-SA. No server re-hosting needed (keeps the zero-egress
  property); rehosting into Supabase Storage stays available as a fallback if TTLs ever
  bite.
- **Frame selection (make it not look broken):** within the bbox prefer frames
  `is_pano=false`, newest `captured_at`, and whose `compass_angle` points *toward* the
  POI (bearing from `computed_geometry` to the POI within ±40°); nearest wins ties.
  ~15 lines of math.
- **Register honesty — present it as what it is.** A Mapillary frame is a street-level
  photo *near* the place, not an editorial photograph *of* it. Render it in the listed
  register with a small "Street view" label and the credit
  ("© {creator} / Mapillary, CC BY-SA 4.0", logo+link in the detail footer). It must
  never silently stand in for a Commons photo on an illustrated sight — different
  register, different framing, same never-mislead principle as never-fabricate.
- **Coverage expectation (to validate, §6):** Mapillary's roots are European
  crowdsourcing and cyclists are heavy contributors; paved rural CEE roads are largely
  covered, though capture dates vary. Since virtually all Waybook POIs sit ≤600 m off a
  rideable route, most listed stops should have *some* frame within ~120 m.

### 1.3 KartaView — legal, but skip

Grab-owned, imagery CC BY-SA 4.0, open API. Same legal shape as Mapillary but its
coverage skews Southeast Asia and its API/docs are dated; in rural CEE it is a strict
subset of Mapillary in practice. A second street-level integration buys ~nothing.
Revisit only if Mapillary's terms ever turn.

### 1.4 Flickr — OUT, and not for the reason you'd guess

Flickr's geo search (`flickr.photos.search` with `lat`/`lon`/`radius` + `license=`
filter) is exactly the right API shape, and CC-licensed photos are the right content.
But the **Flickr API Terms of Use** require that you *"not cache or store any Flickr
user photos other than for reasonable periods"*, and the official Developer Guide
defines the practical ceiling: **cache API results and images for up to 24 hours**
(rationale: deleted/privatized photos must disappear from downstream caches within a
day). Waybook's whole architecture is a permanent cache.

Could we argue the photographer's CC license (irrevocable, granted directly to us)
trumps the API contract? For the *bytes*, maybe — but our only acquisition channel is
the API, the ToS is a contract we'd accept, and an open-source project whose README
documents violating its data source's terms is not a position to build on. Same verdict
class as Google/Mapy: **the content is willing, the channel forbids it.** Out.

### 1.5 Openverse — OUT (no geo search)

Openverse aggregates ~800M CC works and its API is pleasant — but the search API
supports **no latitude/longitude filtering whatsoever** (verified against the API docs'
search/filter reference; it's text-query + field filters only). Title-text search for
"Kaple sv. Rocha" might luck into a hit, but coordinate lookup is the product need.
Moreover its two geo-rich upstream sources are Flickr (dead per §1.4) and Wikimedia
Commons (which we query directly, better). A dead end for this use.

### 1.6 OSM `image=*` tag — normalize, don't trust

421K objects carry `image=`. When the URL is wikimedia-hosted
(`commons.wikimedia.org/wiki/File:…` or an `upload.wikimedia.org` path), normalize it
into the **existing** `commonsFile` path (index.ts:365) — free extra coverage on the
rung already built. Any other host: **skip** — unknown copyright, and hotlinking
arbitrary sites is both fragile and legally live post-*Renckhoff* (see
`waybook-place-types.md` §3). A ~10-line normalizer in the tag-parsing block
(index.ts:2071).

---

## 2. Text for the listed-stop register

The place-types doc already routed most of this (and P7 shipped it): OSM
`description`/`inscription` verbatim, the Wikidata statement template, the venue-website
read. One recommended rung remains unbuilt, plus one assessment:

### 2.1 Wikivoyage listings join — real editor prose, $0, unbuilt

Wikivoyage destination articles carry `{{listing}}`/`{{see}}`/`{{do}}`/`{{eat}}`
entries: name, **lat/lon**, and a one-to-two-sentence *human-written* description — for
exactly the minor sights and cafés that have no article of their own. The live API
can't query listings by coordinate, but the
**[wikivoyage-listings](https://github.com/baturin/wikivoyage-listings)** extractor
emits them as CSV/SQL with coordinates from the public dumps. Plan: run the extractor
in a monthly GitHub Action over `en` (+`de`, later `cs`) dumps → load a
`waybook_wv_listings` table (name, lat, lon, type, description, article, lang) → in
`enrichStage`, proximity join (≤150 m + name similarity ≥0.6, reusing the existing
`similarity()` machinery) for POIs that still lack any prose.

- **Register:** attributed description — *"Wikivoyage: '…'"* — in the facts card, or
  as grounding input to the blurb pass for A/B-adjacent cases. Never unattributed.
- **License:** CC BY-SA 3.0 — same as the Wikipedia text we already ship; the existing
  attribution line already names Wikivoyage. Cache-forever.
- **This is the only free source of actual sentences** for the no-wiki tier besides the
  Wikidata template, and it frequently covers cafés/restaurants (`{{eat}}`,
  `{{drink}}`) — the register web search never touches (search is sights-only by
  design, index.ts:1095).

### 2.2 Venue website (P7) — keep, one extension

Already first-class (`venueStage`, index.ts:853; `VENUE` config :96). It is the correct
mechanism and needs no redesign. One extension worth taking: when a Mapillary photo and
a `venue_note` both exist, the listed card is no longer "visually thin" — photo + quoted
self-description + hours + cuisine is a *complete* café card with zero fabrication.
That combination, not any single source, is what closes the register gap.

### 2.3 What stays honestly impossible

Unchanged from `waybook-place-types.md`: **food/interior photos** (venue sites and
platforms are copyright/ToS-barred; Commons has ~nothing), and **bulk venue hours**
beyond OSM ~25% + website reads. A Mapillary facade shot is the ceiling for venue
imagery — and it's a decent ceiling.

---

## 3. The recommended ladders (decision)

**Photo ladder** — first rung that yields wins; rungs 1–3 exist today:

| # | Rung | Register | Status |
|---|---|---|---|
| 1 | Wikidata P18 | illustrated | shipping |
| 2 | Wikipedia media-list gallery | illustrated | shipping |
| 3 | OSM `wikimedia_commons=` (+ **new**: wikimedia-hosted `image=` normalized in) | illustrated | shipping / +10 lines |
| 4 | **Commons geosearch** (typed radius, junk + P180 gates) | illustrated (it *is* a photo of the place) | **ADD** |
| 5 | **Mapillary nearest frame** (bbox ±120 m, bearing-filtered) | **listed — labeled "Street view"** | **ADD** |
| 6 | nothing → category icon | listed | shipping |

**Text ladder for listed stops** (sights' ladder unchanged): OSM tags passthrough
(shipping) → venue website (shipping) → **Wikivoyage listings join (ADD)** → Wikidata
statement template (shipping) → plain honest row.

**Attribution handling, per new source:**

| Source | Card credit | Detail footer | Cache |
|---|---|---|---|
| Commons geosearch | per-file `extmetadata` credit (existing renderer) | existing Commons line | forever |
| Mapillary | "© {creator} / Mapillary · CC BY-SA 4.0" | Mapillary logo + link (Terms §11) | image **ID** forever; thumb URL refreshed at serve; bytes on device forever |
| Wikivoyage listings | "Wikivoyage" prefix on the quoted line | already covered by the CC BY-SA line | forever (monthly refresh) |

Update the `ATTRIBUTION` const (index.ts:180) to add "Street-level photos © Mapillary
contributors (CC BY-SA 4.0)".

## 4. How this slots into the pipeline

All server-side in `discover/index.ts`; device changes are render-only.

1. **`commonsGeosearch()`** — new, called from the enrichment flow where the P7
   `wikimedia_commons` backfill already runs (the §5b block, index.ts:1780): for every
   selected POI with `!photos?.length && !photo_url`, fire the Commons geosearch
   (parallel, existing `jfetch` + `USER_AGENT`), gate, then push files through the
   **existing** `thumbsFor()` 320/640 batch. Cache result on the enrichment row
   (`geo_photo_file`, nullable; `[]`-style "looked, nothing" sentinel like `photos`).
2. **`mapillaryStage()`** — new stage after the venue stage, same shape as it
   (per-place isolation, wall clock, `maxPerBuild` for uncached): POIs *still*
   photoless → bbox query → frame selection → store `mapillary_image_id` + `creator`
   on the enrichment row (or a small `waybook_streetview` table keyed by OSM id for
   never-resolved venues). New secret: `MAPILLARY_TOKEN`.
3. **Serve-time thumb refresh** — where a catalog body is returned (cache hit or
   fresh), collect `mapillary_image_id`s → one batched
   `graph.mapillary.com/images?image_ids=…&fields=thumb_256_url,thumb_1024_url` call →
   stamp fresh URLs into the response. Adds one HTTP round-trip (~200 ms) to cache
   hits that contain street views; skip cleanly on failure (photo degrades to icon —
   never fails a serve).
4. **Wikivoyage join** — monthly CI workflow runs the extractor → truncate-and-load
   `waybook_wv_listings` → one indexed bbox query per build in `enrichStage`, join by
   distance + `similarity()`; write the matched description into the POI's `fact` /
   `wv_note` extra with the attribution prefix.
5. **Device** — render `photos[].kind = "street"` with the label + credit; footer line
   for Mapillary logo/link in the detail Activity. No new fetch paths (URLs arrive in
   the catalog exactly like Commons ones).

Suggested order: **4 (Wikivoyage) and 1 (Commons geosearch) first** — pure $0 adds
inside existing machinery; then **2+3+5 (Mapillary)** as one PR behind a feature flag,
validated by the §6 spike. Bump `CATALOG_VERSION` → `p8`.

## 5. Cost, latency, cache size

- **API fees: $0 across the board.** No new LLM calls (Mapillary/Commons/Wikivoyage
  rungs are deterministic). Second-order saving: every tier-C/D sight the Wikivoyage
  join or Commons geosearch satisfies is one fewer web-search candidate (~$0.06/place,
  the pipeline's only real cost).
- **Cold-build latency:** Commons geosearch + Mapillary run in parallel over ≤~25
  photoless POIs → +2–4 s on a 30–60 s async build; invisible in the progress UX (add
  a "Fetching photos…" tick). Serve-time Mapillary refresh: +~200 ms on affected cache
  hits.
- **Catalog size:** +a URL/credit pair per newly covered POI — a few KB; fine for the
  ≤100 KB Companion budget.
- **Device photo cache:** worst case ~25 new photos × (20 KB thumb + 150 KB detail)
  ≈ **+4 MB per catalog** — noise next to the existing gallery (up to 5 × 640 px per
  sight) and the Karoo's storage; the LRU already bounds it.
- **Server storage:** Mapillary IDs and Wikivoyage rows are text; the full `en`+`de`
  listings extract is on the order of a few hundred MB raw but the *European subset we
  load* fits easily in Supabase Pro (and can be trimmed to the countries we serve).

## 6. Expected coverage lift — and the spike that verifies it

Honest estimates, to be validated before the Mapillary PR merges:

| Register | Today | After | Mechanism |
|---|---|---|---|
| Landmarks (A/B) photo | ~78% | ~85% | Commons geosearch + `image=` normalize catch stragglers |
| Minor sights (C/D) photo | ~0–10% | **~40–60%** | Commons geosearch (viewpoints esp.) + Mapillary |
| Cafés / venues photo | 0% | **~50–80%** street-view | Mapillary (route-adjacent ⇒ usually a frame ≤120 m) |
| Listed stops with a sentence | ~15–20% (venue sites) | **~35–50%** | + Wikivoyage listings |
| Any POI fully bare (no photo, no prose) | ~40% | **~10–15%** | all of the above |

**The spike (half a day, do it first):** this repo already has the ground truth —
`pois_enriched.json`, ~60 hand-reviewed POIs on a real rural-CEE route. Script: for
every POI without a photo today, hit (a) Commons geosearch at the typed radius and
(b) Mapillary bbox with a free token; count usable hits by eyeball. That turns every
"~" in the table above into a number and decides whether Mapillary ships labeled
prominent or as a quiet detail-view extra. Kill criterion: if Mapillary covers <30% of
the anchor route's venues, demote it to detail-view-only and don't build the serve-time
refresh yet.

---

### Sources (verified July 2026)

- Pipeline: `waybook/backend/supabase/functions/discover/index.ts` (catalog `p7`) — photo ladder :1012–1090, :1626–1643, :1780–1790; venue stage :853; web-search config :47–77; Wikidata geosearch (the only geosearch in the code) :983.
- Mapillary Terms of Use — mapillary.com/terms: §3(b) CC BY-SA for user content; §11 download-and-serve-with-logo+link; §5 no re-identification. Developer policy — mapillary.com/developerpolicy: "materially supplement" requirement.
- Mapillary API v4 — mapillary.com/developer/api-documentation: image fields incl. `thumb_*_url`/`compass_angle`/`is_pano`/`creator`; bbox + radius (≤50 m) search; rate limits 60k/min entity, 10k/min search; thumb-URL TTL noted in the v4 migration guidance (help.mapillary.com "Accessing imagery and data through the Mapillary API").
- Flickr — flickr.com/help/terms/api ("shall not cache or store … other than for reasonable periods") + flickr.com/services/developer/api ("cache API results and images for up to 24 hours").
- Openverse — docs.openverse.org API search reference (no geo filter among supported query fields).
- KartaView — kartaview.org / github.com/kartaview (CC BY-SA 4.0); coverage per OSM-wiki and community docs.
- Wikimedia Commons — API:Geosearch (`list=geosearch`, `gsnamespace=6`), API:Imageinfo `extmetadata` (per-file license/credit).
- Wikivoyage listings — github.com/baturin/wikivoyage-listings (extractor; CSV/SQL with lat/lon); Wikivoyage content CC BY-SA 3.0.
- Legal background carried over: CJEU *Renckhoff* C-161/17 (hotlinking), Google/Mapy ToS analysis in `waybook-google-places-discovery.md` and `guidebook-extension-research.md` §2.
- Prior docs: `waybook-place-types.md` (free-ladder design this doc extends), `waybook-v1-spec.md` §Coverage.
