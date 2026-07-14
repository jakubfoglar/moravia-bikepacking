package cz.tripcompanion

import android.content.Context
import java.time.LocalDate

/** Persisted trip settings + which day we're on. */
object TripSettings {
    private const val PREFS = "trip"
    private const val KEY_DAY = "dayOverride" // 0 = auto, 1, 2

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
