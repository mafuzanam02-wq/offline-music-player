package com.launchpoint.stereosine.data.settings

import org.json.JSONArray
import org.json.JSONObject

/** Manual sound-setting selection only; never an Android output-device identity. */
enum class OutputProfile(val displayName: String) {
    GLOBAL("Global/default"), SPEAKER("Speaker"), WIRED("Wired"), BLUETOOTH("Bluetooth"),
}

data class OutputProfilesState(
    val active: OutputProfile = OutputProfile.GLOBAL,
    val configurations: Map<OutputProfile, AudioEnhancementSettings> = mapOf(OutputProfile.GLOBAL to AudioEnhancementSettings()),
    val initialized: Boolean = false,
    val invalidSelection: Boolean = false,
) {
    val usingGlobalFallback: Boolean get() = active !in configurations
    val effective: AudioEnhancementSettings get() = configurations[active] ?: configurations.getValue(OutputProfile.GLOBAL)
}

/** Versioned local preferences, separate from the explicit backup format. Invalid blobs are never rewritten on read. */
internal object OutputProfileCodec {
    fun encode(s: AudioEnhancementSettings): String = JSONObject().apply {
        put("version", 1); put("enabled", s.eqEnabled); put("preset", s.eqPresetType.name)
        put("custom", JSONArray(s.eqCustomBandLevels)); put("frequencies", JSONArray(s.eqCustomBandFrequenciesHz))
        put("platformIndex", s.eqPlatformPresetIndex ?: JSONObject.NULL)
        put("stereoSineId", s.eqStereoSinePresetId ?: JSONObject.NULL)
    }.toString()

    fun decode(raw: String?): AudioEnhancementSettings? = try {
        require(raw != null && raw.length <= 16_384)
        val json = JSONObject(raw)
        require(json.get("version") == 1 && json.get("enabled") is Boolean)
        fun levels(key: String): List<Int> {
            val a = json.getJSONArray(key)
            require(a.length() <= 128)
            return (0 until a.length()).map {
                val n = a.get(it)
                require(n is Number && n.toDouble() == n.toInt().toDouble())
                n.toInt()
            }
        }
        val custom = levels("custom")
        val frequencies = levels("frequencies")
        require(frequencies.isEmpty() || (frequencies.size == custom.size && frequencies.all { it > 0 }))
        val index = if (json.isNull("platformIndex")) null else {
            val n = json.get("platformIndex")
            require(n is Number && n.toDouble() == n.toInt().toDouble()); n.toInt()
        }
        val id = if (json.isNull("stereoSineId")) null else json.getString("stereoSineId").also { require(it.length <= 128) }
        AudioEnhancementSettings(json.getBoolean("enabled"), EqPresetType.valueOf(json.getString("preset")), index, custom, id, frequencies)
    } catch (_: Exception) { null }
}
