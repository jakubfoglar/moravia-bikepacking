# Live Info — backend design spec (`live` edge function)

> **Status:** design only, not yet built. Companion to
> [`docs/design/live-info-ux.md`](live-info-ux.md) and the appendix in
> [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md). Written to be handed to an implementer as-is.
> Specifies the endpoint, the Google call flow, caching/compliance rules, cost gating, and the
> migration — **not** finished code.

This is the *only* place Waybook calls Google. A new Supabase edge function, `live`, takes one
place from the device, does a live Google Places lookup (fresh hours + rating + a couple of
reviews), and returns **display-ready, already-attributed, ephemeral** data. It caches the one
thing Google's terms permit — the `place_id` — and **nothing else, ever**. It mirrors
`discover`'s discipline exactly: static-token auth, bounded, never throws a naked 500, per-call
usage accounting, a `waybook_counters` circuit breaker.

---

## 1. Principles (inherited from `discover`, non-negotiable)

1. **The Google key never leaves the server.** `GOOGLE_MAPS_API_KEY` lives in Supabase env
   only (public repo). The device authenticates to `live` with the same `x-waybook-token`
   header everything else uses.
2. **Only `place_id` is persisted.** Hours, ratings, reviews are returned to the device and
   **never written to any table, log, or diagnostics payload.** `place_id` is the sole
   long-term stored value (Google explicitly allows this) — cached so "resolve once, reuse
   forever" holds, exactly like the permanent enrichment cache keeps the product free.
3. **Resolve-once, reuse-forever** for the identity; **fetch-fresh-every-time** for the
   content. The place↔`place_id` mapping is shared across all riders and rebuilds; the content
   is per-tap and thrown away.
4. **Cost is gated server-side.** Every billed Place Details call passes a
   `waybook_counters` daily circuit breaker (the web-search fallback's mechanism). The device
   shows no counters; when exhausted the function returns a specific "paused" status.
5. **Bounded, isolated, never throws.** Same as `discover`: a `Result`-shaped JSON reply with a
   human-readable error, wall-clock timeouts, one bad lookup never cascades.
6. **Reviews are untrusted third-party text.** They are returned verbatim for **quoted,
   attributed** display only (the venue-website fence already establishes this pattern). The
   server does not fold them into any Waybook voice and does not let them influence any prompt
   (there is no LLM in this path at all — see §4).

---

## 2. Endpoint contract

`POST {BASE}/live` — same base, same token header, same gzip both ways as `discover`.

### Request (device → `live`)

```jsonc
{
  "name": "Kavárna Na Kopci",     // required — the place's display name
  "lat": 49.1234,                  // required — POI coordinate (disambiguation anchor)
  "lon": 16.5678,                  // required
  "place_id": "ChIJ…",            // optional — send when the device already has it (skips resolve)
  "lang": "en",                    // optional — languageCode for hours phrasing / review translations
  "device": "…"                    // optional — anonymous id, for the per-device soft cap (§6)
}
```

Notes:
- The catalog POI `id` is **per-catalog and not stable across rebuilds**, so it is *not* the
  cache key. The server derives a **stable place key** from `name` + rounded coordinates
  (§5.1). The device may still cache the returned `place_id` on its `Poi` and send it back to
  skip resolution.
- No polyline, no corridor — this is a single-place call.

### Response (`live` → device)

Success (`200`):

```jsonc
{
  "status": "ok",                  // ok | none | notfound | paused | error
  "place_id": "ChIJ…",            // echoed so the device can store it
  "rating": 4.6,                   // optional
  "userRatingCount": 128,          // optional
  "hours": {                       // optional — null when Google has no hours
    "openNow": true,
    "text": "Closes 20:00",       // short, display-ready (server formats from the day's periods)
    "state": "open"                // open | closed | closing_soon  → maps to k_green/k_muted/k_red
  },
  "reviews": [                     // optional, ≤ 2 (server truncates — see §7 review policy)
    {
      "author": "Marta K.",
      "rating": 5,
      "relativeTime": "2 weeks ago",
      "text": "Great coffee stop right on the trail…",
      "authorUri": "https://…"    // Google author attribution link (carried for compliance)
    }
  ],
  "asOf": "2026-07-19T14:32:00Z"   // server fetch time → the device's "as of HH:MM"
}
```

The device maps `status` straight onto the UX state machine:

| `status` | UX state |
|---|---|
| `ok` (with content) | Loaded (3) or Loaded-partial (4) |
| `none` | Loaded-partial → "Google has no live details" |
| `notfound` | Error → "Couldn't find this place on Google" (no retry) |
| `paused` | Error → "Live info is resting for today" (no retry) |
| `error` | Error → generic (retryable) |

All non-2xx transport failures the device already treats as offline (State 5), same as every
other `WaybookApi` call.

---

## 3. The Google calls (Places API **New**)

Two calls, in order. The first is skipped when the device already sent a fresh `place_id`.

### 3.1 Resolve — Text Search (New) → `place_id`

Only when `place_id` is absent or stale (>12 months, §5.2).

```
POST https://places.googleapis.com/v1/places:searchText
Headers:
  X-Goog-Api-Key: $GOOGLE_MAPS_API_KEY
  X-Goog-FieldMask: places.id,places.location,places.displayName   ← cheap mask (IDs/Location)
Body:
  {
    "textQuery": "<name>",
    "locationBias": { "circle": { "center": {lat,lng}, "radius": 400.0 } },
    "maxResultCount": 3,
    "languageCode": "<lang>"
  }
```

- **Confident-match rule (§5.3):** accept the top candidate only if it is within a distance
  threshold of the POI coordinate (e.g. **≤ 250 m**) and its name is a reasonable match. No
  candidate qualifies → `notfound` (do **not** guess a nearby different business).
- Field mask is deliberately **IDs/Location only** — the cheapest Text Search SKU tier — because
  resolution needs nothing else.

### 3.2 Fetch — Place Details (New) → content

```
GET https://places.googleapis.com/v1/places/<place_id>
Headers:
  X-Goog-Api-Key: $GOOGLE_MAPS_API_KEY
  X-Goog-FieldMask: id,currentOpeningHours,rating,userRatingCount,reviews
  Accept-Language: <lang>
```

- The field mask **is** the cost lever — request the minimum. `rating`, `userRatingCount`,
  `currentOpeningHours`, and `reviews` are the only fields we need. `reviews` is the priciest
  tier (see §8) — include it, but nothing else beyond it.
- Server maps `currentOpeningHours.openNow` + the day's `periods` into the short `hours.text`
  and a `state` (`open` / `closed` / `closing_soon` if within ~30 min of close — reuse the same
  threshold as the app's `OpeningHours` closing-soon logic so device and Google agree in
  spirit).
- Take the **first 2** reviews Google returns (already recency/relevance-ordered by the API),
  each mapped to the response shape, carrying `authorAttribution.displayName` and `.uri`.

### 3.3 A place delisted since we stored its id

Place Details on a stale `place_id` can 404 / return `NOT_FOUND`. Treat as `notfound`, **delete
the stored mapping** so a future tap re-resolves (the venue may have a new id).

---

## 4. No LLM, no fetch of arbitrary pages

Unlike `discover`'s venue stage, `live` calls **only** the two Google endpoints. There is no
web fetch, no Anthropic call, no JSON-LD scrape. Reviews are structured data from Google; they
are passed through verbatim for attributed display. This keeps the path cheap, fast (target <2s
warm), and free of the injection surface the venue stage has to fence.

---

## 5. Caching — `place_id` only

### 5.1 Stable place key

`discover` already derives stable entity keys (`wsKey` for web-searched places). Reuse the same
idea for a **place key**:

```
placeKey = sha1( normalize(name) + "@" + round(lat, 4) + "," + round(lon, 4) )
```

(`round(_,4)` ≈ 11 m grid — tight enough that two different venues don't collide, loose enough
that the same venue from two catalog builds maps together. `normalize` = lowercase, trim,
collapse whitespace/diacritics, like the existing name normalization.)

### 5.2 Table (new migration)

```sql
-- 202607xx_waybook_live.sql
create table if not exists public.waybook_places (
  place_key   text primary key,          -- sha1(name@lat,lon) — stable across rebuilds
  place_id    text,                       -- Google place_id; NULL = confidently no match (negative cache)
  name        text,                       -- for debugging only
  lat         double precision,
  lon         double precision,
  resolved_at timestamptz not null default now()
);
alter table public.waybook_places enable row level security;
-- Deny-all RLS (no policies): only the live edge function (service role) reads/writes.
revoke all on public.waybook_places from anon, authenticated;
```

- `place_id` **stored indefinitely** — permitted, and it's what makes repeat taps one call.
- **Negative cache:** a confident "no Google match" stores `place_id = NULL` so the same place
  isn't re-searched on every tap. But a business can *appear* later, so a NULL row is honored
  only for a **soft TTL (e.g. 60 days)** via `resolved_at`; after that a tap re-resolves once.
- **12-month refresh:** a non-null `place_id` older than 12 months (`resolved_at`) is
  re-resolved once (Google's own recommendation — ids drift). On successful re-resolve, update
  `resolved_at`.
- **Never** store hours/rating/reviews here or anywhere. This table has no content columns by
  design — that absence is the compliance guarantee.

### 5.3 Resolution decision table

| State of `waybook_places` row | Action |
|---|---|
| absent | Text Search → store result (id or NULL) |
| `place_id` set, `resolved_at` < 12mo | use it (skip Text Search) |
| `place_id` set, `resolved_at` ≥ 12mo | Text Search once, refresh row |
| `place_id` NULL, `resolved_at` < 60d | short-circuit to `notfound` (no call) |
| `place_id` NULL, `resolved_at` ≥ 60d | Text Search once, refresh row |

---

## 6. Cost gating — circuit breaker

Model the web-search breaker exactly (`discover` §"Global daily circuit breaker"):

- A `waybook_counters` row keyed `"live"` (`{key, day, count}`) counts **billed Place Details
  calls** per UTC day, across all devices.
- Before a Details call: read the counter; if `count >= LIVE.dailyBudget`, **skip the call and
  return `status:"paused"`**. Otherwise increment (upsert) then call.
- **Only Details is counted.** A resolve that hits the `place_id` cache, or a `notfound` that
  never reaches Details, costs the budget nothing.
- **Per-device soft cap (recommended, since taps are user-initiated):** unlike web-search
  (server-initiated during a build), live taps come from a rider. A greedy or buggy client
  shouldn't burn the global budget. Add a lightweight per-device counter (key
  `"live:" + device` or a small `waybook_live_device(device, day, count)` table) with a modest
  daily per-device cap. When a device exceeds it → `paused`. This protects the global budget
  and is fairer than a single global pool. **Decision flagged in §10.**

```
LIVE = {
  dailyBudget: 200,        // global billed Details/UTC day — tune to the cost ceiling
  perDeviceDaily: 25,      // soft cap per anonymous device/day (if adopted)
  matchРadiusM: 250,       // Text Search confident-match threshold
  negativeTtlDays: 60,     // NULL place_id honored this long before a re-search
  refreshMonths: 12,       // non-null place_id re-resolved after this
  detailsTimeoutMs: 8000,  // per Google call
  resolveTimeoutMs: 8000,
}
```

---

## 7. Review handling & compliance surface (server side)

- Return **≤ 2** reviews (the UX card shows 2). Server truncation, not client — smaller
  payload over the Companion link.
- Each review carries `author` (`authorAttribution.displayName`) and `authorUri`
  (`authorAttribution.uri`). The device **must** display author attribution; the server
  guarantees it's present by dropping any review missing it.
- **⚠ Review-text policy (blocker to confirm before build):** Google's Places policy has
  specific rules on displaying review text (attribution required; historically full text /
  no alteration, with a link to the review). This spec returns `text` for a 3-line clamped
  display — **that clamp may be non-compliant.** Resolve the current policy first. If full text
  is required, the server should return full `text` and the card shows **one** review expandable
  to full, rather than two clamped. This changes both payloads and the card layout in the UX
  spec (§3.3 there). **Do not ship until confirmed.**
- The Google wordmark requirement is satisfied **client-side** (bundled asset, per the UX
  spec). The server carries only the per-review author attributions.
- Language: pass `languageCode`/`Accept-Language`; keep review text in whatever language Google
  returns (do not machine-translate, do not strip scripts).

---

## 8. Cost model (verify current SKU pricing before launch)

Places API (New) bills per request by the **field-mask tier** of the fields you ask for. Rough
shape (numbers drift — **confirm against the current Google Maps Platform pricing page**):

| Call | Field mask | SKU tier | Rough $/1k | When it fires |
|---|---|---|---|---|
| Text Search (resolve) | `places.id,location` | Text Search — IDs/Location | lower | first tap per place, ever (then cached) |
| Place Details (content) | `+reviews` | Details — highest (atmosphere/reviews) | **highest** | every tap that reaches Details (breaker-gated) |

Cost-control levers, in order of leverage:
1. **`place_id` cache** — the resolve call fires once per place across *all* riders, forever.
2. **Field mask minimalism** — never request a field the card doesn't show.
3. **Circuit breaker** — hard daily ceiling; `paused` degrades gracefully.
4. **Negative cache** — a place with no Google match never re-searches for 60 days.
5. **Session cache on the device** (UX §4) — paging back doesn't re-hit `live` at all.

Per-call usage accounting: log each `live` invocation the way `discover` logs a build — reuse
`waybook_usage` with a `kind = "live"` row (calls made, resolve vs. cached, details vs. paused,
`place_id` hit/miss), or a small dedicated `waybook_live_usage` table if `waybook_usage`'s
build-shaped columns don't fit. **Flagged in §10.** No review/hours content in usage rows.

---

## 9. Function skeleton (orientation only — not final code)

```ts
// backend/supabase/functions/live/index.ts
Deno.serve(async (req) => {
  if (req.headers.get("x-waybook-token") !== WAYBOOK_TOKEN) return respond({error:"bad token"}, 401, req);
  if (req.method !== "POST") return respond({error:"POST only"}, 405, req);
  const { name, lat, lon, place_id: given, lang, device } = await req.json().catch(() => ({}));
  if (!name || typeof lat !== "number" || typeof lon !== "number")
    return respond({ error: "missing name/lat/lon" }, 400, req);

  const supa = supaClient();
  // 1. resolve place_id (given → cache → Text Search), honoring TTL/negative-cache (§5.3)
  const resolved = await resolvePlaceId(supa, { name, lat, lon, given, lang });
  if (resolved.status === "notfound") return respond({ status: "notfound" }, 200, req);

  // 2. circuit breaker on billed Details calls (§6)
  if (!(await breaker(supa, "live", device))) return respond({ status: "paused" }, 200, req);

  // 3. Place Details (New), minimal field mask (§3.2); NOT cached
  const details = await placeDetails(resolved.placeId, lang); // 404 → drop mapping, notfound
  // 4. shape display-ready + attributed; ≤2 reviews; nothing persisted
  await recordLiveUsage(supa, { device, resolved, details });
  return respond(shapeResponse(resolved.placeId, details), 200, req);
});
```

`respond`, `supaClient`, `WAYBOOK_TOKEN`, and the gzip/CORS handling are lifted from `discover`
(share them rather than re-implement). Every Google call is wrapped in a timeout + try/catch
that degrades to `status:"error"`, never a naked throw.

---

## 10. Deploy & ops

- **`supabase functions deploy live --no-verify-jwt`** — MANDATORY, same as the others (the
  device uses the static token, not a Supabase JWT; without the flag every call 401s before our
  code runs).
- Migration applied via the Management API / `supabase db push`, like the rest.
- New env var: `GOOGLE_MAPS_API_KEY` (Supabase secrets). Restrict the key to the Places API
  (New) in the Google console; set quotas/alerts as a second backstop under the breaker.
- Add a `docs/ARCHITECTURE.md` schema note for `waybook_places` when built.

---

## 11. Open questions (resolve before build)

1. **Review-text truncation vs. Google policy** (§7) — the hard blocker; it changes payload and
   card layout.
2. **Cost accounting home** (§8) — reuse `waybook_usage` (`kind="live"`) vs. a dedicated
   `waybook_live_usage` table.
3. **Per-device cap** (§6) — global-only breaker vs. global + per-device soft cap. Recommended:
   adopt the per-device cap since taps are rider-initiated.
4. **Where `place_id` lives** — server-only `waybook_places` (this spec) vs. also baking it into
   the catalog body at build time. This spec keeps it server-side + device-cached (lazy, no
   build-time spend); confirm.
5. **Photos** — deferred (UX §9). If added later, `places.photos` is another field-mask tier and
   its own attribution rules; a separate spec.
6. **Pricing confirmation** (§8) — verify current SKU tiers/prices and set `LIVE.dailyBudget`
   against the intended monthly ceiling.
```
