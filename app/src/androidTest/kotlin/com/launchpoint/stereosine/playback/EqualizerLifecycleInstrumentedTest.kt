package com.launchpoint.stereosine.playback

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.stereosine.data.settings.AudioEnhancementsRepository
import com.launchpoint.stereosine.data.settings.EqualizerApplicationIssue
import com.launchpoint.stereosine.data.settings.EqualizerApplicationStatus.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic media and an isolated settings store. Never resets app data or changes system volume. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class EqualizerLifecycleInstrumentedTest {
    @Test fun realSessionLifecycleAndUnavailableEqLeavePlaybackRunning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = mutableListOf<File>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = File(context.cacheDir, "listen-01-${System.nanoTime()}.preferences_pb").also(files::add)
        val repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = dataScope) { preferences })
        var player: ExoPlayer? = null
        var controller: AudioEnhancementController? = null
        var competing: Equalizer? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < end) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                Thread.sleep(25)
            }
            throw AssertionError("Timed out; EQ state=${repo.applicationState.value}")
        }
        try {
            val wav = File(context.cacheDir, "listen-01-${System.nanoTime()}.wav").also(files::add)
            val dataSize = 60 * 16_000 * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
            wav.outputStream().use { out -> out.write(header); repeat(60) { out.write(ByteArray(32_000)) } }
            main {
                player = ExoPlayer.Builder(context).build().also {
                    it.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
                    it.prepare(); it.play()
                }
            }
            await { player!!.isPlaying && player!!.audioSessionId > 0 }
            main { controller = AudioEnhancementController(repo, scope).also { it.attach(player!!) } }
            runBlocking { repo.setEqEnabled(true) }
            await { repo.applicationState.value.status in setOf(APPLIED, FAILED, UNAVAILABLE) }
            val initial = repo.applicationState.value
            Log.i("StereoSineListen01", "REAL_APPLICATION=$initial capabilities=${repo.capabilities.value}")
            if (initial.status == APPLIED) {
                assertNotNull(initial.reportedBandLevels)
                main { competing = Equalizer(100, player!!.audioSessionId) }
                await { EqualizerApplicationIssue.CONTROL_UNAVAILABLE in repo.applicationState.value.issues }
                main { competing!!.release(); competing = null }
                await { repo.applicationState.value.status == APPLIED }
            } else {
                assertFalse(initial.issues.isEmpty())
                Log.i("StereoSineListen01", "Control competition not exercised: emulator effect was not confirmed")
            }
            main {
                assertTrue(player!!.isPlaying)
                controller!!.onAudioSessionIdChanged(0)
                assertEquals(PENDING, repo.applicationState.value.status)
                assertNull(repo.capabilities.value)
                controller!!.onAudioSessionIdChanged(player!!.audioSessionId)
            }
            await { repo.applicationState.value.status in setOf(APPLIED, FAILED, UNAVAILABLE) }
            main {
                controller!!.release(); controller!!.release()
                assertNull(repo.capabilities.value)
                assertTrue(player!!.isPlaying)
                controller = AudioEnhancementController(repo, scope) { throw UnsupportedOperationException("fixture") }
                    .also { it.attach(player!!) }
            }
            await { EqualizerApplicationIssue.UNSUPPORTED in repo.applicationState.value.issues }
            main {
                assertTrue(player!!.isPlaying)
                assertEquals(1, player!!.mediaItemCount)
            }
            assertTrue(runBlocking { repo.settings.first().eqEnabled })
        } finally {
            main { competing?.release(); controller?.release(); player?.release() }
            scope.cancel()
            runBlocking { dataScope.coroutineContext[Job]!!.cancelAndJoin() }
            files.forEach { check(it.delete() || !it.exists()) }
        }
    }
}
