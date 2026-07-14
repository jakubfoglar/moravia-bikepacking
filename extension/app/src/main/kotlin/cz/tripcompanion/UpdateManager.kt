package cz.tripcompanion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: reads the latest GitHub Release, and if its build number is higher than
 * the installed one, downloads the APK and launches the system installer. Lets you update
 * the extension on the Karoo over WiFi — no laptop or cable needed.
 */
object UpdateManager {
    const val OWNER = "jakubfoglar"
    const val REPO = "moravia-bikepacking"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    @Volatile
    var lastError: String? = null
        private set

    data class Release(val versionCode: Int, val name: String, val apkUrl: String)

    private fun httpGet(urlStr: String): String {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TripCompanion")
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }?.take(120) ?: ""
            throw java.io.IOException("HTTP $code ${c.responseMessage} $err")
        }
        c.inputStream.bufferedReader().use { return it.readText() }
    }

    /** Latest release (tag "v<N>" carries the build number) or null on error / none. */
    fun fetchLatest(): Release? {
        return try {
            Logger.log("update.fetch", "GET $API")
            val json = JSONObject(httpGet(API))
            val tag = json.optString("tag_name")
            val vc = tag.removePrefix("v").toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apk = a.getString("browser_download_url"); break
                }
            }
            apk?.let { Release(vc, json.optString("name", tag), it) }
        } catch (e: Exception) {
            null
        }
    }

    fun downloadApk(ctx: Activity, url: String): File? {
        return try {
            val dir = ctx.externalCacheDir ?: ctx.cacheDir
            val f = File(dir, "update.apk")
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TripCompanion")
            }
            c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        } catch (e: Exception) {
            null
        }
    }

    /** Launches the system package installer (or asks for install permission first). */
    fun install(ctx: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
