package org.jdownloader.remote.cnl

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jdownloader.remote.R
import org.jdownloader.remote.MainActivity
import org.jdownloader.remote.data.SettingsRepository
import org.jdownloader.remote.domain.DownloaderRepository

@AndroidEntryPoint class CnlForegroundService : Service() {
    @Inject lateinit var server: ClicknLoadServer
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var repo: DownloaderRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cnl", getString(R.string.cnl_service), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CnlForegroundService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(9666, NotificationCompat.Builder(this, "cnl").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.cnl_service)).setContentText(getString(R.string.cnl_notification))
            .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.stop), stop).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            if (intent?.action == "STOP") { settings.update(settings.settings.first().copy(cnl = false)); stopSelf(); return@launch }
            try {
                if (!settings.settings.first().cnl) { stopSelf(); return@launch }
                val devices = repo.devices()
                val id = settings.deviceId()
                devices.firstOrNull { it.id == id }?.let { repo.select(it) } ?: error("DEVICE_REQUIRED")
                server.start()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                settings.update(settings.settings.first().copy(cnl = false)); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { server.stop(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
