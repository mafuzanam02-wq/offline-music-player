package com.launchpoint.stereosine.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackGainOwnershipTest {
    @Test fun directFacadeCommandsAreUnavailableAndCannotBypassTheComposer() {
        val f = PlayerEngineFixture()
        try {
            f.engine.setTransportGain(0.5f)
            assertFalse(f.facade.isCommandAvailable(Player.COMMAND_SET_VOLUME))
            f.facade.volume = 0.9f
            idleMainLooper()
            assertEquals(0.5f, f.p1.volume, 0f)
            assertEquals(1f, f.p2.volume, 0f)
            f.facade.mute(); idleMainLooper()
            assertEquals(0.5f, f.p1.volume, 0f)
            f.facade.unmute(); idleMainLooper()
            assertEquals(0.5f, f.p1.volume, 0f)
        } finally { f.engine.release() }
    }

    @Test fun connectedControllerCannotBypassAndRetainsTransportAndQueueCommands() {
        val r = PromotionRig().playAndPrepare()
        val f = r.f
        val context = RuntimeEnvironment.getApplication()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        var sessionRef: MediaSession? = null
        val wrapped = PreviousBehaviorPlayer(f.facade, { 3000L }, scope, {},
            { PlayerHydrationResult.NoSavedSession }, { emptyList() }, {}, { sessionRef }, {}, {}, {}, {})
        val session = MediaSession.Builder(context, wrapped).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                PlaybackService.withStereoSineCommands(session, controller)
        }).build().also { sessionRef = it }
        var controller: MediaController? = null
        try {
            val future = MediaController.Builder(context, session.token).setConnectionHints(Bundle()).buildAsync()
            repeat(50) { if (!future.isDone) idleMainLooper() }
            controller = future.get(5, TimeUnit.SECONDS)
            f.engine.setTransportGain(0.5f); idleMainLooper()
            assertFalse(controller.isCommandAvailable(Player.COMMAND_SET_VOLUME))
            listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_REPEAT_MODE).forEach {
                assertTrue(controller.isCommandAvailable(it))
            }
            controller.volume = 0.9f; idleMainLooper()
            assertEquals(0.5f, f.p1.volume, 0f)
            controller.mute(); idleMainLooper()
            assertEquals(0.5f, f.p1.volume, 0f)
            controller.unmute(); idleMainLooper()
            assertEquals(0.5f, f.p1.volume, 0f)
            controller.repeatMode = Player.REPEAT_MODE_ONE; idleMainLooper()
            assertEquals(Player.REPEAT_MODE_ONE, f.p1.repeatMode)
            assertEquals(1f, f.p2.volume, 0f)
            // Reset repeat before the already-prepared promotion; verify the connected controller
            // never gains a unilateral volume path when its delegate changes to incoming B.
            controller.repeatMode = Player.REPEAT_MODE_OFF; idleMainLooper()
            assertTrue(r.promote() is PromotionStartResult.Promoted)
            f.engine.setFadeGains(0.6f, 0.8f); idleMainLooper()
            deny(controller)
            assertEquals(0.3f, f.p1.volume, 0.0001f)
            assertEquals(0.4f, f.p2.volume, 0.0001f)
            f.engine.finishRetirement(); idleMainLooper()
            deny(controller)
            assertEquals(0.5f, f.p2.volume, 0f)
        } finally { controller?.release(); session.release(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); f.engine.release() }
    }

    @Test fun unfilteredMedia3ForwarderStillDemonstratesWhyTheRestrictionIsRequired() {
        val f = PlayerEngineFixture()
        try {
            f.engine.setTransportGain(0.5f)
            val unfiltered = object : ForwardingSimpleBasePlayer(f.p1) {}
            assertTrue(unfiltered.isCommandAvailable(Player.COMMAND_SET_VOLUME))
            unfiltered.volume = 0.9f; idleMainLooper()
            assertEquals(0.9f, f.p1.volume, 0f)
            unfiltered.mute(); idleMainLooper()
            assertEquals(0f, f.p1.volume, 0f)
            unfiltered.unmute(); idleMainLooper()
            assertEquals(0.9f, f.p1.volume, 0f)
        } finally { f.engine.release() }
    }

    private fun deny(player: Player) {
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_VOLUME))
        player.volume = 0.9f; player.mute(); player.unmute(); idleMainLooper()
    }

    @Test fun promotionOverlapCancellationAndDelegateReplacementCannotLeakGain() {
        val r = PromotionRig().playAndPrepare()
        try {
            r.engine.setTransportGain(0.5f)
            deny(r.f.facade)
            assertEquals(0.5f, r.p1.volume, 0f)
            assertEquals(1f, r.p2.volume, 0f)
            assertTrue(r.promote() is PromotionStartResult.Promoted)
            r.engine.setFadeGains(0.6f, 0.8f)
            deny(r.f.facade)
            assertEquals(0.3f, r.p1.volume, 0.0001f)
            assertEquals(0.4f, r.p2.volume, 0.0001f)
            r.engine.finishRetirement()
            deny(r.f.facade)
            assertEquals(0.5f, r.p2.volume, 0f)
            assertEquals(1f, r.p1.volume, 0f)
        } finally { r.engine.release() }
    }

    @Test fun failedPromotionAndQuarantineRetainComposerGains() {
        for (failure in listOf(PromotionStep.StartIncoming, PromotionStep.SwapRoles, PromotionStep.RetireClear)) {
            val r = PromotionRig(hook = { if (it == failure) error("fixture") }).playAndPrepare()
            try {
                r.engine.setTransportGain(0.4f)
                val result = r.promote()
                if (failure == PromotionStep.RetireClear) {
                    assertTrue(result is PromotionStartResult.Promoted)
                    assertFalse(r.engine.finishRetirement())
                    deny(r.f.facade)
                    assertEquals(0.4f, r.p2.volume, 0f)
                    assertEquals(0f, r.p1.volume, 0f)
                } else {
                    assertTrue(result is PromotionStartResult.Failed)
                    deny(r.f.facade)
                    assertEquals(0.4f, r.p1.volume, 0f)
                    assertEquals(1f, r.p2.volume, 0f)
                }
            } finally { r.engine.release() }
        }
    }

    @Test fun focusAndTransportRemainTheGainOwnersAndReleasePreventsLaterWrites() {
        val f = PlayerEngineFixture()
        try {
            f.facade.play(); idleMainLooper()
            f.engine.setTransportGain(0.5f)
            val manager = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val focus = org.robolectric.Shadows.shadowOf(manager).lastAudioFocusRequest!!.listener
            focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK); idleMainLooper()
            deny(f.facade); assertEquals(0.1f, f.p1.volume, 0.0001f)
            focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN); idleMainLooper()
            deny(f.facade); assertEquals(0.5f, f.p1.volume, 0f)
            f.engine.release()
            f.p1.commands.clear(); f.p2.commands.clear()
            deny(f.facade); f.engine.setTransportGain(0.8f)
            assertTrue(f.p1.commands.isEmpty()); assertTrue(f.p2.commands.isEmpty())
        } finally { f.engine.release() }
    }

    @Test fun deviceVolumeFlagsAndHandlersAreIndependentOfPlayerGainDenial() {
        val calls = mutableListOf<String>()
        val device = object : androidx.media3.common.SimpleBasePlayer(android.os.Looper.getMainLooper()) {
            override fun getState() = State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setDeviceInfo(androidx.media3.common.DeviceInfo.Builder(androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_LOCAL)
                    .setMaxVolume(10).build()).setDeviceVolume(5).build()
            override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
                calls += "set:$deviceVolume:$flags"; return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
            override fun handleIncreaseDeviceVolume(flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
                calls += "up:$flags"; return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
            override fun handleDecreaseDeviceVolume(flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
                calls += "down:$flags"; return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
            override fun handleSetDeviceMuted(muted: Boolean, flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
                calls += "mute:$muted:$flags"; return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
        }
        val facade = SessionFacade(device)
        val deviceFlags = listOf(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        deviceFlags.forEach { assertEquals(device.isCommandAvailable(it), facade.isCommandAvailable(it)) }
        deny(facade)
        facade.setDeviceVolume(4, 0); facade.increaseDeviceVolume(0); facade.decreaseDeviceVolume(0)
        facade.setDeviceMuted(true, 0); idleMainLooper()
        assertEquals(listOf("set:4:0", "up:0", "down:0", "mute:true:0"), calls)
    }
}
