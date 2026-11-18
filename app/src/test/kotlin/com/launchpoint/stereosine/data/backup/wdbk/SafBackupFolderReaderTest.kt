package com.launchpoint.stereosine.data.backup.wdbk

import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafBackupFolderReaderTest {
    private val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED,
    )
    private val expected = "new.wdbk"
    private val existing = child("existing", "old.wdbk")
    private val fresh = child("fresh", expected)
    private val before = BackupFolderListing.Safe(listOf(existing))

    @Test fun canonicalIdentityUsesAuthorityAndDocumentIdAcrossUriForms() {
        val direct = Uri.parse("content://test.provider/document/id%2Fchild")
        val tree = Uri.parse("content://test.provider/tree/root/document/id%2Fchild")
        assertEquals(BackupDocumentIdentity("test.provider", "id/child"), SafBackupFolderReader.returnedIdentity(direct))
        assertEquals(SafBackupFolderReader.returnedIdentity(direct), SafBackupFolderReader.returnedIdentity(tree))
        assertNull(SafBackupFolderReader.returnedIdentity(Uri.parse("content://test.provider/tree/root")))
        assertNull(SafBackupFolderReader.returnedIdentity(Uri.parse("content://test.provider/document/id/children")))
    }

    @Test fun returnedDocumentMustMatchAnUnambiguousNewDirectChild() {
        val after = BackupFolderListing.Safe(listOf(existing, fresh))
        assertEquals(BackupChildQualification.Safe(fresh), inspect(after))
        assertEquals(BackupChildQualification.Ambiguous, inspect(after, existing.identity))
        assertEquals(BackupChildQualification.Ambiguous, inspect(after, null))
        assertEquals(BackupChildQualification.Ambiguous, inspect(after, fresh.identity.copy(authority = "other.provider")))
        assertEquals(BackupChildQualification.Ambiguous, inspect(before))
        assertEquals(BackupChildQualification.Ambiguous, inspect(BackupFolderListing.Safe(listOf(fresh))))
        assertEquals(
            BackupChildQualification.Ambiguous,
            inspect(BackupFolderListing.Safe(listOf(existing, fresh.copy(displayName = "new (1).wdbk")))),
        )
        assertEquals(
            BackupChildQualification.Ambiguous,
            inspect(BackupFolderListing.Safe(listOf(existing, fresh, child("different-id", expected)))),
        )
        assertEquals(BackupChildQualification.Unavailable, inspect(BackupFolderListing.Unavailable))
    }

    @Test fun incompleteDuplicateOrSelfReferencingRowsAreAmbiguous() {
        for (ids in listOf(listOf(null), listOf("root"), listOf("duplicate", "duplicate"))) {
            MatrixCursor(columns).use { cursor ->
                for (id in ids) cursor.addRow(arrayOf(id, "backup.wdbk", "application/octet-stream", null))
                assertTrue(read(cursor) is BackupFolderListing.Ambiguous)
            }
        }
        MatrixCursor(arrayOf(Document.COLUMN_DISPLAY_NAME)).use {
            assertTrue(read(it) is BackupFolderListing.Ambiguous)
        }
        MatrixCursor(columns).use {
            it.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            assertTrue(read(it) is BackupFolderListing.Ambiguous)
            it.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "offline") }
            assertEquals(BackupFolderListing.Unavailable, read(it))
        }
    }

    @Test fun listingBoundAndInvalidModificationTimesAreExplicit() {
        MatrixCursor(columns).use { cursor ->
            listOf(null, -1, 0, "invalid", 123).forEachIndexed { index, time ->
                cursor.addRow(arrayOf<Any?>("id$index", "same.wdbk", "application/octet-stream", time))
            }
            val safe = read(cursor) as BackupFolderListing.Safe
            assertEquals(listOf(null, null, null, null, 123L), safe.children.map { it.modifiedMillis })
        }
        MatrixCursor(columns).use { cursor ->
            repeat(SafBackupFolderReader.MAX_CHILDREN + 1) {
                cursor.addRow(arrayOf("id$it", "file", "application/octet-stream", null))
            }
            assertTrue(read(cursor) is BackupFolderListing.Ambiguous)
        }
    }

    @Test fun absentProviderIsUnavailableRatherThanAnEmptyListing() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val tree = Uri.parse("content://missing.provider/tree/root")
        assertEquals(BackupFolderListing.Unavailable, SafBackupFolderReader(resolver, tree).listChildren())
    }

    private fun read(cursor: MatrixCursor) = SafBackupFolderReader.readChildren(cursor, "test.provider", "root")

    private fun inspect(after: BackupFolderListing, returned: BackupDocumentIdentity? = fresh.identity) =
        BackupChildQualification.inspect(returned, expected, before, after)

    private fun child(id: String, name: String) =
        BackupFolderChild(BackupDocumentIdentity("test.provider", id), name, "application/octet-stream", null)
}
