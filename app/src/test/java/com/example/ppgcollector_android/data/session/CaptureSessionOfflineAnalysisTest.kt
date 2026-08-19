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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CaptureSessionOfflineAnalysisTest {
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

            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)

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

    private fun withSession(
        leadingPrefixBytes: Int = 0,
        block: (StoredCaptureSession) -> Unit,
    ) {
        val root = Files.createTempDirectory("offline-session")
        val directory = root.resolve("capture")
        Files.createDirectory(directory)
        val files = CaptureSessionRepository.expectedFiles(directory)
        val frameCount = 3_000 / CupBatchProtocolV1.samplesPerFrame
        try {
            CupRawWriter(files.raw).use { writer ->
                if (leadingPrefixBytes > 0) {
                    val previous = frameWire(255)
                    writer.append(
                        500u,
                        previous.copyOfRange(previous.size - leadingPrefixBytes, previous.size),
                    )
                }
                repeat(frameCount) { frameIndex ->
                    writer.append(
                        (1_000_000_000L + frameIndex.toLong() * CupBatchProtocolV1.samplesPerFrame *
                            1_000_000_000L / CupBatchProtocolV1.sampleRateHz).toULong(),
                        frameWire(frameIndex),
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
            val session = CaptureSessionRepository.listSessions(root).single()
            block(session)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun frameWire(frameIndex: Int): ByteArray {
        val frameStart = frameIndex * CupBatchProtocolV1.samplesPerFrame
        return encodeCupBatchFrame(
            CupBatchFrame(
                sequence = frameIndex.toUByte(),
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
