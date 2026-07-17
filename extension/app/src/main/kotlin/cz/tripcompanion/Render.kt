package cz.tripcompanion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/** Builds all RemoteViews. The catalog card renders in three tiers by field height. */
object Render {
    private val CS = Locale.forLanguageTag("cs")

    fun catColor(cat: String): Int = when (cat) {
        "history" -> Color.parseColor("#6A1B9A")
        "cafe" -> Color.parseColor("#E07B00")
        "food" -> Color.parseColor("#C0392B")
        else -> Color.parseColor("#2E7D32")
    }

    fun catEmoji(cat: String): String = when (cat) {
        "history" -> "🏰"
        "cafe" -> "☕"
        "food" -> "🍽"
        else -> "🌄"
    }

    fun catLabel(ctx: Context, cat: String): String = ctx.getString(
        when (cat) {
            "history" -> R.string.cat_history
            "cafe" -> R.string.cat_cafe
            "food" -> R.string.cat_food
            else -> R.string.cat_nature
        },
    )

    fun distParts(km: Double): Pair<String, String> =
        if (km < 1.0) Pair((km * 1000).toInt().toString(), "m")
        else Pair(String.format(CS, "%.1f", km), "km")

    fun distText(km: Double): String {
        val (n, u) = distParts(km)
        return "$n $u"
    }

    private fun distInline(ctx: Context, remainingKm: Double?, located: Boolean): String =
        if (!located || remainingKm == null) ctx.getString(R.string.fld_gps)
        else distText(remainingKm)

    /**
     * "Otevřeno do 20:00" (green) / "Zavírá v 20:00" (red, ≤45 min) / "Zavřeno · otevírá v 11:00"
     * (grey) — or null when the spec is missing/unparseable.
     */
    fun hoursLine(ctx: Context, spec: String?): Pair<String, Int>? =
        when (val st = OpeningHours.status(spec)) {
            is OpeningHours.Status.Open ->
                if (st.closingSoon && st.untilHm != null) {
                    Pair(ctx.getString(R.string.hrs_closing_at, st.untilHm), ctx.getColor(R.color.k_red))
                } else if (st.untilHm != null) {
                    Pair(ctx.getString(R.string.hrs_open_until, st.untilHm), ctx.getColor(R.color.k_green))
                } else {
                    Pair(ctx.getString(R.string.hrs_open), ctx.getColor(R.color.k_green))
                }
            is OpeningHours.Status.Closed ->
                if (st.opensHm != null) {
                    Pair(ctx.getString(R.string.hrs_opens_at, st.opensHm), ctx.getColor(R.color.k_muted_dark))
                } else {
                    Pair(ctx.getString(R.string.hrs_closed), ctx.getColor(R.color.k_muted_dark))
                }
            OpeningHours.Status.Unknown -> null
        }

    private fun rounded(ctx: Context, bmp: Bitmap): Bitmap = try {
        val r = 10f * ctx.resources.displayMetrics.density
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), r, r, paint)
        out
    } catch (e: Exception) {
        bmp
    }

    fun pendingDetail(ctx: Context, poiId: Int): PendingIntent {
        val i = Intent(ctx, PoiDetailActivity::class.java).apply {
            putExtra("id", poiId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            ctx, 1000 + poiId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun pendingNav(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, NavReceiver::class.java).apply {
            this.action = NavReceiver.ACTION
            putExtra("action", action)
        }
        return PendingIntent.getBroadcast(
            ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setPhoto(ctx: Context, rv: RemoteViews, poi: Poi) {
        val bmp = if (poi.hasPhoto) PoiRepository.photo(ctx, poi.id) else null
        if (bmp != null) {
            rv.setViewVisibility(R.id.photo, View.VISIBLE)
            rv.setViewVisibility(R.id.tile, View.GONE)
            rv.setImageViewBitmap(R.id.photo, rounded(ctx, bmp))
        } else {
            rv.setViewVisibility(R.id.photo, View.GONE)
            rv.setViewVisibility(R.id.tile, View.VISIBLE)
            rv.setInt(R.id.tile, "setBackgroundColor", catColor(poi.category))
            rv.setTextViewText(R.id.tile, catEmoji(poi.category))
        }
    }

    /** Category as a small uppercase label in the category colour (the Karoo row-label idiom). */
    private fun setBadge(ctx: Context, rv: RemoteViews, poi: Poi) {
        rv.setTextViewText(R.id.badge, catLabel(ctx, poi.category))
        rv.setTextColor(R.id.badge, catColor(poi.category))
    }

    /** The one line under the header: live hours for eat/drink stops, the hook for sights. */
    private fun hookText(ctx: Context, poi: Poi): Pair<String, Int> {
        val ink = ctx.getColor(R.color.k_ink)
        if (poi.kind == PoiKind.EATDRINK) {
            hoursLine(ctx, poi.opening_hours)?.let { return it }
            poi.cuisine?.let { return Pair(it, ink) }
            // Machine hook ("regional;pasta — from OpenStreetMap") → salvage the cuisine part.
            return Pair(poi.hook.substringBefore("— from OpenStreetMap").trim().replace(";", ", "), ink)
        }
        return Pair(poi.hook, ink)
    }

    // ---- Catalog card: TINY (<160 px) / MEDIUM (<420 px) / FULL, by field height ----
    fun card(ctx: Context, poi: Poi, pos: Int, total: Int, remainingKm: Double?, located: Boolean, nearestMode: Boolean, heightPx: Int = 0): RemoteViews =
        when {
            heightPx in 1 until 160 -> cardTiny(ctx, poi, remainingKm, located)
            heightPx in 160 until 420 -> cardMedium(ctx, poi, remainingKm, located)
            else -> cardFull(ctx, poi, pos, total, remainingKm, located, nearestMode)
        }

    private fun cardTiny(ctx: Context, poi: Poi, remainingKm: Double?, located: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_catalog_tiny)
        rv.setOnClickPendingIntent(R.id.card_root, pendingDetail(ctx, poi.id))
        setPhoto(ctx, rv, poi)
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(
            R.id.dist,
            if (located && remainingKm != null) ctx.getString(R.string.fld_in_dist, distText(remainingKm))
            else ctx.getString(R.string.fld_gps),
        )
        return rv
    }

    private fun cardMedium(ctx: Context, poi: Poi, remainingKm: Double?, located: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_catalog_medium)
        rv.setOnClickPendingIntent(R.id.card_root, pendingDetail(ctx, poi.id))
        setPhoto(ctx, rv, poi)
        setBadge(ctx, rv, poi)
        rv.setTextViewText(R.id.name, poi.name)
        val (hook, hookColor) = hookText(ctx, poi)
        rv.setTextViewText(R.id.hook, hook)
        rv.setTextColor(R.id.hook, hookColor)
        rv.setTextViewText(R.id.dist, distInline(ctx, remainingKm, located))
        rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "prev", 1))
        rv.setOnClickPendingIntent(R.id.btn_next, pendingNav(ctx, "next", 2))
        return rv
    }

    private fun cardFull(ctx: Context, poi: Poi, pos: Int, total: Int, remainingKm: Double?, located: Boolean, nearestMode: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_catalog)
        // Tapping anywhere on the card (outside the nav buttons) opens the full detail.
        rv.setOnClickPendingIntent(R.id.card_root, pendingDetail(ctx, poi.id))
        setPhoto(ctx, rv, poi)
        setBadge(ctx, rv, poi)
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(R.id.sub, "${poi.town} · ${ctx.getString(R.string.fld_day_n, poi.day)}")

        if (located && remainingKm != null) {
            rv.setViewVisibility(R.id.dist_band, View.VISIBLE)
            rv.setViewVisibility(R.id.gps_band, View.GONE)
            val (n, u) = distParts(remainingKm)
            rv.setTextViewText(R.id.dist_num, n)
            rv.setTextViewText(R.id.dist_unit, u)
        } else {
            rv.setViewVisibility(R.id.dist_band, View.GONE)
            rv.setViewVisibility(R.id.gps_band, View.VISIBLE)
        }

        val (hook, hookColor) = hookText(ctx, poi)
        rv.setTextViewText(R.id.hook, hook)
        rv.setTextColor(R.id.hook, hookColor)

        val hasMore = poi.blurb.isNotBlank() && poi.blurb != poi.hook
        if (hasMore) {
            rv.setViewVisibility(R.id.more, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.more, pendingDetail(ctx, poi.id))
        } else {
            rv.setViewVisibility(R.id.more, View.GONE)
        }

        rv.setTextViewText(
            R.id.cnt_label,
            ctx.getString(if (nearestMode) R.string.fld_order_ahead else R.string.fld_order_route),
        )
        rv.setTextViewText(R.id.cnt_value, "$pos / $total")
        rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "prev", 1))
        rv.setOnClickPendingIntent(R.id.btn_next, pendingNav(ctx, "next", 2))
        return rv
    }

    // ---- End (all passed) ----
    fun end(ctx: Context, total: Int, tiny: Boolean = false): RemoteViews {
        if (tiny) {
            val rv = RemoteViews(ctx.packageName, R.layout.poi_catalog_tiny)
            rv.setViewVisibility(R.id.photo, View.GONE)
            rv.setViewVisibility(R.id.tile, View.VISIBLE)
            rv.setInt(R.id.tile, "setBackgroundColor", ctx.getColor(R.color.k_muted))
            rv.setTextViewText(R.id.tile, "🏁")
            rv.setTextViewText(R.id.name, ctx.getString(R.string.fld_end_title, total))
            rv.setTextViewText(R.id.dist, "")
            return rv
        }
        val rv = RemoteViews(ctx.packageName, R.layout.poi_end)
        rv.setTextViewText(R.id.end_title, ctx.getString(R.string.fld_end_title, total))
        rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "reset", 3))
        return rv
    }

    // ---- Train Catcher ----
    fun train(ctx: Context, tv: TrainView): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.train_field)
        fun vColor(v: Verdict) = when (v) {
            Verdict.GREEN -> Color.parseColor("#2E7D32")
            Verdict.AMBER -> Color.parseColor("#E07B00")
            Verdict.RED -> Color.parseColor("#C0392B")
            Verdict.NONE -> Color.parseColor("#14141A")
        }
        if (tv.inactive) {
            rv.setTextViewText(R.id.t_train, "🚆 Train Catcher")
            rv.setTextColor(R.id.t_train, Color.parseColor("#5A5A60"))
            rv.setViewVisibility(R.id.t_need_band, View.GONE)
            rv.setTextViewText(R.id.t_remaining, "activates on Day 2")
            rv.setViewVisibility(R.id.t_you, View.GONE)
            rv.setViewVisibility(R.id.t_verdict, View.GONE)
            rv.setViewVisibility(R.id.t_fallback, View.GONE)
            return rv
        }
        if (!tv.located) {
            rv.setTextViewText(R.id.t_train, "🚆 15:51 / 17:51 → ${TrainConfig.DEST}")
            rv.setTextColor(R.id.t_train, Color.parseColor("#14141A"))
            rv.setViewVisibility(R.id.t_need_band, View.GONE)
            rv.setTextViewText(R.id.t_remaining, "waiting for GPS…")
            rv.setViewVisibility(R.id.t_you, View.GONE)
            rv.setViewVisibility(R.id.t_verdict, View.GONE)
            rv.setViewVisibility(R.id.t_fallback, View.GONE)
            return rv
        }
        if (tv.noTrains) {
            rv.setTextViewText(R.id.t_train, "🚆 last train has gone")
            rv.setTextColor(R.id.t_train, Color.parseColor("#C0392B"))
            rv.setViewVisibility(R.id.t_need_band, View.GONE)
            rv.setTextViewText(R.id.t_remaining, String.format(Locale.US, "%.0f km to %s", tv.remainingKm, TrainConfig.STATION))
            rv.setViewVisibility(R.id.t_you, View.GONE)
            rv.setViewVisibility(R.id.t_verdict, View.GONE)
            rv.setViewVisibility(R.id.t_fallback, View.GONE)
            return rv
        }
        rv.setTextViewText(R.id.t_train, "🚆 ${tv.label} → ${TrainConfig.DEST}")
        rv.setTextColor(R.id.t_train, vColor(tv.verdict))
        rv.setViewVisibility(R.id.t_need_band, View.VISIBLE)
        val needTxt = when {
            tv.requiredKmh >= 100 -> "—"
            tv.requiredKmh >= 10 -> String.format(Locale.US, "%.0f", tv.requiredKmh)
            else -> String.format(Locale.US, "%.1f", tv.requiredKmh)
        }
        rv.setTextViewText(R.id.t_need_num, needTxt)
        rv.setTextColor(R.id.t_need_num, vColor(tv.verdict))
        rv.setTextViewText(R.id.t_remaining, String.format(Locale.US, "%.1f km to %s", tv.remainingKm, TrainConfig.STATION))

        if (tv.youKmh != null) {
            rv.setViewVisibility(R.id.t_you, View.VISIBLE)
            rv.setTextViewText(R.id.t_you, String.format(Locale.US, "you're at %.1f km/h avg", tv.youKmh))
        } else {
            rv.setViewVisibility(R.id.t_you, View.GONE)
        }

        rv.setViewVisibility(R.id.t_verdict, View.VISIBLE)
        if (tv.marginMin != null) {
            if (tv.marginMin >= 0) {
                rv.setTextViewText(R.id.t_verdict, "✓ making it · +${tv.marginMin} min")
                rv.setTextColor(R.id.t_verdict, Color.parseColor("#2E7D32"))
            } else {
                rv.setTextViewText(R.id.t_verdict, "✗ ${-tv.marginMin} min short")
                rv.setTextColor(R.id.t_verdict, Color.parseColor("#C0392B"))
            }
        } else {
            val txt: String
            val col: String
            when (tv.verdict) {
                Verdict.GREEN -> { txt = "comfortable at your pace"; col = "#2E7D32" }
                Verdict.AMBER -> { txt = "doable if you keep rolling"; col = "#E07B00" }
                Verdict.RED -> { txt = "not at your usual pace"; col = "#C0392B" }
                Verdict.NONE -> { txt = ""; col = "#14141A" }
            }
            rv.setTextViewText(R.id.t_verdict, txt)
            rv.setTextColor(R.id.t_verdict, Color.parseColor(col))
        }

        if (tv.fallback != null) {
            rv.setViewVisibility(R.id.t_fallback, View.VISIBLE)
            rv.setTextViewText(R.id.t_fallback, tv.fallback)
        } else {
            rv.setViewVisibility(R.id.t_fallback, View.GONE)
        }
        return rv
    }

    // ---- Day-overview minimap (Canvas-rendered route shape) ----
    fun overview(ctx: Context, viewSize: Pair<Int, Int>, day: Int, track: List<DoubleArray>, totalKm: Double, st: AppState.State): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_overview)
        val d = ctx.resources.displayMetrics.density
        var w = viewSize.first
        var h = viewSize.second
        if (w < 40) w = (400 * d).toInt()
        if (h < 40) h = (330 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        if (track.isEmpty()) { rv.setImageViewBitmap(R.id.map, bmp); return rv }

        val pad = 12f * d
        val textBand = 56f * d
        val mapBottom = h - textBand - pad
        val lats = track.map { it[0] }
        val lons = track.map { it[1] }
        val la0 = lats.minOrNull()!!; val la1 = lats.maxOrNull()!!
        val lo0 = lons.minOrNull()!!; val lo1 = lons.maxOrNull()!!
        val kx = Math.cos(Math.toRadians((la0 + la1) / 2))
        val xr = ((lo1 - lo0) * kx).coerceAtLeast(1e-6)
        val yr = (la1 - la0).coerceAtLeast(1e-6)
        val mapW = w - 2 * pad; val mapH = mapBottom - pad
        val s = Math.min(mapW / xr, mapH / yr)
        val ox = pad + (mapW - xr * s) / 2
        val oy = pad + (mapH - yr * s) / 2
        fun sx(lon: Double) = (ox + (lon - lo0) * kx * s).toFloat()
        fun sy(lat: Double) = (oy + (la1 - lat) * s).toFloat()

        // Day 1: golden "Moravian Tuscany" scenic area under the track.
        if (day == 1 && TripSettings.scenicEnabled(ctx)) {
            val poly = arrayOf(
                doubleArrayOf(49.045, 16.965), doubleArrayOf(49.045, 17.135),
                doubleArrayOf(48.925, 17.145), doubleArrayOf(48.905, 16.975),
            )
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#47DAA520") }
            val gp = Path().apply {
                moveTo(sx(poly[0][1]), sy(poly[0][0]))
                for (i in 1 until poly.size) lineTo(sx(poly[i][1]), sy(poly[i][0]))
                close()
            }
            c.drawPath(gp, fill)
            val glabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9C7A00"); textSize = 9f * d; isFakeBoldText = true }
            c.drawText("Toskánsko", sx(17.00), sy(48.985), glabel)
        }

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3.2f * d
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#E6197A")
        }
        val path = Path().apply {
            moveTo(sx(track[0][1]), sy(track[0][0]))
            for (i in 1 until track.size) lineTo(sx(track[i][1]), sy(track[i][0]))
        }
        c.drawPath(path, line)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = Color.parseColor("#2E7D32")
        c.drawCircle(sx(track[0][1]), sy(track[0][0]), 4f * d, dot)
        dot.color = Color.parseColor("#14141A")
        val ex = sx(track.last()[1]); val ey = sy(track.last()[0])
        c.drawRect(ex - 4f * d, ey - 4f * d, ex + 4f * d, ey + 4f * d, dot)

        var remaining = totalKm
        var pct = 0
        val la = st.lat; val lo = st.lon
        if (st.located && la != null && lo != null) {
            var best = 1e9; var bk = 0.0
            for (t in track) { val dd = RouteMath.haversine(la, lo, t[0], t[1]); if (dd < best) { best = dd; bk = t[2] } }
            remaining = (totalKm - bk).coerceAtLeast(0.0)
            pct = if (totalKm > 0) ((bk / totalKm) * 100).toInt() else 0
            val halo = Paint(Paint.ANTI_ALIAS_FLAG); halo.color = Color.WHITE
            c.drawCircle(sx(lo), sy(la), 6.5f * d, halo)
            dot.color = Color.parseColor("#1a73e8"); c.drawCircle(sx(lo), sy(la), 5f * d, dot)
        }

        val endName = if (day == 2) "OTROKOVICE" else "VELEHRAD"
        val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A5A60"); textSize = 10f * d; isFakeBoldText = true }
        c.drawText("DAY $day · TO $endName", pad, mapBottom + 18f * d, t1)
        val big = "${remaining.toInt()} km"
        val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14141A"); textSize = 25f * d; isFakeBoldText = true }
        c.drawText(big, pad, mapBottom + 44f * d, t2)
        val t3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A5A60"); textSize = 12f * d }
        c.drawText("· $pct% done", pad + t2.measureText(big) + 8f * d, mapBottom + 44f * d, t3)

        rv.setImageViewBitmap(R.id.map, bmp)
        return rv
    }

    // ---- Radar ----
    fun radar(ctx: Context, ui: RadarEngine.Ui, enabled: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.radar_field)
        if (!enabled) {
            rv.setTextViewText(R.id.r_count, "—")
            rv.setTextColor(R.id.r_count, Color.parseColor("#5A5A60"))
            rv.setTextViewText(R.id.r_count_unit, "")
            rv.setTextViewText(R.id.r_detail, "off · enable in Trip Companion")
            rv.setTextColor(R.id.r_detail, Color.parseColor("#5A5A60"))
            return rv
        }
        rv.setTextViewText(R.id.r_count, ui.count.toString())
        rv.setTextColor(R.id.r_count, Color.parseColor("#14141A"))
        rv.setTextViewText(R.id.r_count_unit, "cars passed")
        if (!ui.active) {
            rv.setTextViewText(R.id.r_detail, "waiting for radar signal…")
            rv.setTextColor(R.id.r_detail, Color.parseColor("#5A5A60"))
        } else if (ui.hasTarget) {
            val col = when (ui.threat) {
                3 -> "#C0392B"; 2 -> "#E07B00"; else -> "#2E7D32"
            }
            rv.setTextViewText(R.id.r_detail, "● ${ui.nearestM} m · ~${ui.lastSpeedKmh} km/h")
            rv.setTextColor(R.id.r_detail, Color.parseColor(col))
        } else {
            rv.setTextViewText(R.id.r_detail, "clear · last ~${ui.lastSpeedKmh} km/h")
            rv.setTextColor(R.id.r_detail, Color.parseColor("#2E7D32"))
        }
        return rv
    }

    // ---- Error ----
    fun error(ctx: Context, where: String, e: Throwable): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_error)
        rv.setTextViewText(R.id.err_title, ctx.getString(R.string.fld_err_title, where))
        rv.setTextViewText(
            R.id.err_body,
            "${e.javaClass.simpleName}: ${e.message}\n\n${ctx.getString(R.string.fld_err_hint)}",
        )
        return rv
    }
}
