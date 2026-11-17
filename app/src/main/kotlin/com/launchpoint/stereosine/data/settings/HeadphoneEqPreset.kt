package com.launchpoint.stereosine.data.settings

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** User library snapshot, never a live reference to a profile or a headphone identity. */
data class HeadphoneEqPreset(val id: String, val name: String, val levels: List<Int>, val frequenciesHz: List<Int>, val version: Int = 1)

data class HeadphonePresetLibrary(
    val presets: List<HeadphoneEqPreset> = emptyList(),
    val writable: Boolean = true,
    val unavailableEntries: Int = 0,
)

enum class HeadphonePresetResult {
    SAVED, COPIED_TO_PROFILE, RENAMED, DELETED, UNCHANGED, INVALID_NAME, LIMIT_REACHED,
    PROFILE_CHANGED, PRESET_CHANGED, CONFIGURATION_CHANGED, CAPABILITIES_UNAVAILABLE,
    CAPABILITIES_CHANGED, INCOMPATIBLE, WRONG_PROFILE, NOT_CUSTOM, STORAGE_UNAVAILABLE,
}

internal object HeadphonePresetCodec {
    const val MAX_NAME = 40
    const val MAX_PRESETS = 32
    const val MAX_BANDS = 128
    const val MAX_BYTES = 65_536
    const val MAX_ENTRIES = 64 // retain malformed entries without unbounded parsing
    fun name(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none(Char::isISOControl) }
    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun validCurve(levels: List<Int>, frequencies: List<Int>): Boolean = levels.size in 1..MAX_BANDS &&
        levels.size == frequencies.size && levels.all { it in Short.MIN_VALUE..Short.MAX_VALUE } &&
        frequencies.all { it in 1..384_000 }

    fun encode(p: HeadphoneEqPreset): JSONObject = JSONObject().apply {
        put("version", p.version); put("id", p.id); put("name", p.name)
        put("levels", JSONArray(p.levels)); put("frequencies", JSONArray(p.frequenciesHz))
    }
    private fun preset(value: Any?): HeadphoneEqPreset? = try {
        val o = value as JSONObject
        require(o.get("version") == 1)
        val id = o.get("id") as String; require(validId(id))
        val n = o.get("name") as String; require(name(n) == n)
        fun ints(key: String): List<Int> {
            val a = o.getJSONArray(key); require(a.length() in 1..MAX_BANDS)
            return (0 until a.length()).map {
                val v = a.get(it); require(v is Number && v.toDouble() == v.toInt().toDouble()); v.toInt()
            }
        }
        val levels = ints("levels"); val frequencies = ints("frequencies")
        require(validCurve(levels, frequencies))
        HeadphoneEqPreset(id, n, levels, frequencies)
    } catch (_: Exception) { null }

    /** Reject excessive bytes/depth before invoking recursive JSON parsing. Quotes/escapes do not count as structure. */
    private fun bounded(raw: String): Boolean {
        if (raw.length > MAX_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 8) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return !quoted && depth == 0
    }

    class Document(val root: JSONObject, val entries: JSONArray, val indexed: Map<String, Pair<Int, HeadphoneEqPreset>>, val library: HeadphonePresetLibrary) {
        fun serialized(): String? = root.toString().takeIf { bounded(it) }
    }
    fun document(raw: String?): Document? = try {
        if (raw != null) require(bounded(raw))
        val root = if (raw == null) JSONObject().put("version", 1).put("entries", JSONArray()) else JSONObject(raw)
        require(root.get("version") == 1)
        val entries = root.getJSONArray("entries"); require(entries.length() <= MAX_ENTRIES)
        val parsed = (0 until entries.length()).map { it to preset(entries.opt(it)) }
        val idCounts = (0 until entries.length()).mapNotNull { (entries.opt(it) as? JSONObject)?.opt("id") as? String }.groupingBy { it }.eachCount()
        val valid = parsed.mapNotNull { (i, p) -> p?.takeIf { idCounts[it.id] == 1 }?.let { it.id to (i to it) } }.toMap()
        val list = valid.values.map { it.second }
        Document(root, entries, valid, HeadphonePresetLibrary(list.take(MAX_PRESETS), list.size <= MAX_PRESETS, entries.length() - list.size))
    } catch (_: Exception) { null }
    fun library(raw: String?): HeadphonePresetLibrary = document(raw)?.library ?: HeadphonePresetLibrary(writable = false)
}

internal fun OutputProfile.supportsHeadphonePresets() = this == OutputProfile.WIRED || this == OutputProfile.BLUETOOTH

internal fun headphoneCurveCompatible(levels: List<Int>, frequencies: List<Int>, caps: EqualizerCapabilities?): Boolean =
    caps != null && HeadphonePresetCodec.validCurve(levels, frequencies) &&
        levels.size == caps.bandCount && frequencies == caps.centerFrequenciesHz &&
        caps.minLevelMb <= caps.maxLevelMb && levels.all { it in caps.minLevelMb..caps.maxLevelMb }

/** Unknown legacy origin may be captured only from a matching, successful API readback of the current curve. */
internal fun headphoneSaveCompatible(s: AudioEnhancementSettings, caps: EqualizerCapabilities?, state: EqualizerApplicationState): Boolean {
    if (caps == null || s.eqPresetType != EqPresetType.CUSTOM || !headphoneCurveCompatible(s.eqCustomBandLevels, caps.centerFrequenciesHz, caps)) return false
    return s.eqCustomBandFrequenciesHz == caps.centerFrequenciesHz ||
        (s.eqCustomBandFrequenciesHz.isEmpty() && state.sessionId != null && state.sessionId > 0 && state.requested == s && state.requestedConfigurationConfirmed && state.reportedBandLevels == s.eqCustomBandLevels)
}
