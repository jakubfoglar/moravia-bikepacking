#!/usr/bin/env python3
"""Build the interactive bikepacking review map from researched POIs + the GPX tracks.

Inputs:
  - pois.json            : list of POI dicts (name, category, lat, lon, town, hook, blurb, photo_url, photo_credit, confidence, coord_source)
  - export-30.gpx        : Day 1  (Breclav -> Velehrad)
  - export-29.gpx        : Day 2  (Velehrad -> Otrokovice)

Output:
  - bikepacking-map.html : self-contained (Leaflet via CDN) interactive review map
  - pois_enriched.json   : POIs with computed distance-to-track + day + id (for later GPX build)
"""
import json, math, xml.etree.ElementTree as ET, html, os

HERE = os.path.dirname(os.path.abspath(__file__))
GPX_NS = '{http://www.topografix.com/GPX/1/1}'

DAYS = [
    ("Day 1 · Břeclav → Velehrad", "export-30.gpx", "#e6194B"),
    ("Day 2 · Velehrad → Otrokovice", "export-29.gpx", "#3cb44b"),
]

CAT = {
    "nature":  {"label": "Nature & viewpoints", "color": "#2e7d32", "emoji": "🌄"},
    "history": {"label": "History & architecture", "color": "#6a1b9a", "emoji": "🏰"},
    "cafe":    {"label": "Cafés & coffee", "color": "#e07b00", "emoji": "☕"},
    "food":    {"label": "Restaurants & bistros", "color": "#c0392b", "emoji": "🍽"},
}

def haversine(lat1, lon1, lat2, lon2):
    R = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1); dl = math.radians(lon2 - lon1)
    a = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(a))

def load_track(fn):
    pts = []
    root = ET.parse(os.path.join(HERE, fn)).getroot()
    for tp in root.iter(GPX_NS + 'trkpt'):
        pts.append((float(tp.get('lat')), float(tp.get('lon'))))
    return pts

def downsample(pts, step_km=0.20, cap=2000):
    """Keep a point roughly every step_km, capped."""
    if not pts:
        return pts
    out = [pts[0]]
    acc = 0.0
    for i in range(1, len(pts)):
        acc += haversine(*pts[i-1], *pts[i])
        if acc >= step_km:
            out.append(pts[i]); acc = 0.0
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    if len(out) > cap:
        k = math.ceil(len(out)/cap)
        out = out[::k] + [out[-1]]
    return out

def min_dist_to(pts, lat, lon):
    best = 1e9
    for (a, b) in pts:
        d = haversine(lat, lon, a, b)
        if d < best:
            best = d
    return best

def main():
    with open(os.path.join(HERE, 'pois.json'), encoding='utf-8') as f:
        pois = json.load(f)

    tracks = []
    for name, fn, color in DAYS:
        full = load_track(fn)
        tracks.append({"name": name, "color": color,
                       "full": full, "line": downsample(full, 0.12, 2500),
                       "dist": downsample(full, 0.05, 6000)})

    enriched = []
    for i, p in enumerate(pois):
        lat, lon = float(p['lat']), float(p['lon'])
        d0 = min_dist_to(tracks[0]['dist'], lat, lon)
        d1 = min_dist_to(tracks[1]['dist'], lat, lon)
        day = 1 if d0 <= d1 else 2
        off = round(min(d0, d1), 2)
        e = dict(p)
        e['id'] = i
        e['lat'] = lat; e['lon'] = lon
        e['off_km'] = off
        e['day'] = day
        e['detour_km'] = round(off * 2, 1)  # rough out-and-back straight-line
        enriched.append(e)

    enriched.sort(key=lambda e: (e['day'], e['off_km']))

    with open(os.path.join(HERE, 'pois_enriched.json'), 'w', encoding='utf-8') as f:
        json.dump(enriched, f, ensure_ascii=False, indent=2)

    payload = {
        "tracks": [{"name": t["name"], "color": t["color"], "line": t["line"]} for t in tracks],
        "cats": CAT,
        "pois": enriched,
    }
    html_out = TEMPLATE.replace("/*DATA*/", json.dumps(payload, ensure_ascii=False))
    with open(os.path.join(HERE, 'bikepacking-map.html'), 'w', encoding='utf-8') as f:
        f.write(html_out)

    within10 = [e for e in enriched if e['off_km'] <= 10]
    print(f"POIs: {len(enriched)} total, {len(within10)} within 10km")
    for e in enriched:
        flag = "" if e['off_km'] <= 10 else "  <-- >10km, check"
        print(f"  D{e['day']} {e['off_km']:5.2f}km {e['category'][:4]:4} {e['name']}{flag}")

TEMPLATE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bikepacking POI review · Břeclav → Otrokovice</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  :root { --bg:#f7f7f5; --panel:#fff; --ink:#1c1c1e; --muted:#6b6b70; --line:#e3e3e0; }
  * { box-sizing: border-box; }
  html,body { margin:0; height:100%; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; color:var(--ink); background:var(--bg); }
  #wrap { display:flex; height:100vh; overflow:hidden; }
  #side { width:380px; min-width:380px; background:var(--panel); border-right:1px solid var(--line); display:flex; flex-direction:column; }
  #map { flex:1; }
  header { padding:14px 16px 10px; border-bottom:1px solid var(--line); }
  header h1 { font-size:15px; margin:0 0 2px; }
  header .sub { font-size:12px; color:var(--muted); }
  .filters { padding:10px 16px; border-bottom:1px solid var(--line); display:flex; flex-wrap:wrap; gap:6px; align-items:center; }
  .chip { font-size:12px; padding:4px 9px; border-radius:20px; border:1px solid var(--line); background:#fff; cursor:pointer; user-select:none; display:inline-flex; align-items:center; gap:5px; }
  .chip.off { opacity:.35; }
  .chip .dot { width:9px; height:9px; border-radius:50%; display:inline-block; }
  .controls { padding:8px 16px; display:flex; gap:8px; align-items:center; border-bottom:1px solid var(--line); }
  .controls label { font-size:12px; color:var(--muted); }
  .btn { font-size:12px; padding:6px 11px; border-radius:7px; border:1px solid var(--line); background:#1c1c1e; color:#fff; cursor:pointer; }
  .btn.sec { background:#fff; color:var(--ink); }
  #list { overflow-y:auto; flex:1; padding:4px 0 40px; }
  .poi { padding:9px 16px; border-bottom:1px solid #f0f0ee; cursor:pointer; display:flex; gap:9px; align-items:flex-start; }
  .poi:hover { background:#faf8f4; }
  .poi input { margin-top:3px; transform:scale(1.15); flex:none; }
  .poi .body { flex:1; min-width:0; }
  .poi .nm { font-size:13px; font-weight:600; line-height:1.25; }
  .poi .meta { font-size:11px; color:var(--muted); margin-top:2px; }
  .poi .hook { font-size:12px; color:#3a3a3e; margin-top:3px; }
  .tag { font-size:10px; padding:1px 6px; border-radius:10px; color:#fff; vertical-align:middle; }
  .lp-photo { width:100%; height:130px; object-fit:cover; border-radius:6px; margin-bottom:6px; background:#eee; }
  .lp h3 { margin:0 0 3px; font-size:14px; }
  .lp .m { font-size:11px; color:var(--muted); margin-bottom:5px; }
  .lp p { font-size:12px; margin:0 0 7px; line-height:1.4; }
  .lp a { font-size:12px; margin-right:10px; }
  .leaflet-popup-content { width:230px !important; }
  #picks { position:absolute; bottom:0; left:0; width:380px; background:#fff; border-top:1px solid var(--line); padding:8px 16px; font-size:12px; }
  #pickcount { font-weight:600; }
  textarea { width:100%; height:70px; font-size:11px; margin-top:6px; display:none; }
  @media (max-width:760px){ #wrap{flex-direction:column;} #side{width:100%;min-width:0;height:46vh;} #map{height:54vh;} #picks{width:100%;} }
</style>
</head>
<body>
<div id="wrap">
  <div id="side">
    <header>
      <h1>🚲 Bikepacking POI review</h1>
      <div class="sub">Břeclav → Velehrad → Otrokovice · tick the ones you want → “Copy picks”</div>
    </header>
    <div class="filters" id="catFilters"></div>
    <div class="controls">
      <span id="dayFilters" class="filters" style="padding:0;border:none;"></span>
      <span style="flex:1"></span>
      <button class="btn sec" id="allBtn">All</button>
      <button class="btn sec" id="noneBtn">None</button>
    </div>
    <div id="list"></div>
    <div id="picks">
      <span id="pickcount">0 picked</span>
      <button class="btn" id="copyBtn" style="float:right">Copy picks</button>
      <textarea id="picktext" readonly></textarea>
    </div>
  </div>
  <div id="map"></div>
</div>
<script>
const DATA = /*DATA*/;
const map = L.map('map', {scrollWheelZoom:true});
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
  {maxZoom:19, attribution:'© OpenStreetMap'}).addTo(map);

// tracks
const allLatLng = [];
DATA.tracks.forEach(t => {
  const ll = t.line.map(p => [p[0], p[1]]);
  L.polyline(ll, {color:t.color, weight:4, opacity:.75}).addTo(map);
  ll.forEach(x => allLatLng.push(x));
});
map.fitBounds(L.latLngBounds(allLatLng).pad(0.05));

const state = { cats:new Set(Object.keys(DATA.cats)), days:new Set([1,2]), picks:new Set() };
const markers = {};

function esc(s){ return (s==null?'':String(s)).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function mapsLink(p){ return `https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}`; }
function mapyLink(p){ return `https://mapy.cz/turisticka?x=${p.lon}&y=${p.lat}&z=16&source=coor&id=${p.lon},${p.lat}`; }

function markerIcon(p, picked){
  const c = DATA.cats[p.category].color;
  const em = DATA.cats[p.category].emoji;
  return L.divIcon({className:'', iconSize:[26,26], iconAnchor:[13,13], html:
    `<div style="width:26px;height:26px;border-radius:50%;background:${c};border:3px solid ${picked?'#ffd400':'#fff'};box-shadow:0 1px 4px rgba(0,0,0,.4);display:flex;align-items:center;justify-content:center;font-size:13px">${em}</div>`});
}
function popupHtml(p){
  const img = p.photo_url ? `<img class="lp-photo" src="${esc(p.photo_url)}" loading="lazy" onerror="this.style.display='none'">` : '';
  const hrs = p.opening_hours ? `<div class="m" style="margin-top:4px">🕒 ${esc(p.opening_hours)}</div>` : '';
  const web = p.website ? `<a href="${esc(p.website)}" target="_blank">Website</a>` : '';
  return `<div class="lp">${img}<h3>${esc(p.name)}</h3>`+
    `<div class="m">${DATA.cats[p.category].emoji} ${esc(DATA.cats[p.category].label)} · ${esc(p.town||'')} · <b>${p.off_km} km</b> off route (Day ${p.day})</div>`+
    `<p>${esc(p.blurb||p.hook||'')}</p>`+ hrs +
    `<div style="margin-top:6px"><a href="${mapsLink(p)}" target="_blank">Google Maps</a><a href="${mapyLink(p)}" target="_blank">Mapy.cz</a>${web}</div>`+
    (p.photo_credit?`<div class="m" style="margin-top:6px">📷 ${esc(p.photo_credit)}</div>`:'')+`</div>`;
}

DATA.pois.forEach(p => {
  const m = L.marker([p.lat,p.lon], {icon:markerIcon(p,false)}).bindPopup(popupHtml(p));
  m.on('click', ()=>{ const el=document.getElementById('poi'+p.id); if(el) el.scrollIntoView({block:'center'}); });
  markers[p.id] = m;
});

function refresh(){
  // markers
  DATA.pois.forEach(p=>{
    const vis = state.cats.has(p.category) && state.days.has(p.day);
    const m = markers[p.id];
    if(vis){ if(!map.hasLayer(m)) m.addTo(map); m.setIcon(markerIcon(p, state.picks.has(p.id))); }
    else if(map.hasLayer(m)) map.removeLayer(m);
  });
  // list
  const list = document.getElementById('list');
  list.innerHTML='';
  DATA.pois.filter(p=>state.cats.has(p.category)&&state.days.has(p.day)).forEach(p=>{
    const c = DATA.cats[p.category].color;
    const row=document.createElement('div'); row.className='poi'; row.id='poi'+p.id;
    row.innerHTML=`<input type="checkbox" ${state.picks.has(p.id)?'checked':''}>`+
      `<div class="body"><div class="nm">${esc(p.name)} <span class="tag" style="background:${c}">${p.off_km}km</span></div>`+
      `<div class="meta">Day ${p.day} · ${esc(p.town||'')} · ${DATA.cats[p.category].emoji} ${esc(DATA.cats[p.category].label)}${p.confidence==='low'?' · ⚠︎ coord?':''}</div>`+
      `<div class="hook">${esc(p.hook||'')}</div></div>`;
    const cb=row.querySelector('input');
    cb.addEventListener('click',e=>{ e.stopPropagation(); toggle(p.id, cb.checked); });
    row.addEventListener('click',()=>{ markers[p.id].openPopup(); map.setView([p.lat,p.lon], Math.max(map.getZoom(),13)); });
    list.appendChild(row);
  });
  document.getElementById('pickcount').textContent = state.picks.size+' picked';
  const chosen = DATA.pois.filter(p=>state.picks.has(p.id));
  document.getElementById('picktext').value = chosen.map(p=>`[Day ${p.day}] ${p.name} (${p.town||''}) — ${p.category}, ${p.off_km}km`).join('\n');
}
function toggle(id, on){ if(on) state.picks.add(id); else state.picks.delete(id); refresh(); }

// filter chips
const cf=document.getElementById('catFilters');
Object.entries(DATA.cats).forEach(([k,v])=>{
  const c=document.createElement('span'); c.className='chip'; c.innerHTML=`<span class="dot" style="background:${v.color}"></span>${v.emoji} ${v.label}`;
  c.onclick=()=>{ if(state.cats.has(k)){state.cats.delete(k);c.classList.add('off');} else {state.cats.add(k);c.classList.remove('off');} refresh(); };
  cf.appendChild(c);
});
const df=document.getElementById('dayFilters');
[[1,'Day 1'],[2,'Day 2']].forEach(([d,lab])=>{
  const c=document.createElement('span'); c.className='chip'; c.textContent=lab;
  c.onclick=()=>{ if(state.days.has(d)){state.days.delete(d);c.classList.add('off');} else {state.days.add(d);c.classList.remove('off');} refresh(); };
  df.appendChild(c);
});
document.getElementById('allBtn').onclick=()=>{ DATA.pois.forEach(p=>state.picks.add(p.id)); refresh(); };
document.getElementById('noneBtn').onclick=()=>{ state.picks.clear(); refresh(); };
document.getElementById('copyBtn').onclick=()=>{
  const ta=document.getElementById('picktext'); ta.style.display='block'; ta.select();
  try{ navigator.clipboard.writeText(ta.value); document.getElementById('copyBtn').textContent='Copied ✓'; setTimeout(()=>document.getElementById('copyBtn').textContent='Copy picks',1500);}catch(e){}
};
refresh();
</script>
</body>
</html>"""

if __name__ == '__main__':
    main()
