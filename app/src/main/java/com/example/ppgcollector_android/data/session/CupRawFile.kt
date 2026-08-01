package com.example.ppgcollector_android.data.session

import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

data class CupRawRecord(
    val hostMonotonicNanoseconds: ULong,
    val chunk: ByteArray,
)

sealed interface CupRawTailIssue {
    val offset: Long

    data class TruncatedRecordHeader(
        override val offset: Long,
        val available: Int,
    ) : CupRawTailIssue

    data class ChunkTooLarge(
        override val offset: Long,
        val length: UInt,
    ) : CupRawTailIssue

    data class TruncatedChunk(
        override val offset: Long,
        val expected: Int,
        val available: Int,
    ) : CupRawTailIssue
}

data class CupRawScanSummary(
    val recordCount: Long,
    val validByteCount: Long,
    val totalByteCount: Long,
    val tailIssue: CupRawTailIssue?,
    val peakRecordBufferBytes: Int,
) {
    val trailingByteCount: Long
        get() = maxOf(0L, totalByteCount - validByteCount)
}

sealed class CupRawFileException(message: String) : Exception(message) {
    data object InvalidMagic : CupRawFileException("not a CUPRAW1 file")

    class Tail(val issue: CupRawTailIssue) :
        CupRawFileException("truncated or invalid CUPRAW1 tail: $issue")

    class ChunkTooLarge(val length: Int) :
        CupRawFileException("raw payload exceeds 64 KiB: $length bytes")
}

/** CUPRAW1 append-only container for the original BLE notification chunks. */
object CupRawFormat {
    val magic: ByteArray = byteArrayOf(
        0x43, 0x55, 0x50, 0x52, 0x41, 0x57, 0x31, 0x00,
    )
    const val recordHeaderBytes = 12
    const val maximumChunkLength = 64 * 1024
}

class CupRawWriter(path: Path) : Closeable {
    private val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    private var closed = false

    var recordCount: Long = 0
        private set
    var payloadBytes: Long = 0
        private set
    var bytesWritten: Long = CupRawFormat.magic.size.toLong()
        private set

    init {
        try {
            writeFully(ByteBuffer.wrap(CupRawFormat.magic))
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }

    /** Returns false for an empty notification, matching the Swift writer contract. */
    fun append(hostMonotonicNanoseconds: ULong, chunk: ByteArray): Boolean {
        check(!closed) { "raw writer is closed" }
        if (chunk.isEmpty()) return false
        if (chunk.size > CupRawFormat.maximumChunkLength) {
            throw CupRawFileException.ChunkTooLarge(chunk.size)
        }

        val header = ByteBuffer
            .allocate(CupRawFormat.recordHeaderBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(hostMonotonicNanoseconds.toLong())
        header.putInt(chunk.size)
        header.flip()
        writeFully(header)
        writeFully(ByteBuffer.wrap(chunk))

        recordCount += 1
        payloadBytes += chunk.size
        bytesWritten += CupRawFormat.recordHeaderBytes + chunk.size
        return true
    }

    fun flush() {
        check(!closed) { "raw writer is closed" }
        channel.force(true)
    }

    override fun close() {
        if (closed) return
        try {
            channel.force(true)
        } finally {
            closed = true
            channel.close()
        }
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }
}

object CupRawReader {
    fun read(path: Path): List<CupRawRecord> {
        val records = ArrayList<CupRawRecord>()
        val summary = scan(path) { records += it }
        summary.tailIssue?.let { throw CupRawFileException.Tail(it) }
        return records
    }

    /** Stream the file without loading it into memory; visitor runs per complete record. */
    fun scan(path: Path, visit: (CupRawRecord) -> Unit = {}): CupRawScanSummary {
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            return scan(input, Files.size(path), visit)
        }
    }

    /** Test/helper variant with identical parsing and safe-prefix semantics. */
    fun scan(bytes: ByteArray, visit: (CupRawRecord) -> Unit = {}): CupRawScanSummary =
        bytes.inputStream().use { scan(it, bytes.size.toLong(), visit) }

    private fun scan(
        input: InputStream,
        totalByteCount: Long,
        visit: (CupRawRecord) -> Unit,
    ): CupRawScanSummary {
        val header = readUpTo(input, CupRawFormat.magic.size)
        if (!header.contentEquals(CupRawFormat.magic)) {
            throw CupRawFileException.InvalidMagic
        }

        var offset = CupRawFormat.magic.size.toLong()
        var recordCount = 0L
        var peakRecordBufferBytes = 0
        while (offset < totalByteCount) {
            val recordOffset = offset
            val recordHeader = readUpTo(input, CupRawFormat.recordHeaderBytes)
            if (recordHeader.size != CupRawFormat.recordHeaderBytes) {
                return CupRawScanSummary(
                    recordCount = recordCount,
                    validByteCount = recordOffset,
                    totalByteCount = totalByteCount,
                    tailIssue = CupRawTailIssue.TruncatedRecordHeader(
                        offset = recordOffset,
                        available = recordHeader.size,
                    ),
                    peakRecordBufferBytes = peakRecordBufferBytes,
                )
            }

            val decodedHeader = ByteBuffer.wrap(recordHeader).order(ByteOrder.LITTLE_ENDIAN)
            val timestamp = decodedHeader.long.toULong()
            val declaredLength = decodedHeader.int.toUInt()
            if (declaredLength > CupRawFormat.maximumChunkLength.toUInt()) {
                return CupRawScanSummary(
                    recordCount = recordCount,
                    validByteCount = recordOffset,
                    totalByteCount = totalByteCount,
                    tailIssue = CupRawTailIssue.ChunkTooLarge(
                        offset = recordOffset,
                        length = declaredLength,
                    ),
                    peakRecordBufferBytes = peakRecordBufferBytes,
                )
            }

            val chunkLength = declaredLength.toInt()
            val chunk = readUpTo(input, chunkLength)
            if (chunk.size != chunkLength) {
                return CupRawScanSummary(
                    recordCount = recordCount,
                    validByteCount = recordOffset,
                    totalByteCount = totalByteCount,
                    tailIssue = CupRawTailIssue.TruncatedChunk(
                        offset = recordOffset,
                        expected = chunkLength,
                        available = chunk.size,
                    ),
                    peakRecordBufferBytes = peakRecordBufferBytes,
                )
            }

            peakRecordBufferBytes = maxOf(
                peakRecordBufferBytes,
                CupRawFormat.recordHeaderBytes + chunk.size,
            )
            visit(CupRawRecord(timestamp, chunk))
            recordCount += 1
            offset += CupRawFormat.recordHeaderBytes + chunk.size
        }

        return CupRawScanSummary(
            recordCount = recordCount,
            validByteCount = offset,
            totalByteCount = totalByteCount,
            tailIssue = null,
            peakRecordBufferBytes = peakRecordBufferBytes,
        )
    }

    private fun readUpTo(input: InputStream, requestedCount: Int): ByteArray {
        if (requestedCount == 0) return ByteArray(0)
        val result = ByteArray(requestedCount)
        var offset = 0
        while (offset < requestedCount) {
            val read = input.read(result, offset, requestedCount - offset)
            if (read < 0) break
            if (read == 0) continue
            offset += read
        }
        return if (offset == requestedCount) result else result.copyOf(offset)
    }
}
