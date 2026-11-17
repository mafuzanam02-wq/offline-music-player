package com.launchpoint.stereosine.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.stereosine.ui.components.DestructiveConfirmDialog
import com.launchpoint.stereosine.data.settings.OutputProfile
import com.launchpoint.stereosine.data.settings.customEqRecoveryAvailable
import com.launchpoint.stereosine.data.settings.customEqMatchesCapabilities
import com.launchpoint.stereosine.data.settings.forRequest
import com.launchpoint.stereosine.data.settings.EqualizerApplicationState
import com.launchpoint.stereosine.data.settings.EqualizerApplicationStatus
import com.launchpoint.stereosine.data.settings.EqualizerApplicationIssue
import com.launchpoint.stereosine.data.settings.EqualizerCapabilities
import com.launchpoint.stereosine.data.settings.EqualizerPlatformPreset
import com.launchpoint.stereosine.data.settings.EqPresetType
import com.launchpoint.stereosine.data.settings.StereoSinePresetCatalog
import kotlin.math.roundToInt

private data class CustomBandResetRequest(val profile: OutputProfile, val capabilities: EqualizerCapabilities)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsEqualizerScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profileState by viewModel.outputProfiles.collectAsStateWithLifecycle()
    val requested = profileState.effective
    val selectedProfile = profileState.active
    val eqEnabled = requested.eqEnabled
    val eqPresetType = requested.eqPresetType
    val eqPlatformPresetIndex = requested.eqPlatformPresetIndex
    val eqCustomBandLevels = requested.eqCustomBandLevels
    val eqStereoSinePresetId = requested.eqStereoSinePresetId
    val eqCapabilities        by viewModel.eqCapabilities.collectAsStateWithLifecycle()
    val applicationState by viewModel.eqApplicationState.collectAsStateWithLifecycle()
    var showPresetDialog by remember { mutableStateOf(false) }
    var resetRequest by remember { mutableStateOf<CustomBandResetRequest?>(null) }

    val caps = eqCapabilities
    // A withdrawn/replaced live capability snapshot cannot authorize a later confirmation.
    LaunchedEffect(caps, resetRequest) {
        if (resetRequest != null && caps != resetRequest?.capabilities) resetRequest = null
    }

    val eqPresetLabel = when (eqPresetType) {
        EqPresetType.FLAT           -> "Flat"
        EqPresetType.WAVDROP_PRESET -> StereoSinePresetCatalog.findById(eqStereoSinePresetId)?.displayName ?: "StereoSine"
        EqPresetType.PLATFORM       -> caps?.platformPresets?.firstOrNull { it.index.toInt() == eqPlatformPresetIndex }?.name ?: "System preset ${eqPlatformPresetIndex ?: "unavailable"}"
        EqPresetType.CUSTOM         -> "Custom"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item {
                ToggleSettingsRow(
                    title           = "Equalizer",
                    subtitle        = "Saved settings: ${selectedProfile.displayName}",
                    checked         = eqEnabled,
                    onCheckedChange = { viewModel.setEqualizerEnabled(it, selectedProfile) },
                    enabled         = !profileState.usingGlobalFallback,
                )
            }
            if (profileState.usingGlobalFallback) {
                item { SettingsMessageRow(message = "This saved profile is unavailable. Global settings are in use; select Global/default to edit. Stored profile data is unchanged.") }
            }
            if (eqEnabled) {
                item { SettingsMessageRow(message = equalizerApplicationSummary(applicationState.forRequest(requested), requestedEnabled = eqEnabled)) }
                item {
                    ClickableSettingsRow(
                        title    = "Preset",
                        subtitle = eqPresetLabel,
                        enabled  = !profileState.usingGlobalFallback,
                        onClick  = { if (!profileState.usingGlobalFallback) showPresetDialog = true },
                    )
                }
                if (caps == null) {
                    item { SettingsMessageRow(message = "Device EQ capabilities are unavailable. You can save a preset; band editing and system presets require device capabilities.") }
                } else {
                    if (profileState.usingGlobalFallback || caps.centerFrequenciesHz.any { it <= 0 } ||
                        (eqPresetType == EqPresetType.CUSTOM && !customEqMatchesCapabilities(requested, caps))) {
                        item { SettingsMessageRow(message = "Saved bands do not match current device capabilities. They remain saved; band editing is unavailable.") }
                        if (!profileState.usingGlobalFallback && customEqRecoveryAvailable(requested, caps)) {
                            item {
                                TextButton(
                                    onClick = { resetRequest = CustomBandResetRequest(selectedProfile, caps) },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) { Text("Reset Custom Bands for This Device") }
                            }
                        }
                    } else if (eqPresetType == EqPresetType.CUSTOM || eqPresetType == EqPresetType.WAVDROP_PRESET) {
                        item {
                            val initialLevels = if (eqPresetType == EqPresetType.WAVDROP_PRESET) {
                                StereoSinePresetCatalog.findById(eqStereoSinePresetId)
                                    ?.bandLevels?.takeIf { it.size == caps.bandCount }
                                    ?: List(caps.bandCount) { 0 }
                            } else {
                                eqCustomBandLevels
                            }
                            EqBandSection(
                                bandLevels         = initialLevels,
                                capabilities       = caps,
                                onBandLevelsChange = { viewModel.setEqCustomBandLevels(it, selectedProfile, caps.centerFrequenciesHz) },
                            )
                        }
                    } else {
                        item {
                            val helperText = if (eqPresetType == EqPresetType.PLATFORM) {
                                "System presets are managed by your device. Choose Custom or a StereoSine preset to fine-tune bands."
                            } else {
                                "EQ is flat. Choose Custom or a StereoSine preset to shape your sound."
                            }
                            SettingsMessageRow(message = helperText)
                        }
                    }
                }
            }
        }
    }

    resetRequest?.takeIf { it.capabilities == caps }?.let { target ->
        DestructiveConfirmDialog(
            title = "Reset custom bands?",
            message = "Replace ${target.profile.displayName}'s saved custom curve with ${target.capabilities.bandCount} flat bands for this device? This cannot be undone. Other profiles stay unchanged.",
            confirmLabel = "Reset Custom Bands",
            onConfirm = {
                viewModel.resetCustomBandsForDevice(target.profile, target.capabilities)
                resetRequest = null
            },
            onDismiss = { resetRequest = null },
        )
    }

    if (showPresetDialog) {
        EqPresetDialog(
            presetType              = eqPresetType,
            stereoSinePresetId         = eqStereoSinePresetId,
            platformPresetIndex     = eqPlatformPresetIndex,
            capabilities            = caps,
            onSelectFlat            = { viewModel.setEqFlat(selectedProfile); showPresetDialog = false },
            onSelectCustom          = { caps?.let { viewModel.setEqCustomPreset(it.bandCount, selectedProfile, it.centerFrequenciesHz) }; showPresetDialog = false },
            onSelectStereoSinePreset   = { id -> viewModel.setEqStereoSinePreset(id, selectedProfile); showPresetDialog = false },
            onSelectPlatformPreset  = { index -> viewModel.setEqPlatformPreset(index, selectedProfile); showPresetDialog = false },
            onDismiss               = { showPresetDialog = false },
        )
    }
}

// ── EQ capabilities — fallback used before AudioEnhancementController reports runtime values ──

internal val EQ_FALLBACK_CAPABILITIES = EqualizerCapabilities(
    bandCount           = 5,
    minLevelMb          = -1500,
    maxLevelMb          = 1500,
    centerFrequenciesHz = listOf(60, 230, 910, 3600, 14000),
    platformPresets     = listOf(
        EqualizerPlatformPreset(0, "Normal"),
        EqualizerPlatformPreset(1, "Classical"),
        EqualizerPlatformPreset(2, "Dance"),
        EqualizerPlatformPreset(3, "Flat"),
        EqualizerPlatformPreset(4, "Folk"),
        EqualizerPlatformPreset(5, "Heavy Metal"),
        EqualizerPlatformPreset(6, "Hip Hop"),
        EqualizerPlatformPreset(7, "Jazz"),
        EqualizerPlatformPreset(8, "Pop"),
        EqualizerPlatformPreset(9, "Rock"),
    ),
)

internal fun formatCenterFreqHz(hz: Int): String {
    if (hz >= 1_000) {
        val khz = hz / 1_000f
        return if (khz % 1f == 0f) "${khz.toInt()} kHz" else "%.1f kHz".format(java.util.Locale.US, khz)
    }
    return "$hz Hz"
}

// Maps the 5 canonical S21 center frequencies to user-friendly band names.
// Falls back to the frequency string for any unknown Hz value.
internal fun eqBandLabel(freqHz: Int): String = when (freqHz) {
    60    -> "Bass"
    230   -> "Low Mid"
    910   -> "Mid"
    3600  -> "Presence"
    14000 -> "Air"
    else  -> formatCenterFreqHz(freqHz)
}

// Formats a millibel gain value as a human-readable dB string.
// Zero displays without a sign; positive values get a leading "+".
internal fun formatGainDb(levelMb: Int): String = when {
    levelMb > 0 -> "+%.1f dB".format(java.util.Locale.US, levelMb / 100f)
    levelMb < 0 -> "%.1f dB".format(java.util.Locale.US, levelMb / 100f)
    else        -> "0.0 dB"
}

private fun List<Int>.toEqLevels(bandCount: Int): List<Int> =
    if (size == bandCount) this else List(bandCount) { 0 }

// ── EQ band composables ──────────────────────────────────────────────────────

@Composable
private fun EqBandSection(
    bandLevels: List<Int>,
    capabilities: EqualizerCapabilities,
    onBandLevelsChange: (List<Int>) -> Unit,
) {
    var local by remember(bandLevels) { mutableStateOf(bandLevels.toEqLevels(capabilities.bandCount)) }

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        repeat(capabilities.bandCount) { index ->
            val freqHz = capabilities.centerFrequenciesHz.getOrElse(index) { 0 }
            EqBandRow(
                bandName           = eqBandLabel(freqHz),
                freqLabel          = formatCenterFreqHz(freqHz),
                levelMb            = local.getOrElse(index) { 0 },
                minLevelMb         = capabilities.minLevelMb,
                maxLevelMb         = capabilities.maxLevelMb,
                onLevelChange      = { new ->
                    local = local.toMutableList().also { it[index] = new }
                },
                onLevelChangeFinished = { onBandLevelsChange(local) },
            )
        }
    }
}

@Composable
private fun EqBandRow(
    bandName: String,
    freqLabel: String,
    levelMb: Int,
    minLevelMb: Int,
    maxLevelMb: Int,
    onLevelChange: (Int) -> Unit,
    onLevelChangeFinished: () -> Unit,
) {
    val stepSize = 100
    val steps = ((maxLevelMb - minLevelMb) / stepSize) - 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text  = bandName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = freqLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
                )
            }
            Text(
                text  = formatGainDb(levelMb),
                style = MaterialTheme.typography.bodyMedium,
                color = if (levelMb == 0) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                        else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value                 = levelMb.toFloat(),
            onValueChange         = { raw ->
                val steps100 = (raw / stepSize).roundToInt()
                onLevelChange(steps100.coerceIn(minLevelMb / stepSize, maxLevelMb / stepSize) * stepSize)
            },
            onValueChangeFinished = onLevelChangeFinished,
            valueRange            = minLevelMb.toFloat()..maxLevelMb.toFloat(),
            steps                 = steps,
        )
    }
}

@Composable
private fun EqPresetDialog(
    presetType: EqPresetType,
    stereoSinePresetId: String?,
    platformPresetIndex: Int?,
    capabilities: EqualizerCapabilities?,
    onSelectFlat: () -> Unit,
    onSelectCustom: () -> Unit,
    onSelectStereoSinePreset: (String) -> Unit,
    onSelectPlatformPreset: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preset") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 1. Reset EQ
                PresetDialogRow(
                    label    = "Reset EQ",
                    selected = presetType == EqPresetType.FLAT,
                    onClick  = onSelectFlat,
                )
                // Custom initialization depends on the runtime band count; never seed invented bands.
                if (capabilities != null) {
                    PresetDialogRow(
                        label    = "Custom",
                        selected = presetType == EqPresetType.CUSTOM,
                        onClick  = onSelectCustom,
                    )
                }
                // Saving an owned preset does not assert hardware compatibility or application.
                if (capabilities == null || capabilities.bandCount == 5) {
                    Text(
                        text     = "StereoSine",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    StereoSinePresetCatalog.all.forEach { preset ->
                        PresetDialogRow(
                            label       = preset.displayName,
                            description = preset.description,
                            selected    = presetType == EqPresetType.WAVDROP_PRESET && stereoSinePresetId == preset.id,
                            onClick     = { onSelectStereoSinePreset(preset.id) },
                        )
                    }
                }
                // 4. System Presets
                if (!capabilities?.platformPresets.isNullOrEmpty()) {
                    Text(
                        text     = "System",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    capabilities?.platformPresets.orEmpty().forEach { preset ->
                        PresetDialogRow(
                            label    = preset.name,
                            selected = presetType == EqPresetType.PLATFORM && platformPresetIndex == preset.index.toInt(),
                            onClick  = { onSelectPlatformPreset(preset.index.toInt()) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PresetDialogRow(
    label: String,
    selected: Boolean,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
            }
        }
    }
}

/** API confirmation is deliberately described separately from measured audio output. */
internal fun equalizerApplicationSummary(
    state: EqualizerApplicationState,
    requestedEnabled: Boolean = state.requested?.eqEnabled == true,
): String {
    // An enabled saved request can precede the playback service's first observation.
    if (requestedEnabled && state.status == EqualizerApplicationStatus.DISABLED) return "Equalizer application pending."
    val base = when (state.status) {
        EqualizerApplicationStatus.DISABLED -> "Equalizer is disabled."
        EqualizerApplicationStatus.PENDING -> "Equalizer application pending."
        EqualizerApplicationStatus.APPLIED -> "Device API reports EQ enabled and settings read back. Audible output is not measured."
        EqualizerApplicationStatus.FAILED -> "Equalizer application could not be confirmed. Playback can continue."
        EqualizerApplicationStatus.UNAVAILABLE -> "Equalizer is unavailable. Playback can continue."
    }
    val details = state.issues.map { issue ->
        when (issue) {
            EqualizerApplicationIssue.FLAT_FALLBACK -> "Flat fallback used; requested preset is unchanged."
            EqualizerApplicationIssue.CLAMPED -> "Band levels limited to the device range; saved levels are unchanged."
            EqualizerApplicationIssue.CONTROL_UNAVAILABLE -> "Effect control is unavailable."
            EqualizerApplicationIssue.EXTERNALLY_DISABLED -> "The device effect was disabled externally."
            else -> ""
        }
    }.filter { it.isNotEmpty() }
    return (listOf(base) + details).joinToString(" ")
}
