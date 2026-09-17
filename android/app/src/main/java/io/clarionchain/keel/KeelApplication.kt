package io.clarionchain.keel

import android.app.Application
import io.clarionchain.keel.data.MaintenanceWorker

class KeelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Background VTXO renewal: keeps funds from expiring without the user
        // having to open the app every 24 h (signet server lifetime).
        MaintenanceWorker.schedule(this)
    }
}
