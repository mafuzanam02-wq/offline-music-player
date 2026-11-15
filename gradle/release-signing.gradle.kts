// ── Release signing resolution (StereoSine) ──────────────────────────────────
// Applied by app/build.gradle.kts and exercised directly by ReleaseSigningConfigurationTest.
//
// A signing identity is ONE complete set of four values taken from ONE source:
//   1. keystore.properties at the repo root (gitignored)   storeFile / storePassword / keyAlias / keyPassword
//   2. STEREOSINE_UPLOAD_* environment variables            STORE_FILE / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//   3. WAVDROP_UPLOAD_* environment variables (LEGACY compatibility fallback, same four suffixes)
// Values are NEVER merged across sources (the old per-key "file ?: env" lookup could pair a keystore from one
// place with a password from another). Rules, in order:
//   • No source supplies anything                    → release stays UNSIGNED (safe local default).
//   • A source supplies one to three of the four     → configuration FAILS, naming the missing keys.
//   • Several complete sources that DIFFER           → configuration FAILS, naming the differing keys.
//   • Otherwise the highest-precedence complete source (1 > 2 > 3) is used; identical lower sources are harmless.
// Error messages name keys and sources only; secret values are never echoed. There is no debug-key fallback.
import java.util.Properties

val signingKeyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val signingEnvSuffix = mapOf(
    "storeFile" to "STORE_FILE",
    "storePassword" to "STORE_PASSWORD",
    "keyAlias" to "KEY_ALIAS",
    "keyPassword" to "KEY_PASSWORD",
)

class SigningSource(
    val label: String,
    val values: Map<String, String?>,
    val displayNames: Map<String, String>,
)

fun blankToNull(value: String?): String? = value?.takeIf { it.isNotBlank() }

// Optional override of the properties location (CI or verification runs); default is <root>/keystore.properties.
val signingPropertiesFile: File = providers.gradleProperty("stereosine.keystorePropertiesFile").orNull
    ?.let { rootProject.file(it) }
    ?: rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use { load(it) }
}

fun envSource(label: String, prefix: String) = SigningSource(
    label = label,
    values = signingKeyNames.associateWith { blankToNull(System.getenv("${prefix}_${signingEnvSuffix.getValue(it)}")) },
    displayNames = signingKeyNames.associateWith { "${prefix}_${signingEnvSuffix.getValue(it)}" },
)

val signingSources = listOf(
    SigningSource(
        label = "keystore.properties",
        values = signingKeyNames.associateWith { blankToNull(signingProperties.getProperty(it)) },
        displayNames = signingKeyNames.associateWith { it },
    ),
    envSource("STEREOSINE_UPLOAD_* environment variables", "STEREOSINE_UPLOAD"),
    envSource("WAVDROP_UPLOAD_* environment variables (legacy)", "WAVDROP_UPLOAD"),
)

val configuredSigningSources = signingSources.filter { source -> source.values.values.any { it != null } }

// 1. Every source that supplies anything must be complete on its own.
configuredSigningSources.forEach { source ->
    val missing = signingKeyNames.filter { source.values[it] == null }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Incomplete release signing configuration in ${source.label}: missing " +
                missing.joinToString(", ") { source.displayNames.getValue(it) } + ". " +
                "Each source must supply all four values itself; values are never combined across sources. " +
                "Supply all four, or supply none to build an unsigned release. (Values are intentionally not echoed.)",
        )
    }
}

fun comparable(source: SigningSource, key: String): String? =
    if (key == "storeFile") source.values[key]?.let { rootProject.file(it).absoluteFile.normalize().path } else source.values[key]

// 2. Complete sources must agree with each other.
val selectedSigningSource: SigningSource? = configuredSigningSources.firstOrNull()
if (selectedSigningSource != null) {
    configuredSigningSources.drop(1).forEach { other ->
        val differing = signingKeyNames.filter { comparable(selectedSigningSource, it) != comparable(other, it) }
        if (differing.isNotEmpty()) {
            throw GradleException(
                "Conflicting release signing configuration: ${selectedSigningSource.label} and ${other.label} " +
                    "both define a complete signing identity but differ in: ${differing.joinToString(", ")}. " +
                    "Remove one source or make them identical. (Values are intentionally not echoed.)",
            )
        }
    }
}

val hasReleaseSigningConfig: Boolean = selectedSigningSource != null
val releaseSigningSource: String? = selectedSigningSource?.label
val releaseStoreFile: File? = selectedSigningSource?.values?.get("storeFile")?.let { rootProject.file(it) }
val releaseStorePassword: String? = selectedSigningSource?.values?.get("storePassword")
val releaseKeyAlias: String? = selectedSigningSource?.values?.get("keyAlias")
val releaseKeyPassword: String? = selectedSigningSource?.values?.get("keyPassword")
val releaseStoreFileSetting: String? = selectedSigningSource?.displayNames?.get("storeFile")

extra["hasReleaseSigningConfig"] = hasReleaseSigningConfig
extra["releaseSigningSource"] = releaseSigningSource
extra["releaseStoreFile"] = releaseStoreFile
extra["releaseStorePassword"] = releaseStorePassword
extra["releaseKeyAlias"] = releaseKeyAlias
extra["releaseKeyPassword"] = releaseKeyPassword

// Fail early and clearly if the keystore file is absent, but only when this build will actually sign a release
// artifact (AGP schedules validateSigningRelease for every release packaging task). Debug-only builds and unit
// tests never need the release keystore. Only the file NAME is surfaced, never the full path or any password.
if (releaseStoreFile != null) {
    gradle.taskGraph.whenReady {
        val signsRelease = allTasks.any { it.project == project && it.name == "validateSigningRelease" }
        if (signsRelease && !releaseStoreFile.exists()) {
            throw GradleException(
                "Release keystore file not found: '${releaseStoreFile.name}'. Check ${releaseStoreFileSetting} " +
                    "in ${releaseSigningSource}. The keystore must live outside version control.",
            )
        }
    }
}
