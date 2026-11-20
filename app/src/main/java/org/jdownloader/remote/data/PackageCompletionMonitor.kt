package org.jdownloader.remote.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jdownloader.remote.MainActivity
import org.jdownloader.remote.R
import org.jdownloader.remote.domain.Transfer

internal fun newCompletionIds(previous: Set<String>?, current: Set<String>): Set<String> =
    if (previous == null) emptySet() else current - previous

@Singleton
class PackageCompletionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    private val checks = Mutex()
    private val manager = context.getSystemService(NotificationManager::class.java)

    suspend fun check(deviceId: String, load: suspend () -> List<Transfer>): List<Transfer> = checks.withLock {
        val packages = load()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return@withLock packages
        if (!manager.areNotificationsEnabled() || packages.any { it.finished == null || it.id <= 0 }) {
            return@withLock packages
        }
        val channel = "completed_downloads"
        manager.createNotificationChannel(NotificationChannel(
            channel, context.getString(R.string.completion_alerts), NotificationManager.IMPORTANCE_DEFAULT
        ))
        if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return@withLock packages

        val completed = packages.filter { it.finished == true }.associateBy { it.id.toString() }
        val newIds = settings.recordCompletedPackages(deviceId, completed.keys)
        val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        for (id in newIds) {
            val name = completed.getValue(id).name
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.package_finished))
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(name))
                .setAutoCancel(true)
                .setContentIntent(launch)
                .build()
            manager.notify("completed:$deviceId:$id", 1, notification)
        }
        packages
    }
}
