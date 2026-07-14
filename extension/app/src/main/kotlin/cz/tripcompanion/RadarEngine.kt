package cz.tripcompanion

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

/**
 * Reads the rear-radar stream, counts passing vehicles, and estimates closing speed.
 *
 * Radar-read + closing-speed approach adapted from eiRadar (MIT, © 2025 yrkan):
 * https://github.com/yrkan/eiradar — the Karoo SDK exposes per-target RANGE + threat only
 * (no native speed), so speed is derived from range-over-time.
 */
object RadarEngine {
    private const val THRESHOLD_M = 30 // a "pass" must have come at least this close
    private const val TARGET_JUMP_M = 30 // range jump ⇒ different vehicle → reset speed buffer

    private val RANGE_FIELDS = listOf(
        DataType.Field.RADAR_TARGET_1_RANGE, DataType.Field.RADAR_TARGET_2_RANGE,
        DataType.Field.RADAR_TARGET_3_RANGE, DataType.Field.RADAR_TARGET_4_RANGE,
        DataType.Field.RADAR_TARGET_5_RANGE, DataType.Field.RADAR_TARGET_6_RANGE,
        DataType.Field.RADAR_TARGET_7_RANGE, DataType.Field.RADAR_TARGET_8_RANGE,
    )

    data class Ui(
        val count: Int = 0,
        val lastSpeedKmh: Int = 0,
        val nearestM: Int = 0,
        val threat: Int = 0,
        val hasTarget: Boolean = false,
        val active: Boolean = false,
    )

    data class Pass(val t: Long, val lat: Double?, val lon: Double?, val speedKmh: Int, val rangeM: Int)

    private val _flow = MutableStateFlow(Ui())
    val flow: StateFlow<Ui> = _flow
    val passes: MutableList<Pass> = Collections.synchronizedList(mutableListOf())

    private var started = false
    private val speedBuf = ArrayDeque<Pair<Int, Long>>() // (rangeM, timeMs)
    private var prevHadTarget = false
    private var minRangeEncounter = Int.MAX_VALUE
    private var count = 0

    fun start(karoo: KarooSystemService, ctx: Context) {
        if (started) return
        started = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                karoo.streamDataFlow(DataType.Type.RADAR).collect { s ->
                    val values = (s as? StreamState.Streaming)?.dataPoint?.values
                    if (values == null) {
                        _flow.value = _flow.value.copy(active = false, hasTarget = false)
                    } else {
                        process(values, ctx)
                    }
                }
            } catch (e: Exception) {
                Logger.logError("radar", e)
            }
        }
    }

    private fun process(values: Map<String, Double>, ctx: Context) {
        val threat = values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
        val nearest = RANGE_FIELDS.mapNotNull { values[it]?.toInt()?.takeIf { r -> r > 0 } }.minOrNull()
        val hasTarget = nearest != null
        var speedKmh = _flow.value.lastSpeedKmh

        if (hasTarget) {
            val now = System.currentTimeMillis()
            if (speedBuf.isNotEmpty() && Math.abs(speedBuf.last().first - nearest!!) > TARGET_JUMP_M) speedBuf.clear()
            speedBuf.addLast(nearest!! to now)
            while (speedBuf.size > 3) speedBuf.removeFirst()
            if (speedBuf.size >= 2) {
                val f = speedBuf.first(); val l = speedBuf.last()
                val dt = (l.second - f.second) / 1000.0
                if (dt > 0) {
                    val mps = (f.first - l.first) / dt
                    if (mps > 0) speedKmh = (mps * 3.6).toInt()
                }
            }
            minRangeEncounter = Math.min(minRangeEncounter, nearest)
        }

        // A pass = a target that came within threshold and then vanished.
        if (prevHadTarget && !hasTarget) {
            if (minRangeEncounter in 1..THRESHOLD_M) {
                count++
                val loc = AppState.flow.value
                val pass = Pass(System.currentTimeMillis(), loc.lat, loc.lon, _flow.value.lastSpeedKmh, minRangeEncounter)
                passes.add(pass)
                appendPass(ctx, pass)
            }
            minRangeEncounter = Int.MAX_VALUE
            speedBuf.clear()
        }
        prevHadTarget = hasTarget
        _flow.value = Ui(count, if (hasTarget) speedKmh else _flow.value.lastSpeedKmh, nearest ?: 0, threat, hasTarget, true)
    }

    /** Geotagged pass log for a post-ride map. */
    private fun appendPass(ctx: Context, p: Pass) {
        try {
            File(ctx.filesDir, "radar_passes.jsonl")
                .appendText("{\"t\":${p.t},\"lat\":${p.lat},\"lon\":${p.lon},\"kmh\":${p.speedKmh},\"m\":${p.rangeM}}\n")
        } catch (_: Exception) {
        }
    }
}
