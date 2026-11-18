package com.launchpoint.stereosine.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.launchpoint.stereosine.data.backup.wdbk.BackupChildQualification
import com.launchpoint.stereosine.data.backup.wdbk.BackupFileHandle
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderChild
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderListing
import com.launchpoint.stereosine.data.backup.wdbk.SafBackupFolderReader
import com.launchpoint.stereosine.data.backup.wdbk.WdbkBackupSaver
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.BackupFileMode
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class FolderPublicationBlocked(message: String) : IOException(message)
internal class FolderCapacityReached : IOException()

/** Called under AutoBackupRepository's serializer. This publication path has no deletion capability. */
internal class FolderBackupPublisher(
    private val context: Context,
    private val settings: AppSettingsRepository,
    private val repository: StereoSineBackupRepository,
) {
    suspend fun publish(tree: Uri, mode: BackupFileMode): Boolean {
        val resolver = context.contentResolver
        val folder = SafBackupFolderReader(resolver, tree)
        val before = folder.qualifiedListing()
        // Leave space for the newly created child in the qualified post-create listing.
        if (before.children.size >= MAX_PUBLICATION_CHILDREN) throw FolderCapacityReached()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val treeKey = digest(sequenceOf(requireNotNull(tree.authority), root))
        reconcileReservation(treeKey, before)

        val installation = repository.getOrCreateInstallationId()
        val owner = ManagedBackupGeneration.ownerFor(UUID.fromString(installation))
        checkAdmission(folder, before, owner)

        val snapshot = repository.buildWdbkExportSnapshot()
        check(snapshot.sourceInstallationId == installation)
        val date = if (mode == BackupFileMode.DATED) {
            Instant.ofEpochMilli(snapshot.exportedAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        } else null
        val lineage = ManagedBackupGeneration.Lineage(owner, date)
        val generation = ManagedBackupGeneration.next(
            lineage, UUID.fromString(snapshot.backupId), before.children.map { it.displayName },
        ) ?: throw FolderPublicationBlocked("Backup generation names conflict. Check the selected folder or choose another one.")

        // Commit before the provider call. An ambiguous create can have side effects even when it returns no URI.
        currentCoroutineContext().ensureActive()
        val originalInventory = inventoryDigest(before.children)
        settings.setFolderCreateReservation(treeKey, "${generation.fileName} $originalInventory")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        currentCoroutineContext().ensureActive()
        val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", generation.fileName)
        val identity = created?.let { SafBackupFolderReader.returnedIdentity(it) }
        val after = folder.qualifiedListing()
        val child = (BackupChildQualification.inspect(identity, generation.fileName, before, after)
            as? BackupChildQualification.Safe)?.child
        if (child == null || inventoryDigest(after.children.filter { it.identity != child.identity }) != originalInventory) {
            throw IOException(AMBIGUOUS_CREATE)
        }

        val targetUri = folder.uriFor(child)
        val target = object : BackupFileHandle {
            override fun openOutput(mode: String) = resolver.openOutputStream(targetUri, mode)
            override fun openInput() = resolver.openInputStream(targetUri)
            override fun delete(): Boolean = error("Folder publication does not delete documents.")
        }
        val empty = target.openInput()?.use { it.read() == -1 } == true
        if (!empty) throw IOException("The provider did not supply an empty new backup document. Choose another folder.")
        currentCoroutineContext().ensureActive()
        WdbkBackupSaver().saveFreshAndVerify(target, snapshot)
        currentCoroutineContext().ensureActive()

        val visible = folder.qualifiedListing()
        val confirmed = BackupChildQualification.inspect(identity, generation.fileName, before, visible)
        if (confirmed !is BackupChildQualification.Safe ||
            inventoryDigest(visible.children.filter { it.identity != child.identity }) != originalInventory
        ) throw IOException("The verified backup is missing from the folder or the listing changed.")
        currentCoroutineContext().ensureActive()
        settings.setFolderCreateReservation(treeKey, null)
        return visible.children.size >= CAPACITY_WARNING_CHILDREN
    }

    private suspend fun reconcileReservation(treeKey: String, listing: BackupFolderListing.Safe) {
        val reservation = settings.folderCreateReservation(treeKey) ?: return
        val name = reservation.substringBefore(' ')
        val original = reservation.substringAfter(' ', "")
        val named = listing.children.filter { it.displayName == name }
        val wasAccountedFor = named.size == 1 && named.single().mimeType != Document.MIME_TYPE_DIR &&
            ManagedBackupGeneration.parse(name) != null &&
            inventoryDigest(listing.children.filter { it.identity != named.single().identity }) == original
        if (inventoryDigest(listing.children) != original && !wasAccountedFor) throw FolderPublicationBlocked(AMBIGUOUS_CREATE)
        settings.setFolderCreateReservation(treeKey, null)
    }

    private suspend fun checkAdmission(
        folder: SafBackupFolderReader,
        listing: BackupFolderListing.Safe,
        owner: String,
    ) {
        var unresolved = 0
        for (child in listing.children) {
            currentCoroutineContext().ensureActive()
            val generation = ManagedBackupGeneration.parse(child.displayName) ?: continue
            if (generation.lineage.owner != owner) continue

            // Historical names and other owners stay outside local admission. A canonical local claim
            // requires independent verification before it can be treated as healthy.
            if (child.mimeType == Document.MIME_TYPE_DIR || !isVerified(folder, child, generation)) {
                unresolved++
                if (unresolved >= MAX_UNRESOLVED) throw FolderPublicationBlocked(ARTIFACT_LIMIT)
            }
        }
    }

    private fun isVerified(
        folder: SafBackupFolderReader,
        child: BackupFolderChild,
        generation: ManagedBackupGeneration,
    ): Boolean {
        val read = StereoSineBackupDocumentReader.readUri(context, folder.uriFor(child))
            as? BackupDocumentReadResult.StereoSine ?: return false
        val backup = read.result.backup ?: return false
        return read.container == BackupContainerKind.WDBK && read.result.integrityStatus == BackupIntegrityStatus.VERIFIED &&
            generation.matchesPayload(backup)
    }

    private fun SafBackupFolderReader.qualifiedListing(): BackupFolderListing.Safe = when (val listing = listChildren()) {
        is BackupFolderListing.Safe -> listing
        is BackupFolderListing.Ambiguous -> throw FolderPublicationBlocked("${listing.reason} Choose a backup folder with a reliable file listing.")
        BackupFolderListing.Unavailable -> throw SecurityException("The backup folder cannot be accessed.")
    }

    private fun inventoryDigest(children: List<BackupFolderChild>): String = digest(
        children.sortedBy { it.identity.documentId }.asSequence().flatMap {
            sequenceOf(it.identity.authority, it.identity.documentId, it.displayName, it.mimeType)
        }
    )

    private fun digest(parts: Sequence<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.encodeToByteArray()
            hash.update("${bytes.size}:".encodeToByteArray())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        internal const val MAX_PUBLICATION_CHILDREN = 64
        internal const val CAPACITY_WARNING_CHILDREN = 56
        private const val MAX_UNRESOLVED = 2
        const val ARTIFACT_LIMIT = "Two local backup attempts could not be verified. Check the files, move incomplete attempts, or choose another folder."
        const val AMBIGUOUS_CREATE = "The provider could not confirm a distinct new backup. Restore the folder listing or choose another folder."
    }
}
