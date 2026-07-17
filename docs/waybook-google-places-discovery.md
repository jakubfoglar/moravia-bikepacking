# Waybook — Google Places as a discovery-only source: feasibility

**Question:** Can Waybook use Google Places API *only for discovery* ("there is a place around here"), do all enrichment from free sources (OSM/Wikidata/Wikipedia), never store anything Google-derived — and thereby get Google's superior café/shop coverage while staying ToS-compliant?

**Verdict: NO — not worth it, and not cleanly compliant even in fully-transient form.** Three independent clause groups in the *current* Google Maps Platform Terms (last modified **June 23, 2026**) and Service Specific Terms (last modified **June 10, 2026**) block or taint the scheme, and the economics are terrible because discovery results can't be cached: every build pays Google full price, forever, with none of the corridor-cache leverage that makes the Overpass pipeline free. The café-coverage gap is closed compliantly and at $0 by **Overture Maps Places** (75M+ POIs, 58.9M from Meta — i.e. Facebook business listings — CDLA-Permissive-2.0, bulk parquet, cacheable forever). All clauses below verified against the live documents on 2026-07-17.

---

## 1. Is transient, discovery-only use legal?

### What may be persisted — the complete list
The blanket rule is GMP Terms **§3.2.3(b) "No Caching"**: *"Customer will not cache Google Maps Content except as expressly permitted under the Maps Service Specific Terms."* The Service Specific Terms grant Places exactly two permissions:

- **SST §3 "Google ID Caching"** — *"Customer may cache the Google ID values from the Services that return such field and allow caching … For example, Customer may cache (a) place_id from Places API …"* — `place_id` indefinitely.
- **SST §14.3 (Places API, Legacy and New) "Caching"** — *"Customer may temporarily cache latitude and longitude values from the Places API for up to 30 consecutive calendar days, after which Customer must delete the cached latitude and longitude values."*

That is all. **"The place's existence + coordinates" IS cached Content**: coordinates are the lat/lng values (30-day cap), and a name or type stored alongside them has *no* caching permission at all — §3.2.3(a) even calls out the exact act: *"Customer will not: … (iii) copy and save business names, addresses, or user reviews."* "Google Maps Content" is defined broadly (GMP Terms §18): *"any content provided through the Services … including … places data (including business listings)."*

### But even zero-persistence use hits three other clauses

**(1) §3.2.3(c) "No Creating Content From Google Maps Content"** — the taint clause, and it is *not about storage*:

> *"Customer will not create content based on Google Maps Content. For example, Customer will not: … (iv) use latitude/longitude values from the Places API as an input for point-in-polygon analysis; …"*

Waybook's discovery-only flow — take Google lat/lng in memory, run corridor filtering and OSM/Wikidata cross-matching on it, emit a catalog record — is precisely "using latitude/longitude values from the Places API as an input" to spatial analysis, and the resulting catalog is "content based on Google Maps Content." Google's own chosen example prohibits the *transient analytical use itself*, independent of whether anything is saved.

**(2) §3.2.3(a) "No Scraping"** — *"Customer will not export, extract, or otherwise scrape Google Maps Content for use outside the Services. For example, Customer will not: (i) pre-fetch, index, store, reshare, or rehost Google Maps Content outside the services; (ii) bulk download … places information …"* A server-side build that sweeps Places along a whole route ahead of the ride, to power an app that then works offline, is pre-fetching/indexing places information for use outside the Services.

**(3) §3.2.3(e) + SST §14.2 "No use with a non-Google map"** — SST §14.2: *"Customer must not use Google Maps Content from the Places API **in conjunction with** a non-Google map."* GMP Terms §3.2.3(e): *"Customer will not (i) display or use Places content on a non-Google Map."* Waybook's entire UI is a non-Google map (the Karoo native map, our own SVG route view). The operative verb is **use**, not display — the discovery pipeline uses Places content in conjunction with a non-Google-map product regardless of what pixels are shown. This one is not grey.

Also lurking: **§3.2.3(d)(iii)** — Customer will not *"use the Google Maps Core Services in a listings or directory service"*. A POI guide is arguably a listings service.

**Answer:** You may persist `place_id` forever and lat/lng ≤30 days — nothing else, not even a name. And even a query-use-discard flow violates (c) by Google's own example, (a)'s pre-fetch/index language, and (e)/§14.2's use-with-non-Google-map ban. Transient discovery-only use is **not** a safe harbor.

## 2. Does re-sourcing against OSM/Wikidata cleanse the cached record?

The hoped-for line — "the stored bytes are all OSM/Wikidata, so nothing Google is cached" — addresses only §3.2.3(b). It does not address §3.2.3(c): the *selection* of which entities enter the permanent catalog (its composition, the thing that makes it valuable) was created from Google output. A catalog whose membership is Google's Nearby Search result, laundered through coordinate-matching, is functionally a permanently cached copy of that result with re-typed fields — the kind of derived-database construction that (a) "index" and (c) "create content based on" are written to catch.

**Honest assessment:** there is no clean legal line here. The most defensible reading ("each individual stored record contains zero Google-originated data and would be identical if discovered any other way") is colorable for §3.2.3(b) alone, but (c) prohibits the creation step itself and §14.2 prohibits the surrounding product context. Best case this is grey-and-adverse-to-us on the operative text; realistic case it's a violation Google could terminate a key over (GMP Terms give Google broad suspension rights for AUP/misuse). For a hobby-scale project the practical enforcement risk is small — Google polices this mainly at scraping scale — but the design would be *built on* a violation, and the shared server-side corridor cache makes it systematic rather than incidental.

## 3. Attribution

Places policy (developers.google.com/maps/documentation/places/web-service/policies): *"Places API results displayed on a map must be shown on a Google Map"*; *"When displaying Places API data without a Google Map, you must include the Google logo."* Attribution duties attach to **displaying Content** — if genuinely nothing displayed is Google-sourced, no logo is required. But this is moot in both directions: attribution can't legalize the §14.2/§3.2.3(c) problems (displaying Google-derived selections on a non-Google map is banned outright, not merely unattributed), and if any Google-derived signal *does* survive into the UI (existence, ordering, "N places nearby"), you'd owe attribution for content you're simultaneously forbidden to show there.

## 4. Cost, and does it even help?

### Current pricing (developers.google.com/maps/billing-and-pricing/pricing, fetched 2026-07-17)
| SKU | Price /1,000 (first 100k) | Monthly free cap |
|---|---|---|
| Places API **Nearby Search Pro** | **$32.00** | 5,000 |
| Places API **Text Search Pro** | **$32.00** | 5,000 |
| Text Search Essentials (IDs Only) | $0 | unlimited |
| Place Details Essentials (location, types, address — **no name**) | $5.00 | 10,000 |
| Place Details **Pro** (adds displayName, primaryType) | $17.00 | 5,000 |
| Place Details Photos (Enterprise) | $7.00 | 1,000 |

Volume tiers decline ($32 → $25.60 → $19.20 → $9.60 above 100k/500k/1M), but see below. Note the free-tier trick doesn't work: *Text Search (IDs Only)* is free-unlimited but returns only IDs; `location` needs Essentials Details ($5/1k) and the **name — required to match against OSM — is a Pro field** ($17/1k), so "free discovery" actually prices like Details Pro.

### Per-build cost with no cache leverage
Because results can't be stored, **every** catalog build re-queries Google — the corridor-hash cache and the permanent per-entity cache (the two things that make Waybook ~free at scale) give zero savings on the Google leg. Nearby Search (New) returns max 20 results per call, so a ~200 km corridor needs on the order of **~60 Pro calls/build** (sample every ~4 km, 2 km radius, 2 category groups). At ~3 new-route builds/user/month:

| Users | Google calls/mo | Google bill/mo | Whole rest of Waybook (LLM, per spec) |
|---|---|---|---|
| 100 | ~18k | **~$420** | ~$12 |
| 1,000 | ~180k | **~$5,200** | ~$69 |
| 10,000 | ~1.8M | **~$31,000** | ~$35 (Haiku+batch) |

The discovery booster would cost 30–900× the entire rest of the product. There is no pricing tier that fixes an architecture where the one thing you pay for is the one thing you're forbidden to cache. (The 2026 "Maps Grounding Lite" / Gemini grounding route is no loophole either: SST §10.2.2 caps caching Grounded Output at 30 days, and §10.3.1 forbids *"attempt[ing] to extract or otherwise separate Google Maps Content from the Grounded Output."*)

### Does the benefit even exist for THIS product?
Waybook's headline is *stories about interesting places* — landmarks, which OSM+Wikidata cover well (anything with a Wikipedia article is discoverable via Overpass `wikidata` tags). Google's genuine edge is commercial POIs — but a café that exists in Google and not in OSM almost never has a Wikidata/Wikipedia entry either, so after re-sourcing it enriches to **tier C/D at best: a nameless dot** (we can't legally keep even its Google name). And Google's actually-valuable café data — hours, ratings, photos — is exactly what we could never store or display. The scheme buys, at $32/1k per build, the *existence* of places we then can't say anything about.

## 5. The compliant alternative that closes the café gap

**Overture Maps Places** (docs.overturemaps.org/guides/places/): 75M+ POI records — **Meta 58.9M** (Facebook business listings: precisely the cafés/shops/restaurants OSM misses), Microsoft 7.4M, Foursquare 6.6M, AllThePlaces 1.9M. Licenses CDLA-Permissive-2.0 / Apache-2.0 / CC0 — **permanent caching, redistribution, and derived databases all fine with attribution**. Distributed as bulk GeoParquet on S3/Azure; monthly releases; query the CZ/AT/SK extract with DuckDB into the existing Supabase pipeline as a second candidate source next to Overpass. (Standalone Foursquare OS Places, Apache-2.0, is the subset option.) This delivers Google-adjacent commercial coverage at $0 marginal, cache-forever, no taint — already flagged in `guidebook-extension-research.md` §3 as the amenity backfill.

**Recommendation:** keep Overpass as the free base; add an **Overture places backfill** for the café/food register (v1.1, one-time ETL per region). Do not touch Google Places in any tier, including a paid "Pro booster" — the compliant shape of such a booster (live-only, per-session, BYO-key, nothing persisted, shown near a non-Google map ⇒ still §14.2-conflicted) is both legally awkward and product-pointless on a headless bike computer.

---

## Sources (all verified 2026-07-17)
- Google Maps Platform Terms of Service — cloud.google.com/maps-platform/terms (Last modified June 23, 2026): §3.2.3(a)–(g), §18 definition of "Google Maps Content".
- GMP Service Specific Terms — cloud.google.com/maps-platform/terms/maps-service-terms (Last modified June 10, 2026): §3 (Google ID Caching), §13 (Places Aggregate), §14.1–14.3 (Places API), §15 (Places UI Kit), §10 (Maps Grounding Lite).
- Places API policies — developers.google.com/maps/documentation/places/web-service/policies (attribution/display rules).
- Pricing — developers.google.com/maps/billing-and-pricing/pricing (SKU table, March-2025 model: per-SKU free caps 10k/5k/1k Essentials/Pro/Enterprise).
- Place Details (New) field tiers — developers.google.com/maps/documentation/places/web-service/place-details (location=Essentials, displayName=Pro).
- Overture Places — docs.overturemaps.org/guides/places/ (75M records, source mix, licenses).
- Prior analysis: `docs/guidebook-extension-research.md` §2.1 (offline-cache kill), §3 (open-stack table).
