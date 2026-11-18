package com.launchpoint.stereosine.data.backup.wdbk

import com.launchpoint.stereosine.data.backup.BackupSaveValidator
import com.launchpoint.stereosine.data.backup.StereoSineBackup
import com.launchpoint.stereosine.data.backup.wdbk.WdbkTestSupport.MemoryHandle
import com.launchpoint.stereosine.data.backup.wdbk.WdbkTestSupport.mutateManifest
import com.launchpoint.stereosine.data.backup.wdbk.WdbkTestSupport.rezip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

/** Success is reported only after the bytes on disk were re-read through the production reader and matched. */
class WdbkBackupSaverTest {

    private val backup: StereoSineBackup = WdbkTestSupport.fullBackup(eventCount = 40)
    private val saver = WdbkBackupSaver(writer = WdbkWriter(eventsPerChunk = 10))

    private fun WdbkBackupSaver.saveAndVerify(t: BackupFileHandle, b: StereoSineBackup): WdbkWriteReceipt =
        runBlocking { saveAndVerify(t, WdbkExportSnapshot.fromBackup(b)) }

    private fun assertFailsWith(message: String? = null, block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (e: IOException) {
            if (message != null) assertEquals(message, e.message)
        }
    }

    // ── Manual export ─────────────────────────────────────────────────────────

    @Test fun `manual export writes then re-reads and verifies`() {
        val target = MemoryHandle()
        val receipt = saver.saveAndVerify(target, backup)
        assertNotNull(target.content)
        assertEquals(receipt.containerBytes, target.content!!.size.toLong())
        assertEquals(receipt.backupId, backup.backupId)
        assertNull(WdbkTestSupport.read(target.content!!).error)
    }

    @Test fun `falls back from wt to w only when the provider rejects wt`() {
        val target = object : BackupFileHandle {
            val inner = MemoryHandle()
            val tried = mutableListOf<String>()
            override fun openOutput(mode: String) = tried.add(mode).let { if (mode == "wt") null else inner.openOutput(mode) }
            override fun openInput() = inner.openInput()
            override fun delete() = inner.delete()
        }
        saver.saveAndVerify(target, backup)
        assertEquals(listOf("wt", "w"), target.tried)
    }

    @Test fun `no success when the output stream cannot be opened`() {
        val target = MemoryHandle(failOpenOutput = true)
        assertFailsWith("Could not save the backup file. Try a different location.") { saver.saveAndVerify(target, backup) }
        assertNull(target.content)
    }

    @Test fun `no success when the write fails part-way`() {
        val target = MemoryHandle(failWriteAfterBytes = 300)
        assertFailsWith { saver.saveAndVerify(target, backup) }
    }

    @Test fun `no success when the file cannot be read back`() {
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(MemoryHandle(failOpenInput = true), backup) }
    }

    @Test fun `no success when the saved file is corrupt on read-back`() {
        val flipped = MemoryHandle(corruptOnRead = { it.copyOf().also { b -> b[b.size / 2] = (b[b.size / 2].toInt() xor 0x33).toByte() } })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(flipped, backup) }
        val truncated = MemoryHandle(corruptOnRead = { it.copyOf(it.size - 30) })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(truncated, backup) }
    }

    @Test fun `no success on a manifest digest mismatch`() {
        val target = MemoryHandle(corruptOnRead = { bytes ->
            rezip(bytes) { entries ->
                WdbkTestSupport.mutateEntryDescriptor(entries, "sections/songs.json") { it.put("sha256", "1".repeat(64)) }
            }
        })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(target, backup) }
    }

    @Test fun `no success on a semantic fingerprint mismatch`() {
        val target = MemoryHandle(corruptOnRead = { bytes ->
            rezip(bytes) { entries -> mutateManifest(entries) { it.getJSONObject("integrity").put("fingerprint", "a".repeat(64)) } }
        })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(target, backup) }
    }

    @Test fun `a perfectly valid container of a DIFFERENT backup is not accepted as the saved one`() {
        val other = WdbkTestSupport.write(WdbkTestSupport.fullBackup(eventCount = 3).copy(backupId = "someone-else"), 10).first
        val target = MemoryHandle(corruptOnRead = { other })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(target, backup) }
    }

    // ── Folder (automatic) backup ─────────────────────────────────────────────

    @Test fun freshFolderWriteClosesExactlyOnceAndNeverReopensAfterFailure() {
        for (fault in listOf("partial", "close", "cancel")) {
            var opened = 0
            var closed = 0
            var bytes = 0
            val document = object : BackupFileHandle {
                override fun openOutput(mode: String): OutputStream {
                    assertEquals("w", mode)
                    opened++
                    return object : OutputStream() {
                        override fun write(b: Int) {
                            bytes++
                            if (bytes == 32 && fault == "partial") throw IOException("partial output")
                            if (bytes == 32 && fault == "cancel") throw CancellationException("interrupted write")
                        }

                        override fun close() {
                            closed++
                            if (fault == "close") throw IOException("close failed")
                        }
                    }
                }

                override fun openInput() = error("Readback must not follow failed output.")
                override fun delete() = error("Publication must not delete.")
            }
            if (fault == "cancel") {
                try {
                    runBlocking { saver.saveFreshAndVerify(document, WdbkExportSnapshot.fromBackup(backup)) }
                    fail("Expected cancellation")
                } catch (_: CancellationException) {
                    // Expected.
                }
            } else {
                assertFailsWith { runBlocking { saver.saveFreshAndVerify(document, WdbkExportSnapshot.fromBackup(backup)) } }
            }
            assertEquals(1, opened)
            assertEquals(1, closed)
        }
    }

    @Test fun `receipt manifest matches what a real container contains`() {
        val receipt = saver.saveAndVerify(MemoryHandle(), backup)
        val json = JSONObject(String(receipt.manifest.toJsonBytes(), Charsets.UTF_8))
        assertEquals(receipt.fingerprint, json.getJSONObject("integrity").getString("fingerprint"))
    }
}
