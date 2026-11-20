package org.jdownloader.remote

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.jdownloader.remote.domain.DownloaderRepository
import org.jdownloader.remote.data.RemoteRepository

@HiltAndroidApp class RemoteApplication : Application()
@Module @InstallIn(SingletonComponent::class) object RepositoryModule {
    @Provides fun repository(impl: RemoteRepository): DownloaderRepository = impl
    @Provides fun sessions(impl: org.jdownloader.remote.data.SettingsRepository): org.jdownloader.remote.data.SessionStorage = impl
}
