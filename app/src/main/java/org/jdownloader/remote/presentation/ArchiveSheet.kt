package org.jdownloader.remote.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jdownloader.remote.R
import org.jdownloader.remote.domain.Transfer

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ArchiveSheet(row: Transfer, folder: Boolean, state: RemoteState, vm: RemoteViewModel, dismiss: () -> Unit) {
    LaunchedEffect(row.id, folder) { vm.loadArchiveInfo(row, folder) }
    DisposableEffect(row.id, folder) { onDispose { vm.clearArchiveInfo() } }
    var selectedId by remember(row.id, folder) { mutableStateOf<String?>(null) }
    var archivesOpen by remember { mutableStateOf(false) }
    val archives = state.archives.takeIf { state.archiveTarget == (row.id to folder) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.archive_tools), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { vm.loadArchiveInfo(row, folder) }, enabled = !state.loading) { Text(stringResource(R.string.refresh)) }
            }
            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.extraction_server_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                archives == null -> if (!state.loading) Text(stringResource(R.string.request_error), color = MaterialTheme.colorScheme.error)
                archives.isEmpty() -> Text(stringResource(R.string.archive_not_found))
                else -> {
                    val archive = archives.firstOrNull { it.id == selectedId } ?: archives.first()
                    if (archives.size > 1) Box {
                        OutlinedButton(onClick = { archivesOpen = true }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                            Text(archive.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(archivesOpen, onDismissRequest = { archivesOpen = false }) {
                            for (item in archives) DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedId = item.id; archivesOpen = false })
                        }
                    } else Text(archive.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (archive.status) {
                        "RUNNING" -> R.string.extracting
                        "QUEUED" -> R.string.queued
                        else -> if (archive.ready) R.string.archive_ready else R.string.archive_incomplete
                    }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.archive_parts, archive.parts.values.count { it == "COMPLETE" }, archive.parts.size),
                        style = MaterialTheme.typography.bodySmall)
                    if (!archive.ready) Text(stringResource(R.string.archive_incomplete_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pendingParts = remember(archive.parts) { archive.parts.filterValues { it != "COMPLETE" }.toSortedMap() }
                    if (pendingParts.isNotEmpty()) {
                        var showParts by remember(archive.id) { mutableStateOf(false) }
                        TextButton(onClick = { showParts = !showParts }) {
                            Text(if (showParts) stringResource(R.string.archive_hide_pending_parts)
                                else stringResource(R.string.archive_show_pending_parts, pendingParts.size))
                        }
                        if (showParts) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((name, partStatus) in pendingParts) {
                                Column {
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(when (partStatus) {
                                        "MISSING" -> R.string.archive_part_missing
                                        "INCOMPLETE" -> R.string.archive_part_incomplete
                                        else -> R.string.archive_part_unavailable
                                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    var autoExtract by remember(archive.id, archive.settings, state.archiveRevision) { mutableStateOf(archive.autoExtract) }
                    var path by remember(archive.id, archive.settings, state.archiveRevision) { mutableStateOf(archive.extractPath) }
                    var password by remember(archive.id, archive.settings, state.archiveRevision) { mutableStateOf("") }
                    var passwordVisible by remember(archive.id, archive.settings, state.archiveRevision) { mutableStateOf(false) }
                    var autoOpen by remember(archive.id) { mutableStateOf(false) }
                    val pathValid = path.isNotBlank() || archive.extractPath.isBlank()
                    val changed = autoExtract != archive.autoExtract || path != archive.extractPath || password.isNotBlank()
                    OutlinedButton(onClick = { vm.startExtraction(row, folder) },
                        enabled = !state.loading && !changed && archives.any { it.ready && it.controllerId < 0 }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (archives.size > 1) R.string.extract_or_retry_all else R.string.extract_or_retry))
                    }
                    if (archive.controllerId >= 0) OutlinedButton(onClick = { vm.cancelExtraction(row, folder, archive.controllerId) },
                        enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.cancel_extraction)) }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_extract), Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { autoOpen = true }, enabled = !state.loading) {
                                Text(stringResource(when (autoExtract) { true -> R.string.enabled; false -> R.string.disabled; null -> R.string.server_default }))
                            }
                            DropdownMenu(autoOpen, onDismissRequest = { autoOpen = false }) {
                                val choices: List<Boolean?> = if (archive.autoExtract == null) listOf(null, true, false) else listOf(true, false)
                                for (choice in choices) DropdownMenuItem(text = {
                                    Text(stringResource(when (choice) { true -> R.string.enabled; false -> R.string.disabled; null -> R.string.server_default }))
                                }, onClick = { autoExtract = choice; autoOpen = false })
                            }
                        }
                    }
                    OutlinedTextField(path, { path = it }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.extract_folder)) }, placeholder = { Text(stringResource(R.string.server_default)) },
                        isError = !pathValid, supportingText = { Text(stringResource(R.string.extract_folder_hint)) })
                    OutlinedTextField(password, { password = it }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.archive_password_optional)) },
                        supportingText = { Text(stringResource(R.string.archive_password_hint)) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(painterResource(if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
                        } })
                    Button(onClick = { vm.saveArchiveOptions(row, folder, archive, autoExtract, path, password) },
                        enabled = !state.loading && changed && pathValid, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
                    if (changed) Text(stringResource(R.string.save_before_extract), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
