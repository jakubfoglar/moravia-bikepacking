package cz.tripcompanion

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-app log + crash capture so bugs can be screenshotted / shared from the device. */
object Logger {
    private val buf = ArrayDeque<String>()
    private const val MAX = 250
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "trip-companion.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logError("CRASH ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
        log("Logger", "started, build ${BuildConfig.VERSION_CODE}")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} $tag: $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    fun logError(tag: String, e: Throwable) {
        log(tag, "ERROR ${e.javaClass.simpleName}: ${e.message}")
        e.stackTrace.take(6).forEach { log(tag, "  at $it") }
    }

    @Synchronized
    fun dump(): String = if (buf.isEmpty()) "(no logs yet)" else buf.joinToString("\n")
}
