package com.launchpoint.stereosine.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.stereosine.data.settings.*
import com.launchpoint.stereosine.ui.components.DestructiveConfirmDialog

private data class HeadphoneNameRequest(val profile: OutputProfile, val preset: HeadphoneEqPreset?, val settings: AudioEnhancementSettings, val capabilities: EqualizerCapabilities?)
private data class HeadphoneDeleteRequest(val profile: OutputProfile, val preset: HeadphoneEqPreset)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHeadphonesScreen(
    onNavigateBack: () -> Unit,
    onOutputProfilesClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profiles by viewModel.outputProfiles.collectAsStateWithLifecycle()
    val library by viewModel.headphonePresets.collectAsStateWithLifecycle()
    val caps by viewModel.eqCapabilities.collectAsStateWithLifecycle()
    val application by viewModel.eqApplicationState.collectAsStateWithLifecycle()
    val message by viewModel.headphoneMessage.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<HeadphoneNameRequest?>(null) }
    var deleting by remember { mutableStateOf<HeadphoneDeleteRequest?>(null) }
    val currentCaps = caps
    val headphoneProfile = profiles.active.supportsHeadphonePresets() && !profiles.usingGlobalFallback
    val canSave = headphoneProfile && library.writable && headphoneSaveCompatible(profiles.effective, caps, application)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Headphones") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SettingsMessageRow("Selected profile: ${profiles.active.displayName}. Manual presets save requested EQ curves, not measured headphone performance. No devices are detected or switched.") }
            item { SettingsMessageRow("Presets are local to this device and are not included in backups. Applying copies a curve; later profile edits or preset deletion do not change the other copy.") }
            if (profiles.usingGlobalFallback) {
                item { SettingsMessageRow("The selected profile data is unavailable. Choose a valid Wired or Bluetooth profile in Output Profiles; stored data is unchanged.") }
            } else if (!headphoneProfile) {
                item { SettingsMessageRow("Explicitly select Wired or Bluetooth in Output Profiles before saving or applying a headphone preset.") }
            } else if (!canSave) {
                item { SettingsMessageRow("Saving requires a compatible Custom curve and confirmed device bands. Open Equalizer to edit or recover the curve; legacy bands need matching API readback or a new edit to confirm their frequencies.") }
            }
            if (caps == null && headphoneProfile) {
                item { SettingsMessageRow("Device EQ capabilities are unavailable. Applying is disabled. Open Equalizer to obtain current capabilities; EQ is never enabled automatically.") }
            }
            item { ClickableSettingsRow("Output Profiles", "Choose a profile explicitly", onClick = onOutputProfilesClick) }
            item { ClickableSettingsRow("Edit Equalizer", "Use the existing editor and custom-band recovery", onClick = onEqualizerClick) }
            item { SettingsMessageRow(equalizerApplicationSummary(application.forRequest(profiles.effective), profiles.effective.eqEnabled)) }
            item {
                ClickableSettingsRow("Save current custom EQ as a named preset", "A snapshot leaves your requested sound unchanged", enabled = canSave,
                    modifier = Modifier.testTag("headphone-save"), onClick = {
                        if (currentCaps != null) naming = HeadphoneNameRequest(profiles.active, null, profiles.effective, currentCaps)
                    })
            }
            message?.let { text -> item { SettingsMessageRow(text) } }
            if (!library.writable || library.unavailableEntries > 0) {
                item { SettingsMessageRow("Some saved data is unavailable or read-only. Valid visible curves are retained; unreadable data has not been replaced.") }
            }
            item { SectionHeader("Saved headphone presets") }
            if (library.presets.isEmpty()) item { SettingsMessageRow("No saved headphone presets are available.") }
            items(library.presets, key = { it.id }) { preset ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("headphone-preset-${preset.id}")) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Text("${preset.levels.size} bands · saved Custom curve", style = MaterialTheme.typography.bodySmall)
                    val compatible = headphoneProfile && headphoneCurveCompatible(preset.levels, preset.frequenciesHz, caps)
                    if (headphoneProfile && !compatible) Text("Cannot apply: current device bands or range are unavailable or incompatible.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = compatible, modifier = Modifier.testTag("headphone-apply-${preset.id}"), onClick = {
                            if (currentCaps != null) viewModel.applyHeadphonePreset(profiles.active, preset, currentCaps)
                        }) { Text("Apply") }
                        TextButton(enabled = library.writable, modifier = Modifier.testTag("headphone-rename-${preset.id}"), onClick = {
                            naming = HeadphoneNameRequest(profiles.active, preset, profiles.effective, caps)
                        }) { Text("Rename") }
                        TextButton(enabled = library.writable, modifier = Modifier.testTag("headphone-delete-${preset.id}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            onClick = { deleting = HeadphoneDeleteRequest(profiles.active, preset) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    naming?.let { target ->
        var name by remember(target) { mutableStateOf(target.preset?.name.orEmpty()) }
        var submitted by remember(target) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text(if (target.preset == null) "Save headphone preset" else "Rename headphone preset") },
            text = {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= HeadphonePresetCodec.MAX_NAME) name = it },
                    label = { Text("Name") }, singleLine = true, supportingText = { Text("${name.length}/${HeadphonePresetCodec.MAX_NAME}. Duplicate names are allowed.") })
            },
            confirmButton = {
                TextButton(enabled = !submitted && HeadphonePresetCodec.name(name) != null, onClick = {
                    if (!submitted) {
                        submitted = true
                        if (target.preset == null && target.capabilities != null) viewModel.saveHeadphonePreset(target.profile, target.settings, target.capabilities, name)
                        else target.preset?.let { viewModel.renameHeadphonePreset(target.profile, it, name) }
                        naming = null
                    }
                }) { Text("Save") }
            }, dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } })
    }
    deleting?.let { target ->
        DestructiveConfirmDialog(title = "Delete headphone preset?", message = "Delete \"${target.preset.name}\" from your saved preset library? This cannot be undone. Profiles keep their copied curves.",
            confirmLabel = "Delete preset", onConfirm = { viewModel.deleteHeadphonePreset(target.profile, target.preset); deleting = null },
            onDismiss = { deleting = null })
    }
}

internal fun headphonePresetResultMessage(result: HeadphonePresetResult, profile: OutputProfile): String = when (result) {
    HeadphonePresetResult.SAVED -> "Preset saved. Requested sound settings are unchanged."
    HeadphonePresetResult.COPIED_TO_PROFILE -> "Preset copied to ${profile.displayName}'s requested Custom settings. Device API application is reported separately."
    HeadphonePresetResult.RENAMED -> "Preset renamed. Its ID and curve are unchanged."
    HeadphonePresetResult.DELETED -> "Preset deleted. Profiles keep their requested curves."
    HeadphonePresetResult.UNCHANGED -> "The requested values are already the same."
    HeadphonePresetResult.INVALID_NAME -> "Enter a nonblank name of up to 40 characters without control characters."
    HeadphonePresetResult.LIMIT_REACHED -> "The local preset library reached its count or size limit. Delete a preset before adding another."
    HeadphonePresetResult.PROFILE_CHANGED -> "Profile changed. No changes were made."
    HeadphonePresetResult.PRESET_CHANGED -> "Preset changed or was removed. Reopen the action and try again."
    HeadphonePresetResult.CONFIGURATION_CHANGED -> "The requested curve changed. Reopen Save to capture the current curve."
    HeadphonePresetResult.CAPABILITIES_UNAVAILABLE -> "Current EQ capabilities are unavailable. Open Equalizer; no settings were changed."
    HeadphonePresetResult.CAPABILITIES_CHANGED -> "Device capabilities changed. Reopen the action with current settings."
    HeadphonePresetResult.INCOMPATIBLE -> "The curve does not match current device bands or range. Saved preset and profile are unchanged."
    HeadphonePresetResult.WRONG_PROFILE -> "Select Wired or Bluetooth explicitly in Output Profiles first."
    HeadphonePresetResult.NOT_CUSTOM -> "Choose a compatible Custom curve in Equalizer first."
    HeadphonePresetResult.STORAGE_UNAVAILABLE -> "Saved preset data is unavailable. Existing data has been retained."
}
