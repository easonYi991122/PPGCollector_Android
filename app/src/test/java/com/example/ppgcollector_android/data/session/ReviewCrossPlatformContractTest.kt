package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReviewCrossPlatformContractTest {
    @Test
    fun missingExplicitSwiftFixtureFailsInsteadOfSkippingOrFallingBack() {
        val previous = System.getProperty("ppgReviewSwiftFixtures")
        val root = Files.createTempDirectory("missing-swift-fixture")
        try {
            System.setProperty("ppgReviewSwiftFixtures", root.resolve("absent.zip").toString())
            assertThrows(IllegalArgumentException::class.java) {
                readsActualSwiftArchiveOrExplicitOverrideWithoutReencoding()
            }
        } finally {
            if (previous == null) System.clearProperty("ppgReviewSwiftFixtures")
            else System.setProperty("ppgReviewSwiftFixtures", previous)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun readsActualSwiftArchiveOrExplicitOverrideWithoutReencoding() {
        val override = System.getProperty("ppgReviewSwiftFixtures")
        val bytes = if (override == null) ReviewSessionFixtures.resource("review/swift_baseline_sessions.zip") else {
            val path = Path.of(override)
            require(Files.isRegularFile(path)) { "ppgReviewSwiftFixtures does not exist: $path" }
            Files.readAllBytes(path)
        }
        verifyArchive(bytes, expectedMinimumSessions = 3)
    }

    @Test
    fun readsFrozenKotlinPayloadsAndRecoveryIdentityExactly() {
        val bytes = ReviewSessionFixtures.resource("review/android_sessions.zip")
        val entries = ReviewSessionFixtures.unzip(bytes)
        ReviewSessionFixtures.verifyExpected(entries,
            ReviewSessionFixtures.resource("review/android_sessions_expected.json").toString(Charsets.UTF_8))
        verifyArchive(bytes, expectedMinimumSessions = 6)
        assertTrue(entries.keys.any { it.startsWith("subjects/KOTLIN/MB/wrist_custom_copy_again/") })
    }

    private fun verifyArchive(bytes: ByteArray, expectedMinimumSessions: Int) {
        val entries = ReviewSessionFixtures.unzip(bytes)
        val manifest = JsonParser(entries.getValue("export_manifest.json").toString(Charsets.UTF_8)).parse() as JsonValue.ObjectValue
        val manifestEntries = (manifest.fields.getValue("entries") as JsonValue.ArrayValue).values
        manifestEntries.forEach { entry ->
            val fields = (entry as JsonValue.ObjectValue).fields
            val name = (fields.getValue("entry") as JsonValue.StringValue).value
            val payload = entries.getValue(name)
            assertEquals(name, payload.size.toString(), (fields.getValue("size") as JsonValue.NumberValue).raw)
            assertEquals(name, ReviewSessionFixtures.hash(payload), (fields.getValue("sha256") as JsonValue.StringValue).value)
        }
        val root = Files.createTempDirectory("review-cross-platform")
        try {
            entries.forEach { (name, payload) ->
                val path = root.resolve(name).normalize()
                require(path.startsWith(root))
                Files.createDirectories(path.parent)
                Files.write(path, payload)
            }
            val metadataPaths = entries.keys.filter { it.endsWith(".session.json") }.map(root::resolve)
            assertTrue(metadataPaths.size >= expectedMinimumSessions)
            metadataPaths.forEach { path ->
                val metadata = CaptureSessionMetadataCodec.decode(path)
                assertTrue(metadata.startedUtc >= Instant.parse("2026-01-01T00:00:00Z"))
                val before = CaptureExportSource.hash(path.parent.resolve(metadata.files.raw))
                val inspection = CaptureSessionInspectionService.inspect(path.parent)
                assertTrue("$path: ${inspection.findings}", inspection.findings.none { it.severity == CaptureInspectionSeverity.ERROR })
                assertEquals(metadata.sampleCount, inspection.replay!!.acceptedSamples)
                assertEquals(metadata.sampleCount, inspection.csv!!.completeDataRowCount)
                val csv = SessionCsvLineReader(path.parent.resolve(metadata.files.samples))
                csv.use { reader ->
                    assertEquals(CaptureCsvSchema.header.trimEnd(), reader.next())
                    CupRawReplayEngine.replay(path.parent.resolve(metadata.files.raw),
                        CupStreamProtocolMode.fromProtocolProfileIdentifier(metadata.protocolProfile)) { sample ->
                        val row = CaptureCsvParser.parseRow(requireNotNull(reader.next()))
                        assertEquals(sample.sampleIndex, row.sampleIndex)
                        assertEquals(sample.frameSequence, row.frameSequence)
                        assertEquals(sample.sample.red, row.red)
                        assertEquals(sample.sample.ir, row.ir)
                        assertEquals(sample.sampleInFrame, row.sampleInFrame)
                        assertEquals(sample.hostMonotonicNanoseconds, row.hostFrameTimeNanoseconds)
                    }
                    assertNull(reader.next())
                }
                if (metadata.files.ecg != null) {
                    var ecgIndex = 0L
                    val decoder = Ads1292rStreamDecoder()
                    val tracker = CupFrameSequenceTracker()
                    SessionCsvLineReader(path.parent.resolve(metadata.files.ecg)).use { ecg ->
                        assertEquals(CaptureEcgCsv.header.trimEnd(), ecg.next())
                        CupRawReader.scan(path.parent.resolve(metadata.files.raw)) { record ->
                            decoder.feed(record.chunk).forEach { packet ->
                                val event = tracker.observe(CupBatchFrame(packet.sequenceNumber.toUByte(),
                                    packet.red.indices.map { CupPpgSample(packet.red[it], packet.ir[it]) },
                                    packet.sequenceNumber, CupWireFrameProfile.SENSOR_PACKET_168))
                                if (event != CupSequenceEvent.Duplicate && event != CupSequenceEvent.OutOfOrder) {
                                    packet.ecg.forEach { value ->
                                        val row = parseSessionCsvFields(requireNotNull(ecg.next()))
                                        assertEquals(ecgIndex.toString(), row[2])
                                        assertEquals(ecgIndex / 500.0, row[3].toDouble(), 0.000001)
                                        assertEquals(packet.sequenceNumber.toString(), row[4])
                                        assertEquals(value.toString(), row[5])
                                        ecgIndex++
                                    }
                                }
                            }
                        }
                        assertNull(ecg.next())
                    }
                    assertEquals(metadata.sampleCount * 5, ecgIndex)
                }
                assertEquals(before, CaptureExportSource.hash(path.parent.resolve(metadata.files.raw)))
                if (metadata.recovery?.parentSessionId == "recovery-one") {
                    assertEquals("kotlin-MB-KOTLIN-4", metadata.recovery.sourceSessionId)
                    assertEquals("MB", metadata.recovery.originalCanonicalPrefix)
                    assertEquals(2L, inspection.metrics!!.completeDataRowCount)
                    assertEquals(80L, inspection.ecg!!.completeDataRowCount)
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
