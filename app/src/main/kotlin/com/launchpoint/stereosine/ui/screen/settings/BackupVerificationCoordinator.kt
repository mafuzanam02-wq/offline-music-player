package com.launchpoint.stereosine.ui.screen.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BACKUP-06B. Owns the verification UI state behind a request generation, so an older verification can never become visible
 * after the backup state it described has changed.
 *
 * Every [invalidate] / [request] / [requestAfter] bumps the generation under a lock, cancels the running verification and shows
 * [BackupVerificationUiState.Loading]. A verification publishes only if its generation is still current at the moment it publishes
 * (checked under the same lock), so a result that finishes after cancellation was requested — the race plain cancellation cannot
 * rule out — is discarded. [verify] must not throw other than for cancellation.
 */
internal class BackupVerificationCoordinator(
    private val scope: CoroutineScope,
    private val verify: suspend () -> BackupVerificationUiState.Ready,
) {
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    private val _state = MutableStateFlow<BackupVerificationUiState>(BackupVerificationUiState.Loading)
    val state: StateFlow<BackupVerificationUiState> = _state.asStateFlow()

    /** Drops any shown or running result immediately and returns the new generation. */
    fun invalidate(): Long = synchronized(lock) {
        generation++
        job?.cancel()
        job = null
        _state.value = BackupVerificationUiState.Loading
        generation
    }

    private var entered = false

    /** First verification when a screen opens. Repeated calls (recomposition, re-entry of the same ViewModel) do nothing. */
    fun requestOnce() {
        synchronized(lock) { if (entered) return; entered = true }
        request()
    }

    /**
     * Re-verifies whenever [source] emits a new value after the first one (a different folder, a newly saved backup, including a
     * background automatic one). Only acts once the screen has asked for its first verification. No polling: this follows an existing flow.
     */
    fun requestOnChange(source: Flow<*>) {
        scope.launch {
            source.drop(1).distinctUntilChanged().collect { if (synchronized(lock) { entered }) request() }
        }
    }

    /** Invalidates and starts a fresh verification. */
    fun request(): Long {
        val g = invalidate()
        launchFor(g)
        return g
    }

    /**
     * Invalidates now, then verifies once [operation] (a backup or a folder change) has finished, whether it succeeded or failed.
     * If anything newer invalidated meanwhile, that newer request owns the outcome and this one does nothing.
     */
    fun requestAfter(operation: Job) {
        val g = invalidate()
        scope.launch {
            operation.join()
            launchFor(g)
        }
    }

    private fun launchFor(g: Long) {
        synchronized(lock) {
            if (generation != g) return
            job = scope.launch {
                val ready = verify()
                synchronized(lock) { if (generation == g) _state.value = ready }
            }
        }
    }
}
