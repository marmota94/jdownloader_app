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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.serialization.json.*
import org.jdownloader.remote.R
import org.jdownloader.remote.domain.*
import org.jdownloader.remote.cnl.CnlForegroundService
import org.jdownloader.remote.presentation.theme.RemoteTheme
import org.jdownloader.remote.presentation.theme.color
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RemoteApp(vm: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
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
    RemoteTheme(settings) {
        Scaffold(topBar = {
            TopAppBar(title = { Column {
                Text(state.selected?.name ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.selected != null) Text(
                    state.downloadStatus?.let { stringResource(R.string.speed_per_second, formatByteSize(it.speed, locale)) } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } }, actions = {
                state.selected?.let { device ->
                    key(device.id) { DeviceMenu(state, vm) }
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
                    state.selected == null -> DevicePicker(state.devices, vm::select, vm::refresh, vm::logout)
                    else -> {
                        val labels = listOf(R.string.downloads, R.string.linkgrabber, R.string.browser, R.string.captchas, R.string.settings)
                        ScrollableTabRow(selectedTabIndex = state.tab, edgePadding = 0.dp) {
                            labels.forEachIndexed { index, label -> Tab(selected = state.tab == index, onClick = { vm.tab(index) }, text = { Text(stringResource(label)) }) }
                        }
                        Box(Modifier.weight(1f)) {
                            when (state.tab) {
                                0 -> DownloadsScreen(state, vm)
                                1 -> LinkGrabberScreen(state, vm)
                                2 -> CnlBrowserScreen(settings.cnl, vm.cnlServer)
                                3 -> CaptchaScreen(state, vm)
                                4 -> SettingsScreen(settings, vm::saveSettings) {
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
@Composable private fun DeviceMenu(state: RemoteState, vm: RemoteViewModel) {
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
@Composable fun DevicePicker(devices: List<Device>, select: (Device) -> Unit, refresh: () -> Unit, logout: () -> Unit) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.select_device), style = MaterialTheme.typography.headlineSmall) }
        if (devices.isEmpty()) item { Text(stringResource(R.string.no_devices)) }
        items(devices, key = { it.id }) { device -> Card(onClick = { select(device) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(device.name); Text(device.type, style = MaterialTheme.typography.bodySmall) } } }
        item { Row { TextButton(onClick = refresh) { Text(stringResource(R.string.refresh)) }; TextButton(onClick = logout) { Text(stringResource(R.string.sign_out)) } } }
    }
}
@Composable fun DownloadsScreen(state: RemoteState, vm: RemoteViewModel) = TransferList(state, vm, false)
@Composable fun LinkGrabberScreen(state: RemoteState, vm: RemoteViewModel) {
    Column {
        var links by rememberSaveable(state.incoming) { mutableStateOf(state.incoming) }
        var password by remember { mutableStateOf("") }
        var name by rememberSaveable { mutableStateOf("") }
        var expanded by rememberSaveable { mutableStateOf(state.incoming.isNotEmpty()) }
        LaunchedEffect(state.incoming) { if (state.incoming.isNotEmpty()) expanded = true }
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.add_links)) }
        if (expanded) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(links, { links = it }, label = { Text(stringResource(R.string.links)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.package_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.package_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.add(links, password, name) }, enabled = links.isNotBlank() && !state.loading) { Text(stringResource(R.string.send)) }
        }
        Box(Modifier.weight(1f)) { TransferList(state, vm, true) }
    }
}
@Composable private fun TransferList(state: RemoteState, vm: RemoteViewModel, collector: Boolean) {
    var pending by remember(state.selected?.id, collector) { mutableStateOf<Pair<Transfer, Boolean>?>(null) }
    var expandedFolders by rememberSaveable(state.selected?.id, collector) { mutableStateOf(longArrayOf()) }
    val ns = if (collector) "/linkgrabberv2" else "/downloadsV2"
    val transfers = (if (collector) state.linkGrabber else state.downloads) ?: return
    val packages = transfers.packages
    val linksByPackage = remember(transfers.links) { transfers.links.groupBy { it.packageId } }
    val packageIds = remember(packages) { packages.mapTo(hashSetOf()) { it.id } }
    // Packages and links arrive in separate calls and can change between responses.
    val ungrouped = remember(transfers.links, packageIds) { transfers.links.filter { it.packageId !in packageIds } }
    fun command(path: String, row: Transfer, folder: Boolean, vararg prefix: JsonElement) {
        val ids = JsonArray(listOf(JsonPrimitive(row.id)))
        val empty = JsonArray(emptyList())
        vm.command(path, *prefix, if (folder) empty else ids, if (folder) ids else empty)
    }
    val start = if (collector) "$ns/moveToDownloadlist" else "$ns/forceDownload"
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (packages.isEmpty() && ungrouped.isEmpty()) {
            item { Text(stringResource(R.string.empty_list), Modifier.padding(16.dp)) }
        }
        for (folder in packages) {
            val files = linksByPackage[folder.id].orEmpty()
            val expanded = folder.id in expandedFolders
            item(key = "folder:${folder.id}") {
                TransferRow(folder, collector, state.loading,
                    onStart = { command(start, folder, true) },
                    onPriority = { command("$ns/setPriority", folder, true, JsonPrimitive("HIGH")) },
                    onRemove = { pending = folder to true },
                    expanded = expanded,
                    onToggle = {
                        expandedFolders = if (expanded) expandedFolders.filterNot { it == folder.id }.toLongArray()
                        else expandedFolders + folder.id
                    })
            }
            if (expanded && files.isEmpty()) {
                item(key = "empty:${folder.id}") {
                    Text(stringResource(R.string.empty_folder), Modifier.padding(start = 28.dp))
                }
            }
            if (expanded) items(files, key = { "file:${it.id}" }) { file ->
                TransferRow(file, collector, state.loading,
                    onStart = { command(start, file, false) },
                    onPriority = { command("$ns/setPriority", file, false, JsonPrimitive("HIGH")) },
                    onRemove = { pending = file to false },
                    modifier = Modifier.padding(start = 24.dp))
            }
        }
        items(ungrouped, key = { "file:${it.id}" }) { file ->
            TransferRow(file, collector, state.loading,
                onStart = { command(start, file, false) },
                onPriority = { command("$ns/setPriority", file, false, JsonPrimitive("HIGH")) },
                onRemove = { pending = file to false })
        }
        if (!collector && (packages.isNotEmpty() || transfers.links.isNotEmpty())) item { TextButton(onClick = { vm.command("/downloadsV2/cleanup", JsonArray(emptyList()), JsonArray(emptyList()), JsonPrimitive("DELETE_FINISHED"), JsonPrimitive("REMOVE_LINKS_ONLY"), JsonPrimitive("ALL")) }) { Text(stringResource(R.string.cleanup)) } }
    }
    pending?.let { (row, folder) -> AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.remove)) }, text = { Text(stringResource(if (folder) R.string.confirm_remove_folder else R.string.confirm_remove, row.name)) },
        confirmButton = { TextButton(onClick = {
            command("$ns/removeLinks", row, folder)
            pending = null
        }) { Text(stringResource(R.string.remove)) } }, dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable private fun TransferRow(
    transfer: Transfer,
    collector: Boolean,
    loading: Boolean,
    onStart: () -> Unit,
    onPriority: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    val folder = onToggle != null
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val folderState = if (folder) stringResource(if (expanded) R.string.folder_expanded else R.string.folder_collapsed) else null
            val header = if (onToggle != null) Modifier
                .semantics { stateDescription = checkNotNull(folderState) }
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)
                else Modifier
            Row(Modifier.weight(1f).then(header).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(if (folder) R.drawable.ic_transfer_folder else R.drawable.ic_transfer_file), null)
                Column(Modifier.weight(1f)) {
                    Text(transfer.name, style = if (folder) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    if (transfer.total > 0) {
                        val locale = LocalConfiguration.current.locales[0]
                        val total = remember(transfer.total, locale) { formatByteSize(transfer.total, locale) }
                        val size = if (collector) total else {
                            LinearProgressIndicator(
                                progress = { (transfer.loaded.toFloat() / transfer.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            )
                            val loaded = remember(transfer.loaded, locale) { formatByteSize(transfer.loaded, locale) }
                            stringResource(R.string.transfer_size, loaded, total)
                        }
                        Text(size, style = MaterialTheme.typography.bodySmall)
                    }
                    if (transfer.status.isNotBlank()) Text(transfer.status, style = MaterialTheme.typography.bodySmall)
                }
                if (folder) Icon(painterResource(if (expanded) R.drawable.ic_folder_collapse else R.drawable.ic_folder_expand), null)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !loading) {
                    Icon(painterResource(R.drawable.ic_more), stringResource(R.string.transfer_actions, transfer.name))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { menuOpen = false; onStart() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.priority_high)) }, onClick = { menuOpen = false; onPriority() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menuOpen = false; onRemove() })
                }
            }
        }
    }
}
@Composable fun SettingsScreen(settings: Settings, save: (Settings) -> Unit, logout: () -> Unit) {
    var enableRequested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enableRequested = true }
    val context = LocalContext.current
    val completionPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save(settings.copy(completionAlerts = true))
    }
    LaunchedEffect(enableRequested) { if (enableRequested) { save(settings.copy(cnl = true)); enableRequested = false } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
        val modes = listOf(R.string.system, R.string.light, R.string.dark, R.string.amoled)
        ThemeMode.entries.forEachIndexed { index, mode -> Row { RadioButton(settings.theme == mode, { save(settings.copy(theme = mode)) }); TextButton(onClick = { save(settings.copy(theme = mode)) }) { Text(stringResource(modes[index])) } } }
        if (Build.VERSION.SDK_INT >= 31) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dynamic_colors), Modifier.weight(1f))
                Switch(settings.dynamic, { save(settings.copy(dynamic = it)) })
            }
        }
        AccentPicker(settings.accent, settings.dynamic && Build.VERSION.SDK_INT >= 31) { color ->
            save(settings.copy(accent = color, dynamic = false))
        }
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
        listOf("" to R.string.system, "en" to R.string.english, "es" to R.string.spanish, "de" to R.string.german).forEach { (tag, label) ->
            Row { RadioButton(settings.locale == tag, { save(settings.copy(locale = tag)) }); TextButton(onClick = { save(settings.copy(locale = tag)) }) { Text(stringResource(label)) } }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.completion_alerts), Modifier.weight(1f))
            Switch(settings.completionAlerts, { enable ->
                if (enable && Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    completionPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    save(settings.copy(completionAlerts = enable))
                }
            })
        }
        Text(stringResource(R.string.completion_alerts_detail), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row { Text(stringResource(R.string.cnl_service), Modifier.weight(1f)); Switch(settings.cnl, { enable ->
            if (enable && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else save(settings.copy(cnl = enable))
        }) }
        Text(stringResource(R.string.cnl_explanation), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = logout) { Text(stringResource(R.string.sign_out)) }
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
        AccentColor.PINK to R.string.accent_pink
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
