package com.launchpoint.stereosine.share

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SHARE-APP-01: eligibility (standalone / split / debug), complete copy, identity verification, cleanup and the single-flight gate. */
class AppApkShareTest {

    private lateinit var root: File
    private lateinit var shareDir: File
    private lateinit var apk: File
    private val apkBytes = ByteArray(300_000) { (it * 31 + 7).toByte() }

    private val T0 = 1_700_000_000_000L
    private val signer = setOf("aa11")
    private val running = ApkMetadata("com.launchpoint.stereosine", 10, signer)

    @Before fun setUp() {
        root = Files.createTempDirectory("appshare").toFile()
        shareDir = File(root, "app_share")
        apk = File(root, "base.apk").also { it.writeBytes(apkBytes) }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun info(
        pkg: String = "com.launchpoint.stereosine", versionName: String? = "0.1.0-beta10", code: Long = 10,
        source: String? = apk.absolutePath, splits: List<String> = emptyList(), debuggable: Boolean = false,
    ) = ApkInstallInfo(pkg, versionName, code, source, splits, debuggable)

    private fun preparer(
        reader: ApkMetadataReader = ApkMetadataReader { ApkMetadata("com.launchpoint.stereosine", 10, signer) },
        runningMeta: ApkMetadata? = running,
        free: Long = Long.MAX_VALUE,
        open: (File) -> InputStream = { java.io.FileInputStream(it) },
        clock: () -> Long = { T0 },
        policy: ApkRetentionPolicy = ApkRetentionPolicy(),
        random: () -> String = { java.util.UUID.randomUUID().toString().take(8) },
    ) = AppApkPreparer(shareDir, reader, { runningMeta }, { free }, open, clock, policy, random)

    private fun apks(): List<File> = shareDir.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
    private fun anyFiles(): List<File> = shareDir.walkTopDown().filter { it.isFile }.toList()
    private val hour = 60L * 60 * 1000

    private fun prepare(p: AppApkPreparer, i: ApkInstallInfo = info()) = runBlocking { p.prepare(i) }

    // ---- eligibility ----

    @Test fun standalone_release_is_eligible_as_release() {
        val e = AppSharePolicy.evaluate(info()) as AppShareEligibility.Eligible
        assertEquals(AppShareKind.RELEASE, e.kind)
        assertEquals(apk, e.source)
    }

    @Test fun split_installation_is_refused_even_when_base_is_readable() {
        val e = AppSharePolicy.evaluate(info(splits = listOf("/data/app/x/split_config.arm64_v8a.apk")))
        assertEquals(AppShareEligibility.SplitInstallation, e)
    }

    @Test fun split_installation_never_creates_a_copy() {
        val r = prepare(preparer(), info(splits = listOf("/data/app/x/split_config.en.apk")))
        assertEquals(PrepareResult.Unavailable(AppShareEligibility.SplitInstallation), r)
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun missing_or_blank_source_path_means_no_standalone_apk() {
        assertEquals(AppShareEligibility.NoStandaloneApk, AppSharePolicy.evaluate(info(source = null)))
        assertEquals(AppShareEligibility.NoStandaloneApk, AppSharePolicy.evaluate(info(source = " ")))
    }

    @Test fun nonexistent_or_empty_source_is_unreadable() {
        assertEquals(AppShareEligibility.SourceUnreadable, AppSharePolicy.evaluate(info(source = File(root, "gone.apk").path)))
        val empty = File(root, "empty.apk").also { it.writeBytes(ByteArray(0)) }
        assertEquals(AppShareEligibility.SourceUnreadable, AppSharePolicy.evaluate(info(source = empty.path)))
    }

    @Test fun debug_suffix_or_debuggable_flag_is_development_never_release() {
        val byPackage = AppSharePolicy.evaluate(info(pkg = "com.launchpoint.stereosine.debug")) as AppShareEligibility.Eligible
        val byFlag = AppSharePolicy.evaluate(info(debuggable = true)) as AppShareEligibility.Eligible
        assertEquals(AppShareKind.DEVELOPMENT, byPackage.kind)
        assertEquals(AppShareKind.DEVELOPMENT, byFlag.kind)
    }

    // ---- file name ----

    @Test fun file_name_is_readable_and_sanitized() {
        assertEquals("StereoSine-0.1.0-beta10.apk", AppSharePolicy.fileName(info(), AppShareKind.RELEASE))
        assertEquals("StereoSine-0.1.0-beta10-development.apk", AppSharePolicy.fileName(info(), AppShareKind.DEVELOPMENT))
        val hostile = AppSharePolicy.fileName(info(versionName = "../../evil/\u0000 name?*"), AppShareKind.RELEASE)
        assertTrue(hostile, Regex("StereoSine-[A-Za-z0-9._-]+\\.apk").matches(hostile))
        assertFalse(hostile.contains("/") || hostile.contains(".."))
        assertEquals("StereoSine-v10.apk", AppSharePolicy.fileName(info(versionName = null), AppShareKind.RELEASE))
    }

    // ---- copy completeness ----

    @Test fun successful_copy_is_byte_identical_and_leaves_no_part_file() {
        val r = prepare(preparer()) as PrepareResult.Ready
        assertEquals(AppShareKind.RELEASE, r.kind)
        assertArrayEquals(apkBytes, r.file.readBytes())
        assertEquals(shareDir, r.file.parentFile.parentFile) // <shareDir>/<id>/<apk>: one immutable directory per preparation
        assertEquals(listOf(r.file.name), r.file.parentFile.list()!!.toList())
    }

    @Test fun interrupted_copy_is_never_exposed_and_is_cleaned() {
        val failing = { f: File ->
            object : InputStream() {
                val inner = ByteArrayInputStream(f.readBytes())
                var n = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n++ >= 2) throw IOException("connection lost")
                    return inner.read(b, off, minOf(len, 1000))
                }
            }
        }
        val r = prepare(preparer(open = failing))
        assertEquals(PrepareResult.CopyIncomplete, r)
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun truncated_stream_that_ends_early_is_incomplete() {
        val short = { _: File -> ByteArrayInputStream(apkBytes.copyOf(1234)) as InputStream }
        assertEquals(PrepareResult.CopyIncomplete, prepare(preparer(open = short)))
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun source_changing_during_copy_is_incomplete() {
        val growing = { f: File -> f.appendBytes(byteArrayOf(1)); java.io.FileInputStream(f) as InputStream }
        assertEquals(PrepareResult.CopyIncomplete, prepare(preparer(open = growing)))
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun source_vanishing_before_open_is_source_unreadable() {
        val gone = { f: File -> throw java.io.FileNotFoundException(f.path) }
        assertEquals(PrepareResult.SourceUnreadable, prepare(preparer(open = gone)))
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun security_exception_on_open_is_source_unreadable() {
        val denied = { _: File -> throw SecurityException("denied") }
        assertEquals(PrepareResult.SourceUnreadable, prepare(preparer(open = denied)))
    }

    @Test fun missing_source_file_is_unavailable_not_a_crash() {
        val r = prepare(preparer(), info(source = File(root, "none.apk").path))
        assertEquals(PrepareResult.Unavailable(AppShareEligibility.SourceUnreadable), r)
    }

    @Test fun insufficient_free_space_is_reported_before_copying() {
        assertEquals(PrepareResult.InsufficientStorage, prepare(preparer(free = 1_000)))
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun disk_full_during_write_is_insufficient_storage() {
        val full = { _: File ->
            object : InputStream() {
                override fun read(): Int = throw IOException("write failed: ENOSPC (No space left on device)")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("No space left on device")
            }
        }
        assertEquals(PrepareResult.InsufficientStorage, prepare(preparer(open = full)))
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    // ---- identity ----

    @Test fun different_package_in_copy_is_rejected_and_removed() {
        val other = ApkMetadataReader { ApkMetadata("com.evil.app", 10, signer) }
        assertEquals(PrepareResult.IdentityMismatch, prepare(preparer(reader = other)))
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun different_signing_certificate_is_rejected() {
        val other = ApkMetadataReader { ApkMetadata("com.launchpoint.stereosine", 10, setOf("bb22")) }
        assertEquals(PrepareResult.IdentityMismatch, prepare(preparer(reader = other)))
    }

    @Test fun different_version_is_rejected() {
        val other = ApkMetadataReader { ApkMetadata("com.launchpoint.stereosine", 9, signer) }
        assertEquals(PrepareResult.IdentityMismatch, prepare(preparer(reader = other)))
    }

    @Test fun unparseable_copy_or_unknown_running_identity_is_rejected() {
        assertEquals(PrepareResult.IdentityMismatch, prepare(preparer(reader = { null })))
        assertEquals(PrepareResult.IdentityMismatch, prepare(preparer(runningMeta = null)))
    }

    @Test fun unsigned_copy_is_rejected() {
        val unsigned = ApkMetadataReader { ApkMetadata("com.launchpoint.stereosine", 10, emptySet()) }
        assertEquals(PrepareResult.IdentityMismatch, prepare(preparer(reader = unsigned, runningMeta = running.copy(signerSha256 = emptySet()))))
    }

    @Test fun identity_matches_only_when_all_fields_agree() {
        assertTrue(ApkIdentity.matches(running, running, "com.launchpoint.stereosine"))
        assertFalse(ApkIdentity.matches(running, running, "com.launchpoint.stereosine.debug"))
        assertFalse(ApkIdentity.matches(null, running, "com.launchpoint.stereosine"))
    }

    @Test fun development_build_identity_is_the_debug_package_not_release() {
        val dbg = ApkMetadata("com.launchpoint.stereosine.debug", 10, signer)
        val r = prepare(
            preparer(reader = { dbg }, runningMeta = dbg),
            info(pkg = "com.launchpoint.stereosine.debug", debuggable = true),
        ) as PrepareResult.Ready
        assertEquals(AppShareKind.DEVELOPMENT, r.kind)
        assertTrue(r.file.name.endsWith("-development.apk"))
    }

    // ---- cleanup / accumulation ----

    @Test fun overlapping_shares_do_not_invalidate_a_file_already_handed_to_a_receiver() {
        val a = prepare(preparer()) as PrepareResult.Ready
        val aTime = a.file.lastModified()
        // A receiving app has opened the shared URI and is still reading it.
        val receiver = java.io.FileInputStream(a.file)
        val first = receiver.readNBytes(1000)

        val b = prepare(preparer(clock = { T0 + 1_000 })) as PrepareResult.Ready

        assertTrue(a.file.isFile && a.file.canRead())
        assertArrayEquals(apkBytes, a.file.readBytes())
        assertEquals(aTime, a.file.lastModified())
        assertArrayEquals(apkBytes.copyOf(1000), first)
        assertArrayEquals(apkBytes.copyOfRange(1000, apkBytes.size), receiver.readAllBytes()) // the in-flight read completes
        receiver.close()
        assertTrue(a.file != b.file && a.file.parentFile != b.file.parentFile)
        assertArrayEquals(apkBytes, b.file.readBytes())
        assertEquals(2, apks().size)
    }

    @Test fun a_previously_shared_file_is_never_overwritten_even_on_a_name_collision() {
        val a = prepare(preparer(random = { "same" })) as PrepareResult.Ready
        val before = a.file.readBytes()
        // Same clock and same random token: the second preparation must fail rather than touch the first file.
        val b = prepare(preparer(random = { "same" }))
        assertEquals(PrepareResult.Failed, b)
        assertArrayEquals(before, a.file.readBytes())
        assertEquals(1, apks().size)
    }

    @Test fun different_versions_or_identities_never_share_an_output_path() {
        val debugMeta = ApkMetadata("com.launchpoint.stereosine.debug", 10, signer)
        val v11 = ApkMetadata("com.launchpoint.stereosine", 11, signer)
        val r = prepare(preparer()) as PrepareResult.Ready
        val d = prepare(
            preparer(reader = { debugMeta }, runningMeta = debugMeta),
            info(pkg = "com.launchpoint.stereosine.debug", debuggable = true),
        ) as PrepareResult.Ready
        val n = prepare(preparer(reader = { v11 }, runningMeta = v11), info(versionName = "0.1.0-beta11", code = 11)) as PrepareResult.Ready
        assertEquals(3, setOf(r.file, d.file, n.file).size)
        assertEquals(3, setOf(r.file.parentFile, d.file.parentFile, n.file.parentFile).size)
    }

    @Test fun housekeeping_removes_only_files_eligible_under_the_policy() {
        val old = prepare(preparer(clock = { T0 })) as PrepareResult.Ready
        val mid = prepare(preparer(clock = { T0 + 2 * hour })) as PrepareResult.Ready
        // 25h after the first: only the first is past the 24h retention; the 23h-old one must survive.
        val now = prepare(preparer(clock = { T0 + 25 * hour })) as PrepareResult.Ready
        assertFalse(old.file.exists())
        assertTrue(mid.file.exists() && now.file.exists())
    }

    @Test fun files_younger_than_the_safe_age_are_never_deleted_by_later_preparations() {
        val first = prepare(preparer(clock = { T0 })) as PrepareResult.Ready
        repeat(10) { i -> prepare(preparer(clock = { T0 + (i + 1) * 60_000L })) }
        assertTrue(first.file.exists())
        assertArrayEquals(apkBytes, first.file.readBytes())
    }

    @Test fun cap_reached_with_only_young_files_reuses_the_newest_instead_of_deleting() {
        val policy = ApkRetentionPolicy(maxRetained = 3)
        val files = (0 until 3).map { prepare(preparer(clock = { T0 + it * 1_000L }, policy = policy)) as PrepareResult.Ready }
        val fourth = prepare(preparer(clock = { T0 + 3_000 }, policy = policy)) as PrepareResult.Ready
        assertEquals(files.last().file, fourth.file)
        assertEquals(3, apks().size)
        files.forEach { assertTrue(it.file.exists()) }
    }

    @Test fun cap_evicts_only_oldest_files_already_past_the_safe_age() {
        val policy = ApkRetentionPolicy(maxRetained = 3)
        val files = (0 until 3).map { prepare(preparer(clock = { T0 + it * 1_000L }, policy = policy)) as PrepareResult.Ready }
        val next = prepare(preparer(clock = { T0 + 2 * hour }, policy = policy)) as PrepareResult.Ready
        assertEquals(3, apks().size)
        assertFalse(files[0].file.exists())
        assertTrue(next.file.exists())
    }

    @Test fun storage_stays_within_the_documented_bound_over_many_shares() {
        val policy = ApkRetentionPolicy()
        var max = 0
        repeat(200) { i ->
            prepare(preparer(clock = { T0 + i * 10 * 60_000L }, policy = policy)) // one share every 10 minutes for about 33 hours
            max = maxOf(max, apks().size)
        }
        assertTrue("at most maxRetained copies, was $max", max <= policy.maxRetained)
        assertTrue(anyFiles().sumOf { it.length() } <= (policy.maxRetained + 1) * apkBytes.size.toLong())
    }

    @Test fun stale_partial_directories_are_removed_only_after_retention() {
        shareDir.mkdirs()
        val young = File(shareDir, "${T0 - hour}-crash1").also { it.mkdirs(); File(it, "x.apk.part").writeBytes(byteArrayOf(1)) }
        val stale = File(shareDir, "${T0 - 30 * hour}-crash2").also { it.mkdirs(); File(it, "x.apk.part").writeBytes(byteArrayOf(1)) }
        val loose = File(shareDir, "StereoSine-old.apk").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(T0 - 30 * hour) }
        prepare(preparer(clock = { T0 }))
        assertTrue(young.exists())
        assertFalse(stale.exists())
        assertFalse(loose.exists())
    }

    @Test fun interrupted_preparation_removes_its_partial_file_but_not_earlier_shares() {
        val a = prepare(preparer()) as PrepareResult.Ready
        val failing = { f: File ->
            object : InputStream() {
                val inner = ByteArrayInputStream(f.readBytes())
                var n = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n++ >= 2) throw IOException("connection lost")
                    return inner.read(b, off, minOf(len, 1000))
                }
            }
        }
        assertEquals(PrepareResult.CopyIncomplete, prepare(preparer(open = failing, clock = { T0 + 1_000 })))
        assertEquals(listOf(a.file), anyFiles())
        assertArrayEquals(apkBytes, a.file.readBytes())
    }

    @Test fun cancelling_mid_copy_deletes_the_partial_file() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val slow = { f: File ->
            object : InputStream() {
                val inner = java.io.FileInputStream(f)
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.complete(Unit)
                    Thread.sleep(20)
                    return inner.read(b, off, minOf(len, 100))
                }
                override fun close() = inner.close()
            }
        }
        val job = async(Dispatchers.Default) { preparer(open = slow).prepare(info()) }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    // ---- single flight ----

    @Test fun repeated_taps_enter_the_gate_only_once_until_exit() {
        val gate = SingleFlightGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertFalse(gate.tryEnter())
        gate.exit()
        assertTrue(gate.tryEnter())
    }

    @Test fun concurrent_taps_admit_exactly_one() {
        val gate = SingleFlightGate()
        val admitted = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(16) { Thread { if (gate.tryEnter()) admitted.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, admitted.get())
    }

    @Test fun failure_messages_exist_for_every_non_ready_result_and_never_claim_delivery() {
        val results = listOf(
            PrepareResult.Unavailable(AppShareEligibility.SplitInstallation),
            PrepareResult.Unavailable(AppShareEligibility.NoStandaloneApk),
            PrepareResult.InsufficientStorage, PrepareResult.SourceUnreadable, PrepareResult.CopyIncomplete,
            PrepareResult.IdentityMismatch, PrepareResult.Failed,
        )
        // failureMessage lives in the Android file but is pure string mapping; it is exercised on the JVM.
        results.forEach {
            val m = it.failureMessage()
            assertTrue(it.toString(), !m.isNullOrBlank())
            assertFalse(m!!.contains("sent", ignoreCase = true) || m.contains("installed successfully", ignoreCase = true))
        }
        assertNull(PrepareResult.Ready(File("x"), AppShareKind.RELEASE).failureMessage())
    }
}
