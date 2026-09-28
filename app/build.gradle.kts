import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
}

val migrationMinSdk = 26
val migrationCompileSdk = 37
val migrationTargetSdk = 37

android {
    namespace = "com.example.ppgcollector_android"
    compileSdk {
        version = release(migrationCompileSdk)
    }

    defaultConfig {
        applicationId = "com.example.ppgcollector_android"
        minSdk = migrationMinSdk
        targetSdk = migrationTargetSdk
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
    testOptions.unitTests.isIncludeAndroidResources = true
    buildFeatures {
        compose = true
    }
}

// Resolve test SDKs through Gradle, without Robolectric writing ~/.m2 or a
// user-home download lock. These artifacts never enter an APK.
val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
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
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Android Studio's JBR is Java 25; Robolectric's older ASM cannot read its classes.
    testRuntimeOnly("org.ow2.asm:asm:9.9")
    testRuntimeOnly("org.ow2.asm:asm-commons:9.9")
    testRuntimeOnly("org.ow2.asm:asm-tree:9.9")
    robolectricSdk("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}

// Review controls are opt-in and affect only forked test JVMs.
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir",
        layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
    providers.gradleProperty("ppgReviewHeap").orNull?.let {
        maxHeapSize = it
        maxParallelForks = 1
    }
    listOf("ppgReviewSwiftFixtures", "ppgReviewGenerateFixtures", "ppgReviewFixtureOutput").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

val releasePrivacySourceDir = layout.projectDirectory.dir("src/main")
val releaseBaselineProfile = layout.projectDirectory.file("src/main/baseline-prof.txt")
val releasePrivacyApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
val releaseMergedManifest = layout.buildDirectory.file(
    "intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
)
val releaseApiContractReport = layout.buildDirectory.file("reports/release-api-contract.txt")
val captureServiceSource = layout.projectDirectory.file(
    "src/main/java/com/example/ppgcollector_android/CaptureForegroundService.kt",
)
val captureViewModelSource = layout.projectDirectory.file(
    "src/main/java/com/example/ppgcollector_android/CaptureServiceViewModel.kt",
)
val releaseBleTransportSource = layout.projectDirectory.file(
    "src/main/java/com/example/ppgcollector_android/core/ble/AndroidBleTransport.kt",
)
val releaseLifecycleContractReport = layout.buildDirectory.file(
    "reports/release-lifecycle-contract.txt",
)
val releaseBleTransportContractReport = layout.buildDirectory.file(
    "reports/release-ble-transport-contract.txt",
)

/**
 * Release REL-006/REL-007 report: validate the merged manifest and the declared
 * API contract together, so dependency manifest changes cannot silently remove
 * the connected-device foreground-service permissions or alter the target API.
 */
tasks.register("verifyReleaseApiContract") {
    val expectedMinSdk = migrationMinSdk
    val expectedCompileSdk = migrationCompileSdk
    val expectedTargetSdk = migrationTargetSdk
    val releaseManifestFile = releaseMergedManifest.get().asFile
    val reportFile = releaseApiContractReport.get().asFile
    dependsOn("processReleaseManifest", "processDebugManifest")
    inputs.property("migrationMinSdk", migrationMinSdk)
    inputs.property("migrationCompileSdk", migrationCompileSdk)
    inputs.property("migrationTargetSdk", migrationTargetSdk)
    inputs.file(releaseMergedManifest)
    val debugMergedManifest = layout.buildDirectory.file("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
    inputs.file(debugMergedManifest)
    outputs.file(releaseApiContractReport)

    doLast {
        for (manifestFile in listOf(debugMergedManifest.get().asFile, releaseManifestFile)) {
            check(manifestFile.isFile) {
                "REL-006/REL-007 merged manifest is missing: ${manifestFile.path}"
            }

            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(manifestFile)
            val androidNamespace = "http://schemas.android.com/apk/res/android"
            fun androidAttribute(node: org.w3c.dom.Node, name: String): String =
                node.attributes.getNamedItemNS(androidNamespace, name)?.nodeValue.orEmpty()
            fun nodes(tagName: String): List<org.w3c.dom.Node> {
                val nodeList = document.getElementsByTagName(tagName)
                return (0 until nodeList.length).map { nodeList.item(it) }
            }

            val manifest = document.documentElement
            val sdk = document.getElementsByTagName("uses-sdk").item(0)
            check(sdk != null) { "REL-006 merged manifest has no uses-sdk" }
            val mergedMinSdk = androidAttribute(sdk, "minSdkVersion")
            val mergedTargetSdk = androidAttribute(sdk, "targetSdkVersion")
            check(mergedMinSdk == expectedMinSdk.toString()) {
                "REL-006 minSdk mismatch: merged=$mergedMinSdk expected=$expectedMinSdk"
            }
            check(mergedTargetSdk == expectedTargetSdk.toString()) {
                "REL-006 targetSdk mismatch: merged=$mergedTargetSdk expected=$expectedTargetSdk"
            }

            val permissions = nodes("uses-permission")
                .asSequence()
                .map { androidAttribute(it, "name") }
                .toSet()
            val requiredPermissions = setOf(
                "android.permission.BLUETOOTH",
                "android.permission.BLUETOOTH_ADMIN",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "android.permission.POST_NOTIFICATIONS",
            )
            check(permissions.containsAll(requiredPermissions)) {
                "REL-007 required permission missing: ${requiredPermissions - permissions}"
            }

            for (permission in listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN")) {
                val node = nodes("uses-permission").single { androidAttribute(it, "name") == permission }
                check(androidAttribute(node, "maxSdkVersion") == "30") {
                    "REL-006 $permission must be capped at API 30 in ${manifestFile.path}"
                }
            }

            val locationPermission = nodes("uses-permission")
                .asSequence()
                .firstOrNull {
                    androidAttribute(it, "name") == "android.permission.ACCESS_FINE_LOCATION"
                }
            check(locationPermission != null) {
                "REL-006 legacy BLE location permission is missing"
            }
            check(androidAttribute(locationPermission, "maxSdkVersion") == "30") {
                "REL-006 legacy location maxSdkVersion must remain 30"
            }

            val scanPermission = nodes("uses-permission")
                .asSequence()
                .firstOrNull {
                    androidAttribute(it, "name") == "android.permission.BLUETOOTH_SCAN"
                }
            check(androidAttribute(scanPermission!!, "usesPermissionFlags") == "neverForLocation") {
                "REL-006 BLUETOOTH_SCAN must retain neverForLocation"
            }

            val services = nodes("service")
            val captureService = services.asSequence().firstOrNull {
                androidAttribute(it, "name").endsWith(".CaptureForegroundService")
            }
            check(captureService != null) { "REL-007 capture foreground service is missing" }
            check(androidAttribute(captureService, "exported") == "false") {
                "REL-007 capture foreground service must remain private"
            }
            check(androidAttribute(captureService, "foregroundServiceType") == "connectedDevice") {
                "REL-007 capture foreground service type must be connectedDevice"
            }

            val report = buildString {
                appendLine("REL-006/REL-007 release API contract")
                appendLine("declared_min_sdk=$expectedMinSdk")
                appendLine("declared_compile_sdk=$expectedCompileSdk")
                appendLine("declared_target_sdk=$expectedTargetSdk")
                appendLine("merged_min_sdk=$mergedMinSdk")
                appendLine("merged_target_sdk=$mergedTargetSdk")
                appendLine("merged_package=${manifest.getAttribute("package")}")
                appendLine("legacy_location_max_sdk=30")
                appendLine("bluetooth_scan_flags=neverForLocation")
                appendLine("capture_service_type=connectedDevice")
                appendLine("capture_service_exported=false")
                appendLine("required_permissions=${requiredPermissions.sorted().joinToString(",")}")
                appendLine("status=passed")
            }
            reportFile.apply {
                parentFile.mkdirs()
                writeText(report)
            }
        }
        logger.lifecycle("REL-006/REL-007 debug/release API contract passed: $reportFile")
    }
}

/**
 * Release REL-003/REL-004 lifecycle report: keep the service's source-level
 * ownership and terminal-state contract visible even before an emulator is
 * available for runtime lifecycle tests.
 */
tasks.register("verifyReleaseLifecycleContract") {
    val serviceFile = captureServiceSource.asFile
    val viewModelFile = captureViewModelSource.asFile
    val reportFile = releaseLifecycleContractReport.get().asFile
    dependsOn("compileReleaseKotlin")
    inputs.file(captureServiceSource)
    inputs.file(captureViewModelSource)
    val lifecycleSource = layout.projectDirectory.file("src/main/java/com/example/ppgcollector_android/CaptureServiceLifecycle.kt")
    inputs.file(lifecycleSource)
    outputs.file(releaseLifecycleContractReport)

    doLast {
        val source = serviceFile.readText()
        val viewModelSource = viewModelFile.readText()
        val requiredFragments = listOf(
            "return START_NOT_STICKY",
            "lifecycle = CaptureServiceLifecycle(",
            "lifecycle.start(",
            "lifecycle.destroy()",
            "bleCoordinator.attachRecordingSink(token, recordingController::onRawChunk)",
            "claimRecordingOwner = bleCoordinator::tryAcquireRecordingOwner",
            "releaseRecordingOwner = bleCoordinator::releaseRecordingOwner",
            "detachSink = bleCoordinator::detachRecordingSink",
            "stopSelfResult(startId)",
            "stopForeground(STOP_FOREGROUND_REMOVE)",
            "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE",
        )
        val missing = requiredFragments.filterNot(source::contains)
        check(missing.isEmpty()) {
            "REL-003/REL-004 service lifecycle contract missing fragments: ${missing.joinToString()}"
        }
        val onDestroyStart = source.indexOf("override fun onDestroy()")
        val onDestroyEnd = source.indexOf("fun snapshot()", onDestroyStart)
        check(onDestroyStart >= 0 && onDestroyEnd > onDestroyStart) {
            "REL-003/REL-004 onDestroy body is missing"
        }
        val onDestroy = source.substring(onDestroyStart, onDestroyEnd)
        check(onDestroy.contains("lifecycle.destroy()") &&
            !onDestroy.contains("serviceScope.cancel()") && !onDestroy.contains("recordingController.close()")) {
            "REL-003/REL-004 destroy must hand cleanup to the retained asynchronous owner"
        }
        val lifecycle = lifecycleSource.asFile.readText()
        val lifecycleFragments = listOf(
            "controller.snapshotFlow.collect(::observe)",
            "withContext(ioDispatcher)",
            "transaction.handoffComplete = true",
            "transaction.stopReason?.let(controller::stop)",
            "observe(controller.snapshot)",
            "snapshot.state == CaptureRecordingState.FINALIZED",
            "snapshot.state == CaptureRecordingState.FAILED",
            "if (active !== transaction) return",
            "if (!claimRecordingOwner(transaction.token))",
            "releaseRecordingOwner(transaction.token)",
            "if (transaction.sinkAttached) detachSink(transaction.token)",
            "stopSelf(transaction.startId)",
            "if (destroyed) releaseOwner()",
            "if (active == null) releaseOwner()",
            "acknowledgeStartId(startId)",
            "terminalObserver.cancel()",
            "scope.cancel()",
        )
        check(lifecycleFragments.all(lifecycle::contains)) {
            "REL-003/REL-004 asynchronous handoff, session isolation or exactly-once cleanup contract missing"
        }
        val requiredFailureFlowFragments = listOf(
            "onFailure = { _runtimeFailure.value = it }",
            "fun runtimeFailureFlow(): StateFlow<CaptureStartFailure?>",
            "runtimeFailure = localBinder.runtimeFailureFlow().value",
            "runtimeFailureJob = observeRuntimeFailure(localBinder)",
            "val runtimeFailure = scopedCaptureRuntimeFailure(",
            "+ listOfNotNull(notificationFailure, runtimeFailure)",
            "serviceClient.clearRuntimeFailure()",
        )
        val missingFailureFlow = requiredFailureFlowFragments.filterNot { fragment ->
            source.contains(fragment) || viewModelSource.contains(fragment)
        }
        check(missingFailureFlow.isEmpty()) {
            "REL-007 runtime failure flow contract missing fragments: ${missingFailureFlow.joinToString()}"
        }

        val report = buildString {
            appendLine("REL-003/REL-004 release lifecycle contract")
            appendLine("service=CaptureForegroundService")
            appendLine("start_mode=START_NOT_STICKY")
            appendLine("terminal_states=FINALIZED,FAILED")
            appendLine("stop_observer=capture_recording_snapshot_flow")
            appendLine("notification_action=immutable_stop_and_save")
            appendLine("on_destroy=retained_owner_waits_for_handoff_and_terminal_then_detaches_sink_removes_foreground_stops_start_id")
            appendLine("runtime_failure=service_stateflow_scoped_to_current_capture_gate")
            appendLine("status=passed")
        }
        reportFile.apply {
            parentFile.mkdirs()
            writeText(report)
        }
        logger.lifecycle(
            "REL-003/REL-004 release lifecycle contract passed: $reportFile",
        )
    }
}

/**
 * Release REL-002 report: ensure every Android GATT ownership exit releases
 * the map, callback bookkeeping, pending CCCD write and platform GATT object.
 */
tasks.register("verifyReleaseBleTransportContract") {
    val transportFile = releaseBleTransportSource.asFile
    val reportFile = releaseBleTransportContractReport.get().asFile
    dependsOn("compileReleaseKotlin")
    inputs.file(releaseBleTransportSource)
    outputs.file(releaseBleTransportContractReport)

    doLast {
        val source = transportFile.readText()
        val requiredFragments = listOf(
            "private val scanTimeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MILLIS",
            "mainHandler.postDelayed(scanTimeout, scanTimeoutMillis)",
            "finishScanning(BleScanStopReason.TIMEOUT)",
            "if (!scanning) return@post",
            "private fun releaseGatt(deviceId: String, gatt: BluetoothGatt, disconnect: Boolean)",
            "if (gattsById[deviceId] !== gatt) {",
            "val ownsSlot = gattsById[deviceId] === gatt",
            "if (ownsSlot) {",
            "connectedIds.remove(deviceId)",
            "pendingDescriptors.remove(gatt)",
            "if (disconnect) runCatching { gatt.disconnect() }",
            "runCatching { gatt.close() }",
            "releaseGatt(deviceId, gatt, disconnect = false)",
            "releaseGatt(it.device.address, it, disconnect = true)",
            "override fun activate()",
            "BluetoothAvailability.UNAUTHORIZED",
        )
        val missing = requiredFragments.filterNot(source::contains)
        check(missing.isEmpty()) {
            "REL-002 Android BLE transport contract missing fragments: ${missing.joinToString()}"
        }
        val notificationStart = source.indexOf("override fun setNotificationsEnabled(")
        val notificationEnd = source.indexOf("fun close()", notificationStart)
        check(notificationStart >= 0 && notificationEnd > notificationStart) {
            "REL-001/REL-002 notification transport body is missing"
        }
        val notificationBody = source.substring(notificationStart, notificationEnd)
        val requiredNotificationFragments = listOf(
            "catch (_: SecurityException)",
            "pendingDescriptors.remove(gatt)",
            "未获得蓝牙连接权限",
            "NotificationStateChanged(deviceId, characteristicUuid, false",
        )
        val missingNotification = requiredNotificationFragments.filterNot(notificationBody::contains)
        check(missingNotification.isEmpty()) {
            "REL-001/REL-002 CCCD permission failure contract missing fragments: ${missingNotification.joinToString()}"
        }
        val report = buildString {
            appendLine("REL-002 Android BLE transport lifecycle contract")
            appendLine("scan_timeout_ms=10000")
            appendLine("late_scan_result=ignored_after_stop")
            appendLine("gatt_release=map+connected_ids+pending_descriptor+BluetoothGatt.close")
            appendLine("replacement_release=before_connecting_new_device")
            appendLine("security_exception_release=disconnect_failure_path")
            appendLine("cccd_permission_failure=notification_state_error")
            appendLine("status=passed")
        }
        reportFile.apply {
            parentFile.mkdirs()
            writeText(report)
        }
        logger.lifecycle(
            "REL-002 Android BLE transport contract passed: $reportFile",
        )
    }
}

/**
 * Release-only REL-005 audit: production code must not emit raw PPG, device
 * identity, filesystem paths, or stack traces through ad-hoc logging, and the
 * compressed APK must not carry test/session fixtures.
 */
tasks.register("verifyReleasePrivacy") {
    dependsOn("assembleRelease")
    dependsOn("verifyReleaseApiContract")
    dependsOn("verifyReleaseLifecycleContract")
    dependsOn("verifyReleaseBleTransportContract")
    notCompatibleWithConfigurationCache("uses a streaming APK/source audit action")
    inputs.dir(releasePrivacySourceDir)
    inputs.file(releaseBaselineProfile)
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

        val releaseEntries = ZipFile(releaseApk).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        val artifactViolations = releaseEntries
            .filter { entry ->
                forbiddenEntryTokens.any { token ->
                    entry.contains(token, ignoreCase = true)
                }
            }
        check(artifactViolations.isEmpty()) {
            "REL-005 release artifact audit failed: ${artifactViolations.joinToString()}"
        }
        check(releaseEntries.any { it == "assets/dexopt/baseline.prof" }) {
            "REL-005 release artifact is missing compiled Baseline Profile"
        }
        logger.lifecycle(
            "REL-005 privacy/profile audit passed: no logging/fixtures and compiled Baseline Profile is packaged",
        )
    }
}
