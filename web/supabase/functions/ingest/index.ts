import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import Anthropic from "npm:@anthropic-ai/sdk@0.68.0";

const INGEST_SECRET = Deno.env.get("INGEST_SECRET")!;
const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);
const anthropic = new Anthropic({ apiKey: Deno.env.get("ANTHROPIC_API_KEY")! });

// Optional: mirror every new post to Telegram so the phone buzzes — and the Karoo
// shows it via the Companion app's notification relay. Text only; the photo stays
// on the website (a Telegram photo upload would be a second, much slower round trip).
const TG_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
const TG_CHAT = Deno.env.get("TELEGRAM_CHAT_ID") ?? "";
const SITE_URL = Deno.env.get("SITE_URL") ?? "";

async function notifyTelegram(kind: string, caption: string, hasMedia: boolean) {
  if (!TG_TOKEN || !TG_CHAT || !caption) return;
  const tag = kind === "photo" ? "📷 " : kind === "drawing" ? "✏️ " : "";
  const tail = hasMedia && SITE_URL ? `\n${SITE_URL}` : "";
  try {
    const r = await fetch(`https://api.telegram.org/bot${TG_TOKEN}/sendMessage`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        chat_id: TG_CHAT,
        text: `${tag}${caption}${tail}`,
        disable_web_page_preview: true,
      }),
    });
    if (!r.ok) console.error("telegram", r.status, (await r.text()).slice(0, 120));
  } catch (e) {
    console.error("telegram", String(e)); // never let a notification failure fail the post
  }
}

const VOICE = `Jsi komentátor Kubova bikepackingového výletu Moravou (Břeclav → Velehrad → Otrokovice, 265 km, dva dny).
Píšeš krátké popisky do veřejného deníku, který sleduje hlavně jeho žena.

Jak píšeš:
- Česky. Jedna věta, výjimečně dvě. Nikdy víc.
- Suše vtipně. Podceňuješ, nepřeháníš. Ironie ano, klaunování ne.
- Píšeš Kubovým hlasem, v první osobě.
- Když dostaneš fotku nebo kresbu, komentuješ CO NA NÍ OPRAVDU JE. Ne obecné fráze.
- Žádné emoji, žádné vykřičníky, žádné "Wow" ani "Krása".
- Nikdy nevysvětluj, že jsi AI, a nepiš nic mimo tu jednu větu.

Co znamenají údaje (drž se jich přesně, nedomýšlej si):
- Druhý den končí v Otrokovicích vlakem do Prahy (15:51, záložní 17:51). Vlak JEŠTĚ NEJEL.
- verdikt "green" = vlak stíhám v klidu. "amber" = stihnu ho, ale musím šlapat. "red" = takhle ho nestíhám.
- "aut" = kolik aut mě dnes předjelo (počítá radar). Není to nic dramatického, jen to počítám.
- "stoji_minut" = jak dlouho stojím na místě. Nejspíš jím nebo koukám na něco.
- "procent" = kolik procent dnešní trasy mám za sebou.
- "zbyva_km" = kolik kilometrů ještě zbývá dnes.
- "off"/"o_cem" u místa je jen poznámka k tomu místu, ne něco, co jsem udělal.`;

// If Fable declines or errors, the feed still gets a line rather than a blank.
const FALLBACK: Record<string, string> = {
  start: "Vyrazil jsem.",
  quarter: "Kus za mnou.",
  poi: "Zastávka.",
  stop: "Stojím. Nejspíš jím.",
  cars: "Počítám auta.",
  train: "Vlak se počítá.",
  finish: "Dojel jsem.",
  photo: "Fotka z cesty.",
  drawing: "Kresba z cesty.",
  text: "",
};

function factsToPrompt(kind: string, event: string | null, body: string | null, facts: Record<string, unknown> | null) {
  const lines: string[] = [];
  if (event) lines.push(`Událost: ${event}`);
  if (facts) for (const [k, v] of Object.entries(facts)) lines.push(`${k}: ${v}`);
  if (body) lines.push(`Kuba napsal: "${body}"`);
  if (kind === "photo") lines.push("K tomu je přiložená fotka. Komentuj to, co na ní je.");
  if (kind === "drawing") lines.push("K tomu je přiložená kresba, kterou Kuba nakreslil prstem na Karoo. Komentuj tu kresbu.");
  lines.push("Napiš jednu větu do deníku.");
  return lines.join("\n");
}

async function caption(
  kind: string,
  event: string | null,
  body: string | null,
  facts: Record<string, unknown> | null,
  imageB64: string | null,
  mediaType: string | null,
): Promise<{ text: string; source: "fable" | "fallback" }> {
  const content: Anthropic.ContentBlockParam[] = [];
  if (imageB64 && mediaType) {
    content.push({
      type: "image",
      source: { type: "base64", media_type: mediaType as "image/jpeg", data: imageB64 },
    });
  }
  content.push({ type: "text", text: factsToPrompt(kind, event, body, facts) });

  try {
    const res = await anthropic.beta.messages.create({
      model: "claude-fable-5",
      // Fable's thinking is always on and shares max_tokens with the reply, so this
      // has to be far bigger than the one sentence we want — at 300 an image post
      // could spend the whole budget thinking and return a truncated caption.
      // Costs nothing extra: we're billed on tokens produced, not the ceiling.
      max_tokens: 2000,
      // Do not send a `thinking` param — Fable rejects any explicit thinking config.
      output_config: { effort: "low" },
      betas: ["server-side-fallback-2026-06-01"],
      fallbacks: [{ model: "claude-opus-4-8" }],
      system: VOICE,
      messages: [{ role: "user", content }],
    });

    const fb = { text: FALLBACK[event ?? kind] ?? FALLBACK[kind] ?? "", source: "fallback" as const };
    if (res.stop_reason === "refusal") return fb;
    const text = res.content.filter((b) => b.type === "text").map((b) => b.text).join(" ").trim();
    // A truncated caption is worse than a canned one — don't publish half a sentence.
    if (!text || res.stop_reason === "max_tokens") return fb;
    return { text, source: "fable" };
  } catch (_e) {
    return { text: FALLBACK[event ?? kind] ?? FALLBACK[kind] ?? "", source: "fallback" };
  }
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  if (req.headers.get("x-trip-secret") !== INGEST_SECRET) return new Response("nope", { status: 401 });

  const p = await req.json();

  // State-only ping from the Karoo: refresh the "now" block, don't post to the feed.
  if (p.type === "state") {
    await supabase.from("ride_state").update({
      updated_at: new Date().toISOString(),
      day: p.day, lat: p.lat, lon: p.lon, place: p.place,
      day_km: p.day_km, day_total_km: p.day_total_km,
      moving_secs: p.moving_secs, cars: p.cars,
      stopped_since: p.stopped_since ?? null,
      train_verdict: p.train_verdict ?? null,
    }).eq("id", 1);
    return Response.json({ ok: true });
  }

  const kind: string = p.kind;
  let mediaUrl: string | null = null;
  let imageB64: string | null = null;
  let mediaType: string | null = null;

  if (p.media_b64) {
    imageB64 = p.media_b64;
    mediaType = p.media_type ?? "image/jpeg";
    const ext = mediaType === "image/png" ? "png" : "jpg";
    const path = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}.${ext}`;
    const bytes = Uint8Array.from(atob(p.media_b64), (c) => c.charCodeAt(0));
    const { error } = await supabase.storage.from("media")
      .upload(path, bytes, { contentType: mediaType });
    if (error) return Response.json({ error: error.message }, { status: 500 });
    mediaUrl = supabase.storage.from("media").getPublicUrl(path).data.publicUrl;
  }

  // The Karoo sends SVG stroke paths, not a bitmap — rasterize-free: store as-is,
  // and hand Fable a PNG the client already rendered alongside it.
  if (kind === "drawing" && p.svg) {
    const path = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}.svg`;
    await supabase.storage.from("media")
      .upload(path, new TextEncoder().encode(p.svg), { contentType: "image/svg+xml" });
    mediaUrl = supabase.storage.from("media").getPublicUrl(path).data.publicUrl;
  }

  const c = await caption(kind, p.event ?? null, p.body ?? null, p.facts ?? null, imageB64, mediaType);

  const { data, error } = await supabase.from("posts").insert({
    kind,
    event: p.event ?? null,
    body: p.body ?? null,
    facts: p.facts ?? null,
    media_url: mediaUrl,
    caption: c.text,
    caption_source: c.source,
    lat: p.lat ?? null,
    lon: p.lon ?? null,
    day: p.day ?? null,
  }).select("id, caption").single();

  if (error) return Response.json({ error: error.message }, { status: 500 });

  await notifyTelegram(kind, c.text, mediaUrl != null);

  return Response.json({ ok: true, id: data.id, caption: data.caption });
});
