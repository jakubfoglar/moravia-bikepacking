package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Full-page catalog field: nearest-first, along-route distance, glance-card ⇄ reading pane. */
class PoiCatalogDataType(extension: String) : DataTypeImpl(extension, "poi-catalog") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Logger.log("catalog", "startView size=${config.viewSize} preview=${config.preview}")
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                PoiRepository.load(context)
                AppState.flow.collect { st ->
                    try {
                        emitter.updateView(render(context, st))
                    } catch (e: Exception) {
                        Logger.logError("catalog render", e)
                        emitter.updateView(Render.error(context, "Catalog render", e))
                    }
                }
            } catch (e: Exception) {
                Logger.logError("catalog start", e)
                emitter.updateView(Render.error(context, "Catalog start", e))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun render(context: Context, st: AppState.State): android.widget.RemoteViews {
        val pois = PoiRepository.pois
        if (pois.isEmpty()) return Render.error(context, "Catalog", IllegalStateException("no POIs loaded"))
        val reading = st.mode == AppState.Mode.READING

        if (!st.located || st.lat == null || st.lon == null) {
            val idx = st.index.coerceIn(0, pois.size - 1)
            val poi = pois[idx]
            return if (reading) Render.reading(context, poi, null, false)
            else Render.card(context, poi, idx + 1, pois.size, null, false, nearestMode = false)
        }

        val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
        val ahead = RouteMath.sortedAhead(pois, myKm)
        if (ahead.isEmpty()) return Render.end(context, pois.size)
        val idx = st.index.coerceIn(0, ahead.size - 1)
        val a = ahead[idx]
        return if (reading) Render.reading(context, a.poi, a.remainingKm, true)
        else Render.card(context, a.poi, idx + 1, ahead.size, a.remainingKm, true, nearestMode = true)
    }
}
