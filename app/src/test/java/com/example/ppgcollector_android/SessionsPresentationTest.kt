package com.example.ppgcollector_android

import com.example.ppgcollector_android.data.session.StoredCaptureSession
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsPresentationTest {
    @Test
    fun sessionWorkCancellationIsNotConvertedToPresentationError() {
        try {
            runCatchingCancellable<Int> {
                throw CancellationException("refresh cancelled")
            }
            fail("expected cancellation to reach the coroutine boundary")
        } catch (error: CancellationException) {
            assertEquals("refresh cancelled", error.message)
        }
    }

    @Test
    fun exportPickerCancellationKeepsActionSpecificFeedback() {
        val cancelled = cancelledSessionAction(SessionActionKind.EXPORT)

        assertEquals(SessionActionKind.EXPORT, cancelled.kind)
        assertFalse(cancelled.isRunning)
        assertEquals("导出已取消", cancelled.message)
        assertEquals("操作已取消", cancelledSessionAction(SessionActionKind.RECOVER).message)
    }

    @Test
    fun unreadableOrMissingSessionIsPresentedAsRecoveryCandidate() {
        val root = Files.createTempDirectory("sessions-presentation")
        try {
            val item = SessionListItemMapper.map(
                StoredCaptureSession(
                    directory = root.resolve("broken"),
                    baseName = "broken",
                    modifiedAt = Instant.EPOCH,
                    metadata = null,
                    metadataIsReadable = false,
                    hasAllExpectedFiles = false,
                    totalBytes = 12,
                ),
            )
            assertEquals(null, item.complete)
            assertTrue(item.recoveryCandidate)
            assertEquals(2, item.findings.size)
            assertTrue(item.findings.any { it.contains("metadata") })
            assertTrue(item.findings.any { it.contains("预期文件") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
