package cz.tripcompanion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen, scrollable POI browser. Opened by tapping the catalog card; Prev/Next page
 * through the same nearest-ahead list the field shows, so it's a modal browser rather than a
 * dead-end single card. Runs in its own task (see the manifest's empty taskAffinity), so Back
 * returns to the ride screen you came from — never the app's home screen.
 */
class PoiDetailActivity : AppCompatActivity() {
    private var order: List<Poi> = emptyList()
    private var idx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_poi_detail)
        PoiRepository.load(applicationContext)

        findViewById<Button>(R.id.d_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.d_prev).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.d_next).setOnClickListener { step(1) }

        buildOrder(intent.getIntExtra("id", -1))
        if (order.isEmpty()) { finish(); return }
        bind()
    }

    /** singleTask reuses this instance on a fresh tap — repoint at the newly tapped POI. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        buildOrder(intent.getIntExtra("id", -1))
        if (order.isEmpty()) { finish(); return }
        bind()
    }

    /** Same ordering the catalog field uses: nearest-ahead when located, else the day's list. */
    private fun buildOrder(startId: Int) {
        val day = TripSettings.effectiveDay(this)
        val dayPois = PoiRepository.poisForDay(day)
        val st = AppState.flow.value
        order = if (st.located && st.lat != null && st.lon != null) {
            val myKm = RouteMath.myRouteKm(st.lat, st.lon, PoiRepository.track)
            RouteMath.sortedAhead(dayPois, myKm).map { it.poi }
        } else {
            dayPois
        }
        if (order.isEmpty()) order = PoiRepository.pois.filter { it.id == startId }
        idx = order.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    }

    private fun step(delta: Int) {
        val n = (idx + delta).coerceIn(0, order.size - 1)
        if (n != idx) { idx = n; bind() }
    }

    private fun bind() {
        val poi = order[idx]
        val scroll = findViewById<View>(R.id.d_scroll)
        scroll.scrollTo(0, 0) // new POI starts at the top, not wherever the last one was scrolled

        val badge = findViewById<TextView>(R.id.d_badge)
        badge.text = "${Render.catEmoji(poi.category)} ${Render.catLabel(poi.category)}"
        badge.setBackgroundColor(Render.catColor(poi.category))

        val photo = findViewById<ImageView>(R.id.d_photo)
        val bmp = if (poi.hasPhoto) PoiRepository.photo(this, poi.id) else null
        if (bmp != null) {
            photo.setImageBitmap(bmp); photo.visibility = View.VISIBLE
        } else {
            photo.visibility = View.GONE
        }

        findViewById<TextView>(R.id.d_name).text = poi.name
        findViewById<TextView>(R.id.d_meta).text = "${poi.town} · Day ${poi.day} · ${poi.offKm} km off route"

        val hours = findViewById<TextView>(R.id.d_hours)
        if (poi.opening_hours != null) {
            hours.text = "🕒 ${poi.opening_hours}"; hours.visibility = View.VISIBLE
        } else {
            hours.visibility = View.GONE
        }

        findViewById<TextView>(R.id.d_blurb).text = poi.blurb.ifBlank { poi.hook }

        findViewById<TextView>(R.id.d_count).text = "${idx + 1} / ${order.size}"
        findViewById<Button>(R.id.d_prev).isEnabled = idx > 0
        findViewById<Button>(R.id.d_next).isEnabled = idx < order.size - 1

        findViewById<Button>(R.id.d_maps).setOnClickListener {
            open("https://www.google.com/maps/search/?api=1&query=${poi.lat},${poi.lon}")
        }
        findViewById<Button>(R.id.d_mapy).setOnClickListener {
            open("https://mapy.cz/turisticka?x=${poi.lon}&y=${poi.lat}&z=16")
        }
    }

    private fun open(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // no browser — ignore
    }
}
