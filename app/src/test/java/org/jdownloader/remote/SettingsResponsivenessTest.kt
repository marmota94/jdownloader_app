package org.jdownloader.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.jdownloader.remote.cnl.ClicknLoadServer
import org.jdownloader.remote.cnl.CnlDecryptor
import org.jdownloader.remote.data.SettingsRepository
import org.jdownloader.remote.domain.*
import org.jdownloader.remote.presentation.RemoteViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class SettingsResponsivenessTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun settingsAndTabsRemainResponsiveDuringRefresh() = runBlocking {
        val ui = newSingleThreadContext("test-ui")
        Dispatchers.setMain(ui)
        val store = ViewModelStore()
        val started = CompletableDeferred<Thread>()
        val release = CompletableDeferred<Unit>()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files.root
        }
        val settings = SettingsRepository(context)
        val repo = object : DownloaderRepository {
            override val reconnecting = MutableStateFlow(false)
            override suspend fun select(device: Device) {}
            override suspend fun transfers(collector: Boolean): TransferListData {
                started.complete(Thread.currentThread())
                release.await()
                return TransferListData(emptyList(), emptyList())
            }
            override suspend fun downloadStatus() = DownloadStatus(0, "STOPPED")
            override suspend fun captchas() = emptyList<JsonObject>()
            override suspend fun checkPackageCompletion() {}
            override suspend fun devices() = emptyList<Device>()
            override suspend fun deviceOverview(deviceId: String): DeviceOverview = error("Unexpected overview")
            override suspend fun serverLimits(): ServerLimits = error("Unexpected limits")
            override suspend fun saveServerLimits(value: ServerLimits) = error("Unexpected limits change")
            override suspend fun checkServerUpdates(): Boolean = error("Unexpected update check")
            override suspend fun advancedSettings(search: String): List<AdvancedSetting> = error("Unexpected advanced settings")
            override suspend fun saveAdvancedSetting(setting: AdvancedSetting, value: JsonElement): JsonElement = error("Unexpected advanced change")
            override suspend fun addArchivePassword(linkIds: List<Long>, packageIds: List<Long>, password: String) = error("Unexpected archive password")
            override suspend fun archiveInfo(linkIds: List<Long>, packageIds: List<Long>): List<ArchiveInfo> = error("Unexpected archive lookup")
            override suspend fun saveArchiveOptions(id: String, autoExtract: Boolean?, extractPath: String, password: String) = error("Unexpected extraction change")
            override suspend fun startExtraction(linkIds: List<Long>, packageIds: List<Long>) = error("Unexpected extraction")
            override suspend fun cancelExtraction(controllerId: Long) = error("Unexpected extraction cancellation")
            override suspend fun login(email: String, password: String, rememberSession: Boolean) = error("Unexpected login")
            override suspend fun rpc(path: String, vararg params: JsonElement): JsonElement = error("Unexpected RPC")
            override suspend fun addLinks(links: String, password: String, name: String, archivePassword: String) = error("Unexpected add")
            override suspend fun uploadContainer(type: String, bytes: ByteArray) = error("Unexpected upload")
            override suspend fun logout() = error("Unexpected logout")
        }
        try {
            withTimeout(10000) {
                val vm = withContext(ui) {
                    RemoteViewModel(repo, settings, ClicknLoadServer(CnlDecryptor(context), repo, settings))
                        .also { store.put("remote", it) }
                }
                vm.settingsReady.first { it }
                // Wait for session restoration before starting a deliberately stalled refresh.
                vm.state.first { !it.loading }
                val uiThread = withContext(ui) {
                    vm.select(Device("device", "Test"))
                    Thread.currentThread()
                }
                assertNotEquals(uiThread, started.await())
                withContext(ui) {
                    vm.saveSettings(vm.settings.value.copy(accent = AccentColor.PURPLE, dynamic = false))
                    vm.tab(3)
                }
                vm.settings.first { it.accent == AccentColor.PURPLE && !it.dynamic }
                assertEquals(3, vm.state.value.tab)
                assertTrue(vm.state.value.loading)
                assertNull(vm.state.value.lastSuccessfulRefreshAt)
                assertFalse(release.isCompleted)
                assertEquals(AccentColor.PURPLE, settings.settings.first().accent)
                withContext(ui) { vm.tab(1) }
                assertEquals(1, vm.state.value.tab)
                release.complete(Unit)
                vm.state.first { !it.loading }
                assertNull(vm.state.value.error)
                assertNotNull(vm.state.value.lastSuccessfulRefreshAt)
            }
        } finally {
            release.complete(Unit)
            withContext(ui) { store.clear() }
            Dispatchers.resetMain()
            ui.close()
        }
    }
}
