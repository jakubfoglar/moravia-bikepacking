package cz.tripcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles prev/next/reset taps from the in-field buttons (via PendingIntent). */
class NavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("action")) {
            "next" -> AppState.move(1)
            "prev" -> AppState.move(-1)
            "reset" -> AppState.setIndex(0)
        }
    }

    companion object {
        const val ACTION = "cz.tripcompanion.NAV"
    }
}
