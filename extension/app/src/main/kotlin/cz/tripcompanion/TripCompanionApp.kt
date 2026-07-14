package cz.tripcompanion

import android.app.Application

class TripCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.init(applicationContext)
    }
}
