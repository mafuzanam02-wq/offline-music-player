package com.launchpoint.stereosine.ui.components

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.launchpoint.stereosine.data.local.dao.ImportBaselineDao
import com.launchpoint.stereosine.data.local.dao.LyricsOverrideDao
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.data.repository.PlaylistRepository
import com.launchpoint.stereosine.data.repository.SongRepository
import com.launchpoint.stereosine.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-READABILITY-03D. "Delete from device" from a song row's overflow menu, through the SAME deletion workflow Track Details uses:
 * [MediaStoreSongDeleter] holds the one authoritative request + cleanup implementation (Track Details delegates to it too), and
 * [SongDeletionViewModel] owns the pending request so it is tied to the song that was selected, not to whatever row is on screen
 * when Android's authorization result arrives.
 */

/** Which songs may offer the action. Matches Track Details: Android 11+ (MediaStore.createDeleteRequest) and a real song id. */
object SongDeletionPolicy {
    fun isEligible(songId: Long, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R && songId != Long.MIN_VALUE
}

/** The two Android/data operations of a physical deletion. The production implementation is [MediaStoreSongDeleter]. */
interface SongDeletionBackend {
    /** An authorization request for [songUri], or null when it cannot be created (pre-Android 11, invalid URI, provider error). */
    fun createDeleteRequest(songUri: String): IntentSender?

    /**
     * App-side cleanup after Android confirmed the file is deleted. Keeps track_stats and track_listen_events (history).
     * Idempotent; throws when any step failed (after attempting all), so it can be retried without another authorization.
     */
    suspend fun cleanUp(songId: Long, songUri: String)
}

class MediaStoreSongDeleter(
    private val context: Context,
    private val songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerController: PlayerController,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val importBaselineDao: ImportBaselineDao,
) : SongDeletionBackend {

    override fun createDeleteRequest(songUri: String): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, listOf(Uri.parse(songUri))).intentSender
        }.getOrNull()
    }

    /**
     * Every step is attempted even when an earlier one fails (each is idempotent), then the first failure is rethrown so the caller
     * can keep a recovery marker and retry. The steps are unchanged from the original Track Details cleanup.
     */
    override suspend fun cleanUp(songId: Long, songUri: String) {
        var failure: Throwable? = null
        suspend fun step(block: suspend () -> Unit) {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (failure == null) failure = e }
        }
        step { playerController.handleSongDeleted(songId) }
        step { songRepository.pruneSong(songId) }
        step { playlistRepository.removeSongFromAllPlaylists(songId) }
        step { lyricsOverrideDao.deleteForSong(songId, songUri) }
        // Import baselines for this song are no longer useful: the file has been permanently deleted from the device, so it cannot
        // be re-imported. Clearing them avoids stale deduplication if the user ever imports a similarly-named track.
        // track_stats and track_listen_events are intentionally kept for historical reports.
        step { importBaselineDao.deleteBySongId(songId) }
        failure?.let { throw it }
    }
}

/** CLEANUP_FAILED: Android already deleted the file but app-side cleanup did not finish. A recovery marker, not an error to dismiss. */
enum class SongDeletionPhase { IDLE, CONFIRMING, AWAITING_AUTHORIZATION, CLEANING_UP, CLEANUP_FAILED }

/** The pending request: the exact song chosen in the menu (id + URI + title), independent of any list that shows it. */
data class SongDeletionUiState(
    val phase: SongDeletionPhase = SongDeletionPhase.IDLE,
    val songId: Long = 0L,
    val songUri: String = "",
    val title: String = "",
)

/** Offered to song rows through [LocalSongDeleteRequester]; absent (null) where no deletion host is installed. */
fun interface SongDeleteRequester {
    fun requestDelete(song: Song)
}

val LocalSongDeleteRequester = staticCompositionLocalOf<SongDeleteRequester?> { null }

/**
 * A one-shot state machine. IDLE -> CONFIRMING (menu item) -> AWAITING_AUTHORIZATION (user confirmed, Android asked) ->
 * CLEANING_UP (Android returned RESULT_OK) -> IDLE, or CLEANUP_FAILED when cleanup threw. Every other transition is ignored, which is what prevents a second request,
 * a second confirm or a repeated result from deleting or cleaning up twice. A cancelled or rejected request returns to IDLE with
 * no cleanup. State is mirrored into [SavedStateHandle] so the pending song survives configuration change and process recreation
 * while Android's authorization screen is in front.
 *
 * Recovery: CLEANING_UP and CLEANUP_FAILED are recovery markers for a file Android ALREADY deleted. They are kept (never reset)
 * until [SongDeletionBackend.cleanUp] succeeds for that exact id/URI, and [recoverPendingCleanup] re-runs the idempotent cleanup
 * without another authorization. Library sync independently removes the song row (SongRepository.sync -> applySongSyncPlan), but it
 * deliberately keeps playlist memberships and does not touch the queue, lyrics overrides or import baselines, so those need this
 * cleanup. A marker survives configuration change and system-initiated process death (SavedStateHandle); it is not durable
 * across the user force-stopping the app.
 */
@HiltViewModel
class SongDeletionViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val backend: SongDeletionBackend,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext context: Context,
        songRepository: SongRepository,
        playlistRepository: PlaylistRepository,
        playerController: PlayerController,
        lyricsOverrideDao: LyricsOverrideDao,
        importBaselineDao: ImportBaselineDao,
    ) : this(
        savedStateHandle,
        MediaStoreSongDeleter(context, songRepository, playlistRepository, playerController, lyricsOverrideDao, importBaselineDao),
    )

    private var cleanupJob: Job? = null
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SongDeletionUiState> = _state.asStateFlow()

    private fun restore(): SongDeletionUiState {
        val phase = savedStateHandle.get<String>(KEY_PHASE)?.let { runCatching { SongDeletionPhase.valueOf(it) }.getOrNull() }
            ?: SongDeletionPhase.IDLE
        // CLEANING_UP / CLEANUP_FAILED are recovery markers: the cleanup coroutine dies with the process, the marker must not.
        if (phase == SongDeletionPhase.IDLE) return SongDeletionUiState()
        return SongDeletionUiState(
            phase = phase,
            songId = savedStateHandle.get<Long>(KEY_ID) ?: 0L,
            songUri = savedStateHandle.get<String>(KEY_URI).orEmpty(),
            title = savedStateHandle.get<String>(KEY_TITLE).orEmpty(),
        )
    }

    private fun set(next: SongDeletionUiState) {
        _state.value = next
        savedStateHandle[KEY_PHASE] = next.phase.name
        savedStateHandle[KEY_ID] = next.songId
        savedStateHandle[KEY_URI] = next.songUri
        savedStateHandle[KEY_TITLE] = next.title
    }

    /** Menu item chosen: capture THIS song. Ignored while another request is pending or the song is not eligible. */
    fun requestDelete(song: Song): Boolean {
        if (_state.value.phase == SongDeletionPhase.CLEANUP_FAILED) { recoverPendingCleanup(); return false }
        if (_state.value.phase != SongDeletionPhase.IDLE || !SongDeletionPolicy.isEligible(song.id, sdkInt)) return false
        set(SongDeletionUiState(SongDeletionPhase.CONFIRMING, song.id, song.uri, song.displayTitle))
        return true
    }

    /** Confirmation dialog dismissed (Cancel / Back / outside): nothing was requested, nothing changes. */
    fun cancel() {
        if (_state.value.phase == SongDeletionPhase.CONFIRMING) set(SongDeletionUiState())
    }

    /** Confirmation dialog confirmed: ask Android. [launch] starts the system authorization screen for the pending song only. */
    fun confirm(launch: (IntentSender) -> Unit, onFailed: () -> Unit) {
        val pending = _state.value
        if (pending.phase != SongDeletionPhase.CONFIRMING) return
        val sender = backend.createDeleteRequest(pending.songUri)
        if (sender == null) {
            set(SongDeletionUiState())
            onFailed()
            return
        }
        val awaiting = pending.copy(phase = SongDeletionPhase.AWAITING_AUTHORIZATION)
        set(awaiting)
        try {
            launch(sender)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The authorization screen never opened: nothing was deleted, so release the request and report it.
            if (_state.value == awaiting) set(SongDeletionUiState())
            onFailed()
        }
    }

    /**
     * Android's result for the request launched by [confirm]. Only RESULT_OK (granted and deleted) triggers the app-side cleanup,
     * and only for the song captured at request time. Cancelled/rejected results leave everything untouched.
     */
    fun onAuthorizationResult(granted: Boolean) {
        val pending = _state.value
        if (pending.phase != SongDeletionPhase.AWAITING_AUTHORIZATION) return
        if (!granted) {
            set(SongDeletionUiState())
            return
        }
        startCleanup(pending)
    }

    /**
     * Re-runs cleanup for a pending recovery marker (CLEANING_UP restored after process death, or CLEANUP_FAILED). Targets only the
     * recorded id/URI, needs no new authorization, and does nothing while a cleanup is already running.
     */
    fun recoverPendingCleanup() {
        val pending = _state.value
        if (pending.phase != SongDeletionPhase.CLEANING_UP && pending.phase != SongDeletionPhase.CLEANUP_FAILED) return
        startCleanup(pending)
    }

    private fun startCleanup(pending: SongDeletionUiState) {
        if (cleanupJob?.isActive == true) return
        set(pending.copy(phase = SongDeletionPhase.CLEANING_UP))
        cleanupJob = viewModelScope.launch {
            val completed = try {
                withContext(NonCancellable) { backend.cleanUp(pending.songId, pending.songUri) }
                true
            } catch (e: CancellationException) {
                throw e      // the marker stays CLEANING_UP and is recovered on the next start
            } catch (e: Exception) {
                false
            }
            // The marker is cleared only after a successful cleanup; a failure keeps it for recovery.
            set(if (completed) SongDeletionUiState() else pending.copy(phase = SongDeletionPhase.CLEANUP_FAILED))
        }
    }

    private companion object {
        const val KEY_PHASE = "songDeletionPhase"
        const val KEY_ID = "songDeletionId"
        const val KEY_URI = "songDeletionUri"
        const val KEY_TITLE = "songDeletionTitle"
    }
}

/** Starts Android's authorization screen and reports its result code. Replaced only in tests (see [SongDeletionHost]). */
@Composable
fun rememberAuthorizationLauncher(onResult: (Int) -> Unit): (IntentSender) -> Unit {
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { current(it.resultCode) }
    return remember(launcher) { { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) } }
}

/**
 * Installed once above the screens. Provides [LocalSongDeleteRequester] to every song row below it and owns the destructive
 * confirmation dialog, the authorization launcher and failure feedback, so no screen duplicates deletion logic or state.
 */
@Composable
fun SongDeletionHost(
    viewModel: SongDeletionViewModel = hiltViewModel(),
    authorize: @Composable (onResult: (Int) -> Unit) -> ((IntentSender) -> Unit) = { rememberAuthorizationLauncher(it) },
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launch = authorize { code -> viewModel.onAuthorizationResult(code == Activity.RESULT_OK) }
    val requester = remember(viewModel) { SongDeleteRequester { viewModel.requestDelete(it) } }

    CompositionLocalProvider(LocalSongDeleteRequester provides requester) { content() }

    // Recovery of a deletion Android already performed whose cleanup did not finish, and visible feedback when it fails.
    LaunchedEffect(viewModel) { viewModel.recoverPendingCleanup() }
    LaunchedEffect(state.phase) {
        if (state.phase == SongDeletionPhase.CLEANUP_FAILED) {
            Toast.makeText(context, "\"${state.title}\" was deleted, but cleanup is incomplete. It will be retried.", Toast.LENGTH_LONG).show()
        }
    }

    if (state.phase == SongDeletionPhase.CONFIRMING) {
        DestructiveConfirmDialog(
            title        = DestructiveDialogCopy.DELETE_TRACK_TITLE,
            message      = DestructiveDialogCopy.deleteTrackBody(state.title),
            confirmLabel = DestructiveDialogCopy.DELETE_TRACK_CONFIRM,
            onConfirm    = {
                viewModel.confirm(
                    launch   = launch,
                    onFailed = { Toast.makeText(context, "Could not delete this track", Toast.LENGTH_SHORT).show() },
                )
            },
            onDismiss    = viewModel::cancel,
        )
    }
}
