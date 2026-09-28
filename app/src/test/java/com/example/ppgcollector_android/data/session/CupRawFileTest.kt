package com.example.ppgcollector_android.data.session

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CupRawFileTest {
    @Test
    fun rawCancellationStopsAtTheNextBoundedReadAndPropagatesIdentity() {
        val path = java.nio.file.Files.createTempDirectory("raw-cancel").resolve("source.cupraw")
        try {
            CupRawWriter(path).use { writer -> repeat(10) { writer.append(it.toULong(), ByteArray(64 * 1024)) } }
            var visits = 0
            val cancellation = java.util.concurrent.CancellationException("raw")
            val thrown = org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                CupRawReader.scan(path, cancellationCheck = { if (visits == 2) throw cancellation }) { visits++ }
            }
            org.junit.Assert.assertSame(cancellation, thrown)
            org.junit.Assert.assertEquals(2, visits)
        } finally { path.parent.toFile().deleteRecursively() }
    }

    @Test
    fun writerAndStreamingReaderRoundTripRecordsAndLittleEndianHeader() {
        withTemporaryRawPath { path ->
            CupRawWriter(path).use { writer ->
                assertFalse(writer.append(1u, ByteArray(0)))
                assertTrue(writer.append(0x0102030405060708u, byteArrayOf(1, 2, 3)))
                assertTrue(writer.append(99u, byteArrayOf(0x7F, 0x00)))
                writer.flush()
                assertEquals(2, writer.recordCount)
                assertEquals(5, writer.payloadBytes)
            }

            val records = ArrayList<CupRawRecord>()
            val summary = CupRawReader.scan(path) { records += it }
            assertEquals(2, summary.recordCount)
            assertEquals(Files.size(path), summary.validByteCount)
            assertEquals(0, summary.trailingByteCount)
            assertEquals(null, summary.tailIssue)
            assertEquals(15, summary.peakRecordBufferBytes)
            assertEquals(0x0102030405060708u, records[0].hostMonotonicNanoseconds)
            assertArrayEquals(byteArrayOf(1, 2, 3), records[0].chunk)
            assertEquals(99u.toULong(), records[1].hostMonotonicNanoseconds)
            assertArrayEquals(byteArrayOf(0x7F, 0x00), records[1].chunk)

            val bytes = Files.readAllBytes(path)
            assertArrayEquals(CupRawFormat.magic, bytes.copyOf(8))
            assertEquals(0x08, bytes[8].toInt())
            assertEquals(0x03, bytes[16].toInt())
        }
    }

    @Test
    fun truncatedHeaderReportsSafePrefixAtLastCompleteRecord() {
        val complete = rawBytes(10u, byteArrayOf(4, 5))
        val truncated = complete + byteArrayOf(0x01, 0x02, 0x03)

        val summary = CupRawReader.scan(truncated)

        assertEquals(1, summary.recordCount)
        assertEquals(8 + 12 + 2L, summary.validByteCount)
        assertEquals(3L, summary.trailingByteCount)
        assertEquals(
            CupRawTailIssue.TruncatedRecordHeader(
                offset = 22,
                available = 3,
            ),
            summary.tailIssue,
        )
    }

    @Test
    fun truncatedPayloadReportsRecordOffsetAndAvailableBytes() {
        val valid = rawBytes(10u, byteArrayOf(4, 5))
        val partialHeader = ByteBuffer
            .allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(20L)
            .putInt(4)
            .array()
        val bytes = valid + partialHeader + byteArrayOf(6, 7)

        val summary = CupRawReader.scan(bytes)

        assertEquals(1, summary.recordCount)
        assertEquals(
            CupRawTailIssue.TruncatedChunk(
                offset = 22,
                expected = 4,
                available = 2,
            ),
            summary.tailIssue,
        )
        assertEquals(22L, summary.validByteCount)
    }

    @Test
    fun oversizedPayloadIsRejectedBeforeAllocation() {
        val header = ByteBuffer
            .allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(20L)
            .putInt(CupRawFormat.maximumChunkLength + 1)
            .array()
        val summary = CupRawReader.scan(CupRawFormat.magic + header)

        assertEquals(0, summary.recordCount)
        assertEquals(8L, summary.validByteCount)
        assertEquals(
            CupRawTailIssue.ChunkTooLarge(
                offset = 8,
                length = (CupRawFormat.maximumChunkLength + 1).toUInt(),
            ),
            summary.tailIssue,
        )
    }

    @Test
    fun invalidMagicAndWriterOversizedChunkAreRejected() {
        try {
            CupRawReader.scan(byteArrayOf(0, 1, 2))
            throw AssertionError("expected invalid magic")
        } catch (_: CupRawFileException.InvalidMagic) {
            // expected
        }

        withTemporaryRawPath { path ->
            try {
                CupRawWriter(path).use {
                    it.append(1u, ByteArray(CupRawFormat.maximumChunkLength + 1))
                }
                throw AssertionError("expected oversized writer chunk")
            } catch (error: CupRawFileException.ChunkTooLarge) {
                assertEquals(CupRawFormat.maximumChunkLength + 1, error.length)
            }
        }
    }

    @Test
    fun readRejectsTailButScanStillProvidesSafePrefix() {
        val bytes = rawBytes(10u, byteArrayOf(4, 5)) + byteArrayOf(0x01)

        val summary = CupRawReader.scan(bytes)
        assertTrue(summary.tailIssue != null)
        try {
            val path = Files.createTempFile("cupraw-read", ".cupraw")
            try {
                Files.write(path, bytes)
                CupRawReader.read(path)
                throw AssertionError("expected tail error")
            } finally {
                Files.deleteIfExists(path)
            }
        } catch (error: CupRawFileException.Tail) {
            assertEquals(summary.tailIssue, error.issue)
        }
    }

    private fun rawBytes(timestamp: ULong, chunk: ByteArray): ByteArray {
        val header = ByteBuffer
            .allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(timestamp.toLong())
            .putInt(chunk.size)
            .array()
        return CupRawFormat.magic + header + chunk
    }

    private fun withTemporaryRawPath(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("cupraw-test")
        val path = directory.resolve("capture.cupraw")
        try {
            block(path)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
