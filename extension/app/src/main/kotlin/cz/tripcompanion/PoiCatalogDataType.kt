package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The single catalog field. Renders three tiers off the slot height (see Render.card):
 * TINY = nearest-POI glance (for small slots, e.g. on the map page), MEDIUM = compact card,
 * FULL = the rich browsable card. Nearest-first with along-route distances when located.
 */
class PoiCatalogDataType(extension: String) : DataTypeImpl(extension, "poi-catalog") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Logger.log("catalog", "startView size=${config.viewSize} grid=${config.gridSize} preview=${config.preview}")
        val h = config.viewSize.second
        val w = config.viewSize.first
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                PoiRepository.load(context)
                AppState.flow.collect { st ->
                    try {
                        emitter.updateView(render(context, st, h, w))
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

    private fun render(context: Context, st: AppState.State, heightPx: Int, widthPx: Int): android.widget.RemoteViews {
        val day = TripSettings.effectiveDay(context)
        val pois = PoiRepository.poisForDay(day)
        if (pois.isEmpty()) return Render.error(context, "Catalog", IllegalStateException("no POIs for day $day"))
        // A tiny slot has no paging buttons — it always tracks the nearest POI ahead.
        val tiny = heightPx in 1 until 160

        if (!st.located || st.lat == null || st.lon == null) {
            val idx = if (tiny) 0 else st.index.coerceIn(0, pois.size - 1)
            return Render.card(context, pois[idx], idx + 1, pois.size, null, false, nearestMode = false, heightPx = heightPx, widthPx = widthPx)
        }

        val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
        val ahead = RouteMath.sortedAhead(pois, myKm)
        if (ahead.isEmpty()) return Render.end(context, pois.size, tiny)
        val idx = if (tiny) 0 else st.index.coerceIn(0, ahead.size - 1)
        val a = ahead[idx]
        return Render.card(context, a.poi, idx + 1, ahead.size, a.remainingKm, true, nearestMode = true, heightPx = heightPx, widthPx = widthPx)
    }
}
