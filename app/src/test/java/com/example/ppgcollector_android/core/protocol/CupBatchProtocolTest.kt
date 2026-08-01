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

        assertEquals(408, expected.size)
        assertTrue(expected.contentEquals(makeReferenceFrame(42u)))
    }

    @Test
    fun draftGoldenLayoutDecodesKnownSequenceAndSamples() {
        val wire = makeReferenceFrame(sequence = 42u)

        assertEquals(CupBatchProtocolV1.frameLength, wire.size)
        assertEquals(
            byteArrayOf(0xAB.toByte(), 0xBA.toByte(), 0x15, 0x91.toByte(), 0x01, 0x2A).toList(),
            wire.take(6),
        )
        assertEquals(listOf(0xCD.toByte(), 0xDC.toByte()), wire.takeLast(2).toList())

        val frame = decodeCupBatchFrame(wire)
        assertEquals(42u.toUByte(), frame.sequence)
        assertEquals(CupPpgSample(100_000u, 120_000u), frame.samples.first())
        assertEquals(CupPpgSample(100_833u, 121_127u), frame.samples.last())
        assertEquals(50, frame.samples.size)
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

    private fun assertThrowsProtocol(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected CupProtocolException")
        } catch (_: CupProtocolException) {
            // expected
        }
    }
}
