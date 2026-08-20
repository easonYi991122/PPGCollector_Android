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
            // The prefix is part of the directory/base-name identity. Metadata
            // predates MB and stores only subject+sequence, so constructing a
            // CanonicalSessionIdentity from metadata alone silently defaults
            // every MB session to PPG.
            val parsedName = SessionNamePolicy.parseCanonical(session.baseName)
            val identity = session.metadata?.let { metadata ->
                if (metadata.canonicalSubjectId != null && metadata.canonicalSequence != null) {
                    val prefix = parsedName?.prefix ?: SessionNamePrefix.PPG
                    CanonicalSessionIdentity(
                        subject = metadata.canonicalSubjectId,
                        sequence = metadata.canonicalSequence,
                        prefix = prefix,
                    )
                } else null
            } ?: parsedName
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
        val metrics = CaptureSessionRepository.expectedFiles(session.directory).metrics
        if (metrics != null && java.nio.file.Files.isRegularFile(metrics)) {
            val average = runCatching {
                java.nio.file.Files.newBufferedReader(metrics).use { reader ->
                    val header = reader.readLine()?.let(::parseSessionCsvFields).orEmpty()
                    val valueIndex = header.indexOf("heart_rate_bpm")
                    val validIndex = header.indexOf("heart_rate_valid")
                    if (valueIndex < 0 || validIndex < 0) return@use null
                    var count = 0L
                    var sum = 0.0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val fields = parseSessionCsvFields(line)
                        if (fields.getOrNull(validIndex) == "true") {
                            val value = fields.getOrNull(valueIndex)?.toDoubleOrNull()
                                ?.takeIf(Double::isFinite)
                            if (value != null) {
                                sum += value
                                count++
                            }
                        }
                    }
                    if (count == 0L) null else sum / count
                }
            }.getOrNull()
            if (average != null) return average
        }
        return runCatching {
            CaptureSessionOfflineAnalysisService.listArtifacts(session)
                .maxByOrNull { it.report.endedUtc }
                ?.report?.metrics?.heartRateBpm
        }.getOrNull()
    }

    private fun bloodPressureCount(session: StoredCaptureSession): Int {
        val path = CaptureSessionRepository.expectedFiles(session.directory).bloodPressure
            ?: return 0
        return runCatching { CaptureBloodPressureSeries.scan(path).completeDataRowCount.toInt() }
            .getOrDefault(0)
    }
}
