package com.launchpoint.stereosine.data.backup.wdbk

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

internal data class BackupDocumentIdentity(val authority: String, val documentId: String)

internal data class BackupFolderChild(
    val identity: BackupDocumentIdentity,
    val displayName: String,
    val mimeType: String,
    val modifiedMillis: Long?,
)

internal sealed interface BackupFolderListing {
    data class Safe(val children: List<BackupFolderChild>) : BackupFolderListing
    data class Ambiguous(val reason: String) : BackupFolderListing
    data object Unavailable : BackupFolderListing
}

/** Read-only SAF tree access. An accurate provider listing is a prerequisite, not proof of future write safety. */
internal class SafBackupFolderReader(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) {
    fun listChildren(): BackupFolderListing {
        val authority = treeUri.authority
        if (treeUri.scheme != "content" || authority.isNullOrBlank()) {
            return BackupFolderListing.Ambiguous("Invalid backup tree URI.")
        }
        return try {
            val root = DocumentsContract.getTreeDocumentId(treeUri)
            val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, root)
            resolver.query(queryUri, PROJECTION, null, null, null)?.use { readChildren(it, authority, root) }
                ?: BackupFolderListing.Unavailable
        } catch (_: SecurityException) {
            BackupFolderListing.Unavailable
        } catch (_: IOException) {
            BackupFolderListing.Unavailable
        } catch (_: IllegalArgumentException) {
            BackupFolderListing.Ambiguous("The provider does not support the backup tree query.")
        } catch (_: UnsupportedOperationException) {
            BackupFolderListing.Ambiguous("The provider does not support the backup tree query.")
        }
    }

    fun uriFor(child: BackupFolderChild): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, child.identity.documentId)

    companion object {
        internal const val MAX_CHILDREN = 256
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
        )

        internal fun readChildren(cursor: Cursor, authority: String, rootId: String): BackupFolderListing {
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) {
                return BackupFolderListing.Ambiguous("The provider is still loading the folder listing.")
            }
            if (cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) return BackupFolderListing.Unavailable

            val id = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val modified = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            if (id < 0 || name < 0 || mime < 0) {
                return BackupFolderListing.Ambiguous("The provider omitted required child metadata.")
            }

            val children = mutableListOf<BackupFolderChild>()
            val seen = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                if (children.size == MAX_CHILDREN) {
                    return BackupFolderListing.Ambiguous("The folder exceeds the child listing limit.")
                }
                val childId = cursor.getString(id)
                val displayName = cursor.getString(name)
                val mimeType = cursor.getString(mime)
                if (childId.isNullOrBlank() || childId == rootId || !seen.add(childId) ||
                    displayName.isNullOrBlank() || mimeType.isNullOrBlank()
                ) {
                    return BackupFolderListing.Ambiguous("The folder contains missing or inconsistent child identities.")
                }
                val modifiedMillis = if (modified >= 0 && !cursor.isNull(modified)) {
                    cursor.getString(modified)?.toLongOrNull()?.takeIf { it > 0 }
                } else null
                children += BackupFolderChild(BackupDocumentIdentity(authority, childId), displayName, mimeType, modifiedMillis)
            }
            return BackupFolderListing.Safe(children)
        }

        fun returnedIdentity(uri: Uri): BackupDocumentIdentity? {
            val authority = uri.authority
            if (uri.scheme != "content" || authority.isNullOrBlank()) return null
            val segments = uri.pathSegments
            val isDocument = segments.size == 2 && segments[0] == "document"
            val isTreeDocument = segments.size == 4 && segments[0] == "tree" && segments[2] == "document"
            if (!isDocument && !isTreeDocument) return null
            return try {
                DocumentsContract.getDocumentId(uri).takeIf(String::isNotBlank)
                    ?.let { BackupDocumentIdentity(authority, it) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

internal sealed interface BackupChildQualification {
    data class Safe(val child: BackupFolderChild) : BackupChildQualification
    data object Ambiguous : BackupChildQualification
    data object Unavailable : BackupChildQualification

    companion object {
        /** Compare a provider-returned identity with independent before/after listings. Performs no mutation. */
        fun inspect(
            returned: BackupDocumentIdentity?,
            expectedName: String,
            before: BackupFolderListing,
            after: BackupFolderListing,
        ): BackupChildQualification {
            if (before == BackupFolderListing.Unavailable || after == BackupFolderListing.Unavailable) return Unavailable
            if (returned == null || before !is BackupFolderListing.Safe || after !is BackupFolderListing.Safe ||
                before.children.any { it.identity == returned || it.displayName == expectedName }
            ) return Ambiguous

            val child = after.children.singleOrNull { it.identity == returned } ?: return Ambiguous
            if (child.displayName != expectedName || child.mimeType == Document.MIME_TYPE_DIR ||
                after.children.count { it.displayName == expectedName } != 1
            ) return Ambiguous

            val retained = after.children.associateBy { it.identity }
            if (before.children.any {
                    val listed = retained[it.identity]
                    listed == null || listed.displayName != it.displayName || listed.mimeType != it.mimeType
                }
            ) return Ambiguous
            return Safe(child)
        }
    }
}
