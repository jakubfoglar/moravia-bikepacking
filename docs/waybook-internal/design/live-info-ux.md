# Live Info — UX/UI design spec

> **Status:** design only, not yet built. Feeds the future feature noted in
> [`docs/ARCHITECTURE.md` → Appendix → "an on-demand live-info button"](../ARCHITECTURE.md).
> This document is written to be handed to an implementer (human or agent) as-is.
> It specifies **what the screen does and how it looks**, not the code. The backend half —
> the `live` edge function, Google call flow, caching and cost gating — is in
> [`docs/design/live-info-backend.md`](live-info-backend.md).

Waybook's catalog is permanent, offline, and open-data. **Live Info** is the one place we
touch Google — a per-tap, online-only lookup of *fresh opening hours + ratings/reviews* for a
single place, shown transiently and attributed, then discarded. It never enters the permanent
catalog. The whole design below exists to make that one tap feel calm, honest, and native to
the Karoo — and to fail gracefully, because on a bike the network is usually gone.

---

## 1. Design principles (the bar)

1. **Native to the Karoo, invisible as "a feature."** It reads like one more block on the
   detail screen, in the same white / uppercase-grey / hairline idiom — not a bolted-on
   web panel. If a Hammerhead designer added it, this is how it would look.
2. **Honest about provenance.** Google content is visibly *Google's* — a distinct card, the
   Google wordmark, verbatim attributed reviews — never folded into Waybook's editorial
   voice. This is both a design value and a licence requirement.
3. **One deliberate tap, never a surprise.** Nothing fetches automatically. The rider decides
   to spend the connection. No nag dialogs either — the affordance sets expectations in one
   quiet caption.
4. **Offline is the default, not the error.** The rider is usually moving with no signal. A
   failed lookup is a normal, gentle state with a retry — not a red alarm.
5. **Glanceable, then done.** Sized to answer "is it open? is it any good?" at a stop, in
   sunlight, with gloves. Not a reading experience.
6. **Never breaks the screen.** Consistent with the field discipline: a live-lookup failure
   degrades to a quiet inline message; it never blanks or crashes the detail screen.

---

## 2. Where it lives

Inside `PoiDetailActivity`'s scroll content, a new **Live Info block** is inserted between the
practical rows (`d_rows`) and the **Navigate here** button:

```
 ┌ top bar: 🏰  HISTORY                       3 / 12 ┐
 ├──────────────────────────────────────────────────┤
 │  [ photo / gallery ]                              │
 │  Place name                                       │
 │  Town · detour 1.6 km                             │
 │  story / fact / venue note …                      │
 │ ── practical rows (hours, admission, phone …) ──  │
 │                                                   │
 │  ▸ LIVE INFO BLOCK  ← this spec                   │
 │                                                   │
 │  [   Navigate here   ]        (primary, unchanged)│
 │  photo credit · © OSM · Sources …                 │
 └──────────────────────────────────────────────────┘
   ‹ Back                             ‹      Next ›
```

**Why here, not the bottom bar.** The bottom bar's three pills sit at the physical-button
positions (Back / Prev / Next) — the rider's muscle memory. Live Info must **not** consume a
hardware button. It lives in the scroll flow, reached by touch, right after the practical
rows it augments (fresh hours belong next to OSM hours), and above **Navigate**, which stays
the screen's primary action.

---

## 3. State machine

One block, six visual states. Only one is ever shown at a time. Transitions are driven by a
single per-place lookup.

```
        (place has a chance of a Google match)
                     │
             ┌───────▼────────┐  tap
             │  1 AVAILABLE   ├────────────► 2 LOADING
             │  "Check live   │              │  │
             │   info"        │   cancel /   │  │ result
             └───────▲────────┘   page away  │  │
                     │      ◄────────────────┘  │
        retry / re-tap│                          ▼
             ┌────────┴───────┐        ┌─────────────────┐
             │  5 ERROR       │◄───────┤   (dispatch)    │
             │  (offline /    │  fail  └───┬─────────┬───┘
             │   not found /  │       ok   │         │ ok, but thin
             │   paused)      │            ▼         ▼
             └────────────────┘     3 LOADED    4 LOADED-PARTIAL
                                    (hours +      (hours OR reviews
                                     reviews)      missing — honest)
```

State 6 (**UNAVAILABLE**) is a static variant of 1 — see 3.1.

### 3.1 State 1 — Available (default)

A single secondary pill, full width, with a subordinate caption. Reuses `pill_ghost`.

```
 ┌──────────────────────────────────────────────┐
 │   ⚡  Check live info                          │   ← pill_ghost, k_ink, 13sp bold
 └──────────────────────────────────────────────┘
     Opening hours & reviews · live from Google      ← 9sp, k_muted, centered
```

- Icon: `⚡` (or a globe `🌐`) at 13sp, 6dp before the label.
- Caption sets the two expectations that matter: *what* you get and *that it's live/Google*.
- Tap target: the whole pill, min 44dp tall (glove-friendly).

**6 — Unavailable** is the same pill rendered disabled (`alpha 0.4`, not clickable) with the
caption replaced by **"No connection"**. See §5 for exactly when we render this vs. letting the
tap fail — short answer: we bias toward *enabled*, because the phone (Companion) link can carry
this small call even with no device WiFi, and we can't cheaply prove it's dead without trying.

### 3.2 State 2 — Loading

The pill morphs in place into a determinate-feeling but honest indeterminate row. No spinner
lag before feedback — the tap flips it instantly.

```
 ┌──────────────────────────────────────────────┐
 │   ◔  Checking live info…            Cancel    │   ← inline ProgressBar (small) + label + Cancel
 └──────────────────────────────────────────────┘
```

- A small indeterminate `ProgressBar` (Activity context → allowed; not a RemoteViews field).
- **Cancel** on the right aborts the in-flight request (mirrors the WiFi call's cancellable
  coroutine) and returns to State 1. Cancelling costs nothing the rider can see.
- If the first tap must resolve a `place_id` first (place never looked up), that's still one
  visual state — never show two spinners. Label stays "Checking live info…".
- Timeout: bounded like every other call (see §7). On timeout → State 5 (offline copy).

### 3.3 State 3 — Loaded (the Live card)

The result is a **visually distinct card**, deliberately not the white editorial surface. Fill
= `k_divider_soft` (an existing token — a barely-there grey), 12dp corners, 14dp padding, so it
reads as "a quoted panel from elsewhere," matching how the venue note is already set apart.

```
 ┌────────────────────────────────────────────────┐
 │  Google      LIVE · as of 14:32       ↻ Refresh │  ← wordmark left · muted meta · refresh
 │                                                  │
 │  ★ 4.6   (128)          Open · closes 20:00      │  ← rating left (k_ink) · status right (k_green)
 │  ──────────────────────────────────────────────  │  ← hairline (k_divider)
 │  Marta K.   ★★★★★   2 weeks ago                   │  ← author · stars · relative time (all muted-ish)
 │  “Great coffee stop right on the trail, friendly  │  ← review text, quoted, 3-line clamp
 │   staff and outdoor seating for bikes.”           │
 │                                                  │
 │  Tomáš R.   ★★★★☆   1 month ago                   │
 │  “Closed earlier than posted on a Sunday — call   │
 │   ahead.”                                         │
 │                                                  │
 │  Reviews & hours from Google · may be outdated    │  ← required attribution, 8sp k_muted
 └────────────────────────────────────────────────┘
```

Contents, in priority order (each hides cleanly if absent — see State 4):

| Element | Source field | Style |
|---|---|---|
| **Google wordmark** | bundled asset | required; top-left; light/dark variant per background |
| `LIVE · as of HH:MM` | fetch clock time | 9sp `k_muted`; the timestamp is the honesty anchor |
| **Refresh** `↻` | — | only shown once loaded; re-runs the lookup (see §4 caching) |
| **Rating** `★ 4.6 (128)` | `rating`, `userRatingCount` | star glyph + number `k_ink` 14sp bold; count `k_muted` |
| **Open/closed status** | `currentOpeningHours` | reuse `Render.hoursLine` colour language: open → `k_green`, closed → `k_muted`, closing-soon → `k_red`. Right-aligned on the rating line. |
| **Reviews** (max **2**) | `reviews[]` | author (`k_muted_dark` 10sp) · inline stars · relative time (`k_muted` 9sp) · text quoted, `k_ink` 10sp, **3-line `maxLines` + ellipsize** |
| **Attribution footer** | — | required; 8sp `k_muted` |

**Review count = 2, hard.** On a bike-computer at a stop you want a signal, not a comment
thread. Two recent reviews, each clamped to three lines, is the glance. (See §6 for the
truncation-vs-licence caveat — this is flagged as an open question, not a settled call.)

### 3.4 State 4 — Loaded-partial (honest about gaps)

The place matched on Google but some data is thin. **Show what exists; state what's missing —
never fake it.** Two common shapes:

- **Hours, no reviews:** show rating + status; replace the review list with one muted line:
  *"No reviews yet."*
- **Reviews, no hours:** show reviews; the status slot reads *"Hours not listed"* in
  `k_muted` (never a fabricated "Open").
- **Matched but empty (no rating, no hours, no reviews):** collapse the card to a single
  honest line — *"Google has no live details for this place."* — and keep the timestamp. This
  still counts as a successful, attributed lookup; the rider learns something true.

### 3.5 State 5 — Error (calm, actionable, one line)

Never red-alarm. The block returns to a pill-height row with an inline message and a **Try
again** affordance where sensible. Distinct copy per cause (copy deck in §8):

| Cause | Message | Retry? |
|---|---|---|
| No connection (offline / timeout) | *"No connection — couldn't reach Google"* | yes |
| No Google match for this place | *"Couldn't find this place on Google"* | no (re-tap won't help) — collapse to a quiet static line |
| Daily budget reached (circuit breaker) | *"Live info is resting for today"* | no — reassure, don't scare |
| Server / unexpected | *"Live info isn't available right now"* | yes |

- Offline/server → keep **Try again** (returns to State 2 on tap).
- Not-found / paused → no retry button (it would just re-fail or re-spend). Show the muted
  line; the block is "done" for this place this session.

---

## 4. Interaction, motion & session caching

- **Tap → instant feedback.** The pill flips to Loading on the same frame; no perceptible
  delay before the rider sees their tap registered.
- **Expand/collapse motion.** State→State height changes animate with a cheap 160ms
  fade+height (Karoo hardware is modest — no spring physics, no cross-fade of images). The
  card grows downward; the scroll position of the rider's current anchor (the place name)
  must not jump — expand *below* the fold, let the rider scroll to it.
- **Auto-scroll on load.** When the card first appears, gently bring its top into view
  (smooth `scrollTo`, not a jump) so the rider isn't left staring at the old rows. Only on
  first load, never on refresh.
- **Session cache (in-memory, ephemeral).** A successful result is held in memory keyed by
  `poi.id` for the **lifetime of the Activity** (and a soft **10-minute freshness TTL**).
  - Paging Prev/Next back to a place you already checked shows its card **instantly, with no
    new request and no new spend**, timestamp preserved.
  - After 10 minutes the held result is considered stale: the card still shows (with its
    original "as of" time) but the **Refresh** affordance is emphasised.
  - The cache is a plain map cleared in `onDestroy`. **Nothing is written to disk, ever** —
    that's what keeps this compliant. It is not `PoiRepository`, not a file, not the catalog.
- **Refresh** re-runs the lookup, replaces the card, restamps the time. Same states apply
  (shows Loading over the existing card, or a small inline spinner on the Refresh control).

---

## 5. Edge cases (exhaustive — the part that makes it feel finished)

1. **Rider pages away mid-flight (Prev/Next).** Capture `poi.id` at tap. When the result
   returns, render **only if** `order.getOrNull(idx)?.id == poi.id && !isDestroyed` — the exact
   guard the photo loader already uses. Otherwise the result is dropped into the session cache
   silently (so returning to that place shows it instantly) but the *visible* block for the
   now-current place stays in its own state. Never show place A's hours under place B's name.
2. **Rider leaves the screen (Back / finish) mid-flight.** Cancel the request in `onDestroy`
   (cancellable coroutine, like the WiFi discover call). No dangling callbacks touching a dead
   view.
3. **Each place has independent state.** Paging resets the block to that place's state
   (cached card, or fresh State 1). The block is rebound in `bind()` alongside the rows.
4. **Connectivity unknown at entry.** Do **not** gray the button out on `!onWifi`. The
   Companion (phone-link) transport carries this small call with no device WiFi. Render
   Available; let a genuinely offline tap fail fast into State 5. Only render State 6
   (Unavailable, disabled) when we can affirmatively tell there is *no* path at all (no WiFi
   **and** the Karoo system service / Companion link is not connected).
5. **Place with no chance of a Google match.** Some catalog entries are OSM waypoints with no
   real-world venue identity (a nameless viewpoint, a route waypoint id ≥ `ROUTE_ID_BASE`
   with a generic label). For these, **don't show the block at all** — showing "Check live
   info" only to always return "not found" is a broken promise. Gate visibility on: has a real
   `name` **and** category is a place-type that Google indexes (SIGHT venues, EAT/DRINK).
   Nameless nature/waypoints → block hidden.
6. **Result arrives after a refresh was tapped.** Ignore stale in-flight results; only the
   latest request for the current `poi.id` may render (request-token / generation counter).
7. **Reviews contain unpleasant / off-topic user text.** It's third-party UGC shown verbatim
   and attributed — we don't editorialize or filter wording, but the attribution footer makes
   clear it's Google users' words, not Waybook's. (No profanity filter in v1; flagged in §9.)
8. **Very long author names / review text.** Author `maxLines=1` ellipsize; review text
   `maxLines=3` ellipsize. No horizontal overflow (the field-black lesson's cousin: nothing
   pushes the layout wide).
9. **Non-Latin / RTL content.** Reviews can be in any language/script. Use system font (already
   the app default), respect the glyphs, don't force-uppercase review text (only *labels* are
   uppercase in this design).
10. **Rating present but zero reviews returned** (Google sometimes gives a rating with no text
    reviews on the field mask we request) → State 4 "No reviews yet." with the rating shown.
11. **`place_id` resolved once, then the venue closed / delisted.** A stored `place_id` returns
    a "not found / no longer available" on Details → treat as State 5 not-found, and drop the
    stale `place_id` so a future tap re-resolves.
12. **Slow link (Companion).** Loading may run 10–20s. Keep the Cancel affordance the whole
    time; never let it feel hung. Timeout → offline copy, retryable.
13. **Rapid double-tap on the pill.** Debounce: the first tap moves to Loading (pill no longer
    a tap target for "check"); further taps are no-ops until a terminal state.
14. **Screen used while riding (motion).** Nothing special required, but the 44dp targets and
    2-review cap assume glanced-not-read use. No autoplay, no timed dismissal.
15. **Airplane mode toggled between entry and tap.** Just fails into State 5 offline — no
    special handling needed beyond the fast timeout.

---

## 6. Compliance surface the UI must carry

The design **is** the compliance mechanism — get these wrong and the feature is not shippable:

- **Google wordmark is mandatory** whenever Google content is shown, even without a Google
  map. Bundle Google's official attribution asset (light + dark). It lives in the card header.
- **Reviews shown verbatim + attributed** (author name, star rating, relative time). Never
  paraphrased, never merged into Waybook prose, never re-ordered to editorialize.
- **The "as of HH:MM" timestamp + "may be outdated"** footer are honesty and also hedge the
  fact hours can be stale.
- **Nothing persists to disk.** In-memory, Activity-lifetime, TTL'd. No review/hours/rating
  text touches `filesDir`, the catalog, or diagnostics payloads. (`place_id` is the *only*
  value that may be stored long-term — carried on the `Poi`/catalog, resolved once.)
- **⚠ Open question — review truncation:** Google's policy has specific rules about showing
  reviews (attribution, and historically not truncating/altering review text without a path to
  the full review). The 3-line clamp in §3.3 is a *UX* preference that **must be validated
  against the current Places policy before build**. If full-text is required, prefer **fewer
  reviews shown in full** (e.g. one, expandable) over many clamped. Implementer: confirm this
  first; it may change the card layout.

---

## 7. Data & transport contract (for the implementer, not final code)

The client never holds a Google key (public repo). All of this goes through a **new
`live` Supabase edge function**, mirroring `discover`'s transport discipline exactly.

- **Client → `live`:** `{ id, name, lat, lon, place_id? }` (send the stored `place_id` when
  known so the server skips resolution). Same `x-waybook-token` header. Same WiFi-vs-Companion
  transport pick via `WaybookApi.onWifi`. This payload and its response are **tiny** (hours +
  2 reviews of text) — comfortably inside the 100 KB Companion cap, so **no lite/full split is
  needed** (unlike catalogs and photos).
- **`live` → client:** `{ place_id, rating?, userRatingCount?, status?{openNow, closesAt,
  color}, reviews?[{author, rating, relativeTime, text}], attributionHtmlStripped? }` — the
  server returns display-ready, **already-attributed**, ephemeral data and caches **nothing**
  except (optionally) writing the resolved `place_id` back so the next tap is one call.
- **Bounded + never throws**, returning a `Result` with a human message, like every other
  `WaybookApi` method. New timeouts in the same family (a live lookup should target ~8–15s WiFi
  / longer Companion).
- **Cost gate lives server-side:** the daily circuit breaker in `waybook_counters` (same
  mechanism as the web-search fallback). When tripped, `live` returns a specific "paused"
  status the client maps to State 5 "resting for today". The client shows no counters.

The client method: `WaybookApi.liveInfo(ctx, poi): Result<LiveInfo>` returning a small
`LiveInfo` data class. State is held on the Activity, not in `PoiRepository`.

---

## 8. Copy deck (all user-visible strings)

New `strings.xml` entries, `live_` prefix, matching the terse Karoo voice:

```
live_check            "Check live info"
live_caption          "Opening hours & reviews · live from Google"
live_caption_offline  "No connection"
live_loading          "Checking live info…"
live_cancel           "Cancel"
live_refresh          "Refresh"
live_badge            "LIVE"
live_as_of            "as of %s"            // %s = HH:MM local
live_rating_count     "(%d)"               // review count
live_status_no_hours  "Hours not listed"
live_no_reviews       "No reviews yet."
live_none             "Google has no live details for this place."
live_footer           "Reviews & hours from Google · may be outdated"
live_err_offline      "No connection — couldn't reach Google"
live_err_notfound     "Couldn't find this place on Google"
live_err_paused       "Live info is resting for today"
live_err_generic      "Live info isn't available right now"
live_try_again        "Try again"
```

Tone rules: sentence case in body, UPPERCASE only for the `LIVE` badge and existing row labels.
No exclamation marks. "Resting for today" over "quota exceeded" — calm, not technical.

---

## 9. Accessibility, hardware & polish

- **Targets ≥ 44dp**, glove-friendly; the whole pill and the Refresh control are tappable, not
  just the glyphs.
- **Sunlight contrast:** all text uses existing high-contrast tokens (`k_ink` on light card).
  The card fill `k_divider_soft` is light enough that `k_ink` stays AA. Don't tint reviews grey
  to the point of low contrast — author/time may be muted, **review text stays `k_ink`**.
- **Content descriptions** on the pill, Cancel, Refresh, and the Google wordmark image.
- **No colour-only meaning:** open/closed is colour **and** words ("Open · closes 20:00"),
  never a bare green dot.
- **Physical buttons untouched:** Back/Prev/Next keep their bottom-bar mapping; Live Info is
  touch-only, in-flow.
- **Motion respects modest hardware:** 160ms fade+height, no continuous animation, no image
  cross-fades.
- **v2 candidates (out of scope, note only):** Google Place **photos** (adds transport weight +
  its own attribution — deferred, like the reviews caveat); a lightweight UGC-tone guard on
  reviews; a "call" shortcut when Google returns a phone number.

---

## 10. Implementation checklist (touchpoints)

Purely for orientation — **do not treat as code**:

- [ ] `activity_poi_detail.xml`: insert a `LinearLayout` **Live Info block** container between
      `d_rows` and `d_navigate`; children for pill / loading row / card. New drawable for the
      card (`live_card_bg.xml`: `k_divider_soft` fill, 12dp corners) — reuse `pill_ghost` for
      the trigger.
- [ ] Bundle Google's official attribution wordmark (light + dark) in `res/drawable`.
- [ ] `PoiDetailActivity`: bind/reset the block in `bind()`; `poi.id` identity guard on
      result; session `Map<Int, LiveInfo>` + 10-min TTL; cancel in `onDestroy`; request
      generation counter for refresh; visibility gate (§5.5).
- [ ] `WaybookApi.liveInfo()` + `LiveInfo` model; new timeouts; Reporter event
      (`live_info`: outcome, transport, ms, cache-hit — no PII, no review text).
- [ ] `Poi` / catalog: carry an optional `place_id` (storable indefinitely) so a resolved id
      persists and the next tap is one call.
- [ ] New `strings.xml` entries (§8).
- [ ] **Backend (separate task):** `live` edge function — `place_id` resolve (Text Search) +
      Place Details (hours + reviews field mask), server-side attribution, `waybook_counters`
      circuit breaker, `--no-verify-jwt` deploy. Google key in Supabase env only.
- [ ] Update `docs/ARCHITECTURE.md` appendix (mark the button as spec'd) and, if the marketing
      story changes, the site mockup.

---

## 11. Open questions to resolve before build

1. **Review truncation vs. Google policy** (§6) — the single most important thing to confirm;
   it can change the card layout.
2. **Photos in scope?** This spec is hours + rating + reviews only. Google photos are deferred
   (transport + attribution weight). Confirm that's acceptable for v1.
3. **`place_id` resolution cost** — resolve lazily on first tap (this spec) vs. pre-resolve at
   build time for all venues (faster first tap, but spends money on places never tapped). This
   spec chooses lazy; confirm.
4. **Circuit-breaker granularity** — per-device, global, or per-day-N-taps? The web-search
   breaker is a daily global; live-info taps are rider-initiated, so a per-device soft cap may
   be fairer. Decide server-side.
```
