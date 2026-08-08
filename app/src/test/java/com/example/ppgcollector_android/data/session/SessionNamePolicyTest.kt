package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNamePolicyTest {
    @Test
    fun validatesFreeNamesAndCanonicalSubjectSequence() {
        assertTrue(SessionNamePolicy.isValid("PPG-subject_01-1"))
        assertFalse(SessionNamePolicy.isValid(""))
        assertFalse(SessionNamePolicy.isValid("with space"))
        assertFalse(SessionNamePolicy.isValid("CON"))
        assertFalse(SessionNamePolicy.isValid("a".repeat(65)))
        assertEquals(
            CanonicalSessionIdentity("subject_01", 12),
            SessionNamePolicy.parseCanonical("PPG-subject_01-12"),
        )
        assertEquals(
            CanonicalSessionIdentity("subject", 1),
            SessionNamePolicy.parseCanonical("ppg-subject-1"),
        )
        assertEquals("PPG-subject-1", SessionNamePolicy.normalizeCanonical("ppg-subject-1"))
        assertNull(SessionNamePolicy.parseCanonical("PPG-subject-0"))
    }

    @Test
    fun duplicateIsCaseInsensitiveAndSuggestionUsesActualRawSessions() {
        val root = Files.createTempDirectory("name-policy")
        try {
            assertFalse(SessionNamePolicy.isDuplicate("PPG-A-1", root))
            Files.createDirectory(root.resolve("pPg-A-1"))
            assertTrue(SessionNamePolicy.isDuplicate("PPG-A-1", root))
            root.resolve("pPg-A-1").toFile().deleteRecursively()
            val first = CaptureSessionWriter(
                CaptureSessionConfiguration(
                    sessionId = "first",
                    baseName = "PPG-A-1",
                    startedUtc = java.time.Instant.parse("2026-08-08T00:00:00Z"),
                    softVersion = "test",
                    algorithmVersion = "test",
                    preprocessProfile = "test",
                    protocolProfile = "cup_v1",
                    transportProfile = "test",
                    device = CaptureDeviceContext("CUP", "id", "service", "notify"),
                ),
                root,
            )
            // A newly created but empty directory must not consume sequence 2.
            assertNull(SessionNamePolicy.suggestedBaseName(root))
            first.appendRawThenDerive(1u, byteArrayOf(1, 2, 3)) { emptyList() }
            first.appendMetricEpoch(
                CaptureMetricEpoch(
                    sessionId = "first",
                    connectionGeneration = 1,
                    metricEpoch = 1,
                    sourceSampleIndex = 800,
                    sourceTimeSeconds = 8.0,
                    measuredUtc = java.time.Instant.parse("2026-08-08T00:00:01Z"),
                    snapshot = com.example.ppgcollector_android.core.signal.LiveMetricSnapshot.warmingUp(),
                ),
            )
            first.finish(CaptureStopReason.USER)
            assertEquals("PPG-A-2", SessionNamePolicy.suggestedBaseName(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun emptyHistoryProvidesAnExplicitExampleSuggestion() {
        val root = Files.createTempDirectory("name-policy-example")
        try {
            assertEquals(
                SessionNamePolicy.exampleSuggestedName,
                SessionNamePolicy.suggestedBaseNameOrExample(root),
            )
            assertEquals(
                SessionNamePolicy.exampleSuggestedName,
                CaptureSetupPolicy.suggestion(root),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
