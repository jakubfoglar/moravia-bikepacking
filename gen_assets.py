#!/usr/bin/env python3
"""Generate the Karoo extension's offline asset bundle from the reviewed POIs + GPX.

Outputs into extension/app/src/main/assets/:
  - pois.json   : ≤5km POIs with id, category, town, day, lat/lon, hook, blurb,
                  opening_hours, routeKm (position along the combined route), hasPhoto
  - track.json  : combined Day1+Day2 track as [[lat,lon,cumKm], ...] for snapping position
  - photos/<id>.webp/jpg : bundled POI photos (downloaded once)
"""
import json, math, os, ssl, urllib.request, urllib.parse, time
HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "extension/app/src/main/assets")
PHOTOS = os.path.join(ASSETS, "photos")
os.makedirs(PHOTOS, exist_ok=True)
import xml.etree.ElementTree as ET
NS = '{http://www.topografix.com/GPX/1/1}'
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE

def hav(a,b,c,d):
    R=6371;p1,p2=math.radians(a),math.radians(c);dp=math.radians(c-a);dl=math.radians(d-b)
    return 2*R*math.asin(math.sqrt(math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2))
def load(fn):
    r=ET.parse(os.path.join(HERE,fn)).getroot()
    return [(float(t.get('lat')),float(t.get('lon'))) for t in r.iter(NS+'trkpt')]

# combined track Day1 -> Day2, downsampled ~every 50m, with cumulative km
def combined_track():
    pts = load("export-30.gpx") + load("export-29.gpx")
    out=[]; cum=0.0; last=None; acc=0.0
    for i,p in enumerate(pts):
        if last is not None:
            step=hav(last[0],last[1],p[0],p[1]); cum+=step; acc+=step
        if last is None or acc>=0.05 or i==len(pts)-1:
            out.append((round(p[0],6),round(p[1],6),round(cum,3))); acc=0.0
        last=p
    return out

track = combined_track()
print(f"combined track: {len(track)} pts, {track[-1][2]:.1f} km total")

# per-day track shapes (downsampled, with cumulative km) for the overview minimap
def day_track(fn):
    pts = load(fn)
    out=[]; cum=0.0; last=None; acc=0.0
    for i,p in enumerate(pts):
        if last is not None:
            step=hav(last[0],last[1],p[0],p[1]); cum+=step; acc+=step
        if last is None or acc>=0.4 or i==len(pts)-1:
            out.append([round(p[0],5),round(p[1],5),round(cum,2)]); acc=0.0
        last=p
    return out
day_tracks = {"1": day_track("export-30.gpx"), "2": day_track("export-29.gpx")}
json.dump(day_tracks, open(os.path.join(ASSETS,"day_tracks.json"),"w"), separators=(",",":"))
print(f"day tracks: D1 {len(day_tracks['1'])}pts/{day_tracks['1'][-1][2]:.0f}km, D2 {len(day_tracks['2'])}pts/{day_tracks['2'][-1][2]:.0f}km")

def route_km(lat,lon):
    best=1e9; bk=0.0
    for a,b,k in track:
        d=hav(lat,lon,a,b)
        if d<best: best=d; bk=k
    return round(bk,3), round(best,3)

pois = json.load(open(os.path.join(HERE,"pois_enriched.json"),encoding="utf-8"))
sel = [p for p in pois if p["off_km"]<=5.0]
out=[]
for p in sel:
    rk,off = route_km(p["lat"],p["lon"])
    out.append({
        "id": p["id"], "name": p["name"], "town": p.get("town",""),
        "category": p["category"], "day": p["day"],
        "lat": round(p["lat"],6), "lon": round(p["lon"],6),
        "hook": p.get("hook",""), "blurb": p.get("blurb",""),
        "opening_hours": p.get("opening_hours"),
        "routeKm": rk, "offKm": p["off_km"],
        "hasPhoto": bool(p.get("photo_url")),
        "photo_url": p.get("photo_url"),
        # optional practicalities (type-aware detail screen; null/missing = block hidden)
        **{k: p[k] for k in ("cuisine","admission","effortNote","hoursNote","phone","cashOnly") if p.get(k) is not None},
    })
out.sort(key=lambda x:x["routeKm"])
json.dump([{k:v for k,v in o.items() if k!="photo_url"} for o in out],
          open(os.path.join(ASSETS,"pois.json"),"w",encoding="utf-8"), ensure_ascii=False)
json.dump(track, open(os.path.join(ASSETS,"track.json"),"w"), separators=(",",":"))
print(f"wrote pois.json ({len(out)}) + track.json")

# download photos
ok=0; fail=0
for o in out:
    if not o["photo_url"]: continue
    dst=os.path.join(PHOTOS,f"{o['id']}.jpg")
    if os.path.exists(dst) and os.path.getsize(dst)>2000: ok+=1; continue
    url=o["photo_url"]
    if "?" not in url and "Special:FilePath" in url: url+="?width=400"
    for attempt in range(4):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':'bikepacking-trip/1.0'})
            with urllib.request.urlopen(req,timeout=30,context=ctx) as r:
                data=r.read()
            if len(data)>2000:
                open(dst,"wb").write(data); ok+=1; break
        except Exception as e:
            if attempt==3: print(f"  fail {o['id']} {o['name'][:20]}: {str(e)[:40]}"); fail+=1
            time.sleep(3)
    time.sleep(0.6)
print(f"photos: {ok} ok, {fail} failed")
