package cz.tripcompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Draw a thing with your finger, send it to the website. Fable then comments on the
 * drawing itself, not on the fact that a drawing exists.
 *
 * Strokes go out as an SVG path (a few KB — nothing near the Karoo's 100KB HTTP ceiling)
 * plus a small PNG so Fable has something to actually look at.
 */
class SketchActivity : Activity() {
    private lateinit var pad: SketchView
    private lateinit var karoo: KarooSystemService
    private lateinit var status: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sketch)

        pad = findViewById(R.id.pad)
        status = findViewById(R.id.sk_status)

        // Send stays disabled until the system service is actually connected — otherwise a
        // quick draw-and-send would post through an unconnected service and just vanish.
        val send = findViewById<Button>(R.id.sk_send)
        send.isEnabled = false
        karoo = KarooSystemService(applicationContext)
        karoo.connect { connected ->
            runOnUiThread {
                send.isEnabled = connected
                if (!connected) status.text = "Karoo service nepřipojen."
            }
        }

        findViewById<Button>(R.id.sk_undo).setOnClickListener { pad.undo() }
        findViewById<Button>(R.id.sk_clear).setOnClickListener { pad.clear(); status.text = "" }
        findViewById<Button>(R.id.sk_close).setOnClickListener { finish() }
        send.setOnClickListener { send() }
    }

    private fun send() {
        if (pad.isEmpty()) { status.text = "Nejdřív něco nakresli."; return }
        val btn = findViewById<Button>(R.id.sk_send)
        btn.isEnabled = false
        status.text = "Posílám…"
        val svg = pad.toSvg()
        val png = pad.toPngBase64()
        val st = AppState.flow.value
        val day = TripSettings.effectiveDay(applicationContext)

        scope.launch {
            val payload = JSONObject().apply {
                put("kind", "drawing")
                put("svg", svg)
                put("media_b64", png)
                put("media_type", "image/png")
                put("lat", st.lat); put("lon", st.lon); put("day", day)
            }
            val r = withContext(Dispatchers.IO) { TripPoster.send(karoo, applicationContext, payload) }
            btn.isEnabled = true
            if (r.ok) {
                status.text = r.caption ?: "Odesláno."
                pad.clear()
            } else {
                status.text = r.error ?: "Nepodařilo se."
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { karoo.disconnect() } catch (e: Exception) { /* already gone */ }
        super.onDestroy()
    }
}

/** A finger-drawing pad that can export itself as both SVG and PNG. */
class SketchView(ctx: Context, attrs: android.util.AttributeSet?) : View(ctx, attrs) {
    private val strokes = mutableListOf<MutableList<Pair<Float, Float>>>()
    private var current: MutableList<Pair<Float, Float>>? = null

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111114")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun isEmpty() = strokes.isEmpty()
    fun clear() { strokes.clear(); current = null; invalidate() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                current = mutableListOf(e.x to e.y)
                strokes.add(current!!)
            }
            MotionEvent.ACTION_MOVE -> current?.add(e.x to e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }

    private fun pathOf(s: List<Pair<Float, Float>>): Path {
        val p = Path()
        if (s.isEmpty()) return p
        p.moveTo(s[0].first, s[0].second)
        for (i in 1 until s.size) p.lineTo(s[i].first, s[i].second)
        return p
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        for (s in strokes) canvas.drawPath(pathOf(s), ink)
    }

    /** Vector version for the website — scales cleanly and weighs almost nothing. */
    fun toSvg(): String {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val paths = strokes.filter { it.isNotEmpty() }.joinToString("") { s ->
            val d = StringBuilder("M${s[0].first.toInt()} ${s[0].second.toInt()}")
            for (i in 1 until s.size) d.append("L${s[i].first.toInt()} ${s[i].second.toInt()}")
            """<path d="$d" fill="none" stroke="#111114" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>"""
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h">$paths</svg>"""
    }

    /** Raster version so Fable can actually see it. Downscaled — it's line art, not a photo. */
    fun toPngBase64(): String {
        val scale = (600f / width.coerceAtLeast(1)).coerceAtMost(1f)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        for (s in strokes) c.drawPath(pathOf(s), ink)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
