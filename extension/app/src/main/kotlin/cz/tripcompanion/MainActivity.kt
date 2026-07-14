package cz.tripcompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Info + self-update + logs screen. Ride action is in the data fields; this is for updates & bug reports. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var logs: TextView

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
        refreshDay()
        refreshLogs()
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
}
