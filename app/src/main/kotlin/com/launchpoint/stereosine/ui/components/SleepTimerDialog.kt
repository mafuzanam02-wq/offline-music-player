package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.launchpoint.stereosine.playback.SleepTimerOption
import com.launchpoint.stereosine.playback.SleepTimerPhase
import com.launchpoint.stereosine.playback.SleepTimerState

private const val CUSTOM_MIN_MINUTES = 1
private const val CUSTOM_MAX_MINUTES = 240

/** At or above this font scale (or in a very narrow dialog) the custom field and its Set action stack instead of sharing a row. */
private const val STACK_FONT_SCALE = 1.3f
private const val STACK_MAX_WIDTH_DP = 220
private const val KEYBOARD_RESIZE_MIN_HEIGHT_DP = 480

@Composable
fun SleepTimerDialog(
    state: SleepTimerState,
    /** [Boolean] is the "Finish current track" modifier; it only matters for the duration options. */
    onOptionSelected: (SleepTimerOption, Boolean) -> Unit,
    onCustomDurationSelected: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // A selection is shown only for a running countdown or the standalone "End of current song" boundary. Once a duration timer
    // has expired and is only waiting for the song to finish, no duration is selected (the countdown is over).
    val showSelection = state.phase == SleepTimerPhase.COUNTDOWN ||
        (state.phase == SleepTimerPhase.FINISHING_CURRENT_TRACK && state.option == SleepTimerOption.END_OF_CURRENT_SONG)
    val customActive = showSelection && state.customDurationMs != null
    var finishCurrentTrack by remember { mutableStateOf(state.phase == SleepTimerPhase.COUNTDOWN && state.finishCurrentTrack) }
    var customMinutesText by remember {
        mutableStateOf(state.customDurationMs?.takeIf { state.phase == SleepTimerPhase.COUNTDOWN }?.let { (it / 60_000L).toString() } ?: "")
    }

    val minutes = customMinutesText.toIntOrNull()
    val customError: String? = when {
        customMinutesText.isEmpty() -> null
        minutes == null -> "Enter a value from $CUSTOM_MIN_MINUTES to $CUSTOM_MAX_MINUTES minutes"
        minutes < CUSTOM_MIN_MINUTES || minutes > CUSTOM_MAX_MINUTES ->
            "Enter a value from $CUSTOM_MIN_MINUTES to $CUSTOM_MAX_MINUTES minutes"
        else -> null
    }
    val canSet = minutes != null && customError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            DialogResizesForKeyboard()
            // The dialog bounds this slot to the window height; scrolling keeps every option, the explanation, the custom field and
            // its validation message reachable at large font scales and on short windows. Cancel stays outside it, always visible.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (state.phase == SleepTimerPhase.FINISHING_CURRENT_TRACK) {
                    Text(
                        text = "Finishing current track. Playback will stop when this song ends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                SleepTimerOption.entries.forEach { option ->
                    val selected = showSelection && option == state.option && !customActive
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onOptionSelected(option, finishCurrentTrack) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row is the single selectable target (one activation, one TalkBack node); the radio is only its indicator.
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = finishCurrentTrack,
                            role = Role.Switch,
                            onValueChange = { finishCurrentTrack = it },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Finish current track",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "After the timer ends, stop when the current song finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Switch(checked = finishCurrentTrack, onCheckedChange = null)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    // Field and Set compete for width; at large font (or a very narrow dialog) the action drops below the field.
                    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE || maxWidth < STACK_MAX_WIDTH_DP.dp
                    val indicator: @Composable () -> Unit = {
                        Icon(
                            imageVector = if (customActive) Icons.Default.RadioButtonChecked
                                          else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (customActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
                        OutlinedTextField(
                            value = customMinutesText,
                            onValueChange = { input ->
                                customMinutesText = input.filter { it.isDigit() }.take(4)
                            },
                            label = { Text("Custom (1–240 min)") },
                            // The keyboard may cover the Set button (the window pans), so its Done key performs the same validated Set.
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (canSet) onCustomDurationSelected(minutes!! * 60_000L, finishCurrentTrack)
                            }),
                            singleLine = true,
                            isError = customError != null,
                            supportingText = {
                                if (customError != null) {
                                    Text(customError, color = MaterialTheme.colorScheme.error)
                                }
                            },
                            modifier = fieldModifier,
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    val setButton: @Composable () -> Unit = {
                        TextButton(
                            onClick = { if (canSet) onCustomDurationSelected(minutes!! * 60_000L, finishCurrentTrack) },
                            enabled = canSet,
                        ) {
                            Text("Set")
                        }
                    }
                    if (stacked) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                indicator()
                                field(Modifier.weight(1f))
                            }
                            setButton()
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            indicator()
                            field(Modifier.weight(1f))
                            setButton()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A Compose dialog is its own window, and the platform default for it is to PAN when the keyboard opens: the window keeps its
 * size and is only shifted, so the keyboard covers the Set and Cancel actions (measured on a real device at 320 dp / 2.0x). Asking
 * the dialog window to RESIZE instead shrinks it to the area above the keyboard, so the scrolling body and its actions stay
 * inside the visible region. Scoped to this dialog's own window only.
 */
@Composable
private fun DialogResizesForKeyboard() {
    // Only when the window is tall enough to still hold the dialog above a keyboard (about 200 dp). On a short window (small
    // landscape) a resize would squeeze the body to nothing, so the platform default (pan, which keeps the field visible) stays.
    val tallEnough = LocalConfiguration.current.screenHeightDp >= KEYBOARD_RESIZE_MIN_HEIGHT_DP
    val view = LocalView.current
    SideEffect {
        if (tallEnough) (view.parent as? DialogWindowProvider)?.window
            ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
