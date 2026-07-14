package cz.tripcompanion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen, scrollable POI detail — launched from the catalog card's "Full story ›". */
class PoiDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_poi_detail)
        PoiRepository.load(applicationContext)
        val id = intent.getIntExtra("id", -1)
        val poi = PoiRepository.pois.firstOrNull { it.id == id }
        if (poi == null) { finish(); return }

        findViewById<Button>(R.id.d_back).setOnClickListener { finish() }

        val badge = findViewById<TextView>(R.id.d_badge)
        badge.text = "${Render.catEmoji(poi.category)} ${Render.catLabel(poi.category)}"
        badge.setBackgroundColor(Render.catColor(poi.category))

        val photo = findViewById<ImageView>(R.id.d_photo)
        val bmp = if (poi.hasPhoto) PoiRepository.photo(this, poi.id) else null
        if (bmp != null) photo.setImageBitmap(bmp) else photo.visibility = View.GONE

        findViewById<TextView>(R.id.d_name).text = poi.name
        findViewById<TextView>(R.id.d_meta).text = "${poi.town} · Day ${poi.day} · ${poi.offKm} km off route"

        val hours = findViewById<TextView>(R.id.d_hours)
        if (poi.opening_hours != null) hours.text = "🕒 ${poi.opening_hours}" else hours.visibility = View.GONE

        findViewById<TextView>(R.id.d_blurb).text = poi.blurb.ifBlank { poi.hook }

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
