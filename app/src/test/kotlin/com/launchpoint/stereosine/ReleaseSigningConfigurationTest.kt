package com.launchpoint.stereosine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the REAL `gradle/release-signing.gradle.kts` (the script app/build.gradle.kts applies) inside throw-away
 * fixture Gradle builds. All credentials here are synthetic; no real keystore or password is read or needed.
 */
class ReleaseSigningConfigurationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val repoRoot: File = moduleDir.absoluteFile.normalize().let { if (it.name == "app") it.parentFile else it }
    private val script = File(repoRoot, "gradle/release-signing.gradle.kts")
    private val gradleHome = File(requireNotNull(System.getProperty("stereosine.test.gradleHome")))

    private val signingEnvPrefixes = listOf("STEREOSINE_UPLOAD_", "WAVDROP_UPLOAD_")
    private val secret1 = "synthetic-store-secret-1"
    private val secret2 = "synthetic-key-secret-2"

    private fun props(vararg lines: String): String = lines.joinToString("\n")

    private fun completeProps(alias: String = "alias-props", store: String = "fixture.jks") = props(
        "storeFile=$store", "storePassword=$secret1", "keyAlias=$alias", "keyPassword=$secret2",
    )

    private fun completeEnv(prefix: String, alias: String = "alias-env", store: String = "fixture.jks") = mapOf(
        "${prefix}STORE_FILE" to store,
        "${prefix}STORE_PASSWORD" to secret1,
        "${prefix}KEY_ALIAS" to alias,
        "${prefix}KEY_PASSWORD" to secret2,
    )

    private fun fixture(properties: String? = null): File {
        val dir = tmp.newFolder()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"signing-fixture\"\n")
        File(dir, "build.gradle.kts").writeText(
            """
            apply(from = "${script.absolutePath.replace('\\', '/')}")
            val hasReleaseSigningConfig: Boolean by extra
            val releaseSigningSource: String? by extra
            val releaseKeyAlias: String? by extra
            val releaseStoreFile: File? by extra
            println("RESOLVED has=${'$'}hasReleaseSigningConfig source=${'$'}releaseSigningSource alias=${'$'}releaseKeyAlias store=${'$'}{releaseStoreFile?.name}")
            // Stand-ins for the AGP tasks: a release packaging task and an unrelated (debug-like) one.
            tasks.register("validateSigningRelease")
            tasks.register("debugLikeTask")
            """.trimIndent(),
        )
        if (properties != null) File(dir, "keystore.properties").writeText(properties)
        return dir
    }

    private class BuildResult(val exitCode: Int, val output: String)

    private fun run(dir: File, env: Map<String, String> = emptyMap(), vararg tasks: String, expectFailure: Boolean = false): BuildResult {
        val launcher = File(gradleHome, if (System.getProperty("os.name").startsWith("Windows")) "bin/gradle.bat" else "bin/gradle")
        val command = listOf(launcher.absolutePath, "--no-daemon", "--offline", "--console=plain", "-q") +
            tasks.ifEmpty { arrayOf("debugLikeTask") }
        val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
        // Start from the real environment minus any signing variables, then add this test's synthetic ones.
        builder.environment().apply {
            keys.removeAll { k -> signingEnvPrefixes.any { k.startsWith(it) } }
            put("JAVA_HOME", System.getProperty("java.home"))
            putAll(env)
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        val result = BuildResult(process.waitFor(), output)
        assertTrue("expected ${if (expectFailure) "failure" else "success"}:\n$output", (result.exitCode != 0) == expectFailure)
        // No secret may ever reach build output, success or failure.
        assertFalse(result.output.contains(secret1))
        assertFalse(result.output.contains(secret2))
        return result
    }

    @Test fun `no signing configuration leaves release unsigned`() {
        val out = run(fixture()).output
        assertTrue(out, out.contains("RESOLVED has=false source=null"))
    }

    @Test fun `complete local properties configuration is selected`() {
        val out = run(fixture(completeProps())).output
        assertTrue(out, out.contains("has=true source=keystore.properties alias=alias-props store=fixture.jks"))
    }

    @Test fun `complete STEREOSINE environment configuration is selected`() {
        val out = run(fixture(), completeEnv("STEREOSINE_UPLOAD_")).output
        assertTrue(out, out.contains("has=true source=STEREOSINE_UPLOAD_* environment variables alias=alias-env"))
    }

    @Test fun `complete legacy WAVDROP environment configuration still works`() {
        val out = run(fixture(), completeEnv("WAVDROP_UPLOAD_", alias = "legacy-alias")).output
        assertTrue(out, out.contains("has=true source=WAVDROP_UPLOAD_* environment variables (legacy) alias=legacy-alias"))
    }

    @Test fun `new environment variables take precedence over legacy when identical`() {
        val env = completeEnv("STEREOSINE_UPLOAD_") + completeEnv("WAVDROP_UPLOAD_")
        val out = run(fixture(), env).output
        assertTrue(out, out.contains("source=STEREOSINE_UPLOAD_* environment variables"))
    }

    @Test fun `partial properties configuration fails naming only missing keys`() {
        val result = run(fixture(props("storeFile=fixture.jks", "storePassword=$secret1")), expectFailure = true)
        assertTrue(result.output, result.output.contains("Incomplete release signing configuration in keystore.properties: missing keyAlias, keyPassword"))
    }

    @Test fun `partial new environment configuration fails naming the environment variables`() {
        val env = mapOf("STEREOSINE_UPLOAD_STORE_FILE" to "fixture.jks", "STEREOSINE_UPLOAD_KEY_ALIAS" to "a")
        val result = run(fixture(), env, expectFailure = true)
        assertTrue(result.output, result.output.contains("STEREOSINE_UPLOAD_STORE_PASSWORD, STEREOSINE_UPLOAD_KEY_PASSWORD"))
    }

    @Test fun `credentials are never mixed across sources`() {
        // storeFile from the file, the rest from legacy env: the old per-key lookup would have accepted this.
        val result = run(
            fixture(props("storeFile=fixture.jks")),
            mapOf("WAVDROP_UPLOAD_STORE_PASSWORD" to secret1, "WAVDROP_UPLOAD_KEY_ALIAS" to "a", "WAVDROP_UPLOAD_KEY_PASSWORD" to secret2),
            expectFailure = true,
        )
        assertTrue(result.output, result.output.contains("Incomplete release signing configuration in keystore.properties"))
    }

    @Test fun `a partial source fails even when another source is complete`() {
        val result = run(fixture(props("keyAlias=only-alias")), completeEnv("STEREOSINE_UPLOAD_"), expectFailure = true)
        assertTrue(result.output, result.output.contains("Incomplete release signing configuration in keystore.properties"))
    }

    @Test fun `conflicting complete sources fail naming only the differing keys`() {
        val result = run(fixture(completeProps(alias = "alias-props")), completeEnv("STEREOSINE_UPLOAD_", alias = "alias-env"), expectFailure = true)
        assertTrue(result.output, result.output.contains("Conflicting release signing configuration"))
        assertTrue(result.output, result.output.contains("differ in: keyAlias."))
    }

    @Test fun `identical complete sources are accepted and the highest precedence is reported`() {
        val out = run(fixture(completeProps(alias = "same")), completeEnv("STEREOSINE_UPLOAD_", alias = "same")).output
        assertTrue(out, out.contains("source=keystore.properties alias=same"))
    }

    @Test fun `missing keystore is rejected only when a release signing task is scheduled`() {
        val dir = fixture(completeProps(store = "absent-keystore.jks"))
        val release = run(dir, emptyMap(), "validateSigningRelease", expectFailure = true)
        assertTrue(release.output, release.output.contains("Release keystore file not found: 'absent-keystore.jks'"))
        // A debug-style build with the same complete-but-missing-file configuration must still work.
        val debug = run(dir, emptyMap(), "debugLikeTask").output
        assertTrue(debug, debug.contains("has=true"))
    }

    @Test fun `existing keystore passes release validation`() {
        val dir = fixture(completeProps(store = "present.jks"))
        File(dir, "present.jks").writeText("synthetic placeholder, not a real keystore")
        run(dir, emptyMap(), "validateSigningRelease")
    }

    @Test fun `debug builds work without any release credentials`() {
        val out = run(fixture(), emptyMap(), "debugLikeTask").output
        assertTrue(out, out.contains("has=false"))
    }

    @Test fun `app build script never falls back to the debug key for release`() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertFalse(gradle.contains("getByName(\"debug\")"))
        assertFalse(gradle.contains("debug.keystore"))
        val release = gradle.replace("\r\n", "\n").substringAfter("        release {").substringBefore("    testOptions")
        assertTrue(release.contains("if (hasReleaseSigningConfig) {\n                signingConfig = signingConfigs.getByName(\"release\")"))
        assertFalse(release.contains("signingConfigs.getByName(\"debug\")"))
        assertTrue(File(repoRoot, "gradle/release-signing.gradle.kts").readText().contains("There is no debug-key fallback"))
    }
}
