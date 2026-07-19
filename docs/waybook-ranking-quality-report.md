# Waybook ranking — quality test on 5 iconic routes (2026-07-18)

Method: the P8 ranking is not deployed, so it was ported verbatim into a DB-free harness
(`rank-harness.ts`) and run **cold** against live Overpass + Wikidata + Wikipedia-pageviews
— exactly a first build. Moravia used the real `export-30.gpx`; the other 4 used coarse
polylines through the named waypoints (so their corridors are approximate).

## Headline verdict
The ranking **core works**: Wikipedia-pageview fame reliably floats each route's true icons
to the top. Two things need fixing, one a real bug:
1. **BUG — dense corridors silently drop castle/abbey *relations*** (Karlštejn vanished). The
   `out center 800` cap truncates relations first. High-impact, easy fix, do before the trip.
2. **DESIGN — per-category spacing collapses clusters of distinct famous sights**, and can drop
   the *more* famous of two neighbours (Dürnstein castle, Clos Lucé).

## Per-route

| Route | Icons caught (score, views/yr) | Verdict |
|---|---|---|
| **Loire à Vélo** | Chenonceau 8 (252k), Amboise 8 (153k), Blois 7.6 (77k), Chambord 7.1 (350k) | ★ Excellent — every château on top; cutoff 2.0 drops only 4/69 |
| **Danube Wachau** | Stift Dürnstein 8 (7k), Ruine Hinterhaus 7.5, Stift Melk 6.5 (99k) | ★ Strong, but spacing dropped Burgruine Dürnstein (31k views) |
| **SF Bay** | Golden Gate 7.0 (688k!), Bay Model 7.7, Mt Tam 5.2 (60k) | Good; long fame=0 tail (Mt Tam sub-peaks clump) |
| **Moravia (real GPX)** | Velehrad monastery 7.5, Janův hrad 6.8, Buchlovice 6.5 | Good; Lednice château correctly off-corridor (Janův hrad in) |
| **Prague→Karlštejn** | — Karlštejn Castle **MISSING** — top is Prague museums | ✗ Exposed the truncation bug |

## Findings

**1. Fame is doing real work.** Local-language Wikipedia is essential — Chambord 350k (fr),
Burgruine Dürnstein 31k (de), Golden Gate 688k (en). The truly-famous separate cleanly from
generic POIs on all 5 routes. Summing local+en was the right call.

**2. BUG: relation truncation on dense corridors.** `NODE_BUDGET = 800` + `out center 800`.
Overpass prints nodes, then ways, then relations; a cap hit truncates relations first.
Karlštejn Castle (OSM relation 6706848, on-route, wikidata Q266698, would score ~fame 3) never
entered the 261-candidate set. Any city-edge route can lose its marquee castle/abbey/monastery
this way. **Fix:** give the high-value sight queries (`historic`, monastery, `wikidata`) their
own un-capped `out`, and only budget-cap the café/restaurant/water nodes that actually explode
in cities. (1-line confirm: re-run Karlštejn after splitting the query.)

**3. DESIGN: spacing collapses dense icon clusters — and can drop the *more* famous one.**
Per-category route spacing keeps only the highest-*score* POI in a cluster, but score = fame −
1.2·offKm, and fame **saturates at 3.0** above ~30k views. So among two marquee sights, a
slightly-further but far-more-famous one loses:
- **Wachau:** Burgruine Dürnstein (31k views, 0.26 km off, 7.6) DROPPED for spacing; Stift
  Dürnstein (7k views, 0.06 km off, 8.0) kept. The less-famous one won on off-route.
- **Loire:** Château du Clos Lucé (34k views, Leonardo's house, 0.5 km off) DROPPED for spacing
  next to Château d'Amboise. Riders want both.
**Fix:** exempt high-fame POIs (fame ≥ ~2.5) from spacing suppression — a famous landmark
should never be hidden by a nearby one.

**4. Off-route penalty flips landmarks.** −1.2·offKm is right for a café detour but too strong
for a marquee sight 0.3 km off; it caused the Dürnstein flip. Cap its effect when fame is high,
or rank sights by fame first and use off-route only as a tiebreak.

**5. fame=0 tail (confirms your earlier concern).** Cafés, viewpoints, nature and minor POIs
with no Wikidata are ranked on the OSM prior alone and clump (e.g. Mt Tam West/Middle/Slacker
peaks, all sitelinks=1 → fame 0.5). Only 15–49 of each route's candidates have fame>0. Under
the lenient trip cutoff nothing is hidden, so it's fine for now; the real fix is the
category-specific signals we discussed (elevation/prominence for viewpoints, tag-richness for
cafés) — calibrate post-trip from the thumbs.

**6. No true junk reached the top.** Prior+fame suppress benches/substations well. The
off-theme items (e.g. Prague's Sex Machines Museum, 38k views) are *real* popular attractions,
not noise — fame reflecting real popularity is working as designed; they only look odd because
a city endpoint pulls its whole tourist cluster into a rural-castle ride.

**7. Cutoff calibration.** Lenient (0) is right for the trip. Post-trip, ~2.0 looks defensible
on dense routes (Loire loses 4/69, Wachau 36/128), but is aggressive on sparse ones (Moravia
loses 23/60 = 38%). Don't hard-code — let the thumbs data set it, per-category.

## Recommended changes (priority order)
1. **[bug, pre-trip]** Split the Overpass query so historic/wikidata sights aren't truncated by
   the café-node budget. Re-run Karlštejn to confirm.
2. **[design]** Exempt fame ≥ 2.5 POIs from spacing suppression.
3. **[design]** Cap the off-route penalty's effect on high-fame landmarks.
4. **[post-trip]** Category-specific signals for the fame=0 tail; calibrate cutoff (~2.0) from ratings.
