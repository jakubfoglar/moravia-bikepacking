# Waybook — monetization, unit economics & cost tracking

*2026-07-17. Builds on `guidebook-extension-research.md` §4/§7 (the pre-build estimates) — this doc replaces those numbers with **measured** ones from the real P6a/P6b pipeline (`waybook/backend/supabase/functions/discover/index.ts`) and adds the live cost-tracking layer (`waybook_usage`, deployed today). All prices verified July 2026; sources at each claim.*

---

## 1. Real unit economics

### 1.1 What a route build actually costs

The pipeline is: Overpass + Wikidata/Wikipedia/Wikivoyage/Commons (**$0**, cached permanently per entity) → one batched **Haiku 4.5** paragraph call → a capped **Sonnet 5 + web_search** fallback for named sights that resolved to nothing. Photos hotlink from Wikimedia (zero egress). Prices (platform.claude.com/docs/en/about-claude/pricing, fetched 2026-07-17): Haiku 4.5 **$1/$5 per MTok**; Sonnet 5 **$3/$15** (intro $2/$10 through 2026-08-31); web_search tool **$10 per 1,000 searches** + token costs.

| Cost line | Per unit | Per cold build | Evidence |
|---|---|---|---|
| Wiki/OSM/Commons retrieval | $0 | $0 | free APIs, permanent per-entity cache |
| Haiku paragraph pass (one batched call) | ~**$0.0005/new entity** | **~$0.009** | measured benchmark 2026-07-17, 18 real POIs, this project's key (`discover/index.ts` header comment) |
| Web-search fallback (Sonnet 5, effort low, ≤3 searches/place) | **~$0.03–0.06/place** (≈ $0.045: ~3–6K in + ~0.3–0.8K out tokens + 1–3 billed searches à $0.01) | ≤8 places → **$0–0.36**, typ. $0.10–0.25 in fresh areas | code caps: `maxPerBuild: 8`, `maxToolUses: 3`, `max_tokens: 2000`; now measured live per-build in `waybook_usage.ws_*` |
| **Cold build total** | | **~$0.01 (ws off) · ~$0.10–0.35 (ws on)** | |
| **Warm build (corridor or entity cache hit)** | | **$0.00** | corridor-hash cache returns the stored body; enrichment is permanent per QID / per ws-place (found-nothing included) |

The load-bearing asymmetry: **one web-searched place (~$0.045) costs as much as ~90 Haiku paragraphs (~$0.0005)**. When web search is on, it is ~95% of LLM spend. That is *the* lever.

### 1.2 The cap converts spend into a ceiling

`dailyBudget: 40` searched places/UTC-day, global, in `waybook_counters` — and each place is searched **at most once ever** (permanent cache, misses included). So web-search spend has a hard ceiling of **40 × ~$0.045 ≈ $1.80/day ≈ $54/month, independent of user count**. Growth past the cap degrades *coverage* (some C/D sights stay plain rows), never cost. (Confirmed live today: the counter sat at 40/40 and builds silently skipped the stage.)

### 1.3 Monthly model at 100 / 1,000 / 10,000 MAU

Assumptions (stated): 3 builds/user/month; cold-entity fraction falls with corridor overlap — **70% / 40% / 20%** at 100/1k/10k MAU (same curve as the research doc §4.2; Europe's landmark set is finite); ~5 web-search-eligible fresh places per cold build at 100 MAU, falling to ~2 / ~1 by the same cache logic. Supabase: own project at public launch — Free tier (500K function invocations, 500 MB DB) covers low hundreds of MAU; **Pro $25/mo** beyond (also removes the 1-week pause) ([supabase.com/pricing](https://supabase.com/pricing)). The enrichment cache is text + URLs — tiny; photos never touch our egress.

| | 100 MAU | 1,000 MAU | 10,000 MAU |
|---|---|---|---|
| Cold builds/mo | 210 | 1,200 | 6,000 |
| **Haiku tier only (web search OFF)** | **~$2** | **~$11** | **~$54** |
| Web search ON, capped 40/day | +$30–54 (at/near cap) | +$54 (cap; ~80% of demand skipped) | +$54 (cap; ~95% skipped) |
| Web search ON, uncapped (full coverage) | +$47 | +$270 | +$1,350 |
| Supabase | $0 (Free) | $25 | $25 |
| **Total, capped (the shipping config)** | **~$35–55/mo** | **~$90/mo** | **~$135/mo** |
| Total, ws off | ~$2 | ~$36 | ~$79 |
| Total, uncapped ws | ~$49 | ~$300 | ~$1,430 |

Reading: **the free product never runs away.** Worst case with the current config is ~$135/mo at 10k MAU — and 10k MAU is ~an order of magnitude above the plausible ceiling of the market (§2.3). The only path to real money burned is *raising the web-search cap to keep coverage* — which is precisely what a Pro tier should fund (§4). Note Sonnet 5's intro pricing ($2/$10) makes ws ~30% cheaper until 2026-08-31; the sticker price is used above.

---

## 2. Willingness to pay & comparables (July 2026, cited)

### 2.1 What cyclists pay for route/outdoor software

| Product | 2026 price | Model |
|---|---|---|
| komoot Premium | **€59.99/yr** (€6.99/mo web, €4.99/wk in-app) | subscription; post-Bending-Spoons push is subscription-first, but the **World Pack survives at €29.99 one-time** ([komoot.com/maps](https://www.komoot.com/maps), fetched 2026-07-17; paywall saga: [DC Rainmaker 2025-03](https://www.dcrainmaker.com/2025/03/komoots-expanded-paywalls-trying-to-make-sense-of-it.html), [BikeRadar 2025-09](https://www.bikeradar.com/news/komoot-redesign-2025) — route sync later re-opened to free users) |
| Ride with GPS | Basic **$59.99/yr**, Premium **$79.99/yr** ($7.99/$9.99 mo) | subscription ([ridewithgps.com/pricing](https://ridewithgps.com/pricing)) |
| Strava | **$79.99/yr** (CZ: Kč 999/yr) | subscription ([strava.com/pricing](https://www.strava.com/pricing), fetched 2026-07-17) |
| Gaia GPS | **$39.99/yr**; with Outside+ $89.99/yr | subscription ([gaiagps.com/membership](https://www.gaiagps.com/membership/)) |
| Outdooractive | Pro **€29.99/yr**, Pro+ €59.99/yr | subscription ([outdooractive.com](https://www.outdooractive.com/en/membership/plans.html)) |
| Garmin Connect+ | **$69.99/yr** ($6.99/mo), launched Mar 2025 | AI-insights premium tier; routing stays free ([Garmin newsroom](https://www.garmin.com/en-US/newsroom/press-release/wearables-health/elevate-your-health-and-fitness-goals-with-garmin-connect/)) |
| Wahoo | ELEMNT features **free** (their stated differentiator); SYSTM training $179/yr | ([Wahoo support](https://support.wahoofitness.com/hc/en-us/articles/29239127912466)) |
| **AllTrails Peak** — closest comp for "AI-built guide for my route" | **$79.99/yr** (classic Plus $35.99/yr) | AI smart routes + landmark ID sold as a **+$44/yr premium tier on top** of the classic sub ([TechCrunch 2025-05](https://techcrunch.com/2025/05/12/alltrails-debuts-a-80-year-membership-that-includes-ai-powered-smart-routes/)) |

The 2026 band for a serious consumer outdoor subscription has converged on **€30–90/yr**, with AI/guide features consistently packaged as a *premium tier on top*, not standalone. One-time purchases survive at the edges: komoot World Pack €29.99, Guru Maps Pro $89.99 lifetime, and — most relevant — **Garmin Connect IQ paid data fields at $1.99–$4.99 one-time** (native paid apps since Aug 2024: 15% platform cut, trials; e.g. dynamicWatch RouteIT €2.50 unlock, dwMap $9.99/yr / $29.99 lifetime) ([the5krunner 2024-08](https://the5krunner.com/2024/08/07/garmin-connect-iq-store-allows-paid-for-apps-using-garmin-pay/), [2026-07](https://the5krunner.com/2026/07/16/riversideart-garmin-apps-no-subscription/), [dynamic.watch](https://dynamic.watch/)).

### 2.2 The Karoo ecosystem norm: free, full stop

The canonical directory ([timklge/awesome-karoo](https://github.com/timklge/awesome-karoo)) lists **~41 extensions; every one is free**. The observed monetization ceiling is a tip link (timklge: BuyMeACoffee in FUNDING.yml; valterc/Ki2: PayPal "buy me a coffee"); most authors have **no funding link at all**. Hammerhead's official Extensions library (Mar 2025) is a curated free library — **no billing, no revenue share, no payment channel exists on the platform** ([DC Rainmaker 2025-03](https://www.dcrainmaker.com/2025/03/hammerhead-karoo-adds-app-store-a-few-thoughts.html), [hammerhead.io developer platform](https://www.hammerhead.io/pages/developer-platform)). No forum/Reddit threads about paying for extensions were found — gratitude flows, money doesn't. Charging would make Waybook the first paid Karoo extension ever, with zero platform rails: any payment must live off-device (license key from a web store).

### 2.3 Honest market sizing

No official Karoo unit numbers. Best proxy: the Hammerhead Companion Android app has **~37K total downloads** ([AppBrain](https://www.appbrain.com/app/karoo-companion-app/io.hammerhead.companionapp)); adding iOS and pre-Companion Karoo 2 owners, the active fleet is plausibly **~100–200K devices** — and the extension-aware, sideload-tolerant subset is **hundreds to low thousands** (awesome-karoo's entire community footprint). Waybook's realistic MAU: **100–2,000**. At Garmin-observed price points ($2–5 one-time) and typical 2–5% freemium conversion, gross revenue potential is **low hundreds €/yr at 500 users, €300–2,000/yr at 2,000 users** — coffee money. Nobody should build a business plan on this; the plan below is "cover costs with dignity."

---

## 3. Cost & usage tracking — **implemented and deployed 2026-07-17**

The "track it somewhere" ask is done. Three pieces, all live on the shared `moravia-ride` project:

**a) Table `public.waybook_usage`** (migration `waybook/backend/supabase/migrations/20260720_waybook_usage.sql`, applied via the Management API like prior migrations). One row per discover call that does real work — cold build, cache hit, or given-points enrichment:

`at · kind (build|given_points) · corridor_hash · device · transport (sync|async) · cache_hit · pois · entities_new · blurbs · haiku_in/out (tokens) · ws_eligible / ws_cache_hits / ws_searched / ws_found · ws_in/out (Sonnet tokens) · ws_calls (billed web_search invocations) · est_usd · ms`

`est_usd` is computed in-function from current prices (Haiku $1/$5, Sonnet $3/$15, $10/1k searches) so each row is self-contained if prices change. Deny-all RLS, service-role-only, like every other waybook table.

**b) Instrumentation in `discover/index.ts`** (deployed): a `BuildUsage` accumulator is threaded through `buildCatalog → enrichStage → webSearchStage`; the Haiku call now returns its `usage` tokens (both providers), `webSearchOne` returns tokens + `usage.server_tool_use.web_search_requests`. Recording is **never load-bearing** — a failed insert logs and is swallowed; cache hits (sync + async) and given-points calls are recorded too. The device can optionally send `device` in the POST body (recorded as `''` until the app adds it).

**c) Rollups**: views `waybook_usage_daily` and `waybook_usage_weekly` (cold builds, cache hits, distinct devices, new entities, tokens, searches, `est_usd`, avg cold-build ms). Both `security_invoker` + revoked from anon/authenticated. Query from the SQL editor or Management API:

```sql
select * from waybook_usage_daily limit 14;   -- the operator's cost dashboard
```

**Verified end-to-end**: deployed, exercised with a live given-points call, row landed with correct accounting (including the daily websearch budget being exhausted at 40/40 — the circuit breaker skip was itself visible in the data). One operational note: `supabase functions deploy discover` **must include `--no-verify-jwt`** — a plain deploy flips JWT verification back on and 401s the device (hit and fixed during this deploy).

Not done (deliberately): per-device rate limiting/attribution (the app doesn't send a device id yet) and a spend-based kill switch — the daily search budget already is one, and Haiku spend can't meaningfully run away ($0.009/build needs ~3,700 cold builds/day to hit $1k/mo).

---

## 4. Monetization recommendation — ranked

Principles first: the data is OSM (ODbL) + Wikipedia/Wikivoyage (CC BY-SA) + Commons — free and attributed. **We charge for the service** (the pipeline, caching, curation, the web-search budget), never the data; no license barrier to a paid tier. And the free tier must stay genuinely good — goodwill is the only real asset in a 41-extensions-all-free ecosystem.

**1. Ship free + tip jar (GitHub Sponsors + Ko-fi) — do this at launch.** The ecosystem norm, zero friction, zero goodwill risk. GitHub Sponsors: **0% fees** on personal sponsorships ([docs.github.com](https://docs.github.com/en/sponsors/sponsoring-open-source-contributors/about-sponsorships-fees-and-taxes)); Ko-fi: **0% on one-time donations** ([ko-fi.com/pricing](https://ko-fi.com/pricing)). Donations are gifts — no EU-VAT machinery. Expected: **€50–300/yr**, front-loaded at launch. Add a **"cover-costs" framing** powered by §3: a public line in the README/site — *"Waybook costs about $X/month to run; the web-search budget that writes stories for obscure places is the expensive part — supporters fund more of it."* Tying donations to a visible, honest lever (the daily search budget) is the one framing that converts in tip-jar ecosystems, and `waybook_usage_daily` gives you the real number.

**2. Hold a Pro tier in reserve; build it only past ~500–1,000 MAU** (i.e., only if Waybook becomes the rare breakout Karoo extension). Shape it exactly along the cost/value asymmetry:
- **Pro = wider discovery radius** (`maxOffKm` up to 5 is already in the API), **category filters/preferences** (waybook-future-ideas.md), **deeper enrichment** and — the honest one — **a bigger web-search budget**: Pro builds get `maxPerBuild` 16+ and priority on (or exemption from) the daily cap. Free stays fully usable; Pro is "more/customised", never "unlock the basics".
- **Price: €14.99 one-time unlock** (Garmin-comp $2–5 is for a data field; an auto-built illustrated guide justifies komoot-World-Pack territory, €15–30 one-time) **or €12/yr** — never monthly: at €2–4/mo every merchant-of-record's ~50¢ fixed fee eats 16–33% ([Paddle](https://www.paddle.com/pricing), [Lemon Squeezy](https://www.lemonsqueezy.com/pricing)); annual drops the take to ~6–9%.
- **Rail: a merchant of record**, because a Czech OSVČ selling B2C digital across the EU owes VAT from the first euro via OSS — exactly what MoR removes. In order: **Paddle** (5% + 50¢, stable, but products under $10 need custom pricing), **Lemon Squeezy** (5% + 50¢, still operating but being folded into **Stripe Managed Payments** — 3.5% + Stripe processing, early access as of early 2026 ([stripe support](https://support.stripe.com/questions/managed-payments-pricing), [LS 2026 update](https://www.lemonsqueezy.com/blog/2026-update)) — fine to start on, expect a migration), or **Gumroad** (10% + 50¢, **is** MoR since Jan 2025 ([gumroad.com/pricing](https://gumroad.com/pricing)) — simplest, priciest). Ko-fi Shop/Memberships are **not** MoR — don't sell licenses there. Delivery: license key bought on the web, typed once into Settings (no on-device billing exists; §2.2).
- **Revenue vs. the cost curve:** costs become "real" (> pocket change) only when Supabase Pro (+$25) and web-search coverage matter — **~$90/mo at 1k MAU, ~$135/mo capped at 10k**. At 2–5% conversion × €15: 1k MAU → €300–750/yr (covers the capped bill roughly); uncapping web search at scale (~$300+/mo at 1k, ~$1,400/mo at 10k full coverage) is only fundable if conversion runs hot — so **the cap stays, and Pro purchases literally raise it**. That's a coherent story to tell users, and `waybook_usage` proves it with numbers.

**3. BYO API key — rejected, again.** Reconfirming the research verdict: worst UX imaginable on a headless bike computer (typing an `sk-ant-…` key on a Karoo), highest support burden, key-leakage liability — and now an architectural mismatch too: the **permanent shared cache** means one user's key would fund enrichment every other user consumes; per-user keys make the economics of the shared cache incoherent.

**4. Don't do:** paid-up-front app (no platform rail, kills the ecosystem-norm goodwill, market too small to survive the funnel hit); ads (obviously); selling the data (it's OSM/Wikipedia — not ours to sell, only the service is).

### Bottom line

Free + Sponsors/Ko-fi with a cost-transparent "fund the search budget" pitch, on top of a cost base that the daily cap pins at **~$35–135/mo across the entire plausible user range** — and a pre-shaped Pro tier (radius + filters + search budget, €15 one-time via an MoR) held until usage proves >500–1,000 MAU. The tracking to know when that moment arrives is already live.
