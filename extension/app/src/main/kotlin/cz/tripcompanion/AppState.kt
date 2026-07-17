package cz.tripcompanion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared, process-wide UI state: location and the catalog's paging index. */
object AppState {
    data class State(
        val lat: Double? = null,
        val lon: Double? = null,
        val located: Boolean = false,
        val index: Int = 0,
        val avgKmh: Double? = null,
        /** When the last fix arrived. A frozen position means "GPS lost", not "stopped". */
        val locAt: Long = 0L,
    )

    private val _flow = MutableStateFlow(State())
    val flow: StateFlow<State> = _flow

    fun updateLocation(lat: Double, lon: Double) =
        _flow.update { it.copy(lat = lat, lon = lon, located = true, locAt = System.currentTimeMillis()) }

    fun updateAvg(kmh: Double) =
        _flow.update { it.copy(avgKmh = kmh) }

    fun move(delta: Int) =
        _flow.update { it.copy(index = (it.index + delta).coerceAtLeast(0)) }

    fun setIndex(i: Int) =
        _flow.update { it.copy(index = i.coerceAtLeast(0)) }
}
