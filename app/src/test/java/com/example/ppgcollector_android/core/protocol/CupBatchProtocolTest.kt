package com.example.ppgcollector_android.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CupBatchProtocolTest {
    @Test
    fun goldenFixtureMatchesEncodedReferenceFrameByteForByte() {
        val expected = javaClass.classLoader!!
            .getResourceAsStream("protocol/golden_seq42.hex")!!
            .bufferedReader()
            .readText()
            .filterNot(Char::isWhitespace)
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        assertEquals(168, expected.size)
        assertTrue(expected.contentEquals(makeReferenceFrame(42u)))
    }

    @Test
    fun currentPlanarGoldenLayoutDecodesKnownSequenceAndSamples() {
        val wire = makeReferenceFrame(sequence = 42u)

        assertEquals(CupBatchProtocolV1.frameLength, wire.size)
        assertEquals(
            byteArrayOf(0xAB.toByte(), 0xBA.toByte(), 0x15, 0xA1.toByte(), 0x00, 0x2A).toList(),
            wire.take(6),
        )
        assertEquals(6, CupBatchProtocolV1.redDataOffset)
        assertEquals(86, CupBatchProtocolV1.irDataOffset)
        assertEquals(listOf(0xCD.toByte(), 0xDC.toByte()), wire.takeLast(2).toList())

        val frame = decodeCupBatchFrame(wire)
        assertEquals(42u.toUByte(), frame.sequence)
        assertEquals(CupPpgSample(100_000u, 120_000u), frame.samples.first())
        assertEquals(CupPpgSample(100_323u, 120_437u), frame.samples.last())
        assertEquals(20, frame.samples.size)
        assertEquals(
            listOf(0xA0, 0x86, 0x01, 0x00).map(Int::toByte),
            wire.copyOfRange(6, 10).toList(),
        )
        assertEquals(
            listOf(0xC0, 0xD4, 0x01, 0x00).map(Int::toByte),
            wire.copyOfRange(86, 90).toList(),
        )
    }

    @Test
    fun legacy408ByteInterleavedFixtureRemainsReplayCompatible() {
        val wire = loadHexFixture("protocol/legacy_golden_seq42.hex")

        assertEquals(CupBatchProtocolV1.legacyFrameLength, wire.size)
        val frame = decodeCupBatchFrame(wire)
        assertEquals(CupBatchProtocolV1.legacyProfileIdentifier, frame.protocolProfileIdentifier)
        assertEquals(50, frame.samples.size)
        assertEquals(CupPpgSample(100_000u, 120_000u), frame.samples.first())
        assertEquals(CupPpgSample(100_833u, 121_127u), frame.samples.last())

        val decoder = CupBatchStreamDecoder()
        assertTrue(decoder.feed(wire.copyOfRange(0, 173)).isEmpty())
        val decoded = decoder.feed(wire.copyOfRange(173, wire.size))
        assertEquals(listOf(frame), decoded)
        assertEquals(CupBatchProtocolV1.legacyProfileIdentifier, decoder.detectedProtocolProfile)
    }

    @Test
    fun oneStreamCannotSilentlySwitchBetweenWireLayouts() {
        val decoder = CupBatchStreamDecoder()
        val current = makeReferenceFrame(1u)
        val legacy = loadHexFixture("protocol/legacy_golden_seq42.hex")

        assertEquals(1, decoder.feed(current).size)
        assertTrue(decoder.feed(legacy).isEmpty())
        assertEquals(CupBatchProtocolV1.profileIdentifier, decoder.detectedProtocolProfile)
        assertTrue(decoder.stats.invalidLength >= 1)
    }

    @Test
    fun everyFragmentSizeProducesTheSameFrames() {
        val stream = makeReferenceFrame(1u) + makeReferenceFrame(2u)

        for (chunkSize in 1..CupBatchProtocolV1.frameLength) {
            val decoder = CupBatchStreamDecoder()
            val decoded = ArrayList<CupBatchFrame>()
            var offset = 0
            while (offset < stream.size) {
                val end = minOf(offset + chunkSize, stream.size)
                decoded += decoder.feed(stream.copyOfRange(offset, end))
                offset = end
            }
            assertEquals(listOf(1u.toUByte(), 2u.toUByte()), decoded.map { it.sequence })
            assertEquals(2, decoder.stats.frames)
            assertEquals(0, decoder.pendingByteCount)
        }
    }

    @Test
    fun observedEightByteAuxiliaryFramesAreCountedWithoutDiscardingData() {
        val auxiliaries = listOf(
            auxiliaryFrame(0x02u, 0x4B, 0x60, 0x01),
            auxiliaryFrame(0x06u, 0x00, 0x00, 0x00),
            auxiliaryFrame(0x0Cu, 0x08, 0x00, 0x00),
            auxiliaryFrame(0x0Fu, 0x00, 0x00, 0x00),
        )
        val stream = listOf(makeReferenceFrame(1u)) + auxiliaries + makeReferenceFrame(2u)
        val wire = stream.fold(ByteArray(0)) { accumulated, piece -> accumulated + piece }

        for (chunkSize in 1..31) {
            val decoder = CupBatchStreamDecoder()
            val decoded = ArrayList<CupBatchFrame>()
            var offset = 0
            while (offset < wire.size) {
                val end = minOf(offset + chunkSize, wire.size)
                decoded += decoder.feed(wire.copyOfRange(offset, end))
                offset = end
            }

            assertEquals(listOf(1u.toUByte(), 2u.toUByte()), decoded.map { it.sequence })
            assertEquals(2, decoder.stats.frames)
            assertEquals(4, decoder.stats.auxiliaryFrames)
            assertEquals(0, decoder.stats.invalidFunction)
            assertEquals(0, decoder.stats.invalidLength)
            assertEquals(0, decoder.stats.invalidTail)
            assertEquals(0, decoder.stats.bytesDiscarded)
            assertEquals(0, decoder.pendingByteCount)
        }
    }

    @Test
    fun unknownOrMalformedShortFramesRemainStructuralErrors() {
        val malformedKnown = auxiliaryFrame(0x02u, 0x4B, 0x60, 0x01).also {
            it[it.lastIndex] = 0x00
        }
        val unknown = auxiliaryFrame(0x03u, 0x00, 0x00, 0x00)
        val decoder = CupBatchStreamDecoder()

        val frames = decoder.feed(
            makeReferenceFrame(1u) + malformedKnown + unknown + makeReferenceFrame(2u),
        )

        assertEquals(listOf(1u.toUByte(), 2u.toUByte()), frames.map { it.sequence })
        assertEquals(0, decoder.stats.auxiliaryFrames)
        assertEquals(1, decoder.stats.invalidFunction)
        assertEquals(1, decoder.stats.invalidTail)
        assertEquals(16, decoder.stats.bytesDiscarded)
    }

    @Test
    fun seededRandomFragmentsAndInterFrameNoisePreserveTheWholeStream() {
        val pieces = ArrayList<ByteArray>()
        pieces += byteArrayOf(0x10, 0x20, 0x30)
        repeat(24) { index ->
            pieces += makeReferenceFrame(index.toUByte())
            pieces += byteArrayOf(0x11, 0x22, 0x33, 0x44)
        }
        val stream = pieces.fold(ByteArray(0)) { accumulated, piece -> accumulated + piece }
        val decoder = CupBatchStreamDecoder()
        val decoded = ArrayList<CupBatchFrame>()
        var offset = 0
        var randomState = 0x13579BDF
        while (offset < stream.size) {
            randomState = randomState * 1_664_525 + 1_013_904_223
            val chunkSize = 1 + ((randomState ushr 1) % 73)
            val end = minOf(offset + chunkSize, stream.size)
            decoded += decoder.feed(stream.copyOfRange(offset, end))
            offset = end
        }

        assertEquals((0 until 24).map { it.toUByte() }, decoded.map { it.sequence })
        assertEquals(24, decoder.stats.frames)
        assertTrue(decoder.stats.bytesDiscarded >= 3 + 24 * 4)
        assertEquals(0, decoder.pendingByteCount)
    }

    @Test
    fun noiseBadTailAndBadHeadersResynchronize() {
        val broken = makeReferenceFrame(3u).also { it[it.lastIndex] = 0x00 }
        val stream = byteArrayOf(0x01, 0x02, 0xAB.toByte()) +
            broken +
            makeReferenceFrame(4u)
        val decoder = CupBatchStreamDecoder()

        val frames = decoder.feed(stream)

        assertEquals(listOf(4u.toUByte()), frames.map { it.sequence })
        assertTrue(decoder.stats.invalidTail >= 1)
        assertTrue(decoder.stats.bytesDiscarded >= 5)
        assertEquals(0, decoder.pendingByteCount)
    }

    @Test
    fun partialHeaderIsRetainedAndResetClearsStats() {
        val frame = makeReferenceFrame(7u)
        val decoder = CupBatchStreamDecoder()

        assertTrue(decoder.feed(byteArrayOf(0x00, 0xAB.toByte())).isEmpty())
        assertEquals(1, decoder.pendingByteCount)
        val frames = decoder.feed(byteArrayOf(0xBA.toByte()) + frame.copyOfRange(2, frame.size))

        assertEquals(listOf(7u.toUByte()), frames.map { it.sequence })
        assertEquals(1, decoder.stats.bytesDiscarded)

        decoder.feed(byteArrayOf(0x01, 0x02, 0x03))
        decoder.reset()
        assertEquals(CupDecoderStats(), decoder.stats)
        assertEquals(0, decoder.pendingByteCount)
    }

    @Test
    fun pendingBufferIsBoundedAfterUnframedInput() {
        val decoder = CupBatchStreamDecoder(maxPendingBytes = CupBatchProtocolV1.frameLength)

        decoder.feed(ByteArray(CupBatchProtocolV1.frameLength * 4) { 0x11 })

        assertTrue(decoder.pendingByteCount <= CupBatchProtocolV1.frameLength)
        assertTrue(decoder.stats.bytesDiscarded >= CupBatchProtocolV1.frameLength * 3)
    }

    @Test
    fun directDecodeRejectsInvalidLengthAndTail() {
        val frame = makeReferenceFrame(9u)
        val invalidLength = frame.copyOf().also { it[3] = 0x00; it[4] = 0x00 }
        val invalidTail = frame.copyOf().also { it[it.lastIndex - 1] = 0x00 }

        assertThrowsProtocol { decodeCupBatchFrame(invalidLength) }
        assertThrowsProtocol { decodeCupBatchFrame(invalidTail) }
        assertFalse(frame.contentEquals(invalidTail))
    }

    @Test
    fun streamDecoderCountsInvalidFunctionAndLengthBeforeResync() {
        val invalidFunction = makeReferenceFrame(10u).also { it[2] = 0x16 }
        val invalidLength = makeReferenceFrame(11u).also { it[3] = 0x00; it[4] = 0x00 }
        val decoder = CupBatchStreamDecoder()

        val frames = decoder.feed(invalidFunction + invalidLength + makeReferenceFrame(12u))

        assertEquals(listOf(12u.toUByte()), frames.map { it.sequence })
        assertEquals(1, decoder.stats.invalidFunction)
        assertEquals(1, decoder.stats.invalidLength)
        assertEquals(1, decoder.stats.frames)
    }

    private fun makeReferenceFrame(sequence: UByte): ByteArray {
        val samples = List(CupBatchProtocolV1.samplesPerFrame) { index ->
            CupPpgSample(
                red = (100_000 + index * 17).toUInt(),
                ir = (120_000 + index * 23).toUInt(),
            )
        }
        return encodeCupBatchFrame(CupBatchFrame(sequence, samples))
    }

    private fun loadHexFixture(resourceName: String): ByteArray = javaClass.classLoader!!
        .getResourceAsStream(resourceName)!!
        .bufferedReader()
        .readText()
        .filterNot(Char::isWhitespace)
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun auxiliaryFrame(
        function: UByte,
        payload0: Int,
        payload1: Int,
        payload2: Int,
    ): ByteArray = byteArrayOf(
        0xAB.toByte(),
        0xBA.toByte(),
        function.toByte(),
        payload0.toByte(),
        payload1.toByte(),
        payload2.toByte(),
        0xCD.toByte(),
        0xDC.toByte(),
    )

    private fun assertThrowsProtocol(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected CupProtocolException")
        } catch (_: CupProtocolException) {
            // expected
        }
    }
}
