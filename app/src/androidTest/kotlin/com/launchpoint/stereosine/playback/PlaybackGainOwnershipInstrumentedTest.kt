package com.launchpoint.stereosine.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** Real Media3 controller/session/wrappers and engine; isolated from the production service/queue. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackGainOwnershipInstrumentedTest {
    @Test fun controllerAndDirectCommandsCannotChangeEitherPhysicalGainOrSystemVolume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var assembly: PlaybackAssembly? = null
        var session: MediaLibrarySession? = null
        var controller: MediaController? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            main {
                assembly = assemblePlayback(context, true, AudioAttributes.DEFAULT)
                val wrapper = PreviousBehaviorPlayer(assembly!!.logicalPlayer, { 3000L }, scope, {},
                    { PlayerHydrationResult.NoSavedSession }, { emptyList() }, {}, { session }, {}, {}, {}, {})
                session = MediaLibrarySession.Builder(context, wrapper, object : MediaLibrarySession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                        PlaybackService.withStereoSineCommands(session, controller)
                }).build()
            }
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { future = MediaController.Builder(context, session!!.token).buildAsync() }
            controller = future!!.get(20, TimeUnit.SECONDS)
            main {
                val engine = assembly!!.engine!!
                engine.setTransportGain(0.4f)
                assertFalse(controller!!.isCommandAvailable(Player.COMMAND_SET_VOLUME))
                assertFalse(engine.facade.isCommandAvailable(Player.COMMAND_SET_VOLUME))
                assertTrue(controller!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                assertTrue(controller!!.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                listOf(engine.facade, session!!.player, controller!!).forEach {
                    it.volume = 0.9f; it.mute(); it.unmute()
                }
                // An allowed command acts as an ordered barrier after unsupported controller calls.
                controller!!.repeatMode = Player.REPEAT_MODE_ONE
            }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var reached = false
            while (!reached && System.nanoTime() < end) {
                main { reached = assembly!!.currentPlayer.repeatMode == Player.REPEAT_MODE_ONE }
                if (!reached) Thread.sleep(25)
            }
            assertTrue("Allowed controller command did not reach the physical player", reached)
            main {
                val engine = assembly!!.engine!!
                assertEquals(0.4f, engine.currentPlayer.volume, 0f)
                assertEquals(1f, engine.nextPlayer.volume, 0f)
                assertEquals(systemVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                val flags = listOf(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                flags.forEach { assertEquals(engine.currentPlayer.isCommandAvailable(it), engine.facade.isCommandAvailable(it)) }
                engine.swapRolesForTest()
                engine.facade.volume = 0.9f; engine.facade.mute(); engine.facade.unmute()
                assertEquals(0.4f, engine.currentPlayer.volume, 0f)
                assertEquals(1f, engine.nextPlayer.volume, 0f)
            }
        } finally {
            main { controller?.release(); session?.release(); scope.cancel(); assembly?.engine?.release() }
        }
    }
}
