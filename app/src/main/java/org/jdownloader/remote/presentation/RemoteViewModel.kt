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
    val selected: Device? = null, val tab: Int = 0, val snapshot: Snapshot? = null,
    val error: String? = null, val notice: Boolean = false, val captcha: JsonElement? = null,
    val incoming: String = "", val pendingContainers: Int = 0
)
@HiltViewModel class RemoteViewModel @Inject constructor(private val repo: DownloaderRepository, val settingsRepo: SettingsRepository, api: MyJdApiClient, val cnlServer: org.jdownloader.remote.cnl.ClicknLoadServer) : ViewModel() {
    private val mutable = MutableStateFlow(RemoteState())
    val state = mutable.asStateFlow()
    private val currentSettings = MutableStateFlow(Settings())
    val settings = currentSettings.asStateFlow()
    private val initializedSettings = MutableStateFlow(false)
    val settingsReady = initializedSettings.asStateFlow()
    val connection = api.connection.asStateFlow()
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
            if (kind == "LOGIN_REQUIRED") it.copy(signedIn = false, selected = null, snapshot = null, error = kind)
            else it.copy(error = kind)
        }
        if (kind == "LOGIN_REQUIRED") polling?.cancel()
    }
    private suspend fun selectInternal(device: Device) {
        repo.select(device); mutable.update { it.copy(selected = device, snapshot = null) }
        sendPendingContainers()
        startPolling()
    }
    private fun startPolling() {
        polling?.cancel()
        if (!foreground || state.value.selected == null) return
        polling = viewModelScope.launch {
            while (isActive) {
                try { refreshInternal() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportError(e) }
                delay(3000)
            }
        }
    }
    fun setForeground(value: Boolean) { foreground = value; if (value) startPolling() else { polling?.cancel(); polling = null } }
    fun select(device: Device) = action { selectInternal(device) }
    fun pickDevice() { polling?.cancel(); mutable.update { it.copy(selected = null, snapshot = null) }; action { val devices = repo.devices(); mutable.update { it.copy(devices = devices) } } }
    fun tab(value: Int) { mutable.update { it.copy(tab = value) }; if (value < 2) refresh() }
    private suspend fun refreshInternal() {
        val before = state.value
        if (before.selected == null) return
        val snapshot = repo.snapshot(before.tab == 1)
        mutable.update { if (it.tab == before.tab && it.selected == before.selected) it.copy(snapshot = snapshot) else it }
    }
    fun refresh() = action { if (state.value.selected != null) refreshInternal() else { val devices = repo.devices(); mutable.update { it.copy(devices = devices) } } }
    fun command(path: String, vararg params: JsonElement) = action {
        repo.rpc(path, *params)
        if (path == "/linkgrabberv2/moveToDownloadlist") repo.rpc("/downloadcontroller/start")
        refreshInternal()
    }
    fun add(links: String, password: String, name: String) = action {
        require(links.isNotBlank()); repo.addLinks(links, password, name); mutable.update { it.copy(notice = true, incoming = "") }; refreshInternal()
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
    fun saveSettings(value: Settings) = action { settingsRepo.update(value) }
    fun dismiss() { mutable.update { it.copy(error = null, notice = false) } }
    fun logout() = action {
        polling?.cancel()
        settingsRepo.update(settingsRepo.settings.first().copy(cnl = false))
        try { repo.logout() } finally { containers.clear(); mutable.value = RemoteState() }
    }
}
