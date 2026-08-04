package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisRequest
import com.example.ppgcollector_android.core.signal.LiveMetricRuntimeProfile
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.LivePpgSignalRuntime
import com.example.ppgcollector_android.core.signal.MetricResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM/nightly equivalent of the Swift long-duration raw/CSV/replay gate. */
class LongDurationDataPathTest {
    @Test
    fun thirtyMinuteStreamingKeepsCadenceAndFilesExactlyAligned() {
        verifyRecordedDataPath(durationSeconds = 30 * 60, elapsedBudgetSeconds = 60)
    }

    @Test
    fun twoHourRecordingStreamsWriterReplayAndCsvWithinBudget() {
        verifyRecordedDataPath(durationSeconds = 2 * 60 * 60, elapsedBudgetSeconds = 240)
    }

    private fun verifyRecordedDataPath(durationSeconds: Int, elapsedBudgetSeconds: Long) {
        val frameCount = durationSeconds * CupBatchProtocolV1.sampleRateHz /
            CupBatchProtocolV1.samplesPerFrame
        val sampleCount = frameCount * CupBatchProtocolV1.samplesPerFrame
        val rawChunkCount = frameCount * 2
        val firstChunkLength = 100
        val secondChunkLength = CupBatchProtocolV1.frameLength - firstChunkLength
        val expectedMetricRequestCount =
            (sampleCount - LiveMetricRuntimeProfile.iosBaseline01.windowSampleCount) /
                LiveMetricRuntimeProfile.iosBaseline01.cadenceSampleCount + 1
        val root = Files.createTempDirectory("ppg-long-duration")
        val startedNanos = System.nanoTime()
        try {
            val profile = LiveMetricRuntimeProfile.iosBaseline01
            val writer = CaptureSessionWriter(longConfiguration(profile), root) { Long.MAX_VALUE }
            val decoder = CupBatchStreamDecoder()
            val sequenceTracker = CupFrameSequenceTracker()
            val signalRuntime = LivePpgSignalRuntime(profile)
            val frames = Array(256) { sequence -> encodedFrame(sequence) }
            val metricWindowEnds = ArrayList<Long>(expectedMetricRequestCount)
            var currentMetrics = LiveMetricSnapshot.warmingUp()
            var acceptedSamples = 0L

            for (frameIndex in 0 until frameCount) {
                val frame = frames[frameIndex and 0xFF]
                val chunks = arrayOf(
                    frame.copyOfRange(0, firstChunkLength),
                    frame.copyOfRange(firstChunkLength, frame.size),
                )
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    val events = decoder.feed(chunk).map { decoded ->
                        val sequenceEvent = sequenceTracker.observe(decoded.sequence, decoded.samples.size)
                        CupDecodedFrameEvent(
                            frame = decoded,
                            sequenceEvent = sequenceEvent,
                            isAccepted = sequenceEvent !is CupSequenceEvent.Duplicate &&
                                sequenceEvent !is CupSequenceEvent.OutOfOrder,
                        )
                    }
                    val acceptedInChunk = events.sumOf { event ->
                        if (event.isAccepted) event.frame.samples.size else 0
                    }
                    val acceptedSampleStartIndex =
                        if (acceptedInChunk == 0) null else acceptedSamples
                    val request = if (acceptedInChunk == 0) {
                        null
                    } else {
                        signalRuntime.ingest(
                            decodedFrames = events,
                            acceptedSampleStartIndex = acceptedSamples,
                            measuredAt = longMeasuredAt(frameIndex),
                            nowNanos = frameNanos(frameIndex),
                        ).metricRequest
                    }

                    writer.append(
                        CaptureStreamChunkEvent(
                            hostMonotonicNanoseconds = frameNanos(frameIndex).toULong() +
                                chunkIndex.toULong() * 1_000_000uL,
                            data = chunk,
                            decodedFrames = events,
                            acceptedSampleStartIndex = acceptedSampleStartIndex,
                            metrics = currentMetrics,
                        ),
                    )
                    acceptedSamples += acceptedInChunk.toLong()
                    request?.let {
                        metricWindowEnds += it.windowEndSampleIndex
                        currentMetrics = completedMetrics(it, profile)
                    }
                }
            }

            val diagnostics = decoder.stats
            val sequenceStats = sequenceTracker.stats
            val summary = writer.finish(CaptureStopReason.USER)
            assertTrue(summary.complete)

            assertEquals(rawChunkCount, summary.writer.rawChunkCount.toInt())
            assertEquals(frameCount * CupBatchProtocolV1.frameLength, summary.writer.rawPayloadBytes.toInt())
            assertEquals(
                CupRawFormat.magic.size + frameCount *
                    (CupRawFormat.recordHeaderBytes + firstChunkLength +
                        CupRawFormat.recordHeaderBytes + secondChunkLength),
                summary.writer.rawFileBytes.toInt(),
            )
            assertEquals(frameCount, diagnostics.frames)
            assertEquals(sampleCount, acceptedSamples.toInt())
            assertEquals(sampleCount, summary.writer.acceptedSamples.toInt())
            assertEquals(0, diagnostics.bytesDiscarded)
            assertEquals(0, diagnostics.invalidFunction)
            assertEquals(0, diagnostics.invalidLength)
            assertEquals(0, diagnostics.invalidTail)
            assertEquals(0, decoder.pendingByteCount)
            assertEquals(0, sequenceStats.missingFrames)
            assertEquals(0, sequenceStats.duplicateFrames)
            assertEquals(0, sequenceStats.outOfOrderFrames)
            assertEquals(800, signalRuntime.bufferedSampleCount)
            assertEquals(sampleCount.toLong(), signalRuntime.continuousSamples)
            val finalWaveform = signalRuntime.publishNow(
                nowNanos = durationSeconds.toLong() * 1_000_000_000L,
                measuredAt = Instant.EPOCH,
            )!!
            assertEquals(800, finalWaveform.red.size)
            assertEquals(800, finalWaveform.causalRed.size)
            assertEquals(800, finalWaveform.causalIr.size)
            assertEquals(expectedMetricRequestCount, metricWindowEnds.size)
            assertEquals(799L, metricWindowEnds.first())
            assertEquals((sampleCount - 1).toLong(), metricWindowEnds.last())
            metricWindowEnds.forEachIndexed { offset, end ->
                assertEquals(799L + offset * profile.cadenceSampleCount, end)
            }

            assertEquals(durationSeconds * 5, countWaveformPublications(durationSeconds))

            val rawSummary = auditRaw(summary.directory.resolve("long_duration.cupraw"), rawChunkCount, frameCount)
            assertEquals(0L, rawSummary.trailingByteCount)
            assertNull(rawSummary.tailIssue)
            assertEquals(CupRawFormat.recordHeaderBytes + firstChunkLength, rawSummary.peakRecordBufferBytes)

            val sessions = CaptureSessionRepository.listSessions(root)
            assertEquals(1, sessions.size)
            val session = sessions.single()
            assertTrue(session.isVerifiedComplete)
            val inspection = CaptureSessionInspectionService.inspect(session.directory)
            assertTrue(inspection.isVerifiedConsistent)
            assertTrue(inspection.findings.isEmpty())
            val replay = inspection.replay
            assertNotNull(replay)
            assertEquals(rawChunkCount.toLong(), replay!!.rawRecordCount)
            assertEquals(frameCount, replay.decodedFrames)
            assertEquals(frameCount, replay.acceptedFrames)
            assertEquals(sampleCount.toLong(), replay.acceptedSamples)
            assertEquals(800, replay.recentSamples.size)
            assertEquals(CupRawFormat.recordHeaderBytes + firstChunkLength, replay.peakRawRecordBufferBytes)
            assertEquals(
                durationSeconds - CupBatchProtocolV1.samplesPerFrame.toDouble() / CupBatchProtocolV1.sampleRateHz,
                replay.hostDurationSeconds!!,
                1e-12,
            )
            assertTrue(replay.isStructurallyClean)
            assertEquals(0L, replay.trailingRawBytes)

            val csv = auditCsv(session.directory.resolve("long_duration.csv"), sampleCount, expectedMetricRequestCount, profile)
            assertEquals(sampleCount, csv.rowCount)
            assertEquals(800, csv.firstHeartRateRow)
            assertEquals("7.990000", csv.firstHeartRateTime)
            assertEquals(expectedMetricRequestCount - 1, csv.heartRateTransitionCount)
            assertEquals(800, csv.firstSqiRow)
            assertEquals("7.990000", csv.firstSqiTime)
            assertEquals(expectedMetricRequestCount - 1, csv.sqiTransitionCount)
            assertEquals(formatSeconds(sampleCount - 101), csv.lastHeartRateTime)
            assertEquals(formatSeconds(sampleCount - 1), csv.lastDeviceTime)
            assertTrue(csv.peakReadBufferBytes < 66 * 1024)

            val metadata = CaptureSessionMetadataCodec.decode(
                session.directory.resolve("long_duration.session.json"),
            )
            assertTrue(metadata.complete)
            assertEquals(frameCount.toLong(), metadata.frameCount)
            assertEquals(sampleCount.toLong(), metadata.sampleCount)
            assertEquals(rawChunkCount.toLong(), metadata.rawChunkCount)
            assertEquals(profile.identifier, metadata.algVersion)
            assertEquals(profile.preprocessingProfile.identifier, metadata.preprocessProfile)

            val elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
            assertTrue(
                "long-duration simulation took ${elapsedSeconds}s, budget=${elapsedBudgetSeconds}s",
                elapsedSeconds < elapsedBudgetSeconds,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun auditRaw(path: Path, expectedRecords: Int, expectedFrames: Int): CupRawScanSummary {
        var shortChunks = 0
        var longChunks = 0
        val summary = CupRawReader.scan(path) { record ->
            when (record.chunk.size) {
                CupBatchProtocolV1.frameLength - 100 -> shortChunks++
                100 -> longChunks++
                else -> throw AssertionError("unexpected raw chunk size ${record.chunk.size}")
            }
        }
        assertEquals(expectedRecords.toLong(), summary.recordCount)
        assertEquals(expectedFrames, longChunks)
        assertEquals(expectedFrames, shortChunks)
        return summary
    }

    private fun auditCsv(
        path: Path,
        expectedRows: Int,
        expectedMetricRequestCount: Int,
        profile: LiveMetricRuntimeProfile,
    ): CsvAudit {
        var rowCount = 0
        var firstHeartRateRow: Int? = null
        var firstHeartRateTime: String? = null
        var heartRateTransitionCount = 0
        var lastHeartRateTime: String? = null
        var firstSqiRow: Int? = null
        var firstSqiTime: String? = null
        var sqiTransitionCount = 0
        var lastSqiTime: String? = null
        var lastDeviceTime: String? = null
        val readBufferBytes = 64 * 1024

        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            CaptureCsvParser.requireHeader(reader.readLine() ?: error("missing CSV header"))
            while (true) {
                val line = reader.readLine() ?: break
                val fields = line.split(',', limit = CaptureCsvSchema.columns.size)
                assertEquals(CaptureCsvSchema.columns.size, fields.size)
                val parsed = CaptureCsvParser.parseRow(line)
                assertEquals(rowCount.toLong(), parsed.sampleIndex)
                assertEquals("1.0+long", fields[18])
                assertEquals(profile.identifier, fields[19])
                assertEquals(profile.preprocessingProfile.identifier, fields[20])
                assertEquals(CupBatchProtocolV1.profileIdentifier, fields[21])
                assertTrue(fields[12].isEmpty())
                assertEquals("false", fields[13])
                assertTrue(fields[14].isEmpty())

                if (rowCount < 800) {
                    assertEquals("false", fields[10])
                    assertTrue(fields[9].isEmpty())
                    assertTrue(fields[11].isEmpty())
                    assertEquals("false", fields[16])
                    assertEquals("0", fields[15])
                    assertTrue(fields[17].isEmpty())
                } else {
                    assertEquals("true", fields[10])
                    assertTrue(fields[9].isNotEmpty())
                    assertTrue(fields[11].isNotEmpty())
                    val heartRateTime = fields[11]
                    if (firstHeartRateRow == null) {
                        firstHeartRateRow = rowCount
                        firstHeartRateTime = heartRateTime
                    }
                    if (heartRateTime != lastHeartRateTime) heartRateTransitionCount++
                    lastHeartRateTime = heartRateTime

                    assertEquals("true", fields[16])
                    assertTrue(fields[15].toDouble() in 0.0..1.0)
                    val sqiTime = fields[17]
                    if (firstSqiRow == null) {
                        firstSqiRow = rowCount
                        firstSqiTime = sqiTime
                    }
                    if (sqiTime != lastSqiTime) sqiTransitionCount++
                    lastSqiTime = sqiTime
                    lastDeviceTime = fields[3]
                }
                rowCount++
            }
        }
        assertEquals(expectedRows, rowCount)
        assertEquals(expectedMetricRequestCount - 1, heartRateTransitionCount)
        assertEquals(expectedMetricRequestCount - 1, sqiTransitionCount)
        return CsvAudit(
            rowCount,
            firstHeartRateRow,
            firstHeartRateTime,
            heartRateTransitionCount,
            lastHeartRateTime,
            firstSqiRow,
            firstSqiTime,
            sqiTransitionCount,
            lastDeviceTime,
            readBufferBytes,
        )
    }

    private fun completedMetrics(
        request: LiveMetricAnalysisRequest,
        profile: LiveMetricRuntimeProfile,
    ): LiveMetricSnapshot = LiveMetricSnapshot.runtime(
        heartRateBpm = MetricResult.valid(
            value = 72.0,
            measuredAt = request.measuredAt,
            algorithmVersion = profile.heartRateConfiguration.algorithmVersion,
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
        ),
        signalQuality = MetricResult.valid(
            value = 0.812,
            measuredAt = request.measuredAt,
            algorithmVersion = profile.signalQualityConfiguration.algorithmVersion,
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
            isProvisional = true,
        ),
        ratioOfRatios = MetricResult.valid(
            value = 0.2,
            measuredAt = request.measuredAt,
            algorithmVersion = "ppg-ios-rr-0.1",
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
            isProvisional = true,
        ),
    )

    private fun countWaveformPublications(durationSeconds: Int): Int {
        val runtime = LivePpgSignalRuntime()
        val seed = CupDecodedFrameEvent(
            frame = CupBatchFrame(
                sequence = 0u,
                samples = List(CupBatchProtocolV1.samplesPerFrame) {
                    CupPpgSample(red = 100_000u, ir = 200_000u)
                },
            ),
            sequenceEvent = CupSequenceEvent.First,
            isAccepted = true,
        )
        var publications = if (
            runtime.ingest(listOf(seed), 0L, Instant.EPOCH, 0L).waveform != null
        ) 1 else 0
        for (tick in 1 until durationSeconds * 5) {
            if (runtime.poll(tick * 200_000_000L, Instant.EPOCH) != null) publications++
        }
        return publications
    }

    private fun longConfiguration(profile: LiveMetricRuntimeProfile) = CaptureSessionConfiguration(
        sessionId = "12345678-1234-1234-1234-1234567890AB",
        baseName = "long_duration",
        startedUtc = Instant.ofEpochSecond(1_800_000_000),
        softVersion = "1.0+long",
        algorithmVersion = profile.identifier,
        preprocessProfile = profile.preprocessingProfile.identifier,
        protocolProfile = CupBatchProtocolV1.profileIdentifier,
        transportProfile = "cup-nus-bringup-0.1",
        device = CaptureDeviceContext(
            name = "CUP-SIM-LONG",
            identifier = "ABCDEFAB-CDEF-CDEF-CDEF-ABCDEFABCDEF",
            serviceUuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            notifyCharacteristicUuid = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        ),
    )

    private fun encodedFrame(sequence: Int): ByteArray = encodeCupBatchFrame(
        CupBatchFrame(
            sequence = sequence.toUByte(),
            samples = List(CupBatchProtocolV1.samplesPerFrame) { index ->
                val value = (100_000 + sequence * CupBatchProtocolV1.samplesPerFrame + index).toUInt()
                CupPpgSample(red = value + 1_000u, ir = value + 2_000u)
            },
        ),
    )

    private fun longMeasuredAt(frameIndex: Int): Instant =
        Instant.ofEpochSecond(1_800_000_000L, frameNanos(frameIndex))

    private fun frameNanos(frameIndex: Int): Long =
        frameIndex.toLong() * CupBatchProtocolV1.samplesPerFrame * 1_000_000_000L /
            CupBatchProtocolV1.sampleRateHz

    private fun formatSeconds(sampleIndex: Int): String =
        String.format(java.util.Locale.ROOT, "%.6f", sampleIndex.toDouble() / 100.0)

    private data class CsvAudit(
        val rowCount: Int,
        val firstHeartRateRow: Int?,
        val firstHeartRateTime: String?,
        val heartRateTransitionCount: Int,
        val lastHeartRateTime: String?,
        val firstSqiRow: Int?,
        val firstSqiTime: String?,
        val sqiTransitionCount: Int,
        val lastDeviceTime: String?,
        val peakReadBufferBytes: Int,
    )
}
