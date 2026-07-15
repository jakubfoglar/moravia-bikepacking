package cz.tripcompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Info + self-update + logs screen. Ride action is in the data fields; this is for updates & bug reports. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var postStatus: TextView
    private lateinit var secretField: EditText
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        logs = findViewById(R.id.logs)
        findViewById<TextView>(R.id.version).text =
            "Build ${BuildConfig.VERSION_CODE} · v${BuildConfig.VERSION_NAME}"
        findViewById<Button>(R.id.btn_update).setOnClickListener { checkForUpdate() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refreshLogs() }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyLogs() }
        findViewById<Button>(R.id.btn_share).setOnClickListener { shareLogs() }
        findViewById<Button>(R.id.day_auto).setOnClickListener { setDay(0) }
        findViewById<Button>(R.id.day_1).setOnClickListener { setDay(1) }
        findViewById<Button>(R.id.day_2).setOnClickListener { setDay(2) }
        wireSwitch(R.id.sw_radar, TripSettings.radarEnabled(this), TripSettings.FLAG_RADAR)
        wireSwitch(R.id.sw_map, TripSettings.mapOverlayEnabled(this), TripSettings.FLAG_MAP)
        wireSwitch(R.id.sw_scenic, TripSettings.scenicEnabled(this), TripSettings.FLAG_SCENIC)
        wireSwitch(R.id.sw_posting, TripPoster.postingEnabled(this), TripPoster.FLAG_POSTING)

        postStatus = findViewById(R.id.post_status)
        secretField = findViewById(R.id.ed_secret)
        secretField.setText(TripPoster.secret(this))
        findViewById<Button>(R.id.btn_secret).setOnClickListener {
            TripPoster.setSecret(this, secretField.text.toString())
            postStatus.text = if (TripPoster.secret(this).isEmpty()) "Secret cleared." else "Secret saved."
        }
        findViewById<Button>(R.id.btn_testpost).setOnClickListener { testPost() }
        findViewById<Button>(R.id.btn_sketch).setOnClickListener {
            startActivity(Intent(this, SketchActivity::class.java))
        }

        refreshDay()
        refreshLogs()
    }

    /** Proves the whole chain in one tap: Karoo → Companion → Supabase → Fable → back. */
    private fun testPost() {
        postStatus.text = "Posting…"
        val karoo = KarooSystemService(applicationContext)
        karoo.connect { connected ->
            if (!connected) {
                runOnUiThread { postStatus.text = "Karoo system service not connected." }
                return@connect
            }
            scope.launch {
                val st = AppState.flow.value
                val payload = TripPoster.autoEvent(
                    "test",
                    mapOf("poznamka" to "zkouška z aplikace", "build" to BuildConfig.VERSION_CODE),
                    st.lat, st.lon, TripSettings.effectiveDay(this@MainActivity),
                )
                val r = withContext(Dispatchers.IO) { TripPoster.send(karoo, applicationContext, payload) }
                postStatus.text = if (r.ok) "Fable: ${r.caption ?: "(posted)"}" else "Failed: ${r.error}"
                try { karoo.disconnect() } catch (e: Exception) { /* already gone */ }
                refreshLogs()
            }
        }
    }

    private fun wireSwitch(id: Int, initial: Boolean, flag: String) {
        val sw = findViewById<android.widget.Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, v -> TripSettings.setFlag(this, flag, v) }
    }

    private fun setDay(v: Int) {
        TripSettings.setDayOverride(this, v)
        refreshDay()
    }

    private fun refreshDay() {
        val ov = TripSettings.dayOverride(this)
        val eff = TripSettings.effectiveDay(this)
        val mode = if (ov == 0) "auto" else "manual"
        findViewById<TextView>(R.id.day_effective).text = "Currently: Day $eff ($mode)"
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
    }

    private fun refreshLogs() {
        logs.text = Logger.dump()
    }

    private fun copyLogs() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Trip Companion logs", Logger.dump()))
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Trip Companion logs (build ${BuildConfig.VERSION_CODE})")
            putExtra(Intent.EXTRA_TEXT, Logger.dump())
        }
        startActivity(Intent.createChooser(i, "Share logs"))
    }

    private fun checkForUpdate() {
        setStatus("Checking GitHub for a newer build…")
        Thread {
            val rel = UpdateManager.fetchLatest()
            when {
                rel == null ->
                    setStatus("Couldn't reach GitHub (${UpdateManager.lastError ?: "no response"}). The Karoo needs real WiFi / a phone WiFi-hotspot — a Bluetooth tether usually won't route app traffic.")
                rel.versionCode <= BuildConfig.VERSION_CODE ->
                    setStatus("You're up to date (build ${BuildConfig.VERSION_CODE}).")
                else -> {
                    setStatus("Downloading build ${rel.versionCode}…")
                    val apk = UpdateManager.downloadApk(this, rel.apkUrl)
                    if (apk == null) {
                        setStatus("Download failed — try again.")
                    } else {
                        setStatus("Opening installer for build ${rel.versionCode}…")
                        runOnUiThread { UpdateManager.install(this, apk) }
                    }
                }
            }
        }.start()
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
