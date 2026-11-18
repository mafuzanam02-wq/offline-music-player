package com.launchpoint.stereosine.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderChild
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderListing
import com.launchpoint.stereosine.data.backup.wdbk.SafBackupFolderReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Internal discovery for a selected SAF folder. Keeps only metadata from each decoded backup. */
internal object FolderBackupDiscovery {
    const val MAX_CANDIDATES = 16

    data class Candidate(
        val child: BackupFolderChild,
        val integrity: BackupIntegrityStatus,
        val exportedMillis: Long?,
        val warnings: List<String>,
    )

    sealed interface Result {
        data class Complete(
            val selected: Candidate?,
            val candidateCount: Int,
            val warnings: List<String>,
        ) : Result

        data class Incomplete(val reason: String) : Result
        data object Unavailable : Result
    }

    suspend fun discover(context: Context, treeUri: Uri): Result = withContext(Dispatchers.IO) {
        val folder = SafBackupFolderReader(context.contentResolver, treeUri)
        select(folder.listChildren()) { child ->
            StereoSineBackupDocumentReader.readUri(context, folder.uriFor(child))
        }
    }

    internal suspend fun select(
        listing: BackupFolderListing,
        read: (BackupFolderChild) -> BackupDocumentReadResult,
    ): Result {
        val children = when (listing) {
            is BackupFolderListing.Safe -> listing.children
            is BackupFolderListing.Ambiguous -> return Result.Incomplete(listing.reason)
            BackupFolderListing.Unavailable -> return Result.Unavailable
        }
        val candidates = children.filter {
            it.mimeType != Document.MIME_TYPE_DIR && BackupFileNames.isBackupFileName(it.displayName)
        }
        if (candidates.size > MAX_CANDIDATES) {
            return Result.Incomplete("The folder exceeds the backup verification search limit.")
        }
        val claimed = candidates.mapNotNull { ManagedBackupGeneration.parse(it.displayName) }
        val collisions = claimed.groupBy { it.lineage }.filterValues { group ->
            group.map { it.ordinal }.toSet().size != group.size ||
                group.map { it.backupId }.toSet().size != group.size
        }.keys

        val byLineage = mutableMapOf<ManagedBackupGeneration.Lineage, Pair<Long, Candidate>>()
        val independent = mutableListOf<Candidate>()
        var rejected = 0
        var unqualifiedNames = 0
        for (child in candidates) {
            currentCoroutineContext().ensureActive()
            val raw = read(child) as? BackupDocumentReadResult.StereoSine
            val backup = raw?.result?.backup
            if (raw == null || backup == null || raw.result.integrityStatus == BackupIntegrityStatus.INVALID) {
                rejected++
                continue
            }
            val candidate = Candidate(
                child, raw.result.integrityStatus, BackupPresentation.exportTimestampMillis(backup), raw.result.warnings,
            )
            val name = ManagedBackupGeneration.parse(child.displayName)
            if (name != null && name.lineage !in collisions && raw.container == BackupContainerKind.WDBK &&
                candidate.integrity == BackupIntegrityStatus.VERIFIED && name.matchesPayload(backup)
            ) {
                val previous = byLineage[name.lineage]
                if (previous == null || name.ordinal > previous.first) {
                    byLineage[name.lineage] = name.ordinal to candidate
                }
            } else {
                if (name != null) unqualifiedNames++
                independent += candidate
            }
        }
        currentCoroutineContext().ensureActive()

        val selected = (independent + byLineage.values.map { it.second }).maxWithOrNull(
            compareBy<Candidate> { it.integrity == BackupIntegrityStatus.VERIFIED }
                .thenBy { it.exportedMillis ?: it.child.modifiedMillis ?: Long.MIN_VALUE }
                .thenBy { it.exportedMillis != null }
                .thenBy { BackupFileNames.isCurrentBrand(it.child.displayName) }
                .thenBy { it.child.identity.authority }
                .thenBy { it.child.identity.documentId },
        )
        val warnings = buildList {
            if (rejected > 0) add("Some backup files were unreadable, unsupported or invalid.")
            if (unqualifiedNames > 0) add("Some generation names could not be confirmed from their listing and contents.")
            if (selected?.integrity == BackupIntegrityStatus.UNVERIFIED_LEGACY) {
                add("No integrity-verified backup was found. The selected older-format backup is unverified.")
            }
            if (selected != null && selected.exportedMillis == null) {
                add("The selected backup has no usable embedded export date.")
            }
            if (selected != null) addAll(selected.warnings)
        }
        return Result.Complete(selected, candidates.size, warnings)
    }
}
