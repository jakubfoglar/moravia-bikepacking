package cz.tripcompanion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared, process-wide UI state: last location + current catalog paging index. */
object AppState {
    data class State(
        val lat: Double? = null,
        val lon: Double? = null,
        val located: Boolean = false,
        val index: Int = 0,
    )

    private val _flow = MutableStateFlow(State())
    val flow: StateFlow<State> = _flow

    fun updateLocation(lat: Double, lon: Double) =
        _flow.update { it.copy(lat = lat, lon = lon, located = true) }

    fun move(delta: Int) =
        _flow.update { it.copy(index = (it.index + delta).coerceAtLeast(0)) }

    fun setIndex(i: Int) =
        _flow.update { it.copy(index = i.coerceAtLeast(0)) }
}
