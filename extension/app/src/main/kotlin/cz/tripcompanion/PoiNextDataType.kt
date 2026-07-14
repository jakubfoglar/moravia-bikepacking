package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Compact field: the single nearest POI ahead. */
class PoiNextDataType(extension: String) : DataTypeImpl(extension, "poi-next") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        PoiRepository.load(context)
        val job = CoroutineScope(Dispatchers.IO).launch {
            AppState.flow.collect { st ->
                val pois = PoiRepository.pois
                if (pois.isEmpty()) return@collect

                if (!st.located || st.lat == null || st.lon == null) {
                    emitter.updateView(Render.next(context, pois.first(), null, false))
                } else {
                    val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
                    val nearest = RouteMath.sortedAhead(pois, myKm).firstOrNull()
                    emitter.updateView(Render.next(context, nearest?.poi, nearest?.remainingKm, true))
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
