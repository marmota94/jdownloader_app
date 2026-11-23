package org.jdownloader.remote.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.*
import org.jdownloader.remote.data.*

data class RemoteState(
    val loading: Boolean = false, val signedIn: Boolean = false, val devices: List<Device> = emptyList(),
    val selected: Device? = null, val tab: Int = 0,
    val downloads: TransferListData? = null, val linkGrabber: TransferListData? = null,
    val downloadStatus: DownloadStatus? = null, val captchas: List<JsonObject> = emptyList(),
    val error: String? = null, val notice: Boolean = false, val captcha: JsonElement? = null,
    val incoming: String = "", val pendingContainers: Int = 0,
    val serverLimits: ServerLimits? = null, val serverUpdateAvailable: Boolean? = null,
    val advancedSettings: List<AdvancedSetting> = emptyList(),
    val archiveTarget: Pair<Long, Boolean>? = null, val archives: List<ArchiveInfo>? = null, val archiveRevision: Int = 0,
    val lastSuccessfulRefreshAt: Long? = null
)
@HiltViewModel class RemoteViewModel @Inject constructor(private val repo: DownloaderRepository, val settingsRepo: SettingsRepository, val cnlServer: org.jdownloader.remote.cnl.ClicknLoadServer) : ViewModel() {
    private val mutable = MutableStateFlow(RemoteState())
    val state = mutable.asStateFlow()
    val reconnecting = repo.reconnecting
    private val currentSettings = MutableStateFlow(Settings())
    val settings = currentSettings.asStateFlow()
    private val initializedSettings = MutableStateFlow(false)
    val settingsReady = initializedSettings.asStateFlow()
    private var polling: Job? = null
    private val actions = Mutex()
    private var foreground = false
    private val containers = ArrayDeque<Pair<String, ByteArray>>()
    init { viewModelScope.launch { settingsRepo.settings.collect { value -> currentSettings.value = value; initializedSettings.value = true } } }
    init { action {
        if (settingsRepo.loadSession() != null) {
            mutable.update { it.copy(signedIn = true) }
            val devices = repo.devices()
            mutable.update { it.copy(signedIn = true, devices = devices) }
            devices.firstOrNull { it.id == settingsRepo.deviceId() }?.let { selectInternal(it) }
        }
    } }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch { actions.withLock {
        mutable.update { it.copy(loading = true, error = null) }
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            reportError(e)
        } finally { mutable.update { it.copy(loading = false) } }
    } } }
    fun login(email: String, password: String, rememberSession: Boolean) = action {
        repo.login(email, password, rememberSession)
        mutable.update { it.copy(signedIn = true) }
        val devices = repo.devices()
        mutable.update { it.copy(devices = devices) }
    }
    private fun reportError(error: Exception) {
        val kind = (error as? ApiException)?.kind ?: "REQUEST_FAILED"
        mutable.update {
            if (kind == "LOGIN_REQUIRED") it.withoutDevice().copy(signedIn = false, error = kind)
            else it.copy(error = kind)
        }
        if (kind == "LOGIN_REQUIRED") polling?.cancel()
    }
    private fun RemoteState.withoutDevice() = copy(
        selected = null, downloads = null, linkGrabber = null, downloadStatus = null,
        captchas = emptyList(), captcha = null, serverLimits = null, serverUpdateAvailable = null, advancedSettings = emptyList(),
        archiveTarget = null, archives = null, lastSuccessfulRefreshAt = null
    )
    private suspend fun selectInternal(device: Device) {
        polling?.cancel()
        repo.select(device)
        mutable.update { it.withoutDevice().copy(selected = device) }
        try {
            try { sendPendingContainers() } finally { refreshInternal(bothLists = true) }
        } finally {
            startPolling(immediate = false)
        }
    }
    private fun startPolling(immediate: Boolean = true) {
        polling?.cancel()
        if (!foreground || state.value.selected == null) return
        polling = viewModelScope.launch {
            if (!immediate) delay(3000)
            while (isActive) {
                actions.withLock {
                    try { refreshInternal() } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { reportError(e) }
                }
                delay(3000)
            }
        }
    }
    fun setForeground(value: Boolean) { foreground = value; if (value) startPolling() else { polling?.cancel(); polling = null } }
    fun select(device: Device) = action { selectInternal(device) }
    fun pickDevice() {
        polling?.cancel()
        action {
            polling?.cancel()
            mutable.update { it.withoutDevice() }
            val devices = repo.devices()
            mutable.update { it.copy(devices = devices) }
        }
    }
    fun tab(value: Int) { mutable.update { it.copy(tab = value) } }
    suspend fun deviceOverview(device: Device): DeviceOverview? = try {
        withContext(Dispatchers.IO) { repo.deviceOverview(device.id) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        if (e is ApiException && e.kind == "LOGIN_REQUIRED") reportError(e)
        null
    }
    private suspend fun refreshInternal(bothLists: Boolean = false) = withContext(Dispatchers.IO) {
        val before = state.value
        if (before.selected == null) return@withContext
        var failure: Exception? = null
        // A failed endpoint should not hide the successful results from the other ones.
        suspend fun read(update: suspend () -> Unit) {
            try { update() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.kind == "LOGIN_REQUIRED") throw e
                if (failure == null) failure = e
            }
        }
        val activeCollector = before.tab == 1
        var fetchedDownloads = false
        for (collector in listOf(activeCollector, !activeCollector)) {
            val missing = if (collector) before.linkGrabber == null else before.downloads == null
            if (collector == activeCollector || bothLists || missing) read {
                if (!collector) fetchedDownloads = true
                val transfers = repo.transfers(collector)
                mutable.update { if (collector) it.copy(linkGrabber = transfers) else it.copy(downloads = transfers) }
            }
        }
        if (!fetchedDownloads && settings.value.completionAlerts) read { repo.checkPackageCompletion() }
        read {
            val status = repo.downloadStatus()
            mutable.update { it.copy(downloadStatus = status) }
        }
        if (before.tab == 2 || bothLists) read {
            val captchas = repo.captchas()
            mutable.update { it.copy(captchas = captchas) }
        }
        failure?.let { throw it }
        val refreshedAt = System.currentTimeMillis()
        mutable.update { it.copy(lastSuccessfulRefreshAt = refreshedAt) }
    }
    fun refresh() = action { if (state.value.selected != null) refreshInternal(bothLists = true) else { val devices = repo.devices(); mutable.update { it.copy(devices = devices) } } }
    fun command(path: String, vararg params: JsonElement) = action {
        repo.rpc(path, *params)
        val moved = path == "/linkgrabberv2/moveToDownloadlist"
        if (moved) repo.rpc("/downloadcontroller/start")
        refreshInternal(bothLists = moved)
    }
    fun add(links: String, password: String, name: String, archivePassword: String) = action {
        require(links.isNotBlank()); repo.addLinks(links, password, name, archivePassword); mutable.update { it.copy(notice = true, incoming = "") }; refreshInternal()
    }
    fun incoming(text: String) { mutable.update { it.copy(incoming = text, tab = 1) } }
    fun container(type: String, bytes: ByteArray) = action {
        require(type in setOf("dlc", "ccf", "rsdf") && bytes.size <= 5_000_000)
        require(containers.sumOf { it.second.size } + bytes.size <= 10_000_000)
        containers.addLast(type to bytes)
        mutable.update { it.copy(pendingContainers = containers.size, tab = 1) }
        if (state.value.selected != null) sendPendingContainers()
    }
    private suspend fun sendPendingContainers() {
        while (containers.isNotEmpty()) {
            val (type, bytes) = containers.first()
            repo.uploadContainer(type, bytes)
            containers.removeFirst()
            mutable.update { it.copy(notice = true, pendingContainers = containers.size, tab = 1) }
        }
    }
    fun failure() { mutable.update { it.copy(error = "REQUEST_FAILED") } }
    fun captcha(id: Long, format: String = "text") = action { val result = repo.rpc("/captcha/get", JsonPrimitive(id), JsonPrimitive(format)); mutable.update { it.copy(captcha = result) } }
    fun clearCaptcha() { mutable.update { it.copy(captcha = null) } }
    fun loadServerLimits() = action {
        val limits = withContext(Dispatchers.IO) { repo.serverLimits() }
        mutable.update { it.copy(serverLimits = limits) }
    }
    fun saveServerLimits(value: ServerLimits) = action {
        try {
            withContext(Dispatchers.IO) { repo.saveServerLimits(value) }
        } catch (e: Exception) {
            mutable.update { it.copy(serverLimits = null) }
            throw e
        }
        val limits = withContext(Dispatchers.IO) { repo.serverLimits() }
        mutable.update { it.copy(serverLimits = limits) }
    }
    fun checkServerUpdates() = action {
        mutable.update { it.copy(serverUpdateAvailable = null) }
        val available = withContext(Dispatchers.IO) { repo.checkServerUpdates() }
        mutable.update { it.copy(serverUpdateAvailable = available) }
    }
    fun searchAdvancedSettings(search: String) = action {
        mutable.update { it.copy(advancedSettings = emptyList()) }
        val entries = withContext(Dispatchers.IO) { repo.advancedSettings(search) }
        mutable.update { it.copy(advancedSettings = entries) }
    }
    fun saveAdvancedSetting(setting: AdvancedSetting, value: JsonElement) = action {
        val saved = withContext(Dispatchers.IO) { repo.saveAdvancedSetting(setting, value) }
        mutable.update { current -> current.copy(serverLimits = null,
            advancedSettings = current.advancedSettings.map { if (it == setting) it.copy(value = saved) else it }) }
    }
    fun addArchivePassword(row: Transfer, folder: Boolean, password: String) = action {
        withContext(Dispatchers.IO) {
            repo.addArchivePassword(if (folder) emptyList() else listOf(row.id), if (folder) listOf(row.id) else emptyList(), password)
        }
        refreshInternal()
    }
    private suspend fun refreshArchiveInfo(row: Transfer, folder: Boolean) {
        val target = row.id to folder
        val archives = withContext(Dispatchers.IO) {
            repo.archiveInfo(if (folder) emptyList() else listOf(row.id), if (folder) listOf(row.id) else emptyList())
        }
        mutable.update { if (it.archiveTarget == target) it.copy(archives = archives) else it }
    }
    fun loadArchiveInfo(row: Transfer, folder: Boolean) {
        mutable.update { it.copy(archiveTarget = row.id to folder, archives = null) }
        action { refreshArchiveInfo(row, folder) }
    }
    fun clearArchiveInfo() { mutable.update { it.copy(archiveTarget = null, archives = null) } }
    fun saveArchiveOptions(row: Transfer, folder: Boolean, archive: ArchiveInfo, autoExtract: Boolean?, extractPath: String, password: String) = action {
        withContext(Dispatchers.IO) { repo.saveArchiveOptions(archive.id, autoExtract, extractPath, password) }
        refreshArchiveInfo(row, folder)
        mutable.update { it.copy(archiveRevision = it.archiveRevision + 1) }
    }
    fun startExtraction(row: Transfer, folder: Boolean) = action {
        withContext(Dispatchers.IO) {
            repo.startExtraction(if (folder) emptyList() else listOf(row.id), if (folder) listOf(row.id) else emptyList())
        }
        refreshArchiveInfo(row, folder)
        refreshInternal()
    }
    fun cancelExtraction(row: Transfer, folder: Boolean, controllerId: Long) = action {
        withContext(Dispatchers.IO) { repo.cancelExtraction(controllerId) }
        refreshArchiveInfo(row, folder)
        refreshInternal()
    }
    fun saveSettings(value: Settings) { viewModelScope.launch {
        try { settingsRepo.update(value) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportError(e) }
    } }
    fun dismiss() { mutable.update { it.copy(error = null, notice = false) } }
    fun logout() = action {
        polling?.cancel()
        settingsRepo.update(settingsRepo.settings.first().copy(cnl = false))
        try { repo.logout() } finally { containers.clear(); mutable.value = RemoteState() }
    }
}
