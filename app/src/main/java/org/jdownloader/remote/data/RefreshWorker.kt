package org.jdownloader.remote.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.jdownloader.remote.RemoteApplication
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import org.jdownloader.remote.domain.DownloaderRepository
import java.util.concurrent.TimeUnit

@EntryPoint @InstallIn(SingletonComponent::class) interface WorkerDependencies {
    fun repository(): DownloaderRepository
    fun settings(): SettingsRepository
}
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
        if (deps.settings().loadSession() == null) return Result.success()
        return try { deps.repository().devices(); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.kind in setOf("LOGIN_REQUIRED", "TOKEN_INVALID", "AUTH_FAILED")) Result.failure() else Result.retry() }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("session-refresh", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
