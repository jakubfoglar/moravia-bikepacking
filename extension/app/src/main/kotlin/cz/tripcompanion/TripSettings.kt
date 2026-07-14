package cz.tripcompanion

import android.content.Context
import java.time.LocalDate

/** Persisted trip settings + which day we're on. */
object TripSettings {
    private const val PREFS = "trip"
    private const val KEY_DAY = "dayOverride" // 0 = auto, 1, 2
    private const val KEY_RADAR = "radarEnabled" // default off (needs a radar)
    private const val KEY_MAP = "mapOverlayEnabled" // train-deadline pin on the map
    private const val KEY_SCENIC = "scenicEnabled" // golden Toskánsko area on the Day-1 minimap

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun radarEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_RADAR, false)
    fun mapOverlayEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_MAP, true)
    fun scenicEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_SCENIC, true)
    fun setFlag(ctx: Context, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(key, v).apply()
    const val FLAG_RADAR = KEY_RADAR
    const val FLAG_MAP = KEY_MAP
    const val FLAG_SCENIC = KEY_SCENIC

    private val DAY1 = LocalDate.of(2026, 7, 31)
    private val DAY2 = LocalDate.of(2026, 8, 1)

    fun dayOverride(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DAY, 0)

    fun setDayOverride(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_DAY, v).apply()

    /** Effective day: manual override wins, else infer from the date, else Day 1. */
    fun effectiveDay(ctx: Context): Int {
        val ov = dayOverride(ctx)
        if (ov == 1 || ov == 2) return ov
        val today = try { LocalDate.now() } catch (e: Exception) { DAY1 }
        return if (!today.isBefore(DAY2)) 2 else 1
    }
}
