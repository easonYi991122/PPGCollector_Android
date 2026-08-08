package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSidecarTest {
    @Test
    fun metricAndBloodPressureSidecarsRoundTripAndInspectionSeesV2Files() {
        val root = Files.createTempDirectory("sidecar")
        try {
            val writer = CaptureSessionWriter(
                CaptureSessionConfiguration(
                    sessionId = "sidecar-session",
                    baseName = "PPG-subject-1",
                    startedUtc = Instant.parse("2026-08-08T00:00:00Z"),
                    softVersion = "test",
                    algorithmVersion = "test",
                    preprocessProfile = "test",
                    protocolProfile = "cup_v1",
                    transportProfile = "test",
                    device = CaptureDeviceContext("CUP", "id", "service", "notify"),
                ),
                root,
            )
            writer.appendMetricEpoch(
                CaptureMetricEpoch(
                    sessionId = "sidecar-session",
                    connectionGeneration = 4,
                    metricEpoch = 1,
                    sourceSampleIndex = 800,
                    sourceTimeSeconds = 8.0,
                    measuredUtc = Instant.parse("2026-08-08T00:00:08Z"),
                    snapshot = LiveMetricSnapshot.warmingUp(),
                ),
            )
            writer.appendBloodPressure(
                ManualBloodPressureEvent(
                    reference = CaptureReferenceTimestamp(
                        sessionId = "sidecar-session",
                        connectionGeneration = 4,
                        eventIndex = 0,
                        sourceSampleIndex = 812,
                        sourceTimeSeconds = 8.12,
                        dialogOpenHostMonotonicNanoseconds = 1234u,
                        dialogOpenUtc = Instant.parse("2026-08-08T00:00:08Z"),
                    ),
                    savedUtc = Instant.parse("2026-08-08T00:00:09Z"),
                    systolicMmHg = 120,
                    diastolicMmHg = 80,
                ),
            )
            writer.finish(CaptureStopReason.USER)
            val stored = CaptureSessionRepository.listSessions(root).single()
            val files = CaptureSessionRepository.expectedFiles(stored.directory)
            assertTrue(files.metrics != null && Files.isRegularFile(files.metrics))
            assertTrue(files.bloodPressure != null && Files.isRegularFile(files.bloodPressure))
            val inspection = CaptureSessionInspectionService.inspect(stored.directory)
            assertEquals(1L, inspection.metrics?.completeDataRowCount)
            assertEquals(1L, inspection.bloodPressure?.completeDataRowCount)
            assertTrue(inspection.findings.none { it.severity == CaptureInspectionSeverity.ERROR })
            assertEquals(1L, stored.metadata?.writer?.metricsRows)
            assertEquals(1L, stored.metadata?.writer?.bloodPressureRows)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

