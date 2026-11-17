package com.launchpoint.stereosine.playback

import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.launchpoint.stereosine.BuildConfig
import com.launchpoint.stereosine.data.settings.*
import com.launchpoint.stereosine.data.settings.EqualizerApplicationIssue.*
import com.launchpoint.stereosine.data.settings.EqualizerApplicationStatus.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Service-owned session effect. All mutations/observations run on the service's main scope. */
internal class AudioEnhancementController(
    private val repository: AudioEnhancementsRepository,
    private val scope: CoroutineScope,
    private val createEffect: (Int) -> EqualizerEffect = ::AndroidEqualizerEffect,
) {
    private var equalizer: EqualizerEffect? = null
    private var currentSessionId = 0
    private var isReleased = false
    private var observation: Job? = null
    private var attached = false
    private var cachedSettings = AudioEnhancementSettings()
    private var lastAttempt: AudioEnhancementSettings? = null
    private var capabilities: EqualizerCapabilities? = null

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun attach(player: ExoPlayer, observeSettings: Boolean = true) = attachSession(player.audioSessionId, observeSettings)

    /** Same lifecycle seam used by attach; no playback commands or player ownership. */
    internal fun attachSession(sessionId: Int, observeSettings: Boolean = true) {
        if (isReleased || attached) return
        attached = true
        currentSessionId = sessionId
        if (observeSettings) observation = scope.launch { repository.settings.collect(::onSettingsChanged) }
    }

    /** Production service supplies the same snapshot to crossfade eligibility before this call. */
    internal fun onSettingsChanged(settings: AudioEnhancementSettings) {
        if (isReleased) return
        cachedSettings = settings
        applySettings(settings)
    }

    fun onAudioSessionIdChanged(newSessionId: Int) {
        if (isReleased || newSessionId == currentSessionId) return
        releaseEqualizer()
        currentSessionId = newSessionId
        applySettings(cachedSettings)
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        observation?.cancel()
        releaseEqualizer()
        publish(if (cachedSettings.eqEnabled) UNAVAILABLE else DISABLED, setOf(RELEASED))
    }

    private fun publish(
        status: EqualizerApplicationStatus,
        issues: Set<EqualizerApplicationIssue> = emptySet(),
        levels: List<Int>? = null,
        presetIndex: Int? = null,
    ) {
        val state = EqualizerApplicationState(
            status, currentSessionId.takeIf { it > 0 }, cachedSettings, issues, levels, presetIndex,
        )
        repository.updateApplicationState(state)
        if (BuildConfig.DEBUG) Log.d(TAG, "[application] $state (API observations, not measured output)")
    }

    private fun applySettings(settings: AudioEnhancementSettings) {
        if (isReleased) return
        if (!settings.eqEnabled) {
            releaseEqualizer()
            publish(DISABLED)
            return
        }
        if (currentSessionId <= 0) {
            publish(PENDING, setOf(WAITING_FOR_SESSION))
            return
        }
        // Do not repeatedly write identical requests, including a known failed attempt. A new
        // session, changed request or regained control supplies an explicit retry boundary.
        if (settings == lastAttempt) return
        lastAttempt = settings
        publish(PENDING)
        if (equalizer == null && !tryCreateEqualizer()) return
        val eq = equalizer ?: return
        val caps = capabilities ?: return
        try {
            if (!eq.hasControl()) {
                publish(UNAVAILABLE, setOf(CONTROL_UNAVAILABLE))
                return
            }
            val issues = mutableSetOf<EqualizerApplicationIssue>()
            var expected: List<Int>? = null
            var expectedPreset: Int? = null
            fun write(levels: List<Int>) {
                val clamped = levels.map { it.coerceIn(caps.minLevelMb, caps.maxLevelMb) }
                if (clamped != levels) issues += CLAMPED
                expected = clamped
                clamped.forEachIndexed { i, level ->
                    try { eq.setBandLevel(i.toShort(), level.toShort()) }
                    catch (_: RuntimeException) { issues += WRITE_FAILED }
                }
            }
            fun flatFallback() {
                issues += FLAT_FALLBACK
                write(List(caps.bandCount) { 0 })
            }
            when (settings.eqPresetType) {
                EqPresetType.FLAT -> write(List(caps.bandCount) { 0 })
                EqPresetType.WAVDROP_PRESET -> {
                    val levels = StereoSinePresetCatalog.findById(settings.eqStereoSinePresetId)?.bandLevels
                    if (levels?.size == caps.bandCount) write(levels) else flatFallback()
                }
                EqPresetType.CUSTOM -> {
                    if (customEqMatchesCapabilities(settings, caps)) write(settings.eqCustomBandLevels)
                    else flatFallback()
                }
                EqPresetType.PLATFORM -> {
                    val index = settings.eqPlatformPresetIndex
                    if (index != null && index in 0 until caps.platformPresetCount) {
                        try {
                            eq.usePreset(index.toShort())
                            expectedPreset = index
                        } catch (_: RuntimeException) {
                            issues += WRITE_FAILED
                            flatFallback()
                        }
                    } else flatFallback()
                }
            }
            val result = try { eq.setEnabled(true) } catch (_: RuntimeException) {
                releaseEqualizer() // preserve the existing enable-exception teardown
                publish(FAILED, issues + ENABLE_FAILED)
                return
            }
            if (result != AudioEffect.SUCCESS || !eq.enabled) issues += ENABLE_FAILED
            val levels = try { (0 until caps.bandCount).map { eq.getBandLevel(it.toShort()) } }
            catch (_: RuntimeException) { issues += READBACK_FAILED; null }
            val preset = if (expectedPreset != null) {
                try { eq.currentPreset } catch (_: RuntimeException) { issues += READBACK_FAILED; null }
            } else null
            if (levels != null && expected != null && levels != expected) issues += READBACK_MISMATCH
            if (expectedPreset != null && preset != null && preset != expectedPreset) issues += READBACK_MISMATCH
            if (!eq.hasControl()) {
                publish(UNAVAILABLE, issues + CONTROL_UNAVAILABLE)
                return
            }
            val failed = issues.any { it in setOf(WRITE_FAILED, ENABLE_FAILED, READBACK_FAILED, READBACK_MISMATCH) }
            publish(if (failed) FAILED else APPLIED, issues, levels, preset)
        } catch (_: RuntimeException) {
            // A platform observation failed. Do not issue transport commands, erase preferences,
            // or claim that a partially configured effect is confirmed.
            publish(FAILED, setOf(READBACK_FAILED))
        }
    }

    private fun tryCreateEqualizer(): Boolean {
        val eq = try { createEffect(currentSessionId) } catch (_: UnsupportedOperationException) {
            publish(UNAVAILABLE, setOf(UNSUPPORTED)); return false
        } catch (_: RuntimeException) {
            publish(FAILED, setOf(CREATION_FAILED)); return false
        }
        equalizer = eq
        try {
            val bands = eq.numberOfBands
            val range = eq.bandLevelRange
            if (bands <= 0 || range.size != 2 || range[0] > range[1]) {
                releaseEqualizer()
                publish(UNAVAILABLE, setOf(UNSUPPORTED))
                return false
            }
            val count = try { eq.numberOfPresets } catch (_: RuntimeException) { 0 }
            val presets = (0 until count).mapNotNull { i ->
                try { EqualizerPlatformPreset(i.toShort(), eq.getPresetName(i.toShort())) }
                catch (_: RuntimeException) { null } // preserve original indices across gaps
            }
            capabilities = EqualizerCapabilities(
                bands, range[0].toInt(), range[1].toInt(),
                (0 until bands).map { try { eq.getCenterFreq(it.toShort()) / 1000 } catch (_: RuntimeException) { 0 } },
                presets, count,
            )
            repository.updateCapabilities(capabilities)
            eq.setControlListener { granted ->
                scope.launch {
                    if (isReleased || equalizer !== eq) return@launch
                    if (!granted) {
                        publish(UNAVAILABLE, setOf(CONTROL_UNAVAILABLE))
                    } else if (CONTROL_UNAVAILABLE in repository.applicationState.value.issues) {
                        lastAttempt = null
                        applySettings(cachedSettings)
                    }
                }
            }
            eq.setEnableListener { enabled ->
                scope.launch {
                    if (!isReleased && equalizer === eq && !enabled && cachedSettings.eqEnabled) {
                        try {
                            if (!eq.enabled) publish(UNAVAILABLE, setOf(EXTERNALLY_DISABLED))
                        } catch (_: RuntimeException) { publish(FAILED, setOf(READBACK_FAILED)) }
                    }
                }
            }
            return true
        } catch (_: RuntimeException) {
            releaseEqualizer()
            publish(FAILED, setOf(CAPABILITIES_FAILED))
            return false
        }
    }

    private fun releaseEqualizer() {
        val eq = equalizer
        equalizer = null // invalidate queued callbacks before releasing native resources
        capabilities = null
        lastAttempt = null
        repository.updateCapabilities(null)
        try { eq?.release() } catch (_: RuntimeException) {
            Log.w(TAG, "[release] Equalizer release failed")
        }
    }

    private companion object { const val TAG = "StereoSineAudioEffects" }
}
