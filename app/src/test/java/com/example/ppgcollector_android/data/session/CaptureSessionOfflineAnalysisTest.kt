package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CaptureSessionOfflineAnalysisTest {
    @Test
    fun invalidBloodPressureIsReportedWithoutDiscardingLoadedSignal() {
        withSession(bloodPressureEvent = bloodPressureEvent(2499, 24.99)) { session ->
            val path = CaptureSessionRepository.expectedFiles(session.directory).bloodPressure!!
            Files.writeString(path, CaptureBloodPressureSeries.header + "bogus\n")
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(3_000, trace.rawRed.size)
            assertEquals(3_000, trace.filteredRed.size)
            assertTrue(trace.bloodPressureEvents.isEmpty())
            assertTrue(trace.bloodPressureReadError != null)
        }
    }

    @Test
    fun boundedPreviewKeepsSourceCursorsAndMetricUnavailabilityIsPerMetric() {
        withSession { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session,
                budget = CaptureSignalLoadBudget(32 * 1024 * 1024, 0, maximumPreviewBuckets = 8))
            assertTrue(trace.budgetDegraded)
            assertEquals(3_000L, trace.totalAcceptedSamples)
            assertTrue(trace.rawRed.size <= 54)
            assertEquals(2_999L, trace.sourceSampleIndices.last())
            assertTrue(trace.metricAvailability.values.all { it.unavailableReason != null })
            assertTrue(trace.stages.filterKeys { it != CaptureSignalStage.RAW }.values.all {
                it.state == CaptureSignalStageState.UNAVAILABLE_BUDGET })
        }
        withSession(metricSidecar = metricSidecar(valid = true)) { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(100L, trace.metricAvailability.getValue(CaptureTimelineMetric.HEART_RATE).cadenceSamples)
            assertEquals(CaptureMetricTimelineSource.RECORDED_1_HZ,
                trace.metricAvailability.getValue(CaptureTimelineMetric.HEART_RATE).source)
            assertArrayEquals(longArrayOf(999), trace.metricAvailability.getValue(CaptureTimelineMetric.SQI).unavailableSourceIndices)
            assertEquals(CaptureMetricTimelineSource.UNAVAILABLE, trace.metricAvailability.getValue(CaptureTimelineMetric.SQI).source)
        }
    }

    @Test
    fun artifactIndexReportsInvalidFilesAndCancellationAndChecksSelectedVersion() {
        withSession { session ->
            val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session)
            val summaries = CaptureSessionOfflineAnalysisService.listArtifactSummaries(session)
            assertEquals(1, summaries.size)
            assertEquals(artifact.report.metrics.windowCount.toLong(), summaries.single().windowCount)
            assertEquals(artifact.report.analysisId, CaptureSessionOfflineAnalysisService.readArtifact(
                artifact.path, expectedSourceVersion = summaries.single().sourceVersion).report.analysisId)
            Files.writeString(artifact.path.parent.resolve("broken.json"), "{broken}")
            val indexed = CaptureSessionOfflineAnalysisService.listArtifactSummaries(session)
            assertEquals(2, indexed.size)
            assertEquals(1, indexed.count { it.state == CaptureArtifactReadState.INVALID })
            val cancelled = CancellationException("index")
            org.junit.Assert.assertSame(cancelled, org.junit.Assert.assertThrows(CancellationException::class.java) {
                CaptureSessionOfflineAnalysisService.listArtifactSummaries(session, cancellationCheck = { throw cancelled })
            })
            Files.writeString(artifact.path, CaptureSessionAnalysisCodec.encode(artifact.report.copy(analysisId = "replacement")))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CaptureSessionOfflineAnalysisService.readArtifact(artifact.path, expectedSourceVersion = summaries.single().sourceVersion)
            }
        }
    }

    @Test
    fun rawReplayCreatesImmutableVersionedArtifactsWithoutChangingSources() {
        withSession { session ->
            val files = CaptureSessionRepository.expectedFiles(session.directory)
            val beforeRaw = sha256(files.raw)
            val beforeMetadata = Files.readAllBytes(files.metadata)
            val progress = ArrayList<CaptureSessionAnalysisProgress>()

            val first = CaptureSessionOfflineAnalysisService.analyzeAndSave(
                session,
                progress = progress::add,
                now = fixedClock(
                    Instant.parse("2026-08-02T01:02:03Z"),
                    Instant.parse("2026-08-02T01:02:04Z"),
                ),
            )
            val second = CaptureSessionOfflineAnalysisService.analyzeAndSave(
                session,
                now = fixedClock(
                    Instant.parse("2026-08-02T01:02:05Z"),
                    Instant.parse("2026-08-02T01:02:06Z"),
                ),
            )

            assertNotEquals(first.path, second.path)
            assertTrue(Files.isRegularFile(first.path))
            assertTrue(Files.isRegularFile(second.path))
            assertEquals(beforeRaw, sha256(files.raw))
            assertTrue(beforeMetadata.contentEquals(Files.readAllBytes(files.metadata)))
            assertEquals(2, CaptureSessionOfflineAnalysisService.listArtifacts(session).size)
            assertEquals("ppgcollector_analysis_v1", first.report.schemaVersion)
            assertEquals("ppg-offline-segmented-0.2", first.report.analysisProfile)
            assertEquals("segmented-pulse-0.5-12hz-0.4", first.report.algorithmVersion)
            assertEquals("offline-biquad-filtfilt-0.5-12hz-0.1", first.report.preprocessProfile)
            assertEquals(beforeRaw, first.report.sourceRawSha256)
            assertEquals(3_000L, first.report.input.acceptedSampleCount)
            assertTrue(first.report.metrics.windowCount > 0)
            assertTrue(first.report.metrics.acceptedWindowCount > 0)
            assertTrue(first.report.metrics.heartRateBpm != null)
            assertEquals(200, first.report.averageCycle.phase.size)
            assertEquals(800, first.report.preview.rawRed.size)
            assertEquals(1.0, progress.last().fractionCompleted, 0.0)
            val decoded = CaptureSessionOfflineAnalysisService.readArtifact(first.path).report
            assertEquals(first.report.metrics, decoded.metrics)
            assertEquals(first.report.windows, decoded.windows)
            assertEquals(first.report.sourceRawSha256, decoded.sourceRawSha256)
            assertArrayEquals(first.report.preview.filteredRed, decoded.preview.filteredRed, 0.0)
        }
    }

    @Test
    fun cancellationLeavesNoIncompleteArtifactOrTemporaryFile() {
        withSession { session ->
            var checks = 0
            try {
                CaptureSessionOfflineAnalysisService.analyzeAndSave(
                    session,
                    cancellationCheck = {
                        checks += 1
                        if (checks > 20) throw CancellationException("test cancellation")
                    },
                )
                throw AssertionError("expected cancellation")
            } catch (_: CancellationException) {
                // expected
            }

            val analysisDirectory = session.directory.resolve("analysis")
            assertTrue(CaptureSessionOfflineAnalysisService.listArtifacts(session).isEmpty())
            if (Files.isDirectory(analysisDirectory)) {
                Files.list(analysisDirectory).use { assertFalse(it.findAny().isPresent) }
            }
        }
    }

    @Test
    fun midFrameRawPrefixIsPersistedAsWarningAndNotStructuralDiscard() {
        withSession(leadingPrefixBytes = 28) { session ->
            val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session)

            assertEquals(28, artifact.report.input.leadingAlignmentByteCount)
            assertEquals(0, artifact.report.input.structuralDiscardedByteCount)
            assertTrue(artifact.report.warnings.any { it.contains("28 个前导字节") })
        }
    }

    @Test
    fun completeSignalTraceReplaysAndFiltersEveryAcceptedSample() {
        withSession { session ->
            val files = CaptureSessionRepository.expectedFiles(session.directory)
            val beforeRaw = sha256(files.raw)
            val partial = ArrayList<CaptureSessionSignalTrace>()

            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(
                session,
                onPartial = partial::add,
            )

            assertEquals(3, partial.size)
            assertEquals(3_000, partial.first().rawRed.size)
            assertTrue(partial.first().metricTimeline.isEmpty())
            assertTrue(partial.first().filteredRed.isEmpty())
            assertTrue(partial.first().fixedLagRed.isEmpty())
            assertTrue(partial.all { it.sourceIdentity == trace.sourceIdentity })
            assertEquals(CaptureSignalStageState.READY, partial[1].stages.getValue(CaptureSignalStage.ZERO).state)
            assertTrue(partial.filter { it.filteredRed.isNotEmpty() || it.fixedLagRed.isNotEmpty() }.all {
                it.stages.getValue(CaptureSignalStage.METRICS).state == CaptureSignalStageState.READY
            })
            assertEquals(CaptureSignalStageState.READY, partial[2].stages.getValue(CaptureSignalStage.FIXED).state)
            assertEquals(3_000, trace.timeSeconds.size)
            assertEquals(3_000, trace.rawRed.size)
            assertEquals(3_000, trace.rawIr.size)
            assertEquals(3_000, trace.filteredRed.size)
            assertEquals(3_000, trace.filteredIr.size)
            assertEquals(3_000, trace.fixedLagRed.size)
            assertEquals(3_000, trace.fixedLagIr.size)
            assertTrue(trace.filteredRed.all(Double::isFinite))
            assertTrue(trace.filteredIr.all(Double::isFinite))
            assertEquals(2_800, trace.fixedLagRed.count(Double::isFinite))
            assertEquals(2_800, trace.fixedLagIr.count(Double::isFinite))
            assertEquals(3_000L, trace.replay.acceptedSamples)
            assertEquals("offline-biquad-filtfilt-0.5-12hz-0.1", trace.preprocessProfile)
            assertEquals("fixed-lag-fir-0.5-12hz-0.1", trace.fixedLagProfile)
            assertEquals(beforeRaw, sha256(files.raw))
        }
    }

    @Test
    fun metricSidecarWithWrongSessionIsRejectedBeforeOfflineFallback() {
        withSession(metricSidecar = metricSidecar(valid = true).replace("offline-session-id", "other")) {
            session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)

            assertEquals(PersistedMetricSidecarState.SIDECAR_INVALID, trace.metricTimelineEvidence.persistedState)
            assertTrue(trace.metricTimelineEvidence.detail.contains("session"))
            assertEquals(CaptureMetricTimelineSource.OFFLINE_RECOMPUTED, trace.metricTimelineEvidence.source)
        }
    }

    @Test
    fun singleGapUsesContinuousAcceptedClockWithoutChangingRawEvidence() {
        val sequences = List(150) { index -> if (index < 50) index else index + 1 }
        withSession(sequenceNumbers = sequences) { session ->
            val files = CaptureSessionRepository.expectedFiles(session.directory)
            val beforeRaw = sha256(files.raw)

            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session)

            assertEquals(1, trace.replay.missingFrames)
            assertArrayEquals(intArrayOf(1_000), trace.breakIndices)
            assertEquals(3_000, trace.rawRed.size)
            assertEquals(0.01, trace.timeSeconds[1_000] - trace.timeSeconds[999], 1e-12)
            assertEquals(29.99, trace.timeSeconds.last(), 1e-12)
            assertTrue(trace.filteredRed.all(Double::isFinite))
            assertEquals(2_800, trace.fixedLagRed.count(Double::isFinite))
            assertEquals(CaptureSessionOfflineAnalysisService.analysisSignalProfile, artifact.report.input.analysisSignalProfile)
            assertEquals(1, artifact.report.input.repairGapCount)
            assertEquals(3_000L, artifact.report.input.repairInputSampleCount)
            assertTrue(artifact.report.metrics.acceptedWindowCount > 0)
            assertEquals(beforeRaw, artifact.report.sourceRawSha256)
            assertEquals(beforeRaw, sha256(files.raw))
        }
    }

    @Test
    fun highDensityGapsStillProduceBoundedFiltersWindowsAndAcceptedCursorAlignment() {
        val sequences = List(150) { it * 2 }
        val metric = metricSidecar(valid = true, sourceSampleIndex = 2_500, sourceTimeSeconds = 25.0)
        val bloodPressure = bloodPressureEvent(sourceSampleIndex = 2_500, sourceTimeSeconds = 25.0)
        withSession(
            sequenceNumbers = sequences,
            metricSidecar = metric,
            bloodPressureEvent = bloodPressure,
        ) { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session)

            assertTrue(trace.replay.missingFrames >= 100)
            assertTrue(trace.breakIndices.size >= 100)
            assertEquals(trace.replay.acceptedSamples.toInt(), trace.timeSeconds.size)
            assertEquals((trace.timeSeconds.size - 1) / 100.0, trace.timeSeconds.last(), 1e-12)
            assertTrue(trace.filteredRed.all(Double::isFinite))
            assertTrue(trace.filteredIr.all(Double::isFinite))
            assertEquals(trace.timeSeconds.size - 200, trace.fixedLagRed.count(Double::isFinite))
            assertTrue(artifact.report.metrics.acceptedWindowCount > 0)
            assertEquals(CaptureMetricTimelineSource.RECORDED_1_HZ, trace.metricTimelineEvidence.source)
            assertEquals(25.0, trace.metricTimeline.single().sourceTimeSeconds, 0.0)
            assertEquals(trace.timeSeconds[2_500], trace.metricTimeline.single().sourceTimeSeconds, 0.0)
            assertEquals(25.0, trace.bloodPressureEvents.single().reference.sourceTimeSeconds, 0.0)
            assertEquals(trace.timeSeconds[2_500], trace.bloodPressureEvents.single().reference.sourceTimeSeconds, 0.0)
        }
    }

    @Test
    fun mixedDuplicateOutOfOrderGapAndInvalidFrameKeepOnlyAcceptedSamples() {
        val sequences = listOf(0, 1, 1, 0, 2) + List(145) { it + 4 }
        withSession(sequenceNumbers = sequences, invalidWireAt = 20) { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)

            assertTrue(trace.replay.duplicateFrames >= 1)
            assertTrue(trace.replay.outOfOrderFrames >= 1)
            assertTrue(trace.replay.missingFrames >= 1)
            assertTrue(trace.replay.structurallyInvalidFrames >= 1)
            assertEquals(trace.replay.acceptedSamples.toInt(), trace.rawRed.size)
            assertEquals((trace.rawRed.size - 1) / 100.0, trace.timeSeconds.last(), 1e-12)
            assertTrue(trace.filteredRed.all(Double::isFinite))
            assertEquals(trace.rawRed.size - 200, trace.fixedLagRed.count(Double::isFinite))
        }
    }

    @Test
    fun metricTimelineDistinguishesFourPersistedStatesAndUsesRepairedFallback() {
        withSession(metricSidecar = metricSidecar(valid = true)) { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(PersistedMetricSidecarState.PERSISTED_VALID, trace.metricTimelineEvidence.persistedState)
            assertEquals(CaptureMetricTimelineSource.RECORDED_1_HZ, trace.metricTimelineEvidence.source)
            assertEquals(72.0, trace.metricTimeline.single().heartRateBpm!!, 0.0)
        }
        withSession(metricSidecar = metricSidecar(valid = false)) { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(PersistedMetricSidecarState.PERSISTED_NO_VALID_VALUES, trace.metricTimelineEvidence.persistedState)
            assertEquals(CaptureMetricTimelineSource.OFFLINE_RECOMPUTED, trace.metricTimelineEvidence.source)
            assertTrue(trace.metricTimeline.isNotEmpty())
        }
        withSession { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(PersistedMetricSidecarState.SIDECAR_MISSING, trace.metricTimelineEvidence.persistedState)
            assertEquals(CaptureMetricTimelineSource.OFFLINE_RECOMPUTED, trace.metricTimelineEvidence.source)
            assertTrue(trace.metricTimelineEvidence.detail.contains("不存在"))
        }
        withSession(metricSidecar = "broken header\n") { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(PersistedMetricSidecarState.SIDECAR_INVALID, trace.metricTimelineEvidence.persistedState)
            assertEquals(CaptureMetricTimelineSource.OFFLINE_RECOMPUTED, trace.metricTimelineEvidence.source)
            assertTrue(trace.metricTimelineEvidence.detail.length < 260)
        }
        withSession(sequenceNumbers = List(20) { it }) { session ->
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertEquals(PersistedMetricSidecarState.SIDECAR_MISSING, trace.metricTimelineEvidence.persistedState)
            assertEquals(CaptureMetricTimelineSource.UNAVAILABLE, trace.metricTimelineEvidence.source)
            assertTrue(trace.metricTimeline.isEmpty())
            assertTrue(trace.metricTimelineEvidence.detail.contains("仍无可发布指标"))
        }
    }

    @Test
    fun legacyArtifactWithoutRepairFieldsStillDecodes() {
        withSession { session ->
            val report = CaptureSessionOfflineAnalysisService.analyzeAndSave(session).report
            val legacyJson = CaptureSessionAnalysisCodec.encode(report)
                .lineSequence()
                .filterNot { line ->
                    line.contains("\"analysis_signal_profile\"") ||
                        line.contains("\"repair_gap_count\"") ||
                        line.contains("\"repair_input_sample_count\"")
                }
                .joinToString("\n")

            val decoded = CaptureSessionAnalysisCodec.decode(legacyJson)

            assertNull(decoded.input.analysisSignalProfile)
            assertNull(decoded.input.repairGapCount)
            assertNull(decoded.input.repairInputSampleCount)
        }
    }

    private fun withSession(
        leadingPrefixBytes: Int = 0,
        sequenceNumbers: List<Int> = List(3_000 / CupBatchProtocolV1.samplesPerFrame) { it },
        invalidWireAt: Int? = null,
        metricSidecar: String? = null,
        bloodPressureEvent: ManualBloodPressureEvent? = null,
        block: (StoredCaptureSession) -> Unit,
    ) {
        val root = Files.createTempDirectory("offline-session")
        val directory = root.resolve("capture")
        Files.createDirectory(directory)
        val files = CaptureSessionRepository.expectedFiles(directory)
        val frameCount = sequenceNumbers.size
        try {
            CupRawWriter(files.raw).use { writer ->
                if (leadingPrefixBytes > 0) {
                    val previous = frameWire(255, 255)
                    writer.append(
                        500u,
                        previous.copyOfRange(previous.size - leadingPrefixBytes, previous.size),
                    )
                }
                sequenceNumbers.forEachIndexed { frameIndex, sequence ->
                    if (invalidWireAt == frameIndex) {
                        val invalid = frameWire(frameIndex, sequence).also { it[it.lastIndex] = 0x00 }
                        writer.append((999_000_000L + frameIndex).toULong(), invalid)
                    }
                    writer.append(
                        (1_000_000_000L + frameIndex.toLong() * CupBatchProtocolV1.samplesPerFrame *
                            1_000_000_000L / CupBatchProtocolV1.sampleRateHz).toULong(),
                        frameWire(frameIndex, sequence),
                    )
                }
            }
            Files.writeString(files.csv, CaptureCsvSchema.header)
            Files.writeString(
                files.metadata,
                CaptureSessionMetadataCodec.encode(
                    sampleMetadata(
                        rawChunkCount = frameCount.toLong() + if (leadingPrefixBytes > 0) 1 else 0,
                        frameCount = frameCount.toLong(),
                        sampleCount = (frameCount * CupBatchProtocolV1.samplesPerFrame).toLong(),
                    ),
                ),
            )
            metricSidecar?.let {
                Files.writeString(directory.resolve("capture.metrics.csv"), it)
            }
            bloodPressureEvent?.let {
                Files.writeString(
                    directory.resolve("capture.blood-pressure.csv"),
                    CaptureBloodPressureSeries.header + CaptureBloodPressureSeries.format(it),
                )
            }
            val session = CaptureSessionRepository.listSessions(root).single()
            block(session)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun frameWire(frameIndex: Int, sequence: Int = frameIndex): ByteArray {
        val frameStart = frameIndex * CupBatchProtocolV1.samplesPerFrame
        return encodeCupBatchFrame(
            CupBatchFrame(
                sequence = sequence.toUByte(),
                samples = List(CupBatchProtocolV1.samplesPerFrame) { sampleInFrame ->
                    val sampleIndex = frameStart + sampleInFrame
                    val phase = 2.0 * PI * 1.2 * sampleIndex / CupBatchProtocolV1.sampleRateHz
                    CupPpgSample(
                        red = (100_000.0 + 850.0 * sin(phase)).toUInt(),
                        ir = (120_000.0 + 1_050.0 * sin(phase)).toUInt(),
                    )
                },
            ),
        )
    }

    private fun metricSidecar(
        valid: Boolean,
        sourceSampleIndex: Long = 999,
        sourceTimeSeconds: Double = 9.99,
    ): String {
        val fields = MutableList(CaptureMetricSeries.columns.size) { "" }
        fields[0] = CaptureMetricSeries.schemaVersion
        fields[1] = "offline-session-id"
        fields[2] = "1"
        fields[3] = "1"
        fields[4] = sourceSampleIndex.toString()
        fields[5] = sourceTimeSeconds.toString()
        fields[6] = "2026-08-02T01:00:10Z"
        listOf(8, 13, 18, 23).forEach { fields[it] = "false" }
        listOf(9, 14, 19, 24).forEach { fields[it] = "false" }
        if (valid) {
            fields[7] = "72.0"
            fields[8] = "true"
            fields[10] = "test"
        }
        return CaptureMetricSeries.header + fields.joinToString(",") + "\n"
    }

    private fun bloodPressureEvent(
        sourceSampleIndex: Long,
        sourceTimeSeconds: Double,
    ) = ManualBloodPressureEvent(
        reference = CaptureReferenceTimestamp(
            sessionId = "offline-session-id",
            connectionGeneration = 1,
            eventIndex = 0,
            sourceSampleIndex = sourceSampleIndex,
            sourceTimeSeconds = sourceTimeSeconds,
            dialogOpenHostMonotonicNanoseconds = 1_000u,
            dialogOpenUtc = Instant.parse("2026-08-02T01:00:25Z"),
        ),
        savedUtc = Instant.parse("2026-08-02T01:00:26Z"),
        systolicMmHg = 120,
        diastolicMmHg = 80,
    )

    private fun sampleMetadata(
        rawChunkCount: Long,
        frameCount: Long,
        sampleCount: Long,
    ) = CaptureSessionMetadata(
        schemaVersion = "ppgcollector_session_v1",
        sessionId = "offline-session-id",
        baseName = "capture",
        startedUtc = Instant.parse("2026-08-02T01:00:00Z"),
        endedUtc = Instant.parse("2026-08-02T01:00:30Z"),
        softVersion = "test",
        algVersion = "ppg-live-parity-0.1",
        preprocessProfile = "ios_baseline_0.1",
        protocolProfile = CupBatchProtocolV1.profileIdentifier,
        transportProfile = "cup-nus",
        sampleRateHz = 100,
        samplesPerFrame = CupBatchProtocolV1.samplesPerFrame,
        device = CaptureSessionDeviceMetadata("CUP", "id", "service", "notify", null, null),
        complete = true,
        stopReason = CaptureStopReason.USER,
        frameCount = frameCount,
        sampleCount = sampleCount,
        rawChunkCount = rawChunkCount,
        missingFrames = 0,
        duplicateFrames = 0,
        outOfOrderFrames = 0,
        invalidFrames = 0,
        discardedBytes = 0,
        writer = CaptureSessionWriterMetadata(null, 0, sampleCount, null),
        files = CaptureSessionFilesMetadata("capture.cupraw", "capture.csv"),
        recovery = null,
    )

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(path))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fixedClock(vararg values: Instant): () -> Instant {
        var index = 0
        return { values[minOf(index++, values.lastIndex)] }
    }
}
