package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.*
import com.example.ppgcollector_android.core.signal.OfflinePulseWindow
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReviewLowHeapContractTest {
    @Test
    fun millionAndHalfSamplesWithoutMetricsUseBoundedPreviewBeforeFullAllocation() {
        println("ReviewLowHeapContractTest trace maxMemory=${Runtime.getRuntime().maxMemory()}")
        val root = Files.createTempDirectory("review-low-heap-trace")
        try {
            val writer = CaptureSessionWriter(ReviewSessionFixtures.configuration("PPG-LONG-1"), root)
            writer.finish(CaptureStopReason.USER)
            val files = CaptureSessionRepository.expectedFiles(writer.directory)
            Files.delete(files.raw)
            CupRawWriter(files.raw).use { raw ->
                repeat(75_000) { frameIndex ->
                    val sequence = (frameIndex + if (frameIndex >= 40_000) 1 else 0).toUByte()
                    val frame = CupBatchFrame(sequence, List(20) { offset ->
                        val source = frameIndex * 20 + offset
                        CupPpgSample((100_000 + source).toUInt(), (2_000_000 - source).toUInt())
                    })
                    raw.append(frameIndex.toULong(), encodeCupBatchFrame(frame))
                }
            }
            val metadata = CaptureSessionMetadataCodec.decode(files.metadata).copy(
                rawChunkCount = 75_000, frameCount = 75_000, sampleCount = 1_500_000,
                writer = CaptureSessionMetadataCodec.decode(files.metadata).writer.copy(rawBytes = Files.size(files.raw)))
            Files.write(files.metadata, CaptureSessionMetadataCodec.encodeBytes(metadata))
            val session = CaptureSessionRepository.listSessions(root).single()
            val before = CaptureExportSource.hash(files.raw)
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
            assertTrue(trace.budgetDegraded)
            assertEquals(1_500_000L, trace.totalAcceptedSamples)
            assertTrue(trace.rawRed.size <= trace.budget!!.maximumPreviewBuckets * 6 + 6)
            assertEquals(trace.rawRed.size, trace.sourceSampleIndices.size)
            assertTrue(trace.filteredRed.isEmpty() && trace.fixedLagRed.isEmpty())
            assertTrue(trace.metricTimeline.isEmpty())
            assertEquals(CaptureMetricTimelineSource.UNAVAILABLE, trace.metricTimelineEvidence.source)
            assertTrue(trace.metricTimelineEvidence.detail.contains("内存预算"))
            assertEquals(1, trace.replay.missingFrames)
            assertArrayEquals(longArrayOf(800_000), trace.gapSourceIndices)
            assertEquals(0L, trace.sourceSampleIndices.first())
            assertEquals(1_499_999L, trace.sourceSampleIndices.last())
            trace.sourceSampleIndices.forEachIndexed { index, source ->
                assertEquals(100_000.0 + source, trace.rawRed[index], 0.0)
                assertEquals(2_000_000.0 - source, trace.rawIr[index], 0.0)
                assertEquals(source / 100.0, trace.timeSeconds[index], 0.0)
            }
            assertEquals(before, CaptureExportSource.hash(files.raw))
            println("retainedPreviewPoints=${trace.rawRed.size}; totalAcceptedSamples=${trace.totalAcceptedSamples}")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun indexRetainsEveryOneOf160LargeArtifactsWithoutRetainingWindowsOrPeaks() {
        println("ReviewLowHeapContractTest artifacts maxMemory=${Runtime.getRuntime().maxMemory()}")
        val root = Files.createTempDirectory("review-low-heap-artifacts")
        try {
            val session = ReviewSessionFixtures.writeProtocols(root).first()
            val raw = CaptureSessionRepository.expectedFiles(session.directory).raw
            val before = CaptureExportSource.hash(raw)
            val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session)
            val window = OfflinePulseWindow(0, 0, 800, 0.0, 8.0, "IR", "IR", 72.0, 72.0,
                72.0, 72.0, 0.9, 20.0, "positive", 1.0, 1.0, true, null)
            val large = artifact.report.copy(
                windows = List(1797) { window.copy(startIndex = it * 200, stopIndex = it * 200 + 800) },
                peaks = List(4320) { CaptureSessionAnalysisPeak(it * 83, it * 0.83) },
                metrics = artifact.report.metrics.copy(windowCount = 1797, acceptedWindowCount = 1797,
                    peakCount = 4320, heartRateBpm = 72.0))
            val bytes = CaptureSessionAnalysisCodec.encode(large).toByteArray(Charsets.UTF_8)
            // Verify that this is a decodable legacy-schema full report, then write identical large versions.
            assertEquals(1797, CaptureSessionAnalysisCodec.decode(bytes.toString(Charsets.UTF_8)).windows.size)
            Files.delete(artifact.path)
            repeat(160) { index -> Files.write(artifact.path.parent.resolve("large-$index.json"), bytes) }
            val summaries = CaptureSessionOfflineAnalysisService.listArtifactSummaries(session)
            assertEquals(160, summaries.size)
            assertTrue(summaries.all { it.state == CaptureArtifactReadState.READY })
            assertTrue(summaries.all { it.windowCount == 1797L && it.peakCount == 4320L && it.heartRateBpm == 72.0 })
            assertEquals(160, CaptureSessionOfflineAnalysisService.listArtifacts(session).size)
            assertEquals(before, CaptureExportSource.hash(raw))
            assertFalse(CaptureArtifactSummary::class.java.declaredFields.any { it.name in setOf("report", "windows", "peaks") })
            println("retainedSummaries=${summaries.size}; retainedWindows=0; retainedPeaks=0")
        } finally { root.toFile().deleteRecursively() }
    }
}
