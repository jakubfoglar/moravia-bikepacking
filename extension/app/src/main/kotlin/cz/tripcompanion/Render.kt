package cz.tripcompanion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/** Builds all RemoteViews. Card = glance (hook only), Reading = full story, one tap apart. */
object Render {
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

    private fun catLabel(cat: String): String = when (cat) {
        "history" -> "HISTORY"
        "cafe" -> "CAFÉ"
        "food" -> "FOOD"
        else -> "NATURE"
    }

    private fun pillRes(cat: String): Int = when (cat) {
        "history" -> R.drawable.pill_history
        "cafe" -> R.drawable.pill_cafe
        "food" -> R.drawable.pill_food
        else -> R.drawable.pill_nature
    }

    private fun distParts(km: Double): Pair<String, String> =
        if (km < 1.0) Pair((km * 1000).toInt().toString(), "m")
        else Pair(String.format(Locale.US, "%.1f", km), "km")

    private fun distInline(remainingKm: Double?, located: Boolean): String {
        if (!located || remainingKm == null) return "locating…"
        val (n, u) = distParts(remainingKm)
        return "$n $u"
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

    private fun setBadge(rv: RemoteViews, poi: Poi) {
        rv.setTextViewText(R.id.badge, "${catEmoji(poi.category)} ${catLabel(poi.category)}")
        rv.setInt(R.id.badge, "setBackgroundResource", pillRes(poi.category))
    }

    // ---- Card (glance) ----
    fun card(ctx: Context, poi: Poi, pos: Int, total: Int, remainingKm: Double?, located: Boolean, nearestMode: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_catalog)
        setPhoto(ctx, rv, poi)
        setBadge(rv, poi)
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(R.id.sub, "${poi.town} · Day ${poi.day}")

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

        // Food shows hours on the card; everything else shows the hook.
        if (poi.category == "food" && poi.opening_hours != null) {
            rv.setTextViewText(R.id.hook, "🕒 ${poi.opening_hours}")
            rv.setTextColor(R.id.hook, catColor(poi.category))
        } else {
            rv.setTextViewText(R.id.hook, poi.hook)
            rv.setTextColor(R.id.hook, Color.parseColor("#26262B"))
        }

        val hasMore = poi.blurb.isNotBlank() && poi.blurb != poi.hook
        if (hasMore) {
            rv.setViewVisibility(R.id.more, View.VISIBLE)
            rv.setTextViewText(R.id.more, "Full story ›")
            rv.setTextColor(R.id.more, catColor(poi.category))
            rv.setOnClickPendingIntent(R.id.more, pendingNav(ctx, "more", 4))
        } else {
            rv.setViewVisibility(R.id.more, View.GONE)
        }

        rv.setTextViewText(R.id.cnt_label, if (nearestMode) "NEAREST AHEAD" else "ROUTE ORDER")
        rv.setTextViewText(R.id.cnt_value, "$pos / $total")
        rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "prev", 1))
        rv.setOnClickPendingIntent(R.id.btn_next, pendingNav(ctx, "next", 2))
        return rv
    }

    // ---- Reading (full story) ----
    fun reading(ctx: Context, poi: Poi, remainingKm: Double?, located: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_reading)
        setBadge(rv, poi)
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(R.id.meta, "${poi.town} · Day ${poi.day} · ${distInline(remainingKm, located)}")
        if (poi.opening_hours != null) {
            rv.setViewVisibility(R.id.hours, View.VISIBLE)
            rv.setTextViewText(R.id.hours, "🕒 ${poi.opening_hours}")
        } else {
            rv.setViewVisibility(R.id.hours, View.GONE)
        }
        rv.setTextViewText(R.id.blurb, poi.blurb.ifBlank { poi.hook })
        rv.setOnClickPendingIntent(R.id.btn_back, pendingNav(ctx, "back", 5))
        return rv
    }

    // ---- End (all passed) ----
    fun end(ctx: Context, total: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_end)
        rv.setTextViewText(R.id.end_title, "That's all $total POIs")
        rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "reset", 3))
        return rv
    }

    // ---- Next POI (compact) ----
    fun next(ctx: Context, poi: Poi?, remainingKm: Double?, located: Boolean, heightPx: Int): RemoteViews {
        val small = heightPx in 1 until 140
        return if (small) nextSmall(ctx, poi, remainingKm, located) else nextMedium(ctx, poi, remainingKm, located)
    }

    private fun nextMedium(ctx: Context, poi: Poi?, remainingKm: Double?, located: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_next)
        if (poi == null) {
            rv.setViewVisibility(R.id.photo, View.GONE)
            rv.setViewVisibility(R.id.tile, View.VISIBLE)
            rv.setInt(R.id.tile, "setBackgroundColor", Color.parseColor("#888888"))
            rv.setTextViewText(R.id.tile, "🏁")
            rv.setTextViewText(R.id.name, "All POIs done")
            rv.setTextViewText(R.id.dist, "")
            return rv
        }
        setPhoto(ctx, rv, poi)
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(R.id.dist, "${catEmoji(poi.category)} ${distInline(remainingKm, located)}")
        return rv
    }

    private fun nextSmall(ctx: Context, poi: Poi?, remainingKm: Double?, located: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_next_small)
        rv.setTextViewText(
            R.id.line,
            if (poi == null) "🏁 All POIs done"
            else "${catEmoji(poi.category)} ${distInline(remainingKm, located)} · ${poi.name}",
        )
        return rv
    }

    // ---- Error ----
    fun error(ctx: Context, where: String, e: Throwable): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_error)
        rv.setTextViewText(R.id.err_title, "⚠ $where")
        rv.setTextViewText(
            R.id.err_body,
            "${e.javaClass.simpleName}: ${e.message}\n\nOpen Trip Companion → Share logs.",
        )
        return rv
    }
}
