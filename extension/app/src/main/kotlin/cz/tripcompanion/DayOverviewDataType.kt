package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Day-overview minimap: the current day's route shape + your position + distance to the end. */
class DayOverviewDataType(extension: String) : DataTypeImpl(extension, "day-overview") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Logger.log("overview", "startView size=${config.viewSize}")
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                PoiRepository.load(context)
                AppState.flow.collect { st ->
                    try {
                        val day = TripSettings.effectiveDay(context)
                        emitter.updateView(
                            Render.overview(context, config.viewSize, day, PoiRepository.dayTrack(day), PoiRepository.dayTotalKm(day), st),
                        )
                    } catch (e: Exception) {
                        Logger.logError("overview render", e)
                        emitter.updateView(Render.error(context, "Overview render", e))
                    }
                }
            } catch (e: Exception) {
                Logger.logError("overview start", e)
                emitter.updateView(Render.error(context, "Overview start", e))
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
