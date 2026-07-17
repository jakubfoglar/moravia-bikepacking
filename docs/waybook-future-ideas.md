# Waybook — future ideas (post-v1 brainstorm)

Parking lot for ideas raised during the build. Not in v1 scope; revisit after launch.

## Pro / paid tier (raised 2026-07-17)

A possible way to "monetise a tiny bit" (research recommends staying free + Ko-fi; a light Pro tier is the natural upsell if there's demand). Free tier stays fully usable; Pro unlocks:

- **Wider discovery area** — v1 uses a fixed corridor width around the route; Pro lets the rider widen the search radius to catch more places a bit further off-route (with the detour cost shown so it stays honest).
- **Category preferences / filters** — the rider picks what they care about and mutes the rest: e.g. *architecture & landmarks + coffee ON, pubs OFF*. Maps cleanly onto the category system (history/nature/cafe/food today, the 35 Karoo types + OSM tags later). Could ship a basic free version (a couple of on/off toggles) with the fuller per-category control behind Pro.
- **Probably more** — deeper enrichment (longer stories, multiple photos), offline map region hints, "surprise me" curation, saved preferences across routes.

Mechanics: since Karoo extensions are side-loaded (no app-store billing), Pro would be a license key via an external store (Lemon Squeezy handles EU-VAT as merchant-of-record) entered once in Settings, or a hosted-account model. Keep the free tier genuinely good — Pro is "more/customised," never "unlock the basics." See `guidebook-extension-research.md` §Monetisation for the honest revenue reality (low hundreds/yr ceiling) — build Pro only if usage justifies it.

## Other parked ideas

- Locale-aware paragraphs (v1 is English-only) — write in the rider's device language.
- Live "ask about this place" (the earlier idea) — a button in the detail → server-side web-search LLM answer. Generalises directly.
- Radar close-pass data as a shared road-safety layer (the trip extension already logs geotagged passes) — a different product, but the data's there.
