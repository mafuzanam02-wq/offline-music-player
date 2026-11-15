plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ── Release signing ───────────────────────────────────────────────────────────
// Secrets NEVER live in Git. The resolution rules (sources, precedence, partial/conflict failures, missing-keystore
// check) live in gradle/release-signing.gradle.kts, which is also exercised by ReleaseSigningConfigurationTest.
// Summary: ONE complete identity from keystore.properties, else STEREOSINE_UPLOAD_*, else legacy WAVDROP_UPLOAD_*
// environment variables; none supplied → unsigned release; incomplete or conflicting → configuration failure.
// There is no fallback to the debug key for release.
apply(from = rootProject.file("gradle/release-signing.gradle.kts"))
val hasReleaseSigningConfig: Boolean by extra
val releaseStoreFile: File? by extra
val releaseStorePassword: String? by extra
val releaseKeyAlias: String? by extra
val releaseKeyPassword: String? by extra

// ── P5-F validation variant (debug only) ─────────────────────────────────────
// `-PstereosineValidationBounded=true` compiles the bounded physical queue ON into the DEBUG build only, so the owner can A/B the
// same baseline on a device. It is a build-time constant (never a setting), hard-coded false in the release BuildConfig, and a
// release task combined with the property fails the build. The committed production gate
// (PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED) is not touched.
// `-PwavdropValidationBounded` is the pre-rebrand spelling; it stays accepted as a compatibility alias so existing owner invocations keep working.
val validationBounded = (providers.gradleProperty("stereosineValidationBounded").orNull
    ?: providers.gradleProperty("wavdropValidationBounded").orNull) == "true"
if (validationBounded) {
    gradle.taskGraph.whenReady {
        val release = allTasks.firstOrNull { it.project == project && it.name.contains("Release", ignoreCase = true) }
        if (release != null) {
            throw GradleException("stereosineValidationBounded (alias wavdropValidationBounded) is a debug-only validation switch; refusing to run ${release.name}.")
        }
    }
}

android {
    namespace = "com.launchpoint.stereosine"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.launchpoint.stereosine"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.0-beta10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created only when real credentials are supplied outside Git. Absent
        // otherwise, so the release build produces an unsigned artifact.
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "STEREOSINE_VALIDATION_BOUNDED", validationBounded.toString())
        }
        release {
            buildConfigField("boolean", "STEREOSINE_VALIDATION_BOUNDED", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Attach the release signing config only when it exists. When it does
            // not, the build stays unsigned rather than silently using the debug key.
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests {
            // Makes Android API stubs return safe defaults (0/null/false) in JVM unit tests
            // instead of throwing RuntimeException. Required for Log.d and similar calls.
            isReturnDefaultValues = true
            // ReleaseSigningConfigurationTest runs fixture builds with this Gradle installation (CLI; no TestKit on the test classpath).
            all { it.systemProperty("stereosine.test.gradleHome", gradle.gradleHomeDir!!.absolutePath) }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    sourceSets {
        // Make exported Room schema JSON files available to instrumentation tests
        // so MigrationTestHelper can validate migrations against the known schema.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    kapt {
        correctErrorTypes = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.room.compiler.processing)
    kapt(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Real org.json for JVM unit tests: the Android stubs return null/0 under
    // isReturnDefaultValues, which would silently break exporter round-trip tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
