package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Decides what's worth telling the website about, and when.
 *
 * The Karoo only ever sends facts (km, cars, which POI). Fable turns them into Czech
 * server-side, so the voice is the same whether a post came from here or from the phone.
 *
 * Cadence is event-driven, never a timer: a floor of MIN_GAP between auto-posts keeps a
 * busy stretch from flooding the feed, while start/finish/train bypass it because they're
 * the ones you'd actually want to see land immediately.
 */
object RideNarrator {
    private const val TICK_MS = 15_000L
    private const val STATE_EVERY_MS = 120_000L // "now" block refresh; no feed entry, no Fable
    private const val MIN_GAP_MS = 10 * 60_000L // floor between auto-posts
    private const val STOP_AFTER_MS = 10 * 60_000L // not moving this long ⇒ probably eating
    private const val MOVE_M = 25.0 // GPS jitter below this isn't movement
    private const val POI_NEAR_M = 150.0
    private val CAR_MILESTONES = listOf(10, 25, 50, 100, 150, 200)
    private val TRIP_START = LocalDate.of(2026, 7, 31)
    private val TRIP_END = LocalDate.of(2026, 8, 1)

    private const val GPS_STALE_MS = 120_000L // no fix for this long ⇒ don't trust "not moving"
    private const val PREFS = "narrator"

    private var job: Job? = null

    private var day = 0
    private var startedKm = -1.0
    private var movingSecs = 0
    private var lastMoveMs = 0L
    private var stoppedSince: Long? = null
    private var lastStateMs = 0L
    private var lastAutoMs = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private val firedQuarters = mutableSetOf<Int>()
    private val firedPois = mutableSetOf<Int>()
    private val firedCars = mutableSetOf<Int>()
    private var firedStart = false
    private var firedFinish = false
    private var firedStop = false
    private var lastVerdict: Verdict? = null
    private var pendingVerdict: Verdict? = null // must hold for 2 ticks before we post it

    fun start(karoo: KarooSystemService, ctx: Context, scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            load(ctx)
            Logger.log("narrator", "started (day=$day, movingSecs=$movingSecs, start=$firedStart)")
            while (true) {
                try {
                    tick(karoo, ctx)
                } catch (e: Exception) {
                    Logger.logError("narrator", e)
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    /**
     * Everything here is process-lifetime state, so a mid-ride reboot (or the OS killing the
     * service) would otherwise re-fire "Vyrazil jsem." at km 80 and zero the saddle time.
     * Persisted alongside each state ping and reloaded on start. Keyed by day, so leaving the
     * Karoo on overnight doesn't carry Day 1's "already finished" flags into Day 2.
     */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context) = prefs(ctx).edit().apply {
        putInt("day", day)
        putFloat("startedKm", startedKm.toFloat())
        putInt("movingSecs", movingSecs)
        putBoolean("firedStart", firedStart)
        putBoolean("firedFinish", firedFinish)
        putStringSet("firedQuarters", firedQuarters.map { it.toString() }.toSet())
        putStringSet("firedPois", firedPois.map { it.toString() }.toSet())
        putStringSet("firedCars", firedCars.map { it.toString() }.toSet())
    }.apply()

    private fun load(ctx: Context) = prefs(ctx).run {
        day = getInt("day", 0)
        startedKm = getFloat("startedKm", -1f).toDouble()
        movingSecs = getInt("movingSecs", 0)
        firedStart = getBoolean("firedStart", false)
        firedFinish = getBoolean("firedFinish", false)
        firedQuarters.clear(); getStringSet("firedQuarters", emptySet())?.forEach { firedQuarters.add(it.toInt()) }
        firedPois.clear(); getStringSet("firedPois", emptySet())?.forEach { firedPois.add(it.toInt()) }
        firedCars.clear(); getStringSet("firedCars", emptySet())?.forEach { firedCars.add(it.toInt()) }
    }

    private fun resetForDay(ctx: Context, newDay: Int) {
        Logger.log("narrator", "new day $day → $newDay, resetting")
        day = newDay
        startedKm = -1.0; movingSecs = 0; lastMoveMs = 0L; stoppedSince = null
        lastLat = null; lastLon = null; lastAutoMs = 0L
        firedQuarters.clear(); firedPois.clear(); firedCars.clear()
        firedStart = false; firedFinish = false; firedStop = false
        lastVerdict = null; pendingVerdict = null
        save(ctx)
    }

    private suspend fun tick(karoo: KarooSystemService, ctx: Context) {
        if (!TripPoster.postingEnabled(ctx)) return
        if (!onTrip(ctx)) return
        val st = AppState.flow.value
        if (!st.located || st.lat == null || st.lon == null) return

        val today = TripSettings.effectiveDay(ctx)
        if (today != day) resetForDay(ctx, today)

        val track = PoiRepository.dayTrack(day)
        if (track.isEmpty()) return
        val totalKm = PoiRepository.dayTotalKm(day)
        val myKm = RouteMath.myRouteKm(st.lat, st.lon, track)
        val now = System.currentTimeMillis()
        if (startedKm < 0) startedKm = myKm

        // A lost fix freezes lat/lon, which looks exactly like standing still — without this
        // guard, ten minutes under tree cover publishes "Stojím. Nejspíš jím."
        val gpsFresh = st.locAt > 0 && now - st.locAt < GPS_STALE_MS

        // --- movement bookkeeping ---
        if (gpsFresh) {
            val moved = lastLat?.let { RouteMath.haversine(it, lastLon!!, st.lat, st.lon) * 1000.0 } ?: 0.0
            if (moved > MOVE_M) {
                if (lastMoveMs > 0) movingSecs += ((now - lastMoveMs) / 1000).toInt().coerceAtMost(60)
                lastMoveMs = now
                stoppedSince = null
                firedStop = false
            } else if (lastMoveMs > 0 && now - lastMoveMs > STOP_AFTER_MS && stoppedSince == null) {
                stoppedSince = lastMoveMs
            }
            if (lastMoveMs == 0L) lastMoveMs = now
            lastLat = st.lat; lastLon = st.lon
        } else {
            // Don't accrue a fake stop across the outage.
            lastMoveMs = now
        }

        val cars = if (TripSettings.radarEnabled(ctx)) RadarEngine.flow.value.count else 0
        val verdict = trainVerdict(ctx, day, st)

        // --- the "now" block: cheap, frequent, not a post ---
        if (now - lastStateMs > STATE_EVERY_MS) {
            lastStateMs = now
            save(ctx) // piggyback the state save on the same cadence
            TripPoster.send(
                karoo, ctx,
                TripPoster.state(
                    day = day, lat = st.lat, lon = st.lon, place = placeNear(st.lat, st.lon, day),
                    dayKm = round1(myKm), dayTotalKm = round1(totalKm),
                    movingSecs = movingSecs, cars = cars,
                    stoppedSinceIso = stoppedSince?.let { Instant.ofEpochMilli(it).toString() },
                    trainVerdict = verdict?.name?.lowercase(),
                ),
            )
        }

        // --- events, most interesting first; one per tick ---
        val gapOk = now - lastAutoMs > MIN_GAP_MS

        if (!firedStart && myKm - startedKm > 0.5) {
            firedStart = true
            post(karoo, ctx, "start", mapOf("odkud" to fromName(day), "kam" to toName(day), "dnes_km" to round1(totalKm)), st, day, now)
            return
        }

        if (!firedFinish && totalKm - myKm < 0.6) {
            firedFinish = true
            post(karoo, ctx, "finish", mapOf("kam" to toName(day), "km" to round1(myKm), "v_sedle" to hhmm(movingSecs), "aut" to cars), st, day, now)
            return
        }

        // Verdict changes bypass the gap floor, so they need their own guard: `required` drifts
        // continuously across the 15/22 km/h thresholds, and riding near one would otherwise
        // flip GREEN↔AMBER every tick — each flip a post. Only act once it holds for two ticks.
        if (verdict != null && verdict != lastVerdict) {
            if (verdict != pendingVerdict) {
                pendingVerdict = verdict // first sighting: wait and see
            } else {
                val prev = lastVerdict
                lastVerdict = verdict
                pendingVerdict = null
                if (prev != null) { // don't announce the first reading, only changes
                    post(karoo, ctx, "train", mapOf("verdikt" to verdict.name.lowercase(), "zbyva_km" to round1(totalKm - myKm)), st, day, now)
                    return
                }
            }
        } else if (verdict == lastVerdict) {
            pendingVerdict = null
        }

        if (!gapOk) return

        if (stoppedSince != null && !firedStop) {
            firedStop = true
            val near = nearestPoi(st.lat, st.lon, day)
            post(karoo, ctx, "stop", mapOf("stoji_minut" to ((now - stoppedSince!!) / 60000).toInt(), "u_ceho" to near?.name, "km" to round1(myKm)), st, day, now)
            return
        }

        for (q in listOf(25, 50, 75)) {
            if (q !in firedQuarters && totalKm > 0 && myKm / totalKm * 100 >= q) {
                firedQuarters.add(q)
                post(karoo, ctx, "quarter", mapOf("procent" to q, "km" to round1(myKm), "zbyva_km" to round1(totalKm - myKm)), st, day, now)
                return
            }
        }

        nearestPoi(st.lat, st.lon, day)?.let { p ->
            val dM = RouteMath.haversine(st.lat, st.lon, p.lat, p.lon) * 1000.0
            if (dM < POI_NEAR_M && p.id !in firedPois) {
                firedPois.add(p.id)
                post(karoo, ctx, "poi", mapOf("misto" to p.name, "obec" to p.town, "typ" to p.category, "o_cem" to p.hook), st, day, now)
                return
            }
        }

        for (m in CAR_MILESTONES) {
            if (m !in firedCars && cars >= m) {
                firedCars.add(m)
                post(karoo, ctx, "cars", mapOf("aut" to cars, "km" to round1(myKm)), st, day, now)
                return
            }
        }
    }

    private suspend fun post(
        karoo: KarooSystemService, ctx: Context, event: String,
        facts: Map<String, Any?>, st: AppState.State, day: Int, now: Long,
    ) {
        Logger.log("narrator", "post $event $facts")
        val r = TripPoster.send(karoo, ctx, TripPoster.autoEvent(event, facts, st.lat, st.lon, day))
        if (r.ok) lastAutoMs = now
        save(ctx) // a fired flag must survive even if the very next thing is a reboot
    }

    /**
     * Only narrate on the actual trip days. Otherwise powering the Karoo on at home would
     * publish a position snapped to the nearest track point — telling my wife I'm somewhere
     * near Kyjov while I'm on the sofa. A manual day override counts as "yes, I mean it".
     */
    private fun onTrip(ctx: Context): Boolean {
        if (TripSettings.dayOverride(ctx) != 0) return true
        val today = try { LocalDate.now() } catch (e: Exception) { return false }
        return !today.isBefore(TRIP_START) && !today.isAfter(TRIP_END)
    }

    private fun trainVerdict(ctx: Context, day: Int, st: AppState.State): Verdict? {
        if (day != 2 || st.lat == null || st.lon == null) return null
        val stationKm = PoiRepository.track.lastOrNull()?.getOrNull(2) ?: return null
        val remaining = (stationKm - RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)).coerceAtLeast(0.0)
        val now = try { LocalTime.now() } catch (e: Exception) { return null }
        val nowMin = now.hour * 60 + now.minute
        val train = TrainConfig.trains.firstOrNull { it.first > nowMin } ?: return null
        val minutesLeft = train.first - nowMin
        val required = if (minutesLeft > 0) remaining / (minutesLeft / 60.0) else 999.0
        return when {
            required <= TrainConfig.RELAXED -> Verdict.GREEN
            required <= TrainConfig.PUSH -> Verdict.AMBER
            else -> Verdict.RED
        }
    }

    private fun nearestPoi(lat: Double, lon: Double, day: Int): Poi? =
        PoiRepository.poisForDay(day).minByOrNull { RouteMath.haversine(lat, lon, it.lat, it.lon) }

    /** Coarse "where am I" for the now-block headline: the town of the closest POI. */
    private fun placeNear(lat: Double, lon: Double, day: Int): String? =
        nearestPoi(lat, lon, day)?.town?.takeIf { it.isNotEmpty() }

    private fun fromName(day: Int) = if (day == 1) "Břeclav" else "Velehrad"
    private fun toName(day: Int) = if (day == 1) "Velehrad" else "Otrokovice"
    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun hhmm(secs: Int) = "${secs / 3600}:${((secs % 3600) / 60).toString().padStart(2, '0')}"
}
