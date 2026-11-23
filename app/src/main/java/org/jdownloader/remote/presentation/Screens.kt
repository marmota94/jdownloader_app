package org.jdownloader.remote.presentation

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.json.*
import org.jdownloader.remote.R
import org.jdownloader.remote.domain.*
import org.jdownloader.remote.cnl.CnlForegroundService
import org.jdownloader.remote.presentation.theme.RemoteTheme
import org.jdownloader.remote.presentation.theme.color
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RemoteApp(vm: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reconnecting by vm.reconnecting.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var filtersOpen by remember(state.selected?.id, state.tab) { mutableStateOf(false) }
    LifecycleStartEffect(vm) { vm.setForeground(true); onStopOrDispose { vm.setForeground(false) } }
    LaunchedEffect(settingsReady, settings.cnl, state.selected) {
        if (!settingsReady) return@LaunchedEffect
        if (settings.cnl && state.selected != null) androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CnlForegroundService::class.java))
        else if (!settings.cnl) context.stopService(Intent(context, CnlForegroundService::class.java))
    }
    LaunchedEffect(settingsReady, settings.locale) {
        if (!settingsReady) return@LaunchedEffect
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != settings.locale)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(settings.locale))
    }
    LaunchedEffect(state.selected?.id, state.tab) {
        if (state.selected != null && state.tab == 3) vm.loadServerLimits()
    }
    RemoteTheme(settings) {
        Scaffold(topBar = {
            TopAppBar(title = { Column {
                Text(state.selected?.name ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.selected != null) {
                    val status = state.downloadStatus
                    val lastRefresh = state.lastSuccessfulRefreshAt
                    val subtitle = when {
                        reconnecting -> stringResource(R.string.reconnecting)
                        status != null -> "${serverStateLabel(status.state).lowercase(locale)} · ${stringResource(R.string.speed_per_second, formatByteSize(status.speed, locale))}"
                        else -> "—"
                    }
                    Text(subtitle, style = MaterialTheme.typography.labelMedium,
                        color = if (reconnecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (lastRefresh != null) {
                        val time = remember(lastRefresh, locale, context) {
                            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(lastRefresh))
                        }
                        Text(stringResource(R.string.last_refreshed, time), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } }, actions = {
                state.selected?.let { device ->
                    key(device.id) {
                        DeviceMenu(state, vm, onFilters = if (state.tab == 0) ({ filtersOpen = true }) else null)
                    }
                }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error != null) Row(Modifier.padding(12.dp)) {
                    Text(stringResource(when (state.error) {
                        "LOGIN_REQUIRED" -> R.string.session_expired
                        "AUTH_FAILED", "TOKEN_INVALID" -> R.string.auth_error
                        "DELIVERY_UNCERTAIN" -> R.string.uncertain_error
                        "ARCHIVE_NOT_FOUND" -> R.string.archive_not_found
                        "EXTRACTION_NOT_READY" -> R.string.extraction_not_ready
                        else -> R.string.request_error
                    }), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.dismiss)) }
                }
                if (state.notice) Row(Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.links_added), Modifier.weight(1f))
                    TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.dismiss)) }
                }
                if (state.pendingContainers > 0) Text(stringResource(R.string.pending_containers, state.pendingContainers), Modifier.padding(horizontal = 16.dp))
                when {
                    !state.signedIn -> LoginScreen(state.loading, vm::login)
                    state.selected == null -> DevicePicker(state.devices, vm::select, vm::refresh, vm::logout, vm::deviceOverview)
                    else -> {
                        val labels = listOf(R.string.downloads, R.string.linkgrabber, R.string.captchas, R.string.settings)
                        val pager = rememberPagerState(initialPage = state.tab) { labels.size }
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(state.tab) { pager.scrollToPage(state.tab) }
                        LaunchedEffect(pager) {
                            snapshotFlow { pager.settledPage }.drop(1).collect { vm.tab(it) }
                        }
                        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp,
                            containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.primary) {
                            labels.forEachIndexed { index, label -> Tab(selected = pager.currentPage == index,
                                onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = {
                                    Text(stringResource(label), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                                }) }
                        }
                        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                            when (page) {
                                0 -> TransferList(state, vm, collector = false, filtersOpen = filtersOpen,
                                    onCloseFilters = { filtersOpen = false })
                                1 -> LinkGrabberScreen(state, vm)
                                2 -> CaptchaScreen(state, vm)
                                3 -> SettingsScreen(settings, vm::saveSettings, state, vm) {
                                    context.stopService(Intent(context, CnlForegroundService::class.java)); vm.logout()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun DeviceMenu(state: RemoteState, vm: RemoteViewModel, onFilters: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val paused = state.downloadStatus?.state == "PAUSE"
    val actions = listOf<Pair<Int, () -> Unit>>(
        R.string.play to { vm.command("/downloadcontroller/start") },
        R.string.devices to vm::pickDevice,
        R.string.stop to { vm.command("/downloadcontroller/stop") },
        (if (paused) R.string.resume else R.string.pause_only) to {
            vm.command("/downloadcontroller/pause", JsonPrimitive(!paused))
        },
        R.string.refresh to vm::refresh
    )
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.device_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onFilters != null) DropdownMenuItem(text = { Text(stringResource(R.string.download_filters)) },
                onClick = { expanded = false; onFilters() })
            for ((label, action) in actions) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    enabled = label == R.string.devices || !state.loading,
                    onClick = { expanded = false; action() }
                )
            }
        }
    }
}

@Composable fun LoginScreen(loading: Boolean, login: (String, String, Boolean) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberSession by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val canSignIn = !loading && email.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSignIn) {
            focusManager.clearFocus()
            passwordVisible = false
            login(email.trim(), password, rememberSession)
            password = ""
        }
    }
    Box(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.login_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                email, { email = it },
                label = { Text(stringResource(R.string.email)) },
                singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                password, { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !loading) {
                        Icon(
                            painterResource(if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password)
                        )
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberSession, onCheckedChange = { rememberSession = it }, enabled = !loading)
                Column {
                    Text(stringResource(R.string.remember_session))
                    Text(stringResource(R.string.remember_session_detail), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = submit, enabled = canSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (loading) R.string.signing_in else R.string.sign_in))
            }
        }
    }
}
@Composable fun DevicePicker(devices: List<Device>, select: (Device) -> Unit, refresh: () -> Unit, logout: () -> Unit,
    overview: suspend (Device) -> DeviceOverview?) {
    var refreshVersion by remember { mutableIntStateOf(0) }
    val requests = remember { mutableSetOf<Job>() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.select_device), style = MaterialTheme.typography.titleLarge) }
        if (devices.isEmpty()) item { Text(stringResource(R.string.no_devices)) }
        items(devices, key = { it.id }) { device ->
            var status by remember(device.id, refreshVersion) { mutableStateOf<DeviceOverview?>(null) }
            var loading by remember(device.id, refreshVersion) { mutableStateOf(true) }
            LaunchedEffect(device.id, refreshVersion) {
                val request = currentCoroutineContext().job
                requests.add(request)
                try { status = overview(device); loading = false }
                finally { requests.remove(request) }
            }
            OutlinedCard(onClick = { requests.toList().forEach { it.cancel() }; select(device) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val summary = status
                    val locale = LocalConfiguration.current.locales[0]
                    val statusLabel = (if (summary == null) stringResource(if (loading) R.string.device_status_loading else R.string.device_status_unavailable)
                        else serverStateLabel(summary.state)).lowercase(locale)
                    Text(stringResource(R.string.device_name_status, device.name, statusLabel),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (summary != null) {
                        Text(stringResource(R.string.speed_per_second, formatByteSize(summary.speed, locale)),
                            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        val progress = summary.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.device_progress, formatByteSize(summary.loaded, locale),
                                formatByteSize(summary.total, locale), java.text.NumberFormat.getPercentInstance(locale).format(progress)),
                                style = MaterialTheme.typography.bodySmall)
                        } else Text(stringResource(R.string.device_progress_unknown, formatByteSize(summary.loaded, locale)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { refreshVersion++; refresh() }) { Text(stringResource(R.string.refresh)) }
            TextButton(onClick = { requests.toList().forEach { it.cancel() }; logout() }) { Text(stringResource(R.string.sign_out)) }
        } }
    }
}
@Composable fun LinkGrabberScreen(state: RemoteState, vm: RemoteViewModel) {
    Column(Modifier.fillMaxSize()) {
        var links by rememberSaveable(state.incoming) { mutableStateOf(state.incoming) }
        var password by remember { mutableStateOf("") }
        var archivePassword by remember { mutableStateOf("") }
        var name by rememberSaveable { mutableStateOf("") }
        var expanded by rememberSaveable { mutableStateOf(state.incoming.isNotEmpty()) }
        LaunchedEffect(state.incoming) { if (state.incoming.isNotEmpty()) expanded = true }
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text(stringResource(if (expanded) R.string.hide_form else R.string.add_links)) }
        if (expanded) OutlinedCard(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(links, { links = it }, label = { Text(stringResource(R.string.links)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.package_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.package_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(archivePassword, { archivePassword = it }, label = { Text(stringResource(R.string.archive_password_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.add(links, password, name, archivePassword) }, enabled = links.isNotBlank() && !state.loading) { Text(stringResource(R.string.send)) }
            }
        }
        Box(Modifier.weight(1f)) { TransferList(state, vm, true) }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TransferList(state: RemoteState, vm: RemoteViewModel, collector: Boolean,
    filtersOpen: Boolean = false, onCloseFilters: () -> Unit = {}) {
    var pending by remember(state.selected?.id, collector) { mutableStateOf<Pair<Transfer, Boolean>?>(null) }
    var passwordTarget by remember(state.selected?.id, collector) { mutableStateOf<Pair<Transfer, Boolean>?>(null) }
    var archiveTarget by remember(state.selected?.id, collector) { mutableStateOf<Pair<Transfer, Boolean>?>(null) }
    var expandedFolders by rememberSaveable(state.selected?.id, collector) { mutableStateOf(longArrayOf()) }
    var selectedIds by rememberSaveable(state.selected?.id, collector) { mutableStateOf(longArrayOf()) }
    var query by rememberSaveable(state.selected?.id, collector) { mutableStateOf("") }
    var filter by rememberSaveable(state.selected?.id, collector) { mutableStateOf(DownloadFilter.ALL) }
    val filtering = !collector && (query.isNotBlank() || filter != DownloadFilter.ALL)
    var collapsedResults by remember(query, filter, state.selected?.id) { mutableStateOf(setOf<Long>()) }
    val ns = if (collector) "/linkgrabberv2" else "/downloadsV2"
    val transfers = (if (collector) state.linkGrabber else state.downloads) ?: return
    val packages = transfers.packages
    val linksByPackage = remember(transfers.links) { transfers.links.groupBy { it.packageId } }
    val visible = remember(transfers, query, filter) { matchingDownloads(transfers, query, filter) }
    val visibleLinksByPackage = if (visible === transfers) linksByPackage
        else remember(visible.links) { visible.links.groupBy { it.packageId } }
    val packageIds = remember(packages) { packages.mapTo(hashSetOf()) { it.id } }
    val selectedSet = remember(selectedIds) { selectedIds.toSet() }
    val selection = remember(transfers.links, selectedSet) { transfers.links.filter { it.id in selectedSet } }
    val hiddenSelectionCount = if (filtering) selection.size - visible.links.count { it.id in selectedSet } else 0
    // Packages and links arrive in separate calls and can change between responses.
    val ungrouped = remember(visible.links, packageIds) { visible.links.filter { it.packageId !in packageIds } }
    LaunchedEffect(transfers.links) {
        val currentIds = transfers.links.mapTo(hashSetOf()) { it.id }
        selectedIds = selectedIds.filter { it in currentIds }.toLongArray()
    }
    fun command(path: String, row: Transfer, folder: Boolean, vararg prefix: JsonElement) {
        val ids = JsonArray(listOf(JsonPrimitive(row.id)))
        val empty = JsonArray(emptyList())
        vm.command(path, *prefix, if (folder) empty else ids, if (folder) ids else empty)
    }
    fun selectFiles(files: List<Transfer>) {
        selectedIds = toggleTransferSelection(selectedSet, files.map { it.id }).toLongArray()
    }
    fun selectionState(files: List<Transfer>): ToggleableState? {
        if (collector || files.isEmpty()) return null
        return when {
            files.all { it.id in selectedSet } -> ToggleableState.On
            files.any { it.id in selectedSet } -> ToggleableState.Indeterminate
            else -> ToggleableState.Off
        }
    }
    fun move(row: Transfer, folder: Boolean, direction: Int): (() -> Unit)? {
        if (collector) return null
        val siblings = if (folder) packages else linksByPackage[row.packageId].orEmpty()
        val params = transferMoveParams(row, folder, siblings, direction) ?: return null
        return { vm.command("$ns/${if (folder) "movePackages" else "moveLinks"}", *params) }
    }
    fun retry(file: Transfer): (() -> Unit)? {
        if (collector || file.enabled == false || file.finished == true || file.running) return null
        return { command("$ns/resumeLinks", file, false) }
    }
    val start = if (collector) "$ns/moveToDownloadlist" else "$ns/forceDownload"
    val selectVisible = {
        selectedIds = (selectedSet + visible.links.map { it.id }).toLongArray()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (selection.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.selection_counts, selection.size, selection.count { it.finished == true }),
                    style = MaterialTheme.typography.titleSmall)
                if (hiddenSelectionCount > 0) Text(stringResource(R.string.hidden_selection_count, hiddenSelectionCount),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (enabled in listOf(true, false)) TextButton(onClick = {
                        vm.command("$ns/setEnabled", JsonPrimitive(enabled), JsonArray(selection.map { JsonPrimitive(it.id) }), JsonArray(emptyList()))
                    }, enabled = !state.loading) { Text(stringResource(if (enabled) R.string.enable else R.string.disable)) }
                    TextButton(onClick = { selectedIds = longArrayOf() }) { Text(stringResource(R.string.clear_selection)) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!collector) item(key = "summary") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(if (selection.isEmpty()) R.string.overview else R.string.selection),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.download_counts, transfers.links.size, transfers.links.count { it.finished == true }),
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            state.downloadStatus?.let {
                                Text(stringResource(R.string.speed_per_second, formatByteSize(it.speed, LocalConfiguration.current.locales[0])),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        val summary = if (selection.isEmpty()) transfers.links else selection
                        if (summary.isNotEmpty()) {
                            val knownSize = summary.all { it.total > 0 }
                            TransferProgress(summary.sumOf { it.loaded.coerceAtLeast(0) }, if (knownSize) summary.sumOf { it.total } else 0)
                        }
                    }
                }
            }
            if (!collector) item(key = "select") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = selectVisible, enabled = visible.links.any { it.id !in selectedSet }) {
                        Text(stringResource(if (filtering) R.string.select_results else R.string.select_all))
                    }
                    if (filtering) Text(stringResource(R.string.filtered_download_count, visible.links.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (visible.packages.isEmpty() && ungrouped.isEmpty()) item {
                Text(stringResource(if (filtering) R.string.no_download_matches else R.string.empty_list), Modifier.padding(16.dp))
            }
            for (folder in visible.packages) {
                val files = visibleLinksByPackage[folder.id].orEmpty()
                val allFiles = linksByPackage[folder.id].orEmpty()
                val expanded = if (filtering) folder.id !in collapsedResults else folder.id in expandedFolders
                item(key = "folder:${folder.id}") {
                    TransferRow(folder, collector, state.loading,
                        onStart = { command(start, folder, true) },
                        onPriority = { command("$ns/setPriority", folder, true, JsonPrimitive("HIGH")) },
                        onRemove = { pending = folder to true },
                        onEnabled = { command("$ns/setEnabled", folder, true, JsonPrimitive(it)) },
                        onArchivePassword = { passwordTarget = folder to true },
                        onArchiveTools = { archiveTarget = folder to true },
                        onMoveUp = move(folder, true, -1), onMoveDown = move(folder, true, 1),
                        selectionState = selectionState(files), onSelect = { selectFiles(files) },
                        counts = if (collector) stringResource(R.string.item_count, allFiles.size)
                            else stringResource(R.string.folder_counts, allFiles.count { it.finished == true }, allFiles.size),
                        expanded = expanded,
                        onToggle = {
                            if (filtering) collapsedResults = if (expanded) collapsedResults + folder.id else collapsedResults - folder.id
                            else expandedFolders = if (expanded) expandedFolders.filterNot { it == folder.id }.toLongArray()
                                else expandedFolders + folder.id
                        })
                }
                if (expanded && files.isEmpty()) item(key = "empty:${folder.id}") {
                    Text(stringResource(R.string.empty_folder), Modifier.padding(start = 28.dp))
                }
                if (expanded) items(files, key = { "file:${it.id}" }) { file ->
                    TransferRow(file, collector, state.loading,
                        onStart = { command(start, file, false) },
                        onPriority = { command("$ns/setPriority", file, false, JsonPrimitive("HIGH")) },
                        onRemove = { pending = file to false },
                        onEnabled = { command("$ns/setEnabled", file, false, JsonPrimitive(it)) },
                        onArchivePassword = { passwordTarget = file to false },
                        onArchiveTools = { archiveTarget = file to false },
                        onRetry = retry(file),
                        onMoveUp = move(file, false, -1), onMoveDown = move(file, false, 1),
                        selectionState = selectionState(listOf(file)), onSelect = { selectFiles(listOf(file)) },
                        modifier = Modifier.padding(start = 24.dp))
                }
            }
            items(ungrouped, key = { "file:${it.id}" }) { file ->
                TransferRow(file, collector, state.loading,
                    onStart = { command(start, file, false) },
                    onPriority = { command("$ns/setPriority", file, false, JsonPrimitive("HIGH")) },
                    onRemove = { pending = file to false },
                    onEnabled = { command("$ns/setEnabled", file, false, JsonPrimitive(it)) },
                    onArchivePassword = { passwordTarget = file to false },
                    onArchiveTools = { archiveTarget = file to false },
                    onRetry = retry(file),
                    selectionState = selectionState(listOf(file)), onSelect = { selectFiles(listOf(file)) })
            }
        }
    }
    if (!collector && filtersOpen) AlertDialog(
        onDismissRequest = onCloseFilters,
        title = { Text(stringResource(R.string.download_filters)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_downloads)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in DownloadFilter.entries) FilterChip(selected = option == filter, onClick = { filter = option }, label = {
                        Text(stringResource(when (option) {
                            DownloadFilter.ALL -> R.string.filter_all
                            DownloadFilter.ACTIVE -> R.string.downloading
                            DownloadFilter.FINISHED -> R.string.finished
                            DownloadFilter.DISABLED -> R.string.disabled
                        }))
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = onCloseFilters) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            if (filtering) TextButton(onClick = { query = ""; filter = DownloadFilter.ALL }) {
                Text(stringResource(R.string.clear_filters))
            }
        }
    )
    pending?.let { (row, folder) -> AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.remove)) }, text = { Text(stringResource(if (folder) R.string.confirm_remove_folder else R.string.confirm_remove, row.name)) },
        confirmButton = { TextButton(onClick = {
            command("$ns/removeLinks", row, folder)
            pending = null
        }) { Text(stringResource(R.string.remove)) } }, dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } }) }
    passwordTarget?.let { (row, folder) ->
        var password by remember(row.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordTarget = null }, title = { Text(stringResource(R.string.archive_password)) },
            text = { Column {
                Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
            } }, confirmButton = { TextButton(enabled = password.isNotBlank() && !state.loading, onClick = {
                vm.addArchivePassword(row, folder, password); passwordTarget = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { passwordTarget = null }) { Text(stringResource(R.string.cancel)) } })
    }
    archiveTarget?.let { (row, folder) -> ArchiveSheet(row, folder, state, vm) { archiveTarget = null } }
}

@Composable private fun TransferProgress(loaded: Long, total: Long, enabled: Boolean = true, compact: Boolean = false) {
    val locale = LocalConfiguration.current.locales[0]
    if (total > 0) {
        val progress = (loaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            trackColor = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        if (!compact) Text(stringResource(R.string.device_progress, formatByteSize(loaded, locale), formatByteSize(total, locale),
            java.text.NumberFormat.getPercentInstance(locale).format(progress)), style = MaterialTheme.typography.bodySmall)
    } else if (!compact) Text(stringResource(R.string.device_progress_unknown, formatByteSize(loaded, locale)), style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TransferRow(
    transfer: Transfer,
    collector: Boolean,
    loading: Boolean,
    onStart: () -> Unit,
    onPriority: () -> Unit,
    onRemove: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onArchivePassword: () -> Unit,
    onArchiveTools: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    selectionState: ToggleableState? = null,
    onSelect: (() -> Unit)? = null,
    counts: String? = null
) {
    val folder = onToggle != null
    val compact = folder && !expanded
    val disabled = transfer.enabled == false
    val selected = selectionState == ToggleableState.On
    val locale = LocalConfiguration.current.locales[0]
    var menuOpen by remember { mutableStateOf(false) }
    val background = when {
        disabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    OutlinedCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = background),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectionState != null) {
                    val description = stringResource(R.string.select_transfer, transfer.name)
                    TriStateCheckbox(state = selectionState, onClick = onSelect,
                        modifier = Modifier.semantics { contentDescription = description })
                }
                val folderState = if (folder) stringResource(if (expanded) R.string.folder_expanded else R.string.folder_collapsed) else null
                val header = if (onToggle != null) Modifier
                    .semantics { stateDescription = checkNotNull(folderState) }
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)
                    else Modifier
                Row(Modifier.weight(1f).then(header).heightIn(min = 48.dp).alpha(if (disabled) 0.65f else 1f),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (folder) Icon(painterResource(R.drawable.ic_transfer_folder), null, Modifier.size(20.dp)) else HostIcon(transfer.host, !disabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(transfer.name, maxLines = 1, softWrap = false, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                            style = if (folder) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                        if (transfer.host.isNotBlank()) Text(transfer.host, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (folder) Icon(painterResource(if (expanded) R.drawable.ic_folder_collapse else R.drawable.ic_folder_expand), null, Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !loading) {
                        Icon(painterResource(R.drawable.ic_more), stringResource(R.string.transfer_actions, transfer.name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { menuOpen = false; onStart() })
                        if (onRetry != null) DropdownMenuItem(text = { Text(stringResource(R.string.retry_download)) }, enabled = !loading,
                            onClick = { menuOpen = false; onRetry() })
                        for (enabled in listOf(true, false)) DropdownMenuItem(text = { Text(stringResource(if (enabled) R.string.enable else R.string.disable)) },
                            onClick = { menuOpen = false; onEnabled(enabled) })
                        if (!collector) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, enabled = onMoveUp != null,
                                onClick = { menuOpen = false; onMoveUp?.invoke() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, enabled = onMoveDown != null,
                                onClick = { menuOpen = false; onMoveDown?.invoke() })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.priority_high)) }, onClick = { menuOpen = false; onPriority() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.archive_password)) }, onClick = { menuOpen = false; onArchivePassword() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.archive_tools)) }, onClick = { menuOpen = false; onArchiveTools() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menuOpen = false; onRemove() })
                    }
                }
            }
            counts?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(it, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (compact && !collector && transfer.total > 0) Text(java.text.NumberFormat.getPercentInstance(locale)
                        .format((transfer.loaded.toDouble() / transfer.total).coerceIn(0.0, 1.0)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
            }
            if (!collector) TransferProgress(transfer.loaded, transfer.total, !disabled, compact)
            else if (transfer.total > 0) Text(formatByteSize(transfer.total, locale), style = MaterialTheme.typography.bodySmall)
            val status = when {
                transfer.status.isNotBlank() -> transfer.status
                transfer.finished == true -> stringResource(R.string.finished)
                transfer.running -> stringResource(R.string.downloading)
                else -> stringResource(R.string.queued)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (disabled) "${stringResource(R.string.disabled)} · $status" else status,
                    modifier = Modifier.weight(1f), maxLines = if (compact) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!collector && transfer.speed > 0) Text(stringResource(R.string.speed_per_second, formatByteSize(transfer.speed, locale)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            if (!compact && !collector && transfer.eta >= 0 && transfer.finished != true) Text(stringResource(R.string.time_remaining,
                android.text.format.DateUtils.formatElapsedTime(transfer.eta)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun SettingsScreen(settings: Settings, save: (Settings) -> Unit, state: RemoteState, vm: RemoteViewModel, logout: () -> Unit) {
    var enableRequested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enableRequested = true }
    val context = LocalContext.current
    val completionPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save(settings.copy(completionAlerts = true))
    }
    LaunchedEffect(enableRequested) { if (enableRequested) { save(settings.copy(cnl = true)); enableRequested = false } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsGroup(stringResource(R.string.appearance)) {
            var themeExpanded by remember { mutableStateOf(false) }
            val modes = listOf(R.string.system, R.string.light, R.string.dark, R.string.amoled)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.theme_mode), Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { themeExpanded = true }) { Text(stringResource(modes[settings.theme.ordinal])) }
                    DropdownMenu(themeExpanded, onDismissRequest = { themeExpanded = false }) {
                        for (mode in ThemeMode.entries) DropdownMenuItem(text = { Text(stringResource(modes[mode.ordinal])) }, onClick = {
                            themeExpanded = false; save(settings.copy(theme = mode))
                        })
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val label = stringResource(R.string.dynamic_colors)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(settings.dynamic, { save(settings.copy(dynamic = it)) }, modifier = Modifier.semantics { contentDescription = label })
                }
            }
            AccentPicker(settings.accent, settings.dynamic && Build.VERSION.SDK_INT >= 31) { color ->
                save(settings.copy(accent = color, dynamic = false))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.language), Modifier.weight(1f))
                var expanded by remember { mutableStateOf(false) }
                val languages = listOf("" to R.string.system, "en" to R.string.english, "es" to R.string.spanish, "de" to R.string.german)
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(stringResource(languages.firstOrNull { it.first == settings.locale }?.second ?: R.string.system)) }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        for ((tag, label) in languages) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            expanded = false; save(settings.copy(locale = tag))
                        })
                    }
                }
            }
        }
        ServerSettingsSection(state, vm)
        SettingsGroup(stringResource(R.string.notifications)) {
            val label = stringResource(R.string.completion_alerts)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f))
                Switch(settings.completionAlerts, { enable ->
                    if (enable && Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        completionPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        save(settings.copy(completionAlerts = enable))
                    }
                }, modifier = Modifier.semantics { contentDescription = label })
            }
            Text(stringResource(R.string.completion_alerts_detail), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup(stringResource(R.string.cnl_service)) {
            val label = stringResource(R.string.cnl_service)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), Modifier.weight(1f))
                Switch(settings.cnl, { enable ->
                    if (enable && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else save(settings.copy(cnl = enable))
                }, modifier = Modifier.semantics { contentDescription = label })
            }
            Text(stringResource(R.string.cnl_explanation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = logout, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.sign_out)) }
    }
}

@Composable private fun AccentPicker(accent: AccentColor, dynamic: Boolean, select: (AccentColor) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        AccentColor.TEAL to R.string.accent_teal,
        AccentColor.BLUE to R.string.accent_blue,
        AccentColor.PURPLE to R.string.accent_purple,
        AccentColor.GREEN to R.string.accent_green,
        AccentColor.ORANGE to R.string.accent_orange,
        AccentColor.PINK to R.string.accent_pink,
        AccentColor.RED to R.string.accent_red,
        AccentColor.AMBER to R.string.accent_amber,
        AccentColor.LIME to R.string.accent_lime,
        AccentColor.CYAN to R.string.accent_cyan,
        AccentColor.INDIGO to R.string.accent_indigo,
        AccentColor.LAVENDER to R.string.accent_lavender,
        AccentColor.MAGENTA to R.string.accent_magenta,
        AccentColor.SLATE to R.string.accent_slate
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.accent_color), Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(stringResource(if (dynamic) R.string.accent_wallpaper else labels.getValue(accent)))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (color in AccentColor.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(labels.getValue(color))) },
                        leadingIcon = { Box(Modifier.size(20.dp).background(color.color(dark = false), CircleShape)) },
                        onClick = { expanded = false; select(color) }
                    )
                }
            }
        }
    }
}
