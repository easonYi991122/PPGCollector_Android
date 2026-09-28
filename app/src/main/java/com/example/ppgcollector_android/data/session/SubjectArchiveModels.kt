package com.example.ppgcollector_android.data.session

import java.nio.file.Path
import java.time.Instant

data class SubjectArchiveSession(
    val session: StoredCaptureSession,
    val identity: CanonicalSessionIdentity,
    val heartRateBpm: Double? = null,
    val bloodPressureGroupCount: Int = 0,
    val profileMissing: Boolean = false,
)

data class SubjectArchiveSummary(
    val subject: String,
    val recordingCount: Int,
    val maxSequence: Long?,
    val firstStartedUtc: Instant?,
    val lastStartedUtc: Instant?,
    val completeCount: Int,
    val anomalyCount: Int,
    val bloodPressureGroupCount: Int,
    val heartRateBpm: Double?,
    val profileMissing: Boolean,
)

data class SubjectArchiveGroup(
    val summary: SubjectArchiveSummary,
    val sessions: List<SubjectArchiveSession>,
    val profile: SubjectProfile? = null,
)

data class SubjectArchiveSnapshot(
    val groups: List<SubjectArchiveGroup> = emptyList(),
    val unclassified: List<StoredCaptureSession> = emptyList(),
    val generatedAt: Instant = Instant.EPOCH,
) {
    val allSessions: List<StoredCaptureSession>
        get() = groups.flatMap { it.sessions.map(SubjectArchiveSession::session) } + unclassified
}

data class CaptureArchiveSelection(
    val sessionDirectories: Set<Path> = emptySet(),
    val subjectIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = sessionDirectories.isEmpty() && subjectIds.isEmpty()
}

object SubjectArchiveRepository {
    fun rebuild(
        sessionsRoot: Path,
        subjectsRoot: Path,
        now: Instant = Instant.now(),
    ): SubjectArchiveSnapshot {
        val sessions = CaptureSessionRepository.listSessions(sessionsRoot)
        val profileStore = SubjectProfileStore(subjectsRoot)
        val grouped = LinkedHashMap<String, MutableList<SubjectArchiveSession>>()
        val unclassified = ArrayList<StoredCaptureSession>()
        sessions.forEach { session ->
            val identity = identityFor(session)
            if (identity == null || identity.subject.isBlank()) {
                unclassified += session
            } else {
                val profile = runCatching { profileStore.read(identity.subject) }.getOrNull()
                grouped.getOrPut(identity.subject) { ArrayList() } += SubjectArchiveSession(
                    session = session,
                    identity = identity,
                    heartRateBpm = heartRateFromEvidence(session),
                    bloodPressureGroupCount = bloodPressureCount(session),
                    profileMissing = profile?.latest == null,
                )
            }
        }
        val groups = grouped.map { (subject, entries) ->
            val ordered = entries.sortedWith(
                compareBy<SubjectArchiveSession> { it.identity.sequence }
                    .thenBy { it.session.baseName },
            )
            SubjectArchiveGroup(
                summary = summary(subject, ordered),
                sessions = ordered,
                profile = runCatching { profileStore.read(subject) }.getOrNull(),
            )
        }.sortedBy { it.summary.subject }
        return SubjectArchiveSnapshot(
            groups = groups,
            unclassified = unclassified.sortedWith(
                compareByDescending<StoredCaptureSession> { it.modifiedAt }
                    .thenBy { it.baseName },
            ),
            generatedAt = now,
        )
    }

    fun identityFor(session: StoredCaptureSession): CanonicalSessionIdentity? {
        val parsed = SessionNamePolicy.parseCanonical(session.baseName)
        val metadata = session.metadata ?: return parsed
        val prefix = parsed?.prefix ?: when (metadata.recovery?.originalCanonicalPrefix) {
            "PPG" -> SessionNamePrefix.PPG
            "MB" -> SessionNamePrefix.MB
            else -> null
        }
        return if (metadata.canonicalSubjectId != null && metadata.canonicalSequence != null && prefix != null) {
            CanonicalSessionIdentity(metadata.canonicalSubjectId, metadata.canonicalSequence, prefix)
        } else parsed
    }

    fun selectedSessions(
        snapshot: SubjectArchiveSnapshot,
        selection: CaptureArchiveSelection,
    ): List<StoredCaptureSession> {
        val byDirectory = snapshot.allSessions.associateBy { it.directory }
        val subjectDirectories = snapshot.groups
            .filter { it.summary.subject in selection.subjectIds }
            .flatMap { it.sessions }
            .map { it.session.directory }
        return (selection.sessionDirectories + subjectDirectories)
            .mapNotNull(byDirectory::get)
            .distinctBy { it.directory }
    }

    private fun summary(subject: String, sessions: List<SubjectArchiveSession>): SubjectArchiveSummary {
        val complete = sessions.count { it.session.isVerifiedComplete }
        val anomaly = sessions.size - complete
        val hr = sessions.mapNotNull { it.heartRateBpm }.takeIf { it.isNotEmpty() }?.average()
        return SubjectArchiveSummary(
            subject = subject,
            recordingCount = sessions.size,
            maxSequence = sessions.maxOfOrNull { it.identity.sequence },
            firstStartedUtc = sessions.mapNotNull { it.session.metadata?.startedUtc }.minOrNull(),
            lastStartedUtc = sessions.mapNotNull { it.session.metadata?.startedUtc }.maxOrNull(),
            completeCount = complete,
            anomalyCount = anomaly,
            bloodPressureGroupCount = sessions.sumOf { it.bloodPressureGroupCount },
            heartRateBpm = hr,
            profileMissing = sessions.any { it.profileMissing },
        )
    }

    private fun heartRateFromEvidence(session: StoredCaptureSession): Double? {
        val lease = CaptureSessionAccessRegistry.app.tryAcquire(session.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)
            ?: return null
        return lease.use {
            val metrics = CaptureSessionRepository.expectedFiles(session.directory).metrics
            if (metrics != null && java.nio.file.Files.isRegularFile(metrics)) {
                try {
                    val points = CaptureMetricSeries.readTimeline(metrics,
                        acceptedSessionIds = session.metadata.allowedRowSessionIds())
                    points.mapNotNull { it.heartRateBpm }.takeIf { it.isNotEmpty() }?.average()?.let { return it }
                } catch (error: Exception) {
                    if (error is java.util.concurrent.CancellationException) throw error
                }
            }
            CaptureSessionOfflineAnalysisService.listSummariesUnderLease(session.directory, {})
                .firstOrNull { it.state == CaptureArtifactReadState.READY &&
                    it.sourceSessionId in session.metadata.allowedRowSessionIds() }?.heartRateBpm
        }
    }

    private fun bloodPressureCount(session: StoredCaptureSession): Int {
        val path = CaptureSessionRepository.expectedFiles(session.directory).bloodPressure
            ?: return 0
        val lease = CaptureSessionAccessRegistry.app.tryAcquire(session.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)
            ?: return 0
        return lease.use {
            try { CaptureBloodPressureSeries.scan(path, session.metadata.allowedRowSessionIds()).completeDataRowCount.toInt() }
            catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                0
            }
        }
    }
}
