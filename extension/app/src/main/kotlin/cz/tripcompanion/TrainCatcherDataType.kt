package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.roundToInt

/** Trip-specific config: the trains home from Otrokovice, and pace thresholds from ride history. */
object TrainConfig {
    const val DEST = "Praha"
    const val STATION = "Otrokovice"
    // (minute-of-day, label)
    val trains = listOf(15 * 60 + 51 to "15:51", 17 * 60 + 51 to "17:51")
    const val RELAXED = 15.0 // your long-ride elapsed avg (incl. stops)
    const val PUSH = 22.0 // your long-ride moving avg — realistic ceiling if you stop faffing
}

enum class Verdict { GREEN, AMBER, RED, NONE }

data class TrainView(
    val located: Boolean,
    val remainingKm: Double = 0.0,
    val label: String = "",
    val requiredKmh: Double = 0.0,
    val verdict: Verdict = Verdict.NONE,
    val youKmh: Double? = null,
    val marginMin: Int? = null,
    val fallback: String? = null,
    val noTrains: Boolean = false,
    val inactive: Boolean = false,
)

/** "Can I still make the train?" — remaining route km vs time-to-departure, judged against your real pace. */
class TrainCatcherDataType(extension: String) : DataTypeImpl(extension, "train-catcher") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Logger.log("train", "startView size=${config.viewSize}")
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                PoiRepository.load(context)
                AppState.flow.collect { st ->
                    try {
                        emitter.updateView(Render.train(context, compute(context, st)))
                    } catch (e: Exception) {
                        Logger.logError("train render", e)
                        emitter.updateView(Render.error(context, "Train render", e))
                    }
                }
            } catch (e: Exception) {
                Logger.logError("train start", e)
                emitter.updateView(Render.error(context, "Train start", e))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun compute(context: android.content.Context, st: AppState.State): TrainView {
        if (TripSettings.effectiveDay(context) != 2) return TrainView(located = st.located, inactive = true)
        if (!st.located || st.lat == null || st.lon == null) return TrainView(located = false)
        val stationKm = PoiRepository.track.lastOrNull()?.getOrNull(2) ?: return TrainView(located = false)
        val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
        val remaining = (stationKm - myKm).coerceAtLeast(0.0)

        val now = LocalTime.now()
        val nowMin = now.hour * 60 + now.minute
        val future = TrainConfig.trains.filter { it.first > nowMin }
        if (future.isEmpty()) return TrainView(located = true, remainingKm = remaining, noTrains = true)

        val (mod, label) = future.first()
        val minutesLeft = mod - nowMin
        val required = if (minutesLeft > 0) remaining / (minutesLeft / 60.0) else 999.0
        val verdict = when {
            required <= TrainConfig.RELAXED -> Verdict.GREEN
            required <= TrainConfig.PUSH -> Verdict.AMBER
            else -> Verdict.RED
        }
        val you = st.avgKmh?.takeIf { it > 3.0 }
        val margin = you?.let { (minutesLeft - (remaining / it * 60.0)).roundToInt() }
        val fallback = future.getOrNull(1)?.let { (m2, l2) ->
            val ml2 = m2 - nowMin
            val req2 = if (ml2 > 0) remaining / (ml2 / 60.0) else 999.0
            "next: $l2 · need ${req2.roundToInt()} km/h"
        }
        return TrainView(true, remaining, label, required, verdict, you, margin, fallback)
    }
}
