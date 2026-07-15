#!/usr/bin/env python3
"""Bundle the route + POIs into one compact file for the follow website.

Run after editing pois.json (same trigger as gen_assets.py).
Emits web/site/ride.json.
"""
import json
from pathlib import Path

ROOT = Path(__file__).parent
ASSETS = ROOT / "extension/app/src/main/assets"
OUT = ROOT / "web/site/ride.json"

DAY_LABEL = {
    1: {"from": "Břeclav", "to": "Velehrad", "date": "2026-07-31"},
    2: {"from": "Velehrad", "to": "Otrokovice", "date": "2026-08-01"},
}

tracks = json.loads((ASSETS / "day_tracks.json").read_text())
pois = json.loads((ASSETS / "pois.json").read_text())

days = {}
for d in ("1", "2"):
    pts = tracks[d]
    days[d] = {
        "pts": [[round(p[0], 5), round(p[1], 5), round(p[2], 1)] for p in pts],
        "total_km": round(pts[-1][2], 1),
        **DAY_LABEL[int(d)],
    }

# Town labels for the map: the towns that actually have POIs, in route order,
# thinned so labels never collide on a 440px-wide page.
def towns_for(day):
    seen = {}
    for p in pois:
        if p["day"] != day:
            continue
        seen.setdefault(p["town"], p["routeKm"])
    ordered = sorted(seen.items(), key=lambda kv: kv[1])
    out, last = [], -99
    for name, km in ordered:
        if km - last < days[str(day)]["total_km"] * 0.12:
            continue
        out.append({"name": name, "km": round(km, 1)})
        last = km
    return out

for d in ("1", "2"):
    days[d]["towns"] = towns_for(int(d))

ride = {
    "title": "Břeclav → Otrokovice",
    "rider": "Jakub",
    "days": days,
    "pois": [
        {
            "id": p["id"],
            "name": p["name"],
            "town": p["town"],
            "cat": p["category"],
            "day": p["day"],
            "lat": round(p["lat"], 5),
            "lon": round(p["lon"], 5),
            "km": round(p["routeKm"], 1),
            "off": round(p["offKm"], 2),
            "hook": p["hook"],
        }
        for p in pois
    ],
}

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(ride, ensure_ascii=False, separators=(",", ":")))

kb = OUT.stat().st_size / 1024
print(f"ride.json  {kb:.1f} KB")
for d in ("1", "2"):
    print(f"  day {d}: {len(days[d]['pts'])} pts, {days[d]['total_km']} km, "
          f"{len(days[d]['towns'])} town labels, "
          f"{sum(1 for p in ride['pois'] if p['day'] == int(d))} POIs")
