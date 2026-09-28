package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.*
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class ReviewAndroidFixtureProducerTest {
    @Test
    fun currentWriterProducesFrozenFileContract() {
        val root = Files.createTempDirectory("review-kotlin-writer")
        try {
            val sessions = root.resolve("sessions")
            val originals = ReviewSessionFixtures.writeProtocols(sessions)
            val hashes = originals.flatMap { CaptureSessionRepository.expectedFiles(it.directory).allPaths }
                .associateWith { ReviewSessionFixtures.hash(Files.readAllBytes(it)) }
            val wrist = originals.single { it.baseName.startsWith("MB-") }
            val first = CaptureSessionRecoveryService.recover(wrist, "wrist_custom_copy",
                Instant.parse("2026-09-27T00:00:01.123456789Z"), "recovery-one", "review")
            val firstStored = CaptureSessionRepository.listSessions(sessions).single { it.directory == first.directory }
            CaptureSessionRecoveryService.recover(firstStored, "wrist_custom_copy_again",
                Instant.parse("2026-09-27T00:00:02.123Z"), "recovery-two", "review")
            hashes.forEach { (path, sha) -> assertEquals(sha, ReviewSessionFixtures.hash(Files.readAllBytes(path))) }
            val all = CaptureSessionRepository.listSessions(sessions)
            assertEquals(6, all.size)
            all.forEach { session ->
                val inspection = CaptureSessionInspectionService.inspect(session.directory)
                assertTrue(inspection.findings.toString(), inspection.findings.none { it.severity == CaptureInspectionSeverity.ERROR })
            }
            val zipPath = root.resolve("android_sessions.zip")
            Files.newOutputStream(zipPath).use { output ->
                CaptureArchiveExportService.export(sessions, root.resolve("subjects"),
                    CaptureArchiveSelection(sessionDirectories = all.map { it.directory }.toSet()), output,
                    now = Instant.parse("2026-09-27T00:00:03.123456789Z"))
            }
            val entries = ReviewSessionFixtures.unzip(Files.readAllBytes(zipPath))
            val expected = ReviewSessionFixtures.expectedJson(entries)
            if (System.getProperty("ppgReviewGenerateFixtures") == "true") {
                val output = requireNotNull(System.getProperty("ppgReviewFixtureOutput")) {
                    "ppgReviewGenerateFixtures requires an explicit ppgReviewFixtureOutput"
                }
                val directory = Path.of(output)
                Files.createDirectories(directory)
                Files.copy(zipPath, directory.resolve("android_sessions.zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Files.writeString(directory.resolve("android_sessions_expected.json"), expected)
            }
            val generatedDirectory = System.getProperty("ppgReviewFixtureOutput")?.takeIf {
                System.getProperty("ppgReviewGenerateFixtures") == "true"
            }?.let(Path::of)
            val frozenZip = generatedDirectory?.resolve("android_sessions.zip")?.let(Files::readAllBytes)
                ?: ReviewSessionFixtures.resource("review/android_sessions.zip")
            val frozenExpected = generatedDirectory?.resolve("android_sessions_expected.json")?.let(Files::readString)
                ?: ReviewSessionFixtures.resource("review/android_sessions_expected.json").toString(Charsets.UTF_8)
            val frozen = ReviewSessionFixtures.unzip(frozenZip)
            ReviewSessionFixtures.verifyExpected(frozen, frozenExpected)
            assertEquals(entries.keys, frozen.keys)
            // Writer finalization uses wall-clock UTC; compare immutable signal rows exactly.
            entries.filterKeys { it.endsWith(".csv") || it.endsWith(".cupraw") }.forEach { (name, bytes) ->
                assertArrayEquals(name, frozen.getValue(name), bytes)
            }
        } finally { root.toFile().deleteRecursively() }
    }
}

internal object ReviewSessionFixtures {
    val instant: Instant = Instant.parse("2026-09-27T00:00:00.123456789Z")
    fun configuration(name: String, protocol: String = CupBatchProtocolV1.profileIdentifier): CaptureSessionConfiguration {
        val identity = SessionNamePolicy.parseCanonical(name)
        return CaptureSessionConfiguration("kotlin-$name", name, instant, "review-kotlin", "review", "review",
            protocol, "cup-nus-bringup-0.1", CaptureDeviceContext("CUP-review", "synthetic", "service", "notify"),
            canonicalSubjectId = identity?.subject, canonicalSequence = identity?.sequence)
    }

    fun writeProtocols(root: Path): List<StoredCaptureSession> {
        val profiles = listOf(CupBatchProtocolV1.legacyProfileIdentifier, CupBatchProtocolV1.profileIdentifier,
            CupSensorPacketProtocolV1.profileIdentifier, Ads1292rPacketProtocol.profileIdentifier)
        profiles.forEachIndexed { index, profile ->
            val name = if (index == 3) "MB-KOTLIN-4" else "PPG-KOTLIN-${index + 1}"
            val config = configuration(name, profile).copy(startedUtc = if (index % 2 == 0) instant else Instant.parse("2026-09-27T00:00:00.123Z"))
            CaptureSessionWriter(config, root).use { writer ->
                val tracker = CupFrameSequenceTracker()
                val decoder = CupBatchStreamDecoder(protocolMode = CupStreamProtocolMode.fromProtocolProfileIdentifier(profile))
                val sequences = if (index >= 2) listOf(UInt.MAX_VALUE - 1u, UInt.MAX_VALUE, 0u, 2u) else listOf(254u, 255u, 0u, 2u)
                sequences.forEachIndexed { frameIndex, sequence ->
                    val host = 10_000uL + frameIndex.toULong() * 1_000uL
                    if (index == 3) {
                        val packet = Ads1292rPacket(sequence, List(20) { (3_000_000_000u + it.toUInt() + frameIndex.toUInt()) },
                            List(4) { 100_000u + frameIndex.toUInt() * 4u + it.toUInt() },
                            List(4) { 200_000u + frameIndex.toUInt() * 4u + it.toUInt() })
                        writer.appendRawThenDerive(host, Ads1292rPacketProtocol.encode(packet)) { emptyList() }
                        writer.appendAds1292rPacket(host, packet, when (frameIndex) {
                            0 -> CupSequenceEvent.First
                            3 -> CupSequenceEvent.Gap(1)
                            else -> CupSequenceEvent.Continuous
                        })
                    } else {
                        val count = if (index == 0) 50 else 20
                        val frame = CupBatchFrame(sequence.toUByte(), List(count) {
                            CupPpgSample(100_000u + frameIndex.toUInt() * count.toUInt() + it.toUInt(),
                                200_000u + frameIndex.toUInt() * count.toUInt() + it.toUInt())
                        }, sequence, when (index) {
                            0 -> CupWireFrameProfile.BATCH_408_LEGACY
                            1 -> CupWireFrameProfile.BATCH_168
                            else -> CupWireFrameProfile.SENSOR_PACKET_168
                        })
                        val wire = when (index) {
                            0 -> legacyWire(frame)
                            2 -> encodeCupSensorPacketFrame(frame)
                            else -> encodeCupBatchFrame(frame)
                        }
                        writer.appendRawThenDerive(host, wire) {
                            decoder.feed(wire).map { decoded ->
                                val event = tracker.observe(decoded)
                                CupDecodedFrameEvent(decoded, event, event != CupSequenceEvent.Duplicate && event != CupSequenceEvent.OutOfOrder)
                            }
                        }
                    }
                }
                val sampleCount = if (index == 0) 200L else if (index == 3) 16L else 80L
                repeat(2) { generation ->
                    val cursor = sampleCount - 2 + generation
                    writer.appendMetricEpoch(CaptureMetricEpoch(config.sessionId, generation.toLong(), 1, cursor,
                        cursor / 100.0, config.startedUtc.plusMillis(generation.toLong()), LiveMetricSnapshot.runtime(
                            MetricResult.valid(72.0, instant, "review-hr", cursor, cursor / 100.0),
                            MetricResult.valid(0.9, instant, "review-sqi", cursor, cursor / 100.0))))
                }
                writer.appendBloodPressure(ManualBloodPressureEvent(
                    CaptureReferenceTimestamp(config.sessionId, 0, 0, 0, 0.0, 10_000u, config.startedUtc),
                    config.startedUtc.plusNanos(1), 120, 80))
                writer.finish(CaptureStopReason.USER)
            }
        }
        return CaptureSessionRepository.listSessions(root)
    }

    private fun legacyWire(frame: CupBatchFrame): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(408).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0xAB.toByte(), 0xBA.toByte(), 0x15))
        buffer.putShort(401.toShort())
        buffer.put(frame.sequence.toByte())
        frame.samples.forEach { buffer.putInt(it.red.toInt()); buffer.putInt(it.ir.toInt()) }
        buffer.put(byteArrayOf(0xCD.toByte(), 0xDC.toByte()))
        return buffer.array()
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun resource(name: String): ByteArray = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing frozen $name" }.use { it.readBytes() }
    fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && !entry.name.startsWith('/') && ".." !in entry.name)
                require(put(entry.name, zip.readBytes()) == null) { "duplicate ZIP entry" }
            }
        }
    }
    fun expectedJson(entries: Map<String, ByteArray>): String = JsonWriter.write(JsonValue.ObjectValue(mapOf(
        "schema_version" to JsonValue.StringValue("ppgcollector_review_fixture_v1"),
        "producer" to JsonValue.StringValue("Kotlin CaptureSessionWriter and CaptureArchiveExportService"),
        "entries" to JsonValue.ArrayValue(entries.map { (path, bytes) ->
            val counts = if (path.endsWith(".csv")) {
                mapOf("data_rows" to JsonValue.NumberValue((bytes.count { it == 10.toByte() } - 1).toString()))
            } else if (path.endsWith(".cupraw")) {
                val metadataPath = path.removeSuffix(".cupraw") + ".session.json"
                val metadata = CaptureSessionMetadataCodec.decode(entries.getValue(metadataPath))
                val replay = CupRawReplayEngine.replay(bytes, CupStreamProtocolMode.fromProtocolProfileIdentifier(metadata.protocolProfile))
                mapOf("raw_records" to JsonValue.NumberValue(replay.rawRecordCount.toString()),
                    "accepted_frames" to JsonValue.NumberValue(replay.acceptedFrames.toString()),
                    "accepted_samples" to JsonValue.NumberValue(replay.acceptedSamples.toString()),
                    "missing_frames" to JsonValue.NumberValue(replay.missingFrames.toString()))
            } else emptyMap()
            JsonValue.ObjectValue(mapOf("path" to JsonValue.StringValue(path),
                "payload_base64" to JsonValue.StringValue(Base64.getEncoder().encodeToString(bytes)),
                "size" to JsonValue.NumberValue(bytes.size.toString()), "sha256" to JsonValue.StringValue(hash(bytes)),
                "expected_counts" to JsonValue.ObjectValue(counts)))
        }),
    )))
    fun verifyExpected(entries: Map<String, ByteArray>, expected: String) {
        val root = JsonParser(expected).parse() as JsonValue.ObjectValue
        val list = (root.fields.getValue("entries") as JsonValue.ArrayValue).values
        assertEquals(entries.size, list.size)
        list.forEach { entry ->
            val fields = (entry as JsonValue.ObjectValue).fields
            val path = (fields.getValue("path") as JsonValue.StringValue).value
            val payload = Base64.getDecoder().decode((fields.getValue("payload_base64") as JsonValue.StringValue).value)
            assertArrayEquals(path, entries.getValue(path), payload)
            assertEquals(payload.size.toString(), (fields.getValue("size") as JsonValue.NumberValue).raw)
            assertEquals(hash(payload), (fields.getValue("sha256") as JsonValue.StringValue).value)
        }
    }
}
