package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Cross-platform session stem policy. The stem is deliberately stricter than
 * a platform filename so a capture can be copied to Windows/macOS/Linux
 * without changing its identity or colliding only by case.
 */
data class CanonicalSessionIdentity(
    val subject: String,
    val sequence: Long,
    val prefix: SessionNamePrefix = SessionNamePrefix.PPG,
)

enum class SessionNamePrefix(val wireValue: String, val displayName: String) {
    PPG("PPG", "指尖 PPG"),
    MB("MB", "腕部脉搏"),
    ;

    companion object {
        fun fromWireValue(value: String): SessionNamePrefix? =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
    }
}

enum class SessionNameInvalidReason {
    EMPTY,
    TOO_LONG,
    ILLEGAL_CHARACTER,
    RESERVED_NAME,
    INVALID_SEQUENCE,
}

data class SessionNameValidation(
    val name: String,
    val invalidReason: SessionNameInvalidReason? = null,
    val duplicate: Boolean = false,
) {
    val isValid: Boolean get() = invalidReason == null && !duplicate
}

object SessionNamePolicy {
    const val maximumLength = 64
    /** Example shown before the first real canonical session exists. */
    const val exampleSuggestedName = "PPG-subject-seq"
    private val allowed = Regex("[A-Za-z0-9_-]{1,$maximumLength}")
    private val canonical = Regex(
        "(PPG|MB)-([A-Za-z0-9_][A-Za-z0-9_-]*)-([1-9][0-9]*)",
        RegexOption.IGNORE_CASE,
    )
    private val reserved = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach {
            add("COM$it")
            add("LPT$it")
        }
    }

    fun validateSyntax(name: String): SessionNameValidation {
        val reason = when {
            name.isEmpty() -> SessionNameInvalidReason.EMPTY
            name.length > maximumLength -> SessionNameInvalidReason.TOO_LONG
            !allowed.matches(name) -> SessionNameInvalidReason.ILLEGAL_CHARACTER
            name.uppercase(Locale.ROOT) in reserved -> SessionNameInvalidReason.RESERVED_NAME
            else -> null
        }
        return SessionNameValidation(name, reason)
    }

    fun validate(name: String, sessionsRoot: Path? = null): SessionNameValidation {
        val syntax = validateSyntax(name)
        if (!syntax.isValid || sessionsRoot == null) return syntax
        return syntax.copy(duplicate = isDuplicate(name, sessionsRoot))
    }

    fun isValid(name: String): Boolean = validateSyntax(name).isValid

    fun duplicateKey(name: String): String = name.lowercase(Locale.ROOT)

    fun isDuplicate(name: String, sessionsRoot: Path): Boolean {
        if (!Files.isDirectory(sessionsRoot)) return false
        val key = duplicateKey(name)
        Files.list(sessionsRoot).use { entries ->
            return entries.anyMatch { path ->
                Files.isDirectory(path) && duplicateKey(path.fileName.toString()) == key
            }
        }
    }

    fun parseCanonical(name: String): CanonicalSessionIdentity? {
        val match = canonical.matchEntire(name) ?: return null
        val prefix = SessionNamePrefix.fromWireValue(match.groupValues[1]) ?: return null
        val sequence = match.groupValues[3].toLongOrNull() ?: return null
        if (sequence < 1L) return null
        return CanonicalSessionIdentity(match.groupValues[2], sequence, prefix)
    }

    /** Canonicalizes the prefix while preserving the user-supplied subject spelling. */
    fun normalizeCanonical(name: String): String? =
        parseCanonical(name)?.let { identity ->
            "${identity.prefix.wireValue}-${identity.subject}-${identity.sequence}"
        }

    fun isLogicalDuplicate(
        name: String,
        sessionsRoot: Path,
        excludeName: String? = null,
    ): Boolean {
        val identity = parseCanonical(name) ?: return false
        if (!Files.isDirectory(sessionsRoot)) return false
        val excluded = excludeName?.let(::duplicateKey)
        return CaptureSessionRepository.listSessions(sessionsRoot).any { session ->
            duplicateKey(session.baseName) != excluded &&
                (session.metadata?.rawChunkCount ?: 0L) > 0L &&
                parseCanonical(session.baseName) == identity
        }
    }

    fun nextSequenceForSubject(
        sessionsRoot: Path,
        prefix: SessionNamePrefix,
        subject: String,
    ): Long {
        if (!Files.isDirectory(sessionsRoot)) return 1L
        return CaptureSessionRepository.listSessions(sessionsRoot)
            .asSequence()
            .filter { (it.metadata?.rawChunkCount ?: 0L) > 0L }
            .mapNotNull { parseCanonical(it.baseName) }
            .filter {
                it.prefix == prefix &&
                    it.subject.equals(subject, ignoreCase = true)
            }
            .maxOfOrNull { it.sequence }
            ?.plus(1L)
            ?: 1L
    }

    /** Returns the next real suggestion, or an explicit example for a new install. */
    fun suggestedBaseNameOrExample(
        sessionsRoot: Path,
        prefix: SessionNamePrefix = SessionNamePrefix.PPG,
    ): String =
        suggestedBaseNameForPrefix(sessionsRoot, prefix)
            ?: "${prefix.wireValue}-subject-seq"

    fun suggestedBaseNameForPrefix(
        sessionsRoot: Path,
        prefix: SessionNamePrefix,
    ): String? {
        if (!Files.isDirectory(sessionsRoot)) return null
        val candidates = CaptureSessionRepository.listSessions(sessionsRoot)
            .asSequence()
            .filter { (it.metadata?.rawChunkCount ?: 0L) > 0L }
            .mapNotNull { session ->
                parseCanonical(session.baseName)?.let { identity -> session to identity }
            }
            .filter { it.second.prefix == prefix }
            .toList()
        val last = candidates.maxWithOrNull(
            compareBy<Pair<StoredCaptureSession, CanonicalSessionIdentity>> { it.first.modifiedAt }
                .thenBy { it.second.sequence },
        ) ?: return null
        val subject = last.second.subject
        val next = nextSequenceForSubject(sessionsRoot, prefix, subject)
        return "${prefix.wireValue}-$subject-$next"
    }

    /**
     * Rebuilds the next suggestion from actual sessions. Empty reservations do
     * not advance a sequence, so a failed start cannot consume a subject ID.
     */
    fun suggestedBaseName(sessionsRoot: Path): String? {
        if (!Files.isDirectory(sessionsRoot)) return null
        val candidates = CaptureSessionRepository.listSessions(sessionsRoot)
            .asSequence()
            .filter { (it.metadata?.rawChunkCount ?: 0L) > 0L }
            .mapNotNull { session ->
                parseCanonical(session.baseName)?.let { identity -> session to identity }
            }
            .toList()
        val last = candidates.maxWithOrNull(
            compareBy<Pair<StoredCaptureSession, CanonicalSessionIdentity>> { it.first.modifiedAt }
                .thenBy { it.second.sequence },
        ) ?: return null
        val subject = last.second.subject
        val subjectKey = subject.lowercase(Locale.ROOT)
        val prefix = last.second.prefix
        val next = candidates
            .asSequence()
            .filter {
                it.second.prefix == prefix &&
                    it.second.subject.lowercase(Locale.ROOT) == subjectKey
            }
            .maxOfOrNull { it.second.sequence }
            ?.plus(1L)
            ?: 1L
        return "${prefix.wireValue}-$subject-$next"
    }

    fun suggestedBaseNameForSubject(
        sessionsRoot: Path,
        subject: String,
        prefix: SessionNamePrefix = SessionNamePrefix.PPG,
    ): String? {
        if (subject.isEmpty() || !Regex("[A-Za-z0-9_][A-Za-z0-9_-]*").matches(subject)) return null
        return "$prefix-$subject-${nextSequenceForSubject(sessionsRoot, prefix, subject)}"
    }
}
