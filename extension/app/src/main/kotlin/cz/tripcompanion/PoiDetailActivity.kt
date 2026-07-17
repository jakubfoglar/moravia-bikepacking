package cz.tripcompanion

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen, scrollable POI browser in the Karoo OS look (Open Sans, white, uppercase grey
 * labels, pill actions where the physical buttons sit). Opened by tapping the catalog card;
 * Prev/Next page through the same nearest-ahead list the field shows. Runs in its own task
 * (see the manifest's empty taskAffinity), so Back returns to the ride screen you came from.
 *
 * Type-aware: a SIGHT reads story-first (photo → paragraph → practical rows), an EAT/DRINK
 * stop reads practical-first (big live hours line → rows) and never shows a fake story.
 */
class PoiDetailActivity : AppCompatActivity() {
    private var order: List<Poi> = emptyList()
    private var idx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_poi_detail)
        PoiRepository.load(applicationContext)

        findViewById<TextView>(R.id.d_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.d_prev).setOnClickListener { step(-1) }
        findViewById<TextView>(R.id.d_next).setOnClickListener { step(1) }

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
        // A POI tapped in the app's catalog list can be behind the rider or on the other day —
        // it won't be in the nearest-ahead order. Fall back to the whole catalog in route order
        // (always contains the tapped POI), so the detail never opens the wrong item.
        if (order.none { it.id == startId }) {
            order = PoiRepository.pois.sortedWith(compareBy({ it.day }, { it.routeKm }))
        }
        idx = order.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    }

    private fun step(delta: Int) {
        val n = (idx + delta).coerceIn(0, order.size - 1)
        if (n != idx) { idx = n; bind() }
    }

    private fun bind() {
        val poi = order[idx]
        findViewById<View>(R.id.d_scroll).scrollTo(0, 0)

        // Top bar: category as the screen title.
        findViewById<TextView>(R.id.d_cat_icon).text = Render.catEmoji(poi.category)
        findViewById<TextView>(R.id.d_title).apply {
            text = Render.catLabel(this@PoiDetailActivity, poi.category)
            setTextColor(Render.catColor(poi.category))
        }
        findViewById<TextView>(R.id.d_count).text = "${idx + 1} / ${order.size}"

        val photo = findViewById<ImageView>(R.id.d_photo)
        val bmp = if (poi.hasPhoto) PoiRepository.photo(this, poi.id) else null
        if (bmp != null) {
            photo.setImageBitmap(bmp); photo.visibility = View.VISIBLE
        } else {
            photo.visibility = View.GONE
        }

        findViewById<TextView>(R.id.d_name).text = poi.name
        findViewById<TextView>(R.id.d_meta).text = listOf(
            poi.town.ifBlank { null },
            getString(R.string.fld_day_n, poi.day),
            detourText(poi),
        ).filterNotNull().joinToString(" · ")

        // Big live hours line — the headline of an EAT/DRINK stop.
        val hoursBig = findViewById<TextView>(R.id.d_hours_big)
        val status = if (poi.kind == PoiKind.EATDRINK) Render.hoursLine(this, poi.opening_hours) else null
        if (status != null) {
            hoursBig.text = status.first
            hoursBig.setTextColor(status.second)
            hoursBig.visibility = View.VISIBLE
        } else {
            hoursBig.visibility = View.GONE
        }

        // Story (SIGHT) / optional honest note (EAT/DRINK) — machine-made filler never renders.
        val blurb = findViewById<TextView>(R.id.d_blurb)
        val story = when (poi.kind) {
            PoiKind.SIGHT -> poi.blurb.ifBlank { poi.hook }
            PoiKind.EATDRINK -> poi.blurb.takeUnless { it.isBlank() || looksMachineMade(it) } ?: ""
        }
        if (story.isBlank()) {
            blurb.visibility = View.GONE
        } else {
            blurb.text = story
            blurb.visibility = View.VISIBLE
        }

        bindRows(poi)

        findViewById<TextView>(R.id.d_prev).apply { isEnabled = idx > 0; alpha = if (idx > 0) 1f else 0.35f }
        findViewById<TextView>(R.id.d_next).apply {
            isEnabled = idx < order.size - 1
            alpha = if (idx < order.size - 1) 1f else 0.35f
        }
    }

    /** Practical rows, ordered by type; every row hides when its data is absent. */
    private fun bindRows(poi: Poi) {
        val rows = findViewById<LinearLayout>(R.id.d_rows)
        rows.removeAllViews()

        val hoursStatus = Render.hoursLine(this, poi.opening_hours)
        // EAT/DRINK already headlines the live verdict — its row carries the weekly schedule.
        // SIGHT gets the coloured verdict as the row value, schedule + caveat as the note.
        val hoursValue: String?
        val hoursColor: Int?
        val hoursNote: String?
        if (poi.kind == PoiKind.EATDRINK && hoursStatus != null) {
            hoursValue = poi.opening_hours
            hoursColor = null
            hoursNote = poi.hoursNote
        } else {
            hoursValue = hoursStatus?.first ?: poi.opening_hours
            hoursColor = hoursStatus?.second
            hoursNote = listOfNotNull(
                poi.opening_hours.takeIf { hoursStatus != null },
                poi.hoursNote,
            ).joinToString(" · ").ifBlank { null }
        }
        val payment = when (poi.cashOnly) {
            true -> getString(R.string.det_cash_only)
            false -> getString(R.string.det_cards_ok)
            null -> null
        }
        val detour = getString(
            R.string.det_detour_value,
            Render.distText(poi.offKm.coerceAtLeast(0.0)),
        ).takeIf { poi.offKm > 0.15 }

        data class Row(val icon: String, val label: Int, val value: String?, val color: Int?, val note: String? = null)
        val spec: List<Row> = when (poi.kind) {
            PoiKind.EATDRINK -> listOf(
                Row("🕒", R.string.det_lbl_hours, hoursValue, hoursColor, hoursNote),
                Row("🍽", R.string.det_lbl_cuisine, poi.cuisine ?: cleanedCuisine(poi), null),
                Row("💳", R.string.det_lbl_payment, payment, null),
                Row("📞", R.string.det_lbl_phone, poi.phone, null),
                Row("📍", R.string.det_lbl_detour, detour, null),
            )
            PoiKind.SIGHT -> listOf(
                Row("🕒", R.string.det_lbl_hours, hoursValue, hoursColor, hoursNote),
                Row("🎟", R.string.det_lbl_admission, poi.admission, null),
                Row("🥾", R.string.det_lbl_effort, poi.effortNote, null),
                Row("📞", R.string.det_lbl_phone, poi.phone, null),
                Row("📍", R.string.det_lbl_detour, detour, null),
            )
        }

        val inflater = LayoutInflater.from(this)
        var any = false
        for (r in spec) {
            val value = r.value?.takeIf { it.isNotBlank() } ?: continue
            val v = inflater.inflate(R.layout.row_detail, rows, false)
            v.findViewById<TextView>(R.id.row_icon).text = r.icon
            v.findViewById<TextView>(R.id.row_label).text = getString(r.label)
            v.findViewById<TextView>(R.id.row_value).apply {
                text = value
                r.color?.let { setTextColor(it) }
            }
            v.findViewById<TextView>(R.id.row_note).apply {
                if (r.note != null) { text = r.note; visibility = View.VISIBLE } else visibility = View.GONE
            }
            rows.addView(v)
            rows.addView(divider())
            any = true
        }
        findViewById<View>(R.id.d_rows_top_divider).visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(getColor(R.color.k_divider_soft))
    }

    private fun detourText(poi: Poi): String =
        if (poi.offKm <= 0.15) getString(R.string.det_on_route)
        else getString(R.string.det_detour, Render.distText(poi.offKm))

    /** The OSM-derived filler blurbs ("Sit-down option… Hours from OpenStreetMap…") stay hidden. */
    private fun looksMachineMade(s: String): Boolean =
        s.contains("OpenStreetMap") || s.startsWith("Sit-down option") || s.contains("Cuisine:")

    /** Until the content pass fills `cuisine`, salvage it from the machine hook ("regional;pasta — from OpenStreetMap"). */
    private fun cleanedCuisine(poi: Poi): String? {
        if (poi.kind != PoiKind.EATDRINK) return null
        val raw = poi.hook.substringBefore("— from OpenStreetMap").trim().trimEnd('—').trim()
        if (raw.isBlank() || raw == poi.hook) return null // hand-written hooks are not cuisine lists
        return raw.replace(";", ", ")
    }
}
