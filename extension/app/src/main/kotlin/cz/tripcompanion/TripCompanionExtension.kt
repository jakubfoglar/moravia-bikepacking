package cz.tripcompanion

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The extension service: registers the data fields and feeds them live location + average speed. */
class TripCompanionExtension : KarooExtension("trip-companion", "1.0") {
    private lateinit var karooSystem: KarooSystemService
    private var consumerId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val types by lazy {
        listOf(
            PoiCatalogDataType(extension),
            PoiNextDataType(extension),
            TrainCatcherDataType(extension),
            DayOverviewDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        PoiRepository.load(applicationContext)
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected && consumerId == null) {
                consumerId = karooSystem.addConsumer<OnLocationChanged> { loc ->
                    AppState.updateLocation(loc.lat, loc.lng)
                }
                streamAvgSpeed()
            }
        }
    }

    private fun streamAvgSpeed() {
        scope.launch {
            try {
                karooSystem.streamDataFlow(DataType.Type.AVERAGE_SPEED).collect { s ->
                    (s as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                        // Karoo speed streams are m/s → km/h.
                        AppState.updateAvg(it * 3.6)
                    }
                }
            } catch (e: Exception) {
                Logger.logError("avgSpeed", e)
            }
        }
    }

    override fun onDestroy() {
        consumerId?.let { karooSystem.removeConsumer(it) }
        scope.cancel()
        try {
            karooSystem.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }
}
