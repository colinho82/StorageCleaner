package com.storagecleaner.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint

/**
 * Placeholder foreground service. Not currently wired into the UI —
 * scheduled/background scanning is deferred to Phase 2 (WorkManager).
 */
@AndroidEntryPoint
class ScanForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
