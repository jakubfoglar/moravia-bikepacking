package cz.tripcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Poi(
    val id: Int,
    val name: String,
    val town: String = "",
    val category: String = "nature",
    val day: Int = 1,
    val lat: Double,
    val lon: Double,
    val hook: String = "",
    val blurb: String = "",
    val opening_hours: String? = null,
    val routeKm: Double = 0.0,
    val offKm: Double = 0.0,
    val hasPhoto: Boolean = false,
)

/** Loads the bundled POIs, combined track, and photos from assets (offline). */
object PoiRepository {
    @Volatile
    private var loaded = false
    var pois: List<Poi> = emptyList()
        private set
    var track: List<DoubleArray> = emptyList()
        private set
    private val bmpCache = HashMap<Int, Bitmap?>()

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val json = Json { ignoreUnknownKeys = true }
        val pjson = ctx.assets.open("pois.json").bufferedReader().use { it.readText() }
        pois = json.decodeFromString<List<Poi>>(pjson)
        val tjson = ctx.assets.open("track.json").bufferedReader().use { it.readText() }
        val raw = json.decodeFromString<List<List<Double>>>(tjson)
        track = raw.map { doubleArrayOf(it[0], it[1], it[2]) }
        loaded = true
    }

    fun photo(ctx: Context, id: Int): Bitmap? {
        synchronized(bmpCache) {
            if (bmpCache.containsKey(id)) return bmpCache[id]
        }
        val b = try {
            ctx.assets.open("photos/$id.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        synchronized(bmpCache) { bmpCache[id] = b }
        return b
    }
}
