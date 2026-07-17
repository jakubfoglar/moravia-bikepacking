package cz.tripcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Presentation type. The four categories collapse into two ways of reading a place:
 * a SIGHT is story-first (photo → paragraph → practicalities), an EATDRINK stop is
 * practical-first (am I fed? open? how far off route?) and gets no fake story.
 */
enum class PoiKind { SIGHT, EATDRINK }

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
    // ---- optional practicalities (content pass fills these in; every block hides when null) ----
    /** Cleaned, Czech, comma-separated cuisine ("regionální, těstoviny") — EATDRINK only. */
    val cuisine: String? = null,
    /** Admission as display text — a range or "od …" when the exact price is unverified. */
    val admission: String? = null,
    /** Physical effort to actually see it ("302 schodů na ochoz", "1,5 km pěšky"). */
    val effortNote: String? = null,
    /** Caveat shown under the hours row ("letní sezóna — ověřit"). */
    val hoursNote: String? = null,
    val phone: String? = null,
    /** true = jen hotově, false = karty berou, null = unknown (row hidden). */
    val cashOnly: Boolean? = null,
) {
    val kind: PoiKind
        get() = if (category == "food" || category == "cafe") PoiKind.EATDRINK else PoiKind.SIGHT
}

/** Loads the bundled POIs, combined track, and photos from assets (offline). */
object PoiRepository {
    @Volatile
    private var loaded = false
    var pois: List<Poi> = emptyList()
        private set
    var track: List<DoubleArray> = emptyList()
        private set
    var dayTracks: Map<Int, List<DoubleArray>> = emptyMap()
        private set
    private val bmpCache = HashMap<Int, Bitmap?>()

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val json = Json { ignoreUnknownKeys = true }
        val pjson = ctx.assets.open("pois.json").bufferedReader().use { it.readText() }
        pois = json.decodeFromString<List<Poi>>(pjson)
        val tjson = ctx.assets.open("track.json").bufferedReader().use { it.readText() }
        track = json.decodeFromString<List<List<Double>>>(tjson).map { doubleArrayOf(it[0], it[1], it[2]) }
        val djson = ctx.assets.open("day_tracks.json").bufferedReader().use { it.readText() }
        dayTracks = json.decodeFromString<Map<String, List<List<Double>>>>(djson)
            .mapKeys { it.key.toInt() }
            .mapValues { e -> e.value.map { doubleArrayOf(it[0], it[1], it[2]) } }
        loaded = true
    }

    fun dayTrack(day: Int): List<DoubleArray> = dayTracks[day] ?: emptyList()
    fun dayTotalKm(day: Int): Double = dayTracks[day]?.lastOrNull()?.get(2) ?: 0.0
    fun poisForDay(day: Int): List<Poi> = pois.filter { it.day == day }

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
