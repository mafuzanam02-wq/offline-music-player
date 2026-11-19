package com.launchpoint.stereosine.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LS-1: structural facts that keep system-surface progress owned by the player itself. The behavioural proof is
 * [LockScreenProgressRealMedia3Test]; these guards stop a later change from quietly re-introducing a second position
 * authority, a UI-ticker dependency, a speculative timer, or a frozen position snapshot in the façade.
 */
class LockScreenProgressStructureGuardTest {

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/stereosine/$path").readText()
    private fun code(path: String) = src(path).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    private val positionSetters = listOf("setContentPositionMs(", "setAdPositionMs(", "setContentBufferedPositionMs(", "setAdBufferedPositionMs(", "setTotalBufferedDurationMs(")
    private val setterCall = Regex("""\.set[A-Za-z]+\(""")
    private val removedCommand = Regex("""\.remove\((Player\.COMMAND_[A-Z_]+)\)""")

    /** getState() body only: from its declaration up to the next member (comments are already stripped by [code]). */
    private fun getStateBlock(facade: String) = facade.substringAfter("override fun getState()").substringBefore("private fun withLogicalQueue")
    private fun positionSettersIn(facade: String) = positionSetters.filter { facade.contains(it) }
    private fun settersIn(block: String) = setterCall.findAll(block).map { it.value }.toSet()
    private fun commandsRemovedIn(block: String) = removedCommand.findAll(block).map { it.groupValues[1] }.toSet()

    /**
     * Exact contract of getState(): the logical play state (play-when-ready + suppression reason), the one-shot auto-transition
     * discontinuity, and the command-availability filter that hides COMMAND_SET_VOLUME (e0b9544: Media3 shares that command for
     * volume/mute, and forwarding it would bypass the gain composer). None of these is a position field.
     */
    private val getStateSetters = setOf(".setAvailableCommands(", ".setPlayWhenReady(", ".setPlaybackSuppressionReason(", ".setPositionDiscontinuity(")

    @Test fun `the facade never rewrites a position field so Media3 live position suppliers are preserved`() {
        val facade = code("playback/SessionFacade.kt")
        assertEquals("SessionFacade must not call a position setter (it would replace the live supplier)", emptyList<String>(), positionSettersIn(facade))
        val getState = getStateBlock(facade)
        assertEquals(
            "getState overlays exactly the logical play state, the one-shot discontinuity and the command-availability filter",
            getStateSetters,
            settersIn(getState),
        )
        assertEquals(
            "the command-availability filter hides only COMMAND_SET_VOLUME and grants nothing",
            setOf("Player.COMMAND_SET_VOLUME"),
            commandsRemovedIn(getState),
        )
        assertFalse("getState must not add commands (only the logical-queue overlay may)", getState.contains(".add(Player.COMMAND") || getState.contains(".addIf("))
        val logical = facade.substringAfter("private fun withLogicalQueue").substringBefore("private fun withStableTimelineIdentity")
        assertEquals(
            "the logical-queue overlay rewrites only queue-shaped fields, never a position field",
            setOf(".setPlaylist(", ".setCurrentMediaItemIndex(", ".setRepeatMode(", ".setShuffleModeEnabled(", ".setAvailableCommands("),
            settersIn(logical),
        )
    }

    // Negative proof: the same checks, run against deliberately tampered copies of the REAL source, must see the tampering.
    @Test fun `an unauthorised position setter anywhere in the facade is detected`() {
        val facade = code("playback/SessionFacade.kt")
        for (setter in positionSetters) {
            val tampered = facade.replace(".setPlaybackSuppressionReason(", ".$setter 0L).setPlaybackSuppressionReason(")
            assertEquals(listOf(setter), positionSettersIn(tampered))
            assertTrue("$setter inside getState also breaks the exact setter set", settersIn(getStateBlock(tampered)) != getStateSetters)
        }
        assertTrue(positionSettersIn(facade).isEmpty())
    }

    @Test fun `any extra getState setter or a missing command filter is detected`() {
        val facade = code("playback/SessionFacade.kt")
        val getState = getStateBlock(facade)
        assertEquals(getStateSetters, settersIn(getState))
        val extraSetter = getStateBlock(facade.replace(".setPlayWhenReady(", ".setCurrentMediaItemIndex(1).setPlayWhenReady("))
        assertTrue(settersIn(extraSetter) != getStateSetters)
        val commandFilterRemoved = getStateBlock(facade.replace(".setAvailableCommands(", ".copyState("))
        assertTrue(settersIn(commandFilterRemoved) != getStateSetters)
        val widerFilter = getStateBlock(facade.replace(".remove(Player.COMMAND_SET_VOLUME)", ".remove(Player.COMMAND_SET_VOLUME).remove(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)"))
        assertTrue(commandsRemovedIn(widerFilter) != setOf("Player.COMMAND_SET_VOLUME"))
    }

    @Test fun `the one-shot promotion pin is always cleared and nothing but the logical owner invalidates state`() {
        val facade = code("playback/SessionFacade.kt")
        val replace = facade.substringAfter("fun replaceDelegate").substringBefore("var commandBoundary")
        assertTrue(replace.contains("finally {") && replace.substringAfter("finally {").contains("pinnedAutoTransitionPositionMs = null"))
        assertEquals("invalidateState is called only by bindPlayWhenReadyOwner, invalidateLogicalState and bindLogicalQueue", 3, Regex("""invalidateState\(\)""").findAll(facade).count())
    }

    @Test fun `no session-side component depends on the UI position ticker or runs its own timer`() {
        for (file in listOf("SessionFacade.kt", "PlayerEngine.kt", "PreviousBehaviorPlayer.kt")) {
            val c = code("playback/$file")
            assertFalse("$file must not reference the UI position ticker", c.contains("PositionTicker") || c.contains("positionTicker"))
            assertFalse("$file must not post delayed work", c.contains("postDelayed") || c.contains("Handler("))
            if (file != "PreviousBehaviorPlayer.kt") assertFalse("$file must not keep an elapsed-time counter", c.contains("elapsedRealtime"))
        }
        // the only clock read in the policy wrapper is the timestamp inside its debug transport log line
        val previous = code("playback/PreviousBehaviorPlayer.kt").lines().filter { it.contains("elapsedRealtime") }
        assertEquals(previous.toString(), 1, previous.size)
        assertTrue(previous.single().contains("t="))
        val service = code("playback/PlaybackService.kt")
        assertFalse(service.contains("PositionTicker") || service.contains("positionTicker"))
        assertFalse(service.contains("postDelayed"))
        assertFalse("the service never forces a session state refresh", service.contains("invalidateState("))
        assertEquals("the only refresh hook is the logical-queue-change invalidator, never a timer or position", 1, service.split("invalidateLogicalState()").size - 1)
    }

    @Test fun `the custom layout is rebuilt only for the notification setting or shuffle and repeat, never for position`() {
        val service = code("playback/PlaybackService.kt")
        assertEquals("a single call site rebuilds the layout", 1, Regex("""setCustomLayout\(""").findAll(service).count())
        val block = service.substringAfter("combine(").substringBefore("mediaSession?.setCustomLayout")
        assertTrue(block.contains("notificationControlsSetting"))
        assertTrue(block.contains("it.shuffleEnabled to it.repeatMode"))
        assertTrue(block.contains("distinctUntilChanged()"))
        assertFalse(block.contains("position", ignoreCase = true))
    }

    @Test fun `the session is built on the stable session-facing player and the physical delegate is never given to it`() {
        val service = code("playback/PlaybackService.kt")
        assertTrue(service.contains("MediaLibrarySession.Builder(this, sessionPlayer,"))
        assertTrue(service.contains("player = logicalPlayer"))
        val assembly = code("playback/PlaybackAssembly.kt")
        assertTrue("the engine topology hands the stable facade, not a physical player, to the session chain", assembly.contains("PlaybackAssembly(topology, engine.currentPlayer, engine, engine.facade)"))
    }

    @Test fun `the production media item carries no fabricated duration`() {
        val item = code("playback/PlaybackMediaItem.kt")
        assertFalse("duration comes from the player timeline, never from app metadata", item.contains("setDurationMs") || item.contains("durationMs"))
    }
}
