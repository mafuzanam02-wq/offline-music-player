package com.launchpoint.stereosine.ui.screen.settings

import com.launchpoint.stereosine.data.backup.BackupIntegrityStatus
import com.launchpoint.stereosine.data.backup.BackupVerificationResult
import com.launchpoint.stereosine.data.backup.VerificationSearchStatus
import com.launchpoint.stereosine.data.backup.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupVerificationUiTest {
    private val partial = BackupVerificationResult(
        status = VerificationStatus.PARTIAL,
        fileName = "wavdrop-backup.wdbk",
        integrityStatus = BackupIntegrityStatus.VERIFIED,
        searchStatus = VerificationSearchStatus.COMPLETE,
    )

    @Test fun verifiedIntegrityRemainsVisibleWithPartialCoverage() {
        assertEquals("Newest verified backup has partial data", partial.verificationTitle())
        assertEquals("Verified", partial.verificationIntegrityLabel())
        assertEquals("Complete", partial.verificationSearchLabel())
    }

    @Test fun incompleteSearchCannotClaimTheNewestCandidate() {
        val incomplete = partial.copy(searchStatus = VerificationSearchStatus.INCOMPLETE)
        assertEquals("Backup search incomplete", incomplete.verificationTitle())
        assertEquals("Verified", incomplete.verificationIntegrityLabel())
        assertTrue(incomplete.verificationSearchLabel()!!.contains("Other backups may be newer"))
    }

    @Test fun legacyFallbackIsQualifiedEvenWhenCoreDataIsPresent() {
        val legacy = partial.copy(
            status = VerificationStatus.VERIFIED,
            integrityStatus = BackupIntegrityStatus.UNVERIFIED_LEGACY,
        )
        assertEquals("Older backup found", legacy.verificationTitle())
        assertEquals("Older format, unverified", legacy.verificationIntegrityLabel())
    }
}
