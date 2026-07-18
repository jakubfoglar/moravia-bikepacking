# Waybook public web app — design & decision doc

*Research date: 2026-07-18. Live-data figures below were queried read-only from the
`moravia-ride` Supabase project (`nqtvcxztuuoywznxrxga`) via the Management API. No code
was written; nothing was committed.*

## 0. TL;DR verdict

**Worth building, but smaller and later-shifted than the idea suggests.** The v1 that
earns its keep is a **read-only place explorer** — one server-rendered page per enriched
place plus a map/directory — built inside the existing `site/` Next.js app, reading a new
curated `waybook_places` projection server-side (tables stay deny-all; nothing new is
exposed). Effort: **S/M (~2–4 focused days)**.

The honest catch: **the corpus is currently tiny.** The live DB holds **148 enrichment
rows**, of which only **~86 have a story** and **~56 have a photo gallery** — and some are
dev-test junk (a Spanish cargo aircraft, a hill near Madrid). SEO from ~60–80 quality
pages of 2–3-sentence blurbs is not a growth channel *today*; it becomes one only as the
corpus grows (organically via riders, or deliberately via seeded enrichment runs). Build
the v1 cheap, make every page honest and well-attributed, let the flywheel exist — but
don't expect traffic from it this quarter.

**Route planner:** phase 2, gated hard (every cold build costs real money).
**User-added places:** a trap in v1 — the open-data-native answer is "fix it in OSM /
Wikidata", not a parallel write path. **Biggest risk:** thin content over a tiny corpus —
mitigated by indexing only the good pages and adding the cycling-context data Wikipedia
doesn't have.

---

## 1. What the live data actually looks like (queried 2026-07-18)

| Metric | Value |
|---|---|
| `waybook_enrichment` rows | **148** total |
| — real Wikidata entities (`Q…`) | **119** |
| — web-search fallback rows (`ws:…`) | 29 (17 found a blurb, 12 "nothing found" sentinels) |
| — with a guide blurb | **86** |
| — with a Wikipedia/Wikivoyage extract | 69 |
| — with a P18 image | 94 |
| — with a ≥1-photo gallery | **56** |
| — extract language | 52 cs · 17 en · 50 none |
| — avg blurb length | ~200 chars (2–3 sentences, as designed) |
| — avg extract length | ~410 (cs) / ~520 (en) chars |
| `waybook_catalogs` | **1** stored catalog (7 POIs, ~6 KB) — older ones orphaned by `CATALOG_VERSION` bumps |
| `waybook_venues` | 4 rows (3 ok, 1 with hours) |
| `waybook_usage` / `waybook_logs` | 9 builds, **2 distinct devices** / 277 log rows, 4 devices |
| Postgres extensions | `vector` 0.8.2, `postgis` 3.3.7, `pg_trgm` **available, none installed** |

Three design-shaping facts fall out of this:

1. **`waybook_enrichment` has no coordinates, no canonical name, no category.** It's keyed
   by QID only. Coordinates live in (a) catalog bodies — which are *ephemeral*, orphaned
   on every `CATALOG_VERSION` bump (hence 1 catalog today), and (b) `ws:` cache keys. A
   place page needs name + coords + category, so **a durable place projection is a
   prerequisite** — the enrichment cache alone can't power the site (the `/admin` places
   map already works around exactly this, in `site/app/lib/adminData.ts`).
2. **The corpus contains test noise** (CASA C-212 Aviocar, Cerro de los Ángeles — clearly
   from a dev route near Seville/Madrid). Publishing needs a curation gate, not a
   `SELECT *`.
3. **The corpus grows only when riders build routes** — and there are 2 devices. Left
   organic, coverage compounds slowly. If SEO is a goal in itself, the corpus can be
   **seeded deliberately**: batch-run `discover` over popular cycling routes/regions
   (Moravian wine trails, Elbe path, Danube path…). Enrichment is mostly free API calls +
   one batched Haiku call; the marginal cost per place is fractions of a cent — the
   existing `waybook_usage` accounting would show exactly what a 5,000-place seed costs.

---

## 2. What the web app is (proposed shape)

### v1 — Place explorer (the whole of v1)

- **`/places`** — a directory: the map (reuse the `/admin` Leaflet canvas-renderer
  approach — it's already built for thousands of dots) + a filterable list (category,
  country/region, has-photo). Client-side search is fine at this scale: even 5,000 places
  is a small JSON index; `pg_trgm` can wait.
- **`/places/{slug}-{qid}`** — one page per *published* place:
  - name, town, category, hero photo + gallery (each image credited: author + licence,
    linked to its Commons file page — same data the card already shows),
  - the guide blurb, and beneath it the source extract ("From Wikipedia: …", linked),
  - the honest fact register for tier-C places (`fact_line`, venue hours quoted +
    date-stamped),
  - a small map, **nearby published places** (the internal-linking mesh SEO needs),
    and later "on these routes",
  - a per-page sources footer (see licensing, §3).
- **`sitemap.xml`**, canonical URLs, `Article`/`Place` JSON-LD, OpenGraph images from the
  Commons hero photo.
- **Publish gate:** a page is indexable only if it has a blurb *and* at least one photo
  *and* passes a one-time human/agent curation pass (kills the aircraft). Everything else
  either doesn't get a page or gets `noindex` (visible on the map, not in Google). At
  today's counts that's **~50–70 indexable pages** — be honest about that number.

### Phase 2 — Routes

- **Curated route pages** (`/routes/{slug}`): hand-picked, owner-published routes with the
  track drawn (hairline SVG — the follow-site aesthetic works here), the guide inline, and
  links into place pages. **Not** an automatic gallery of every stored catalog — see
  privacy, §6: a stored catalog is *some rider's actual planned route*; auto-publishing
  the corpus leaks where individual users ride. Publishing is an explicit owner action per
  route.
- **Web planner** ("paste a GPX, get the guide"): reuses `discover?async` + job polling —
  the backend needs almost nothing new. The hard part is **cost abuse**: the
  `x-waybook-token` is a low-stakes gate and a public web form is a free-LLM-spend
  endpoint. Gate it like the web-search fallback already is: cache-hits always free and
  instant; cold builds behind a per-IP limit + a global daily budget row in
  `waybook_counters` + (if abused) an email or Turnstile. This is the single biggest
  operational risk of the planner, and why it isn't v1.

### Phase 3 — Embeddings (`pgvector` is one `create extension` away)

Embed `blurb + extract` per entity (~150 rows today — trivial; cheap even at 100k).
Uses, in value order: "places like this" on place pages (internal linking + engagement),
"more for your route" in the planner, and eventually recommendation of detours the
corridor query didn't select. Genuinely premature before the corpus is ~10× larger —
similarity over 119 places mostly returns "the other Czech castle".

### Not recommended — user-added places (see §5)

---

## 3. The SEO angle — honest assessment

**Why it's better than content-farm SEO:** each page is real, sourced, attributed data —
photo, facts, a story written from retrieved facts only, and *cycling context no generic
source has*: detour distance off real routes, practical stops nearby, "what else is within
5 km of your ride". That last part is the moat. A page that is only a republished
Wikipedia summary will not rank — Google actively demotes Wikipedia mirrors — so the
route-graph context isn't decoration, it's the ranking case.

**Why it's marginal today:** ~50–70 publishable pages of ~200-char blurbs is a thin site.
Realistic search demand for "Buchlov castle cycling"-shaped queries is long-tail and
low-volume; the value is compounding coverage (thousands of small pages each owning a
micro-query), and that requires a corpus 50–100× today's. Two honest paths there:
organic (slow at 2 devices) or **seeded enrichment of famous cycling regions** — which is
cheap, uses the existing pipeline unchanged, and converts the SEO plan from "wait for
users" to "a weekend of batch runs". If SEO matters, seed.

**Relative to the Karoo audience:** the extension's direct audience is intrinsically tiny
(sideloading Karoo owners). The web is the only surface where Waybook's data can meet a
large audience, and every place page is an ad for the extension. So the *strategic* case
is sound; only the *timeline* is slow.

**Do:** index only blurb+photo pages; interlink (nearby places, category/region hubs);
region hub pages ("Places to ride past in South Moravia") which aggregate and are
naturally non-thin; `lastmod` from `fetched_at`.
**Don't:** publish 12 "searched, nothing found" sentinels, tier-D water taps, or
auto-generated pages with no photo — those are the thin pages that drag a small domain
down.

### Licensing for republishing on the web (obligations, with citations)

The extension already complies (per-card sources, `NOTICE.md`); the web must match, plus
one new obligation (share-alike on the *database*):

- **OpenStreetMap → ODbL 1.0** (<https://opendatacommons.org/licenses/odbl/1-0/>,
  <https://www.openstreetmap.org/copyright>). The places directory is a **derivative
  database** of OSM (selection + coords + tags). Publicly using it requires:
  - **attribution** — "© OpenStreetMap contributors" linked to osm.org/copyright,
    site-footer + place-page footer (ODbL §4.2–4.3; OSMF attribution guidelines);
  - **share-alike** — the derived database must be offered under ODbL (§4.4). For a free
    open project this is a feature, not a burden: publish a `/data` page with a periodic
    dump (or API) of the places dataset under ODbL. That satisfies §4.4 explicitly rather
    than arguably.
- **Wikipedia / Wikivoyage text → CC BY-SA 4.0**
  (<https://creativecommons.org/licenses/by-sa/4.0/>). Extracts require attribution —
  link to the source article + the licence (§3(a)). The **LLM blurb is an adaptation of
  the extract**, so under §3(b) the blurb text itself must be offered under CC BY-SA 4.0:
  mark it ("Text adapted from Wikipedia, CC BY-SA 4.0", linked) on the page. This is
  page-footer work, not an architecture problem.
- **Commons photos** — licence varies per image; show author + licence per photo, linked
  to the file page (already fetched into `image_attr` / `photos[].attr`).
- **Wikidata → CC0** — no obligations; attribute anyway for trust.

---

## 4. Architecture

**Extend `site/`** — not a sibling app. One deploy, shared CSS/components with the
marketing page and `/admin` (which already has the Leaflet map, the catalog-parsing code,
and the Supabase server client). Next 14 App Router is fine for this; no framework change
needed.

**Data access — keep deny-all; render server-side.** Do *not* add RLS policies or expose
an anon-readable view. Instead:

1. **New table `waybook_places`** — the curated public projection, written by `discover`
   at enrichment time (and backfilled once from the current catalog + `ws:` keys):
   `qid PK, slug, name, town, country, lat, lon, category, tier, published bool default
   false, updated_at`. This fixes the "no coordinates in enrichment" gap durably —
   catalog bodies stop being the only home of lat/lon, and version bumps stop erasing
   the site's substrate. `published` is the curation gate (flip in `/admin`).
2. **Next.js server components + ISR/SSG** read `waybook_places` ⋈ `waybook_enrichment`
   through the existing service-role server client (`supabaseAdmin.ts`), exactly like
   `/admin` does — but into *static, cached* public pages (`revalidate` ~1 day; the data
   is near-immutable). No Supabase key of any kind reaches the browser; no new attack
   surface; and statically rendered pages are what SEO wants anyway.
3. The map/list get their data as a build-time JSON payload from the same query — no
   client-side DB access at all.

**Search:** client-side over the places JSON in v1; `pg_trgm` + a server route when the
corpus outgrows a single payload (~10k+ places).
**Map at scale:** the `/admin` canvas-renderer pattern (no cluster plugin) is already
sized for thousands of markers; reuse `PlacesMap.tsx` with public styling. Remember the
basemap tiles: OSM's public tile servers have a usage policy — at any real traffic,
switch to a funded tile provider or vector tiles (attribution unchanged).
**Embeddings:** `create extension vector`, an `embedding vector(…)` column on
`waybook_enrichment` (or `waybook_places`), populated in the enrich step; HNSW index when
rows justify it. Phase 3.
**Planner:** the existing `discover?async` job flow, called from a Next server route that
adds the abuse gate (per-IP + `waybook_counters` daily budget) before forwarding.

---

## 5. "Add places" — feasibility: a trap in v1

What it actually costs: an auth system (accounts or abuse-magnet anonymity), a moderation
queue and the ongoing human attention to run it, a write path into the shared permanent
cache (today service-role-only — its integrity is the product's honesty guarantee), spam
defense, and a story for how a web-contributed place reaches the extension (schema
version, `CATALOG_VERSION` interplay) and how a bad one is recalled. That's an L-sized
subsystem serving, today, an audience of 2 devices.

And it's philosophically redundant: **Waybook is open-data-native — the write path
already exists and it's called OpenStreetMap and Wikidata.** A missing café belongs in
OSM; a wrong castle date belongs in Wikidata; Waybook re-syncs and everyone downstream
benefits. That's a *better* contribution story than a proprietary parallel database, and
it costs a "Spot a mistake? Fix it on OpenStreetMap / suggest an edit" footer link per
page.

**Recommendation:** v1 gets that footer plus, at most, a "suggest a correction" form
writing to a *private* moderation table read in `/admin` (S-sized, no auth, no public
writes). Real accounts/curation only if the site ever has real contributors asking for
them — likely tied to route curation ("submit your route"), not places.

---

## 6. Privacy & data separation

**Public, ever:** enrichment content (blurb, extract, photos+credits, fact_line,
sitelinks), the curated `waybook_places` projection, owner-published route tracks,
venue-quoted practicals (attributed + date-stamped).

**Never public:** `waybook_logs` and `waybook_usage` in their entirety (device UUIDs,
build events, error details, cost data), `waybook_jobs`, `waybook_counters`, corridor
hashes joined to anything device-shaped, and — easy to miss — **the stored catalogs as a
browsable set**: each catalog is a real rider's planned route; route start points are
homes. Route pages exist only by explicit owner publication (and consider trimming the
first/last km of any published track, the standard heat-map lesson).

The mechanism making this robust is structural, not procedural: tables stay deny-all,
the public site reads only the curated projection server-side, and telemetry tables are
simply never queried by any public page. `waybook_usage`'s `device` column keeps its
existing `revoke` posture; the `security_invoker` views stay admin-only.

---

## 7. Effort & phasing (honest t-shirt sizes)

| Phase | Contents | Size |
|---|---|---|
| **v1 — Place explorer** | `waybook_places` migration + `discover` write-through + one-time backfill; `/places` map+list; `/places/{slug}` SSG pages; sitemap + JSON-LD; licensing footers + `/data` ODbL dump; curation flag in `/admin` | **S/M — ~2–4 days** |
| **v1.5 — Seeding (optional, if SEO matters)** | batch `discover` runs over chosen regions; curation pass over results | **S** per region + review time; low $ (watch `waybook_usage.est_usd`) |
| **2a — Curated route pages** | owner-publish flag, `/routes/{slug}` with track + inline guide | **S/M** |
| **2b — Web planner** | GPX upload → `discover?async` → in-browser guide; abuse gating | **M**, plus ongoing cost exposure |
| **3 — Embeddings** | pgvector, embed-on-enrich, "places like this", planner recs | **S/M**, valuable only at ~10× corpus |
| **✗ — User contributions** | auth + moderation + shared-cache writes | **L**, not recommended; footer link to OSM instead |

---

## 8. Verdict

**Build v1, small.** The place explorer is cheap (the admin dashboard already contains
half of it), structurally safe (no new data exposure), strategically right (the only
large-audience surface Waybook can have, and each page markets the extension), and it
forces the two pieces of housekeeping the project needs anyway: a durable place
projection with coordinates, and web-grade licensing compliance (including the ODbL
share-alike dump, which the current web-less posture has quietly not owed).

**Highest-value least-effort v1:** curated `waybook_places` + ~50–70 indexable
server-rendered place pages + the map + sitemap + attribution/ODbL-dump footers. No
accounts, no writes, no planner.

**Single biggest risk:** **thin content over a tiny corpus.** 148 rows — 86 blurbs, some
of them test noise, most of them one Czech region — is not an SEO asset yet, and
publishing the weak pages would hurt the domain more than help it. Mitigate by gating
what's indexable, leaning on the cycling-context data that makes pages more than
Wikipedia mirrors, and — if SEO is actually the goal rather than a bonus — budgeting the
seeding runs that grow the corpus deliberately instead of waiting for a 2-device
flywheel to spin.
