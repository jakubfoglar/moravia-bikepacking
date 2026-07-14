package cz.tripcompanion

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Info + self-update screen. The ride action is in the data fields; this is for updating over WiFi. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<TextView>(R.id.version).text =
            "Installed: build ${BuildConfig.VERSION_CODE} (v${BuildConfig.VERSION_NAME})"
        findViewById<Button>(R.id.btn_update).setOnClickListener { checkForUpdate() }
    }

    private fun checkForUpdate() {
        setStatus("Checking GitHub for a newer build…")
        Thread {
            val rel = UpdateManager.fetchLatest()
            when {
                rel == null ->
                    setStatus("Couldn't reach GitHub. Is the Karoo online (WiFi / phone hotspot)?")
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
