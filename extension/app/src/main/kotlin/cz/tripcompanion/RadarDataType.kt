package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Rear-radar field: cars passed, closest range, estimated speed. Recorded to FIT + a geotag log. */
class RadarDataType(extension: String) : DataTypeImpl(extension, "radar") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Logger.log("radar", "startView size=${config.viewSize}")
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                RadarEngine.flow.collect { ui ->
                    try {
                        emitter.updateView(Render.radar(context, ui, TripSettings.radarEnabled(context)))
                    } catch (e: Exception) {
                        Logger.logError("radar render", e)
                        emitter.updateView(Render.error(context, "Radar render", e))
                    }
                }
            } catch (e: Exception) {
                Logger.logError("radar start", e)
                emitter.updateView(Render.error(context, "Radar start", e))
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
