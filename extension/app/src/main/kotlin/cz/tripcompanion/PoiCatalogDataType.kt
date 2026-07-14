package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Full-page catalog field: nearest-first, along-route distance, prev/next paging. */
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

    private fun render(context: Context, st: AppState.State) =
        with(PoiRepository.pois) {
            when {
                isEmpty() -> Render.error(context, "Catalog", IllegalStateException("no POIs loaded"))
                !st.located || st.lat == null || st.lon == null -> {
                    val idx = st.index.coerceIn(0, size - 1)
                    Render.catalog(context, this[idx], idx + 1, size, null, false, nearestMode = false)
                }
                else -> {
                    val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
                    val ahead = RouteMath.sortedAhead(this, myKm)
                    if (ahead.isEmpty()) {
                        Render.catalog(context, null, 0, 0, null, true, nearestMode = true)
                    } else {
                        val idx = st.index.coerceIn(0, ahead.size - 1)
                        val a = ahead[idx]
                        Render.catalog(context, a.poi, idx + 1, ahead.size, a.remainingKm, true, nearestMode = true)
                    }
                }
            }
        }
}
