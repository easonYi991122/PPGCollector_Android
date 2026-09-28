package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureSidecarTest {
    @Test
    fun generationResetAndSafeTailShareOneValidationRule() {
        val path = Files.createTempFile("generation", ".csv")
        try {
            fun row(generation: Long, epoch: Long, source: Long): String =
                CaptureMetricSeries.format(CaptureMetricEpoch(
                    "source", generation, epoch, source, source / 100.0,
                    Instant.EPOCH, LiveMetricSnapshot.warmingUp(),
                ))
            val prefix = CaptureMetricSeries.header + row(0, 1, 799) + row(1, 1, 899)
            Files.writeString(path, prefix + row(1, 2, 999).trimEnd())
            val scan = CaptureMetricSeries.scan(path)
            assertEquals(2L, scan.completeDataRowCount)
            assertEquals(prefix.toByteArray().size.toLong(), scan.validByteCount)
            assertEquals(2, CaptureMetricSeries.readTimeline(path).size)
            assertTrue(scan.hasTruncatedFinalLine)
            Files.writeString(path, prefix + row(0, 2, 999))
            assertTrue(CaptureMetricSeries.scan(path).monotonicityError != null)
            assertThrows(IllegalArgumentException::class.java) { CaptureMetricSeries.readTimeline(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun allSidecarsExcludeUnterminatedRowFromEveryCommittedField() {
        val path = Files.createTempFile("safe-tail", ".csv")
        try {
            val bp = ManualBloodPressureEvent(
                CaptureReferenceTimestamp("original", 0, 0, 0, 0.0, 0u, Instant.EPOCH),
                Instant.EPOCH, 120, 80,
            )
            Files.writeString(path, CaptureBloodPressureSeries.header + CaptureBloodPressureSeries.format(bp).trimEnd())
            val pressure = CaptureBloodPressureSeries.scan(path)
            assertEquals(0L, pressure.completeDataRowCount)
            assertEquals(CaptureBloodPressureSeries.header.length.toLong(), pressure.validByteCount)
            assertEquals(null, pressure.sessionId)
            Files.writeString(path, CaptureEcgCsv.header + CaptureEcgCsv.format("original", 0, 0u, 5u).trimEnd())
            val ecg = CaptureEcgCsv.scan(path)
            assertEquals(0L, ecg.completeDataRowCount)
            assertEquals(CaptureEcgCsv.header.length.toLong(), ecg.validByteCount)
            assertEquals(null, ecg.sessionId)
        } finally {
            Files.deleteIfExists(path)
        }
    }

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
                    snapshot = LiveMetricSnapshot.runtime(
                        heartRateBpm = MetricResult.valid(
                            value = 72.0,
                            measuredAt = Instant.parse("2026-08-08T00:00:08Z"),
                            algorithmVersion = "hr-test",
                            sourceSampleIndex = 800,
                            sourceTimeSeconds = 8.0,
                        ),
                        signalQuality = MetricResult.valid(
                            value = 0.91,
                            measuredAt = Instant.parse("2026-08-08T00:00:08Z"),
                            algorithmVersion = "sqi-test",
                            sourceSampleIndex = 800,
                            sourceTimeSeconds = 8.0,
                            isProvisional = true,
                        ),
                        ratioOfRatios = MetricResult.valid(
                            value = 0.52,
                            measuredAt = Instant.parse("2026-08-08T00:00:08Z"),
                            algorithmVersion = "rr-test",
                            sourceSampleIndex = 800,
                            sourceTimeSeconds = 8.0,
                        ),
                        perfusionIndex = MetricResult.valid(
                            value = 1.25,
                            measuredAt = Instant.parse("2026-08-08T00:00:08Z"),
                            algorithmVersion = "pi-test",
                            sourceSampleIndex = 800,
                            sourceTimeSeconds = 8.0,
                        ),
                    ),
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
            val metricTimeline = CaptureMetricSeries.readTimeline(files.metrics!!)
            assertEquals(1, metricTimeline.size)
            assertEquals(800L, metricTimeline.single().sourceSampleIndex)
            assertEquals(8.0, metricTimeline.single().sourceTimeSeconds, 0.0)
            assertEquals(72.0, metricTimeline.single().heartRateBpm!!, 0.0)
            assertEquals(0.91, metricTimeline.single().signalQuality!!, 0.0)
            assertEquals(0.52, metricTimeline.single().ratioOfRatios!!, 0.0)
            assertEquals(1.25, metricTimeline.single().perfusionIndexPercent!!, 0.0)
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

    @Test
    fun metricTimelineRejectsWrongSessionAndNonCursorTime() {
        val path = Files.createTempFile("metrics-invalid", ".csv")
        try {
            val fields = MutableList(CaptureMetricSeries.columns.size) { "" }
            fields[0] = CaptureMetricSeries.schemaVersion
            fields[1] = "other-session"
            fields[2] = "1"
            fields[3] = "0"
            fields[4] = "100"
            fields[5] = "2.0"
            fields[6] = Instant.parse("2026-08-08T00:00:01Z").toString()
            fields[8] = "false"
            fields[13] = "false"
            fields[18] = "false"
            fields[23] = "false"
            Files.writeString(path, CaptureMetricSeries.header + fields.joinToString(",") + "\n")

            assertThrows(IllegalArgumentException::class.java) {
                CaptureMetricSeries.readTimeline(path, expectedSessionId = "expected-session")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
