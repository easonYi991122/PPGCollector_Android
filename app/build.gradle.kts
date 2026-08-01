import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ppgcollector_android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ppgcollector_android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val releasePrivacySourceDir = layout.projectDirectory.dir("src/main")
val releasePrivacyApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")

/**
 * Release-only REL-005 audit: production code must not emit raw PPG, device
 * identity, filesystem paths, or stack traces through ad-hoc logging, and the
 * compressed APK must not carry test/session fixtures.
 */
tasks.register("verifyReleasePrivacy") {
    dependsOn("assembleRelease")
    notCompatibleWithConfigurationCache("uses a streaming APK/source audit action")
    inputs.dir(releasePrivacySourceDir)
    inputs.file(releasePrivacyApk)

    doLast {
        val loggingPattern = Regex(
            "(?i)\\b(android\\.util\\.Log|Timber|printStackTrace|System\\.(out|err)|println\\s*\\()",
        )
        val forbiddenEntryTokens = listOf(
            "/androidTest/",
            "/test/",
            "fixture",
            "golden",
            ".cupraw",
            ".session.json",
        )
        val sourceViolations = releasePrivacySourceDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { source ->
                if (loggingPattern.containsMatchIn(source.readText())) sequenceOf(source.path)
                else emptySequence()
            }
            .toList()
        check(sourceViolations.isEmpty()) {
            "REL-005 production logging audit failed: ${sourceViolations.joinToString()}"
        }

        val releaseApk = releasePrivacyApk.get().asFile
        check(releaseApk.isFile) { "REL-005 release APK is missing: ${releaseApk.path}" }

        val artifactViolations = ZipFile(releaseApk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { entry ->
                    forbiddenEntryTokens.any { token ->
                        entry.contains(token, ignoreCase = true)
                    }
                }
                .toList()
        }
        check(artifactViolations.isEmpty()) {
            "REL-005 release artifact audit failed: ${artifactViolations.joinToString()}"
        }
        logger.lifecycle(
            "REL-005 privacy audit passed: no production logging APIs and no test/session fixture APK entries",
        )
    }
}
