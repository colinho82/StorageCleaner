package com.storagecleaner.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.storagecleaner.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications for scan/archive/restore completion (v6-lite §16).
 * All notifications are local-only — no push services, no analytics.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "storage_cleaner_status"
        private const val NOTIF_SCAN = 2001
        private const val NOTIF_ARCHIVE = 2002
        private const val NOTIF_RESTORE = 2003
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cleanup status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Scan, archive and restore completion notices"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notifyScanComplete(recoverableLabel: String, groupCount: Int) {
        show(
            NOTIF_SCAN,
            "Scan complete",
            "Found $groupCount duplicate group(s) — $recoverableLabel recoverable"
        )
    }

    fun notifyArchiveComplete(fileCount: Int) {
        show(NOTIF_ARCHIVE, "Archive complete", "Successfully archived $fileCount file(s).")
    }

    fun notifyRestoreComplete(fileCount: Int) {
        show(NOTIF_RESTORE, "Restore complete", "Restored $fileCount archived file(s).")
    }

    private fun show(id: Int, title: String, text: String) {
        if (!hasPermission()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between check and notify — ignore.
        }
    }
}
