package com.launchpoint.stereosine.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.stereosine.data.settings.OutputProfile
import com.launchpoint.stereosine.data.settings.forRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsOutputProfilesScreen(
    onNavigateBack: () -> Unit,
    onEqualizerClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.outputProfiles.collectAsStateWithLifecycle()
    val application by viewModel.eqApplicationState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Output Profiles") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SettingsMessageRow("Manual selection changes StereoSine's saved sound settings, not the phone's output device or system volume.") }
            item { SettingsMessageRow("The first category selection copies your current Global settings to all three categories. Each category then keeps its own edits.") }
            item { SectionHeader("Saved profile") }
            items(OutputProfile.entries.size) { index ->
                val profile = OutputProfile.entries[index]
                ClickableSettingsRow(
                    title = profile.displayName,
                    subtitle = if (state.active == profile) "Selected" else "Select saved settings",
                    onClick = { viewModel.selectOutputProfile(profile) },
                    modifier = Modifier.semantics { selected = state.active == profile },
                )
            }
            if (state.usingGlobalFallback || state.invalidSelection) {
                item { SettingsMessageRow("Saved profile data is unavailable. Global settings are in use; select Global/default to edit. Stored data has not been replaced.") }
            }
            item { SectionHeader("Requested Equalizer") }
            item { SettingsMessageRow("${state.active.displayName}: Equalizer ${if (state.effective.eqEnabled) "on" else "off"}. Selection does not confirm audible application.") }
            item { SettingsMessageRow(equalizerApplicationSummary(application.forRequest(state.effective), state.effective.eqEnabled)) }
            item { ClickableSettingsRow(title = "Edit Equalizer", subtitle = "Use the existing editor for the selected profile", onClick = onEqualizerClick) }
        }
    }
}
