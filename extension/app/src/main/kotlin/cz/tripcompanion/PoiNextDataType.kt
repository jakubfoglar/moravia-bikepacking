package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Compact field: the single nearest POI ahead. Small vs medium by field height. */
class PoiNextDataType(extension: String) : DataTypeImpl(extension, "poi-next") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Logger.log("next", "startView size=${config.viewSize}")
        val h = config.viewSize.second
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                PoiRepository.load(context)
                AppState.flow.collect { st ->
                    try {
                        val pois = PoiRepository.pois
                        if (pois.isEmpty()) return@collect
                        if (!st.located || st.lat == null || st.lon == null) {
                            emitter.updateView(Render.next(context, pois.first(), null, false, h))
                        } else {
                            val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
                            val nearest = RouteMath.sortedAhead(pois, myKm).firstOrNull()
                            emitter.updateView(Render.next(context, nearest?.poi, nearest?.remainingKm, true, h))
                        }
                    } catch (e: Exception) {
                        Logger.logError("next render", e)
                        emitter.updateView(Render.error(context, "Next render", e))
                    }
                }
            } catch (e: Exception) {
                Logger.logError("next start", e)
                emitter.updateView(Render.error(context, "Next start", e))
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
