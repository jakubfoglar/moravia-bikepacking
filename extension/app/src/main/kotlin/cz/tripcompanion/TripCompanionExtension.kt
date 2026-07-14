package cz.tripcompanion

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalTime

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

    /** Native-map overlay: the moving "catch the train" deadline pin (Day 2 only). */
    override fun startMap(emitter: Emitter<MapEffect>) {
        val job = scope.launch {
            try {
                PoiRepository.load(applicationContext)
                var shownKm = -999.0
                AppState.flow.collect { st ->
                    val pt = deadlinePoint(st)
                    if (pt != null) {
                        if (Math.abs(pt.third - shownKm) > 0.05) {
                            emitter.onNext(
                                ShowSymbols(
                                    listOf(Symbol.Icon("train-deadline", pt.first, pt.second, R.drawable.ic_train_pin, 0f)),
                                ),
                            )
                            shownKm = pt.third
                        }
                    } else if (shownKm > -900.0) {
                        emitter.onNext(HideSymbols(listOf("train-deadline")))
                        shownKm = -999.0
                    }
                }
            } catch (e: Exception) {
                Logger.logError("startMap", e)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    /** (lat, lon, deadlineKm) of where you must be *now* to catch the earliest future train, else null. */
    private fun deadlinePoint(st: AppState.State): Triple<Double, Double, Double>? {
        if (TripSettings.effectiveDay(applicationContext) != 2) return null
        if (!st.located || st.lat == null || st.lon == null) return null
        val track = PoiRepository.track
        val stationKm = track.lastOrNull()?.getOrNull(2) ?: return null
        val now = try { LocalTime.now() } catch (e: Exception) { return null }
        val nowMin = now.hour * 60 + now.minute
        val train = TrainConfig.trains.firstOrNull { it.first > nowMin } ?: return null
        val hoursLeft = (train.first - nowMin) / 60.0
        val pace = st.avgKmh?.takeIf { it > 5.0 } ?: TrainConfig.PUSH
        val deadlineKm = (stationKm - pace * hoursLeft).coerceIn(0.0, stationKm)
        val (lat, lon) = RouteMath.pointAtKm(track, deadlineKm)
        return Triple(lat, lon, deadlineKm)
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
