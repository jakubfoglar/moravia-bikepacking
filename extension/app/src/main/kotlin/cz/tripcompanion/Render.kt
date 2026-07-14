package cz.tripcompanion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/** Builds the RemoteViews for both fields (Layout B for the catalog). */
object Render {
    fun catColor(cat: String): Int = when (cat) {
        "history" -> Color.parseColor("#6a1b9a")
        "cafe" -> Color.parseColor("#e07b00")
        "food" -> Color.parseColor("#c0392b")
        else -> Color.parseColor("#2e7d32")
    }

    fun catEmoji(cat: String): String = when (cat) {
        "history" -> "🏰"   // castle
        "cafe" -> "☕"            // coffee
        "food" -> "🍽"      // plate
        else -> "🌄"        // sunrise / viewpoint
    }

    fun catLabel(cat: String): String = when (cat) {
        "history" -> "HISTORY"
        "cafe" -> "CAFÉ"
        "food" -> "FOOD"
        else -> "NATURE"
    }

    private fun distText(remainingKm: Double?, located: Boolean): String {
        if (!located || remainingKm == null) return "➜ — locating…"
        return if (remainingKm < 1.0) "➜ ${(remainingKm * 1000).toInt()} m"
        else String.format(Locale.US, "➜ %.1f km", remainingKm)
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

    fun catalog(
        ctx: Context,
        poi: Poi?,
        pos: Int,
        total: Int,
        remainingKm: Double?,
        located: Boolean,
        nearestMode: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_catalog)
        if (poi == null) {
            rv.setViewVisibility(R.id.top, View.GONE)
            rv.setViewVisibility(R.id.divider, View.GONE)
            rv.setTextViewText(R.id.blurb, "🏁  That's all your POIs — everything's behind you now. Nice riding!")
            rv.setTextViewText(R.id.counter, "done")
            rv.setTextViewText(R.id.btn_prev, "‹ back to first")
            rv.setTextViewText(R.id.btn_next, "")
            rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "reset", 3))
            return rv
        }

        val bmp = if (poi.hasPhoto) PoiRepository.photo(ctx, poi.id) else null
        if (bmp != null) {
            rv.setViewVisibility(R.id.photo, View.VISIBLE)
            rv.setViewVisibility(R.id.tile, View.GONE)
            rv.setImageViewBitmap(R.id.photo, bmp)
        } else {
            rv.setViewVisibility(R.id.photo, View.GONE)
            rv.setViewVisibility(R.id.tile, View.VISIBLE)
            rv.setInt(R.id.tile, "setBackgroundColor", catColor(poi.category))
            rv.setTextViewText(R.id.tile, catEmoji(poi.category))
        }

        rv.setTextViewText(R.id.badge, "${catEmoji(poi.category)} ${catLabel(poi.category)}")
        rv.setInt(R.id.badge, "setBackgroundColor", catColor(poi.category))
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(R.id.sub, "${poi.town} · Day ${poi.day}")
        rv.setTextViewText(R.id.dist, distText(remainingKm, located))

        val body = if (poi.category == "food" && poi.opening_hours != null) {
            "🕒 ${poi.opening_hours}\n${poi.hook}"
        } else {
            poi.blurb.ifBlank { poi.hook }
        }
        rv.setTextViewText(R.id.blurb, body)
        rv.setTextViewText(
            R.id.counter,
            (if (nearestMode) "next ahead\n" else "route order\n") + "$pos / $total",
        )
        rv.setTextViewText(R.id.btn_prev, "‹ prev")
        rv.setTextViewText(R.id.btn_next, "next ›")
        rv.setOnClickPendingIntent(R.id.btn_prev, pendingNav(ctx, "prev", 1))
        rv.setOnClickPendingIntent(R.id.btn_next, pendingNav(ctx, "next", 2))
        return rv
    }

    fun next(ctx: Context, poi: Poi?, remainingKm: Double?, located: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.poi_next)
        if (poi == null) {
            rv.setViewVisibility(R.id.photo, View.GONE)
            rv.setViewVisibility(R.id.tile, View.VISIBLE)
            rv.setInt(R.id.tile, "setBackgroundColor", Color.parseColor("#888888"))
            rv.setTextViewText(R.id.tile, "🏁")
            rv.setTextViewText(R.id.name, "All POIs passed")
            rv.setTextViewText(R.id.dist, "done")
            return rv
        }
        val bmp = if (poi.hasPhoto) PoiRepository.photo(ctx, poi.id) else null
        if (bmp != null) {
            rv.setViewVisibility(R.id.photo, View.VISIBLE)
            rv.setViewVisibility(R.id.tile, View.GONE)
            rv.setImageViewBitmap(R.id.photo, bmp)
        } else {
            rv.setViewVisibility(R.id.photo, View.GONE)
            rv.setViewVisibility(R.id.tile, View.VISIBLE)
            rv.setInt(R.id.tile, "setBackgroundColor", catColor(poi.category))
            rv.setTextViewText(R.id.tile, catEmoji(poi.category))
        }
        rv.setTextViewText(R.id.name, poi.name)
        rv.setTextViewText(R.id.dist, "${catEmoji(poi.category)} ${distText(remainingKm, located)}")
        return rv
    }
}
