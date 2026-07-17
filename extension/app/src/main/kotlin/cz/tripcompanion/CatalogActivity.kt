package cz.tripcompanion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * App home: the catalog itself — a scrollable list of every POI, grouped by day, each row
 * tapping into the detail. Settings (update, day, features, follow-site, logs) live behind the
 * gear, top-right. This is the launcher; the old setup screen is now MainActivity, opened from here.
 */
class CatalogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catalog)
        findViewById<TextView>(R.id.c_settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        PoiRepository.load(applicationContext)
        buildList()
    }

    private fun buildList() {
        val list = findViewById<LinearLayout>(R.id.c_list)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val pois = PoiRepository.pois.sortedWith(compareBy({ it.day }, { it.routeKm }))
        var lastDay = -1
        for (poi in pois) {
            if (poi.day != lastDay) {
                lastDay = poi.day
                list.addView(dayHeader(poi.day))
            }
            list.addView(row(inflater, list, poi))
        }
    }

    private fun dayHeader(day: Int): View {
        val label = when (day) {
            1 -> "1. DEN · BŘECLAV → VELEHRAD"
            2 -> "2. DEN · VELEHRAD → OTROKOVICE"
            else -> "$day. DEN"
        }
        return TextView(this).apply {
            text = label
            setTextColor(resources.getColor(R.color.k_muted, theme))
            textSize = 9f // sp — calibrated for the Karoo's 2.0-density screen
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(dp(18), dp(16), dp(18), dp(6))
        }
    }

    private fun row(inflater: LayoutInflater, parent: ViewGroup, poi: Poi): View {
        val v = inflater.inflate(R.layout.row_catalog, parent, false)

        val photo = v.findViewById<ImageView>(R.id.r_photo)
        val tile = v.findViewById<TextView>(R.id.r_tile)
        val bmp = if (poi.hasPhoto) PoiRepository.photo(this, poi.id) else null
        if (bmp != null) {
            photo.setImageBitmap(bmp); photo.visibility = View.VISIBLE; tile.visibility = View.GONE
        } else {
            photo.visibility = View.GONE
            tile.visibility = View.VISIBLE
            tile.text = Render.catEmoji(poi.category)
            tile.setBackgroundColor(Render.catColor(poi.category))
        }

        v.findViewById<TextView>(R.id.r_cat).apply {
            text = Render.catLabel(this@CatalogActivity, poi.category)
            setTextColor(Render.catColor(poi.category))
        }
        v.findViewById<TextView>(R.id.r_name).text = poi.name
        v.findViewById<TextView>(R.id.r_place).text =
            listOf(poi.town, "${poi.day}. den").filter { it.isNotBlank() }.joinToString(" · ")

        v.setOnClickListener {
            startActivity(
                Intent(this, PoiDetailActivity::class.java).putExtra("id", poi.id),
            )
        }
        // Cheap press feedback without a ripple drawable.
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> view.setBackgroundColor(Color.parseColor("#F2F2F4"))
                else -> view.setBackgroundColor(Color.TRANSPARENT)
            }
            false
        }
        return v
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
