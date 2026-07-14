package cz.tripcompanion

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.OnLocationChanged

/** The extension service: registers the two data fields and feeds them the live location. */
class TripCompanionExtension : KarooExtension("trip-companion", "1.0") {
    private lateinit var karooSystem: KarooSystemService
    private var consumerId: String? = null

    override val types by lazy {
        listOf(
            PoiCatalogDataType(extension),
            PoiNextDataType(extension),
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
            }
        }
    }

    override fun onDestroy() {
        consumerId?.let { karooSystem.removeConsumer(it) }
        try {
            karooSystem.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }
}
