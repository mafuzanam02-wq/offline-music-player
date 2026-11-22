package com.launchpoint.stereosine.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.stereosine.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI-READABILITY-03D: "Delete from device" in the song-row overflow menu. Production [SongRowWithOverflow], [SongDeletionHost] and
 * [SongDeletionViewModel] with a recording [SongDeletionBackend] and a recording authorization launcher. The Android authorization
 * screen is NOT shown and no file is deleted: these tests prove the app's confirmation, identity and cleanup contract, not a real
 * MediaStore deletion.
 */
class SongRowDeleteFlowInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private class RecordingBackend : SongDeletionBackend {
        val requested = mutableListOf<String>()
        val cleaned = mutableListOf<Pair<Long, String>>()
        val attempts = mutableListOf<Pair<Long, String>>()
        var failRequest = false
        var cleanFailuresLeft = 0
        var gate: CompletableDeferred<Unit>? = null

        override fun createDeleteRequest(songUri: String): IntentSender? {
            requested += songUri
            if (failRequest) return null
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            return PendingIntent.getBroadcast(ctx, 0, Intent("com.launchpoint.stereosine.test.NOOP").setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE).intentSender
        }

        override suspend fun cleanUp(songId: Long, songUri: String) {
            attempts += songId to songUri
            gate?.await()
            if (cleanFailuresLeft > 0) { cleanFailuresLeft--; throw IllegalStateException("cleanup failed") }
            cleaned += songId to songUri
        }
    }

    private val backend = RecordingBackend()
    private val handle = SavedStateHandle()
    private val viewModel by lazy { SongDeletionViewModel(handle, backend, sdkInt = 34) }
    private val launched = mutableListOf<IntentSender>()
    private var deliverResult: (Int) -> Unit = {}
    private var launcherThrows = false
    private val calls = mutableListOf<String>()
    private val songs = mutableStateListOf<Song>()

    private fun song(i: Long, id: Long = i) = Song(
        id = id, title = "Song %02d".format(i), artist = "Artist", album = "Album", albumId = i, duration = 200_000L,
        uri = "content://test/media/$id", dateAdded = 0L, trackNumber = 1, year = 2024,
    )

    private fun show(initial: List<Song> = (1L..4L).map { song(it) }) {
        songs.clear(); songs.addAll(initial)
        rule.setContent {
            SongDeletionHost(
                viewModel = viewModel,
                authorize = { onResult -> deliverResult = onResult; { sender -> if (launcherThrows) throw IllegalStateException("no activity to handle the request"); launched += sender } },
            ) {
                Column(Modifier) {
                    songs.forEach { s ->
                        key(s.id) {
                            SongRowWithOverflow(
                                song = s, isCurrent = false, isFavorite = false,
                                onPlay = { calls += "Play ${s.id}" }, onPlayNext = { calls += "Play next" }, onAddToQueue = { calls += "Queue" },
                                onToggleFavorite = { calls += "Favorite" }, onAddToPlaylist = { calls += "Playlist" }, onTrackDetails = { calls += "Details" },
                                onRemove = { calls += "Remove ${s.id}" },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openMenu(index: Int) {
        rule.onAllNodes(hasClickAction() and hasContentDescription("More actions"))[index].performClick()
        rule.waitForIdle()
    }

    private fun chooseDelete() {
        rule.onNode(hasText("Delete from device") and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun confirmDialogShown() = rule.onAllNodes(hasText("Delete from device?")).fetchSemanticsNodes().isNotEmpty()
    private fun menuOpen() = rule.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()
    private fun confirm() { rule.onNode(hasText("Delete from device") and hasClickAction()).performClick(); rule.waitForIdle() }
    private fun deliver(code: Int) { rule.runOnUiThread { deliverResult(code) }; rule.waitForIdle(); Thread.sleep(150); rule.waitForIdle() }

    // ── Menu item ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun eligible_song_offers_delete_from_device_last_and_distinct_from_remove() {
        show()
        openMenu(0)
        rule.onNode(hasText("Remove") and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Delete from device") and hasClickAction()).performScrollTo().assertIsDisplayed()
        val items = rule.onAllNodes(hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(isPopup())).fetchSemanticsNodes()
        val texts = items.map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        assertEquals("Delete from device is the final menu action", "Delete from device", texts.last())
        assertTrue("Remove stays a separate, earlier action", texts.indexOf("Remove") in 0 until texts.size - 1)
    }

    @Test fun ineligible_song_does_not_offer_delete() {
        show(listOf(song(1, id = Long.MIN_VALUE)))
        openMenu(0)
        rule.onNode(hasText("Delete from device")).assertDoesNotExist()
        rule.onNode(hasText("Remove") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    @Test fun a_row_without_a_deletion_host_has_no_delete_action() {
        rule.setContent {
            SongRowWithOverflow(
                song = song(1), isCurrent = false, isFavorite = false, onPlay = {}, onPlayNext = {}, onAddToQueue = {},
                onToggleFavorite = {}, onAddToPlaylist = {}, onTrackDetails = {},
            )
        }
        rule.onNode(hasClickAction() and hasContentDescription("More actions")).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Delete from device")).assertDoesNotExist()
    }

    // ── Confirmation ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun choosing_delete_closes_the_menu_and_asks_for_confirmation_without_touching_anything() {
        show()
        openMenu(1)
        chooseDelete()
        assertFalse("the overflow menu closes", menuOpen())
        assertTrue("the destructive confirmation appears", confirmDialogShown())
        rule.onNode(hasText("Delete from device?")).assertIsDisplayed()
        assertEquals("nothing requested yet", emptyList<String>(), backend.requested)
        assertEquals(emptyList<Pair<Long, String>>(), backend.cleaned)
        assertEquals("no other callback fired", emptyList<String>(), calls)
    }

    @Test fun cancel_leaves_everything_unchanged_and_the_row_can_be_deleted_later() {
        show()
        openMenu(0); chooseDelete()
        rule.onNodeWithText("Cancel").performClick(); rule.waitForIdle()
        assertFalse(confirmDialogShown())
        assertEquals(emptyList<String>(), backend.requested); assertEquals(emptyList<Pair<Long, String>>(), backend.cleaned); assertTrue(launched.isEmpty())
        openMenu(0); chooseDelete()
        assertTrue("the flow can start again after Cancel", confirmDialogShown())
    }

    // ── Authorization and cleanup ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun confirm_requests_android_authorization_for_exactly_the_selected_song_and_cleans_up_only_after_ok() {
        show()
        openMenu(2); chooseDelete(); confirm()
        assertEquals("authorization requested once for the selected URI", listOf("content://test/media/3"), backend.requested)
        assertEquals("the system screen was launched once", 1, launched.size)
        assertEquals("no cleanup before Android confirms", emptyList<Pair<Long, String>>(), backend.cleaned)
        deliver(Activity.RESULT_OK)
        assertEquals("cleanup for exactly the selected song", listOf(3L to "content://test/media/3"), backend.cleaned)
        assertEquals("the row never navigated or played", emptyList<String>(), calls)
    }

    @Test fun rejected_or_cancelled_authorization_cleans_up_nothing_and_allows_a_retry() {
        show()
        openMenu(0); chooseDelete(); confirm()
        deliver(Activity.RESULT_CANCELED)
        assertEquals("no cleanup after a rejected request", emptyList<Pair<Long, String>>(), backend.cleaned)
        openMenu(0); chooseDelete()
        assertTrue("a new request can be made after a rejection", confirmDialogShown())
    }

    @Test fun a_failed_authorization_request_reports_failure_and_cleans_up_nothing() {
        backend.failRequest = true
        show()
        openMenu(0); chooseDelete(); confirm()
        assertTrue("no authorization screen launched", launched.isEmpty())
        assertEquals(emptyList<Pair<Long, String>>(), backend.cleaned)
        assertFalse("the dialog is gone after the failure", confirmDialogShown())
    }

    @Test fun list_changes_while_authorization_is_pending_cannot_redirect_the_deletion() {
        show()
        openMenu(1); chooseDelete(); confirm()           // song id 2
        rule.runOnIdle { songs.removeAt(0); songs.add(song(9)); songs.reverse() }   // reorder, remove and add rows
        rule.waitForIdle()
        deliver(Activity.RESULT_OK)
        assertEquals("cleanup targets the originally selected song", listOf(2L to "content://test/media/2"), backend.cleaned)
    }

    @Test fun duplicate_requests_confirmations_and_results_cause_one_cleanup() {
        show()
        openMenu(0); chooseDelete()
        rule.runOnIdle { viewModel.requestDelete(songs[1]) }       // a second request while the first is confirming: ignored
        confirm()
        rule.runOnIdle { viewModel.confirm({ launched += it }, {}) } // a second confirm while awaiting: ignored
        assertEquals(1, backend.requested.size); assertEquals(1, launched.size)
        rule.runOnIdle { viewModel.requestDelete(songs[2]) }       // a request while awaiting authorization: ignored
        deliver(Activity.RESULT_OK)
        deliver(Activity.RESULT_OK)                                  // a repeated result: ignored
        assertEquals("exactly one cleanup, for the first song", listOf(1L to "content://test/media/1"), backend.cleaned)
    }

    @Test fun the_pending_song_survives_recreation_through_saved_state() {
        show()
        openMenu(3); chooseDelete(); confirm()                       // AWAITING_AUTHORIZATION for song 4
        val restored = SongDeletionViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }), backend, sdkInt = 34)
        assertEquals(SongDeletionPhase.AWAITING_AUTHORIZATION, restored.state.value.phase)
        restored.onAuthorizationResult(granted = true)
        Thread.sleep(200)
        assertEquals("the restored request cleans up the original song", listOf(4L to "content://test/media/4"), backend.cleaned)
    }

    // ── Authorization launch failure (blocker A) ────────────────────────────────────────────────────────────────────────

    @Test fun a_launcher_that_throws_reports_failure_releases_the_request_and_allows_the_next_deletion() {
        launcherThrows = true
        show()
        openMenu(0); chooseDelete(); confirm()
        assertTrue("no authorization screen was shown", launched.isEmpty())
        assertEquals("authorization was requested for the selected song only", listOf("content://test/media/1"), backend.requested)
        assertEquals("nothing is cleaned up", emptyList<Pair<Long, String>>(), backend.cleaned)
        assertEquals("not stuck awaiting authorization", SongDeletionPhase.IDLE, viewModel.state.value.phase)
        assertFalse(confirmDialogShown())
        launcherThrows = false
        openMenu(1); chooseDelete()
        assertTrue("another song can begin its deletion flow", confirmDialogShown())
        confirm(); deliver(Activity.RESULT_OK)
        assertEquals("an ordinary launch still behaves as before, for the new song only", listOf(2L to "content://test/media/2"), backend.cleaned)
    }

    @Test fun a_throwing_launcher_invokes_the_failure_callback_exactly_once() {
        show()
        openMenu(0); chooseDelete()
        var failures = 0
        rule.runOnUiThread { viewModel.confirm(launch = { throw IllegalStateException("boom") }, onFailed = { failures++ }) }
        rule.waitForIdle()
        assertEquals(1, failures)
        assertEquals(SongDeletionPhase.IDLE, viewModel.state.value.phase)
        assertEquals(emptyList<Pair<Long, String>>(), backend.attempts)
    }

    // ── Cleanup failure and recovery (blocker B) ────────────────────────────────────────────────────────────────────────

    private fun awaitPhase(phase: SongDeletionPhase) {
        val end = System.currentTimeMillis() + 3_000
        while (viewModel.state.value.phase != phase && System.currentTimeMillis() < end) { Thread.sleep(25); rule.waitForIdle() }
        assertEquals(phase, viewModel.state.value.phase)
    }

    @Test fun a_throwing_cleanup_keeps_a_recovery_marker_and_does_not_crash() {
        backend.cleanFailuresLeft = 1
        show()
        openMenu(1); chooseDelete(); confirm(); deliver(Activity.RESULT_OK)
        awaitPhase(SongDeletionPhase.CLEANUP_FAILED)
        assertEquals("the marker names the authorized song", 2L, viewModel.state.value.songId)
        assertEquals("content://test/media/2", viewModel.state.value.songUri)
        assertEquals(1, backend.attempts.size); assertEquals(emptyList<Pair<Long, String>>(), backend.cleaned)
        assertEquals("the marker is persisted", SongDeletionPhase.CLEANUP_FAILED.name, handle.get<String>("songDeletionPhase"))
    }

    @Test fun recovery_after_a_failed_cleanup_needs_no_new_authorization_and_targets_the_original_song_after_list_changes() {
        backend.cleanFailuresLeft = 1
        show()
        openMenu(1); chooseDelete(); confirm(); deliver(Activity.RESULT_OK)      // song 2
        awaitPhase(SongDeletionPhase.CLEANUP_FAILED)
        rule.runOnIdle { songs.removeAt(0); songs.add(song(9)); songs.reverse() }
        rule.waitForIdle()
        rule.runOnUiThread { viewModel.recoverPendingCleanup() }
        awaitPhase(SongDeletionPhase.IDLE)
        assertEquals("exactly the originally authorized song is cleaned", listOf(2L to "content://test/media/2"), backend.cleaned)
        assertEquals("both attempts targeted song 2", listOf(2L to "content://test/media/2", 2L to "content://test/media/2"), backend.attempts)
        assertEquals("no second authorization request", listOf("content://test/media/2"), backend.requested)
        assertEquals(1, launched.size)
    }

    @Test fun a_new_request_during_a_failed_cleanup_never_cleans_another_song_and_only_retries_the_marker() {
        backend.cleanFailuresLeft = 1
        show()
        openMenu(0); chooseDelete(); confirm(); deliver(Activity.RESULT_OK)      // song 1 fails
        awaitPhase(SongDeletionPhase.CLEANUP_FAILED)
        val accepted = booleanArrayOf(true)
        rule.runOnUiThread { accepted[0] = viewModel.requestDelete(songs[2]) }    // song 3: refused, triggers recovery of song 1
        assertFalse("a new deletion is not accepted over a pending recovery marker", accepted[0])
        awaitPhase(SongDeletionPhase.IDLE)
        assertEquals(listOf(1L to "content://test/media/1"), backend.cleaned)
        assertFalse("song 3 was never selected for deletion", backend.attempts.any { it.first == 3L })
        rule.runOnUiThread { accepted[0] = viewModel.requestDelete(songs[2]) }
        assertTrue("after recovery a new deletion is accepted", accepted[0])
    }

    @Test fun repeated_failures_keep_the_marker_until_cleanup_finally_succeeds() {
        backend.cleanFailuresLeft = 2
        show()
        openMenu(2); chooseDelete(); confirm(); deliver(Activity.RESULT_OK)
        awaitPhase(SongDeletionPhase.CLEANUP_FAILED)
        rule.runOnUiThread { viewModel.recoverPendingCleanup() }
        awaitPhase(SongDeletionPhase.CLEANUP_FAILED)                  // still failing: the marker is not cleared early
        assertEquals(3L, viewModel.state.value.songId)
        rule.runOnUiThread { viewModel.recoverPendingCleanup() }
        awaitPhase(SongDeletionPhase.IDLE)
        assertEquals(3, backend.attempts.size)
        assertEquals(listOf(3L to "content://test/media/3"), backend.cleaned)
    }

    @Test fun recovery_after_interrupted_cleanup_restores_the_marker_and_cleans_without_authorization() {
        show()
        openMenu(3); chooseDelete(); confirm()
        // Simulate process death mid-cleanup: the saved marker says CLEANING_UP for song 4.
        val saved = handle.keys().associateWith { handle.get<Any>(it) }.toMutableMap().apply { put("songDeletionPhase", "CLEANING_UP") }
        val restored = SongDeletionViewModel(SavedStateHandle(saved), backend, sdkInt = 34)
        assertEquals(SongDeletionPhase.CLEANING_UP, restored.state.value.phase)
        assertEquals(4L, restored.state.value.songId)
        rule.runOnUiThread { restored.recoverPendingCleanup() }
        val end = System.currentTimeMillis() + 3_000
        while (restored.state.value.phase != SongDeletionPhase.IDLE && System.currentTimeMillis() < end) Thread.sleep(25)
        assertEquals(SongDeletionPhase.IDLE, restored.state.value.phase)
        assertEquals(listOf(4L to "content://test/media/4"), backend.cleaned)
        assertEquals("recovery asked Android for nothing more", listOf("content://test/media/4"), backend.requested)
    }

    @Test fun concurrent_recovery_and_duplicate_callbacks_run_one_cleanup() {
        backend.gate = CompletableDeferred()
        show()
        openMenu(0); chooseDelete(); confirm()
        deliver(Activity.RESULT_OK)
        deliver(Activity.RESULT_OK)                                           // duplicate authorization callback
        rule.runOnUiThread { viewModel.recoverPendingCleanup(); viewModel.recoverPendingCleanup() }
        rule.runOnUiThread { assertFalse(viewModel.requestDelete(songs[1])) } // overlapping new request while cleaning
        Thread.sleep(150)
        assertEquals("one cleanup is running", 1, backend.attempts.size)
        assertEquals(SongDeletionPhase.CLEANING_UP, viewModel.state.value.phase)
        backend.gate!!.complete(Unit)
        awaitPhase(SongDeletionPhase.IDLE)
        assertEquals(listOf(1L to "content://test/media/1"), backend.cleaned)
        assertEquals(1, backend.attempts.size)
    }

    @Test fun rejected_authorization_leaves_no_marker_and_no_cleanup() {
        show()
        openMenu(0); chooseDelete(); confirm(); deliver(Activity.RESULT_CANCELED)
        assertEquals(SongDeletionPhase.IDLE, viewModel.state.value.phase)
        assertEquals(emptyList<Pair<Long, String>>(), backend.attempts)
        rule.runOnUiThread { viewModel.recoverPendingCleanup() }
        Thread.sleep(150)
        assertEquals("recovery has nothing to do without a marker", emptyList<Pair<Long, String>>(), backend.attempts)
    }

    // ── Existing row behavior is untouched ──────────────────────────────────────────────────────────────────────────────

    @Test fun remove_and_play_still_invoke_their_own_callbacks_without_starting_deletion() {
        show()
        openMenu(0)
        rule.onNode(hasText("Remove") and hasClickAction()).performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Remove 1"), calls)
        assertFalse(confirmDialogShown()); assertEquals(emptyList<String>(), backend.requested)
        calls.clear()
        openMenu(1)
        rule.onNode(hasText("Play") and hasClickAction()).performClick(); rule.waitForIdle()
        assertEquals(listOf("Play 2"), calls)
    }
}
