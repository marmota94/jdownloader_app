package org.jdownloader.remote.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.jdownloader.remote.R
import org.jdownloader.remote.domain.AdvancedSetting

@Composable internal fun ServerSettingsSection(state: RemoteState, vm: RemoteViewModel) {
    SettingsGroup(stringResource(R.string.server_settings)) {
        val limits = state.serverLimits
        if (limits != null) {
            var speed by rememberSaveable(state.selected?.id, limits) { mutableStateOf(speedLimitText(limits.speedLimit)) }
            var downloads by rememberSaveable(state.selected?.id, limits) { mutableStateOf(limits.maxDownloads.toString()) }
            var chunks by rememberSaveable(state.selected?.id, limits) { mutableStateOf(limits.maxChunks.toString()) }
            var perHost by rememberSaveable(state.selected?.id, limits) { mutableStateOf(limits.maxPerHost.toString()) }
            val parsed = parseServerLimits(speed, downloads, chunks, perHost)
            OutlinedTextField(speed, { speed = it }, label = { Text(stringResource(R.string.server_speed)) },
                supportingText = { Text(stringResource(R.string.server_speed_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(downloads, { downloads = it }, label = { Text(stringResource(R.string.parallel_short)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.setting_range, 1, 20)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(chunks, { chunks = it }, label = { Text(stringResource(R.string.chunks_short)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.setting_range, 1, 20)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(perHost, { perHost = it }, label = { Text(stringResource(R.string.per_host_short)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.setting_range, 1, 40)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            if (parsed == null) Text(stringResource(R.string.server_limits_invalid), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { parsed?.let(vm::saveServerLimits) }, enabled = !state.loading && parsed != null && parsed != limits,
                    modifier = Modifier.weight(1f)) { Text(stringResource(R.string.apply_to_server)) }
                TextButton(onClick = vm::loadServerLimits, enabled = !state.loading) { Text(stringResource(R.string.refresh)) }
            }
        } else TextButton(onClick = vm::loadServerLimits, enabled = !state.loading) { Text(stringResource(R.string.reload_server_settings)) }
    }
    SettingsGroup(stringResource(R.string.server_updates)) {
        OutlinedButton(onClick = vm::checkServerUpdates, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.check_server_updates)) }
        state.serverUpdateAvailable?.let { available -> Text(stringResource(if (available) R.string.server_update_available else R.string.server_update_check_requested),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    AdvancedSettingsSection(state, vm)
}

@Composable private fun AdvancedSettingsSection(state: RemoteState, vm: RemoteViewModel) {
    var expanded by rememberSaveable(state.selected?.id) { mutableStateOf(false) }
    var search by rememberSaveable(state.selected?.id) { mutableStateOf("") }
    var editing by remember(state.selected?.id) { mutableStateOf<AdvancedSetting?>(null) }
    SettingsGroup(stringResource(R.string.advanced_settings)) {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.hide_settings else R.string.browse_settings)) }
        if (expanded) {
            OutlinedTextField(search, { search = it.take(100) }, label = { Text(stringResource(R.string.search_settings)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { vm.searchAdvancedSettings(search) }, enabled = !state.loading && search.trim().length >= 2) { Text(stringResource(R.string.search)) }
            Text(stringResource(R.string.advanced_count, state.advancedSettings.size), style = MaterialTheme.typography.bodySmall)
            if (state.advancedSettings.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.advancedSettings, key = { "${it.interfaceName}:${it.storage}:${it.key}" }) { setting ->
                    OutlinedCard(onClick = { editing = setting }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(setting.key, style = MaterialTheme.typography.titleSmall)
                            Text(if (setting.secret) "••••••" else advancedValueText(setting), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (setting.description.isNotBlank()) Text(setting.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    editing?.let { setting ->
        var input by remember(setting) { mutableStateOf(advancedValueText(setting)) }
        var optionsOpen by remember(setting) { mutableStateOf(false) }
        val parsed = parseAdvancedValue(setting, input)
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(setting.key) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (setting.description.isNotBlank()) Text(setting.description, maxLines = 6, overflow = TextOverflow.Ellipsis)
                when {
                    setting.options.isNotEmpty() -> Box {
                        OutlinedButton(onClick = { optionsOpen = true }) { Text(input) }
                        DropdownMenu(optionsOpen, onDismissRequest = { optionsOpen = false }) {
                            for (option in setting.options) DropdownMenuItem(text = { Text(option) }, onClick = { input = option; optionsOpen = false })
                        }
                    }
                    (setting.value as? JsonPrimitive)?.booleanOrNull != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.enabled), Modifier.weight(1f))
                        Switch(input == "true", { input = it.toString() })
                    }
                    else -> OutlinedTextField(input, { input = it }, label = { Text(stringResource(R.string.setting_value)) },
                        isError = parsed == null, maxLines = 6, visualTransformation = if (setting.secret) PasswordVisualTransformation() else VisualTransformation.None)
                }
                if (setting.value is JsonArray || setting.value is JsonObject) Text(stringResource(R.string.advanced_json_hint), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = parsed != null && parsed != setting.value && !state.loading, onClick = {
            parsed?.let { vm.saveAdvancedSetting(setting, it) }; editing = null
        }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

internal fun advancedValueText(setting: AdvancedSetting): String =
    (setting.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: setting.value.toString()

internal fun parseAdvancedValue(setting: AdvancedSetting, input: String): JsonElement? = runCatching {
    val old = setting.value
    val value = if (old == JsonNull && input == "null") JsonNull
        else if (setting.type == "STRING" || setting.options.isNotEmpty() || (old as? JsonPrimitive)?.isString == true) JsonPrimitive(input)
        else Json.parseToJsonElement(input)
    require(setting.options.isEmpty() || (value as? JsonPrimitive)?.content in setting.options)
    when (old) {
        is JsonArray -> require(value is JsonArray)
        is JsonObject -> require(value is JsonObject)
        is JsonPrimitive -> if (old != JsonNull) {
            require(value is JsonPrimitive && value != JsonNull)
            if (old.booleanOrNull != null) require(value.booleanOrNull != null)
            else if (!old.isString) require(value.doubleOrNull?.isFinite() == true && !value.isString)
        }
    }
    when (setting.type) {
        "INT" -> require((value as? JsonPrimitive)?.intOrNull != null)
        "LONG" -> require((value as? JsonPrimitive)?.longOrNull != null)
        "BOOLEAN" -> require((value as? JsonPrimitive)?.booleanOrNull != null)
    }
    value
}.getOrNull()
