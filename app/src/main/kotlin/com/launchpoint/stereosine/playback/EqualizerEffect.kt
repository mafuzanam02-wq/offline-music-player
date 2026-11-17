package com.launchpoint.stereosine.playback

import android.media.audiofx.Equalizer

/** Only the Android calls the controller owns; permits lifecycle/failure tests without emulating DSP. */
internal interface EqualizerEffect {
    val numberOfBands: Int
    val bandLevelRange: ShortArray
    val numberOfPresets: Int
    val currentPreset: Int
    val enabled: Boolean
    fun hasControl(): Boolean
    fun getPresetName(index: Short): String
    fun getCenterFreq(band: Short): Int
    fun setBandLevel(band: Short, level: Short)
    fun getBandLevel(band: Short): Int
    fun usePreset(index: Short)
    fun setEnabled(enabled: Boolean): Int
    fun setControlListener(listener: (Boolean) -> Unit)
    fun setEnableListener(listener: (Boolean) -> Unit)
    fun release()
}

internal class AndroidEqualizerEffect(sessionId: Int) : EqualizerEffect {
    private val effect = Equalizer(0, sessionId)
    override val numberOfBands get() = effect.numberOfBands.toInt()
    override val bandLevelRange get() = effect.bandLevelRange
    override val numberOfPresets get() = effect.numberOfPresets.toInt()
    override val currentPreset get() = effect.currentPreset.toInt()
    override val enabled get() = effect.enabled
    override fun hasControl() = effect.hasControl()
    override fun getPresetName(index: Short): String = effect.getPresetName(index)
    override fun getCenterFreq(band: Short) = effect.getCenterFreq(band)
    override fun setBandLevel(band: Short, level: Short) = effect.setBandLevel(band, level)
    override fun getBandLevel(band: Short) = effect.getBandLevel(band).toInt()
    override fun usePreset(index: Short) = effect.usePreset(index)
    override fun setEnabled(enabled: Boolean) = effect.setEnabled(enabled)
    override fun setControlListener(listener: (Boolean) -> Unit) =
        effect.setControlStatusListener { _, granted -> listener(granted) }
    override fun setEnableListener(listener: (Boolean) -> Unit) =
        effect.setEnableStatusListener { _, enabled -> listener(enabled) }
    override fun release() = effect.release()
}
