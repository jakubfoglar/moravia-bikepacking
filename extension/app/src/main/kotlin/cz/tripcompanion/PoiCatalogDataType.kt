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
        PoiRepository.load(context)
        val job = CoroutineScope(Dispatchers.IO).launch {
            AppState.flow.collect { st ->
                val pois = PoiRepository.pois
                if (pois.isEmpty()) return@collect

                if (!st.located || st.lat == null || st.lon == null) {
                    // No fix yet → plain route order (pois are pre-sorted by routeKm).
                    val idx = st.index.coerceIn(0, pois.size - 1)
                    emitter.updateView(
                        Render.catalog(context, pois[idx], idx + 1, pois.size, null, false, nearestMode = false),
                    )
                } else {
                    val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
                    val ahead = RouteMath.sortedAhead(pois, myKm)
                    if (ahead.isEmpty()) {
                        emitter.updateView(Render.catalog(context, null, 0, 0, null, true, nearestMode = true))
                    } else {
                        val idx = st.index.coerceIn(0, ahead.size - 1)
                        val a = ahead[idx]
                        emitter.updateView(
                            Render.catalog(context, a.poi, idx + 1, ahead.size, a.remainingKm, true, nearestMode = true),
                        )
                    }
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
