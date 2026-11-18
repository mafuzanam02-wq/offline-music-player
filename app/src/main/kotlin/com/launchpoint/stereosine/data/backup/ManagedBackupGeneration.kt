package com.launchpoint.stereosine.data.backup

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Version 1 of the folder-managed name, independent of the WDBK format version. */
internal data class ManagedBackupGeneration(
    val lineage: Lineage,
    val ordinal: Long,
    val backupId: UUID,
) {
    init {
        require(ordinal > 0)
    }

    /** A null date denotes REPLACE_PREVIOUS. A date denotes the DATED group for that day. */
    data class Lineage(val owner: String, val date: LocalDate? = null) {
        init {
            require(OWNER.matches(owner))
            require(date == null || date.year in 1..9999)
        }
    }

    val fileName: String
        get() {
            val scope = lineage.date?.let { "d-$it" } ?: "r"
            return "$PREFIX${lineage.owner}-$scope-$ordinal-$backupId${BackupFileNames.EXTENSION}"
        }

    /** Name and verified payload must agree before discovery trusts lineage ordering. */
    fun matchesPayload(backup: StereoSineBackup): Boolean =
        backup.backupId == backupId.toString() &&
            parseUuid(backup.sourceInstallationId)?.let(::ownerFor) == lineage.owner

    companion object {
        private const val PREFIX = "${BackupFileNames.CURRENT_PREFIX}-g1-"
        private val OWNER = Regex("[0-9a-f]{32}")
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val UUID_TEXT = Regex(UUID_PATTERN)
        private val NAME = Regex(
            "$PREFIX([0-9a-f]{32})-(?:r|d-([0-9]{4}-[0-9]{2}-[0-9]{2}))-([1-9][0-9]{0,18})-($UUID_PATTERN)\\.wdbk"
        )

        fun ownerFor(installationId: UUID): String =
            MessageDigest.getInstance("SHA-256")
                .digest("stereosine/backup-owner/g1/$installationId".encodeToByteArray())
                .take(16)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun parse(name: String?): ManagedBackupGeneration? {
            val match = name?.let(NAME::matchEntire) ?: return null
            val (owner, dateText, ordinalText, backupId) = match.destructured
            val ordinal = ordinalText.toLongOrNull() ?: return null
            val date = if (dateText.isEmpty()) null else try {
                LocalDate.parse(dateText).takeIf { it.year in 1..9999 } ?: return null
            } catch (_: DateTimeParseException) {
                return null
            }
            return ManagedBackupGeneration(Lineage(owner, date), ordinal, UUID.fromString(backupId))
        }

        /** Accepts only an already qualified inventory. Gaps and failed attempts still reserve their ordinals. */
        fun next(lineage: Lineage, backupId: UUID, visibleNames: Iterable<String>): ManagedBackupGeneration? {
            val ordinals = mutableSetOf<Long>()
            val ids = mutableSetOf<UUID>()
            var max = 0L
            for (name in visibleNames) {
                val generation = parse(name) ?: continue
                if (generation.backupId == backupId) return null
                if (generation.lineage != lineage) continue
                if (!ordinals.add(generation.ordinal) || !ids.add(generation.backupId)) return null
                max = maxOf(max, generation.ordinal)
            }
            if (max == Long.MAX_VALUE) return null
            return ManagedBackupGeneration(lineage, max + 1, backupId)
        }

        private fun parseUuid(value: String?): UUID? =
            value?.takeIf(UUID_TEXT::matches)?.let(UUID::fromString)
    }
}
