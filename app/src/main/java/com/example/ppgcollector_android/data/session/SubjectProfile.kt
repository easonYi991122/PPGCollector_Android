package com.example.ppgcollector_android.data.session

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

data class SubjectProfileRevision(
    val revisionId: String,
    val createdUtc: Instant,
    val sex: String?,
    val genderCode: Int? = genderCodeFor(sex.orEmpty()),
    val ageYears: Int?,
    val heightCm: Double?,
    val weightKg: Double?,
    val smokingFreq: String = "",
    val drinkingFreq: String = "",
    val additionalFields: Map<String, String> = emptyMap(),
) {
    val isComplete: Boolean
        get() = !sex.isNullOrBlank() && ageYears != null && heightCm != null && weightKg != null

    fun asParticipantSnapshot(subject: String) = CaptureParticipantSnapshot(
        subjectId = subject,
        profileRevisionId = revisionId,
        profileComplete = isComplete,
        sex = sex,
        genderCode = genderCode,
        ageYears = ageYears,
        heightCm = heightCm,
        weightKg = weightKg,
        smokingFreq = smokingFreq,
        drinkingFreq = drinkingFreq,
        additionalFields = additionalFields,
    )
}

data class SubjectProfile(
    val schemaVersion: String = SubjectProfileStore.schemaVersion,
    val subject: String,
    val currentRevision: String?,
    val updatedUtc: Instant,
    val revisions: List<SubjectProfileRevision>,
) {
    val latest: SubjectProfileRevision?
        get() = currentRevision?.let { id -> revisions.lastOrNull { it.revisionId == id } }
            ?: revisions.lastOrNull()
}

class SubjectProfileStore(private val root: Path) {
    init {
        Files.createDirectories(root)
    }

    fun pathFor(subject: String): Path {
        require(SUBJECT_PATTERN.matches(subject)) { "invalid subject id" }
        return root.resolve("$subject.profile.json")
    }

    fun read(subject: String): SubjectProfile? {
        val path = pathFor(subject)
        if (!Files.isRegularFile(path)) return null
        return SubjectProfileCodec.decode(Files.readAllBytes(path))
    }

    fun saveRevision(
        subject: String,
        sex: String?,
        ageYears: Int?,
        heightCm: Double?,
        weightKg: Double?,
        smokingFreq: String = "",
        drinkingFreq: String = "",
        additionalFields: Map<String, String> = emptyMap(),
        revisionId: String = UUID.randomUUID().toString(),
        now: Instant = Instant.now(),
    ): SubjectProfile {
        val profileAdditionalFields = additionalFields.withoutSessionScopedParticipantFields()
        validateRevision(
            sex, ageYears, heightCm, weightKg, smokingFreq, drinkingFreq, profileAdditionalFields,
        )
        val previous = read(subject)
        val revision = SubjectProfileRevision(
            revisionId = revisionId,
            createdUtc = now,
            sex = sex,
            genderCode = genderCodeFor(sex.orEmpty()),
            ageYears = ageYears,
            heightCm = heightCm,
            weightKg = weightKg,
            smokingFreq = smokingFreq,
            drinkingFreq = drinkingFreq,
            additionalFields = profileAdditionalFields.toSortedMap(),
        )
        val profile = SubjectProfile(
            subject = subject,
            currentRevision = revisionId,
            updatedUtc = now,
            revisions = (previous?.revisions.orEmpty() + revision).takeLast(maximumRevisions),
        )
        writeAtomically(pathFor(subject), profile)
        return profile
    }

    private fun validateRevision(
        sex: String?,
        ageYears: Int?,
        heightCm: Double?,
        weightKg: Double?,
        smokingFreq: String,
        drinkingFreq: String,
        additionalFields: Map<String, String>,
    ) {
        require(additionalFields.size <= maximumAdditionalFields) { "too many additional fields" }
        require(additionalFields.keys.all { it.isNotBlank() && it.length <= maximumFieldKeyLength }) {
            "invalid additional field key"
        }
        require(additionalFields.values.all { it.length <= maximumFieldValueLength }) {
            "additional field value is too long"
        }
        require(smokingFreq in CaptureParticipantDraft.smokingOptions) {
            "invalid smoking frequency"
        }
        require(drinkingFreq in CaptureParticipantDraft.drinkingOptions) {
            "invalid drinking frequency"
        }
        require(ageYears == null || ageYears in 0..150) { "age is out of technical range" }
        require(heightCm == null || heightCm.isFinite() && heightCm > 0.0) { "height is invalid" }
        require(weightKg == null || weightKg.isFinite() && weightKg > 0.0) { "weight is invalid" }
    }

    private fun writeAtomically(path: Path, profile: SubjectProfile) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        val bytes = SubjectProfileCodec.encode(profile).toByteArray(Charsets.UTF_8)
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
                channel.force(true)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val schemaVersion = "ppgcollector_subject_profile_v1"
        const val maximumRevisions = 128
        const val maximumAdditionalFields = 32
        const val maximumFieldKeyLength = 64
        const val maximumFieldValueLength = 512
        private val SUBJECT_PATTERN = Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
    }
}

private object SubjectProfileCodec {
    fun encode(profile: SubjectProfile): String = JsonWriter.write(toJson(profile))

    fun decode(bytes: ByteArray): SubjectProfile = fromJson(JsonParser(bytes.toString(Charsets.UTF_8)).parse())

    private fun toJson(profile: SubjectProfile): JsonValue.ObjectValue = JsonValue.ObjectValue(
        mapOf(
            "schema_version" to JsonValue.StringValue(profile.schemaVersion),
            "subject" to JsonValue.StringValue(profile.subject),
            "current_revision" to (profile.currentRevision?.let(JsonValue::StringValue)
                ?: JsonValue.NullValue),
            "updated_utc" to JsonValue.StringValue(profile.updatedUtc.toString()),
            "revisions" to JsonValue.ArrayValue(profile.revisions.map(::toJson)),
        ),
    )

    private fun toJson(revision: SubjectProfileRevision): JsonValue.ObjectValue = JsonValue.ObjectValue(
        mapOf(
            "revision_id" to JsonValue.StringValue(revision.revisionId),
            "created_utc" to JsonValue.StringValue(revision.createdUtc.toString()),
            "sex" to (revision.sex?.let(JsonValue::StringValue) ?: JsonValue.NullValue),
            "gender_code" to (revision.genderCode?.let { JsonValue.NumberValue(it.toString()) }
                ?: JsonValue.NullValue),
            "age_years" to (revision.ageYears?.let { JsonValue.NumberValue(it.toString()) }
                ?: JsonValue.NullValue),
            "height_cm" to (revision.heightCm?.let { JsonValue.NumberValue(it.toString()) }
                ?: JsonValue.NullValue),
            "weight_kg" to (revision.weightKg?.let { JsonValue.NumberValue(it.toString()) }
                ?: JsonValue.NullValue),
            "smoking_freq" to JsonValue.StringValue(revision.smokingFreq),
            "drinking_freq" to JsonValue.StringValue(revision.drinkingFreq),
            "additional_fields" to JsonValue.ObjectValue(
                revision.additionalFields.toSortedMap().mapValues { JsonValue.StringValue(it.value) },
            ),
        ),
    )

    private fun fromJson(value: JsonValue): SubjectProfile {
        val root = value.asObject("subject_profile")
        val revisions = root.requiredArray("revisions").values.map { child ->
            val item = child.asObject("revision")
            SubjectProfileRevision(
                revisionId = item.requiredString("revision_id"),
                createdUtc = item.requiredString("created_utc").toInstant("created_utc"),
                sex = item.optionalString("sex"),
                genderCode = item.optionalLong("gender_code")?.toIntChecked("gender_code")
                    ?: genderCodeFor(item.optionalString("sex").orEmpty()),
                ageYears = item.optionalLong("age_years")?.toIntChecked("age_years"),
                heightCm = item.optionalDouble("height_cm"),
                weightKg = item.optionalDouble("weight_kg"),
                smokingFreq = item.optionalString("smoking_freq").orEmpty(),
                drinkingFreq = item.optionalString("drinking_freq").orEmpty(),
                additionalFields = item.optionalObject("additional_fields")?.fields.orEmpty()
                    .mapValues { (key, childValue) -> childValue.asString("additional_fields.$key") },
            )
        }
        return SubjectProfile(
            schemaVersion = root.requiredString("schema_version"),
            subject = root.requiredString("subject"),
            currentRevision = root.optionalString("current_revision"),
            updatedUtc = root.requiredString("updated_utc").toInstant("updated_utc"),
            revisions = revisions,
        )
    }

    private fun JsonValue.ObjectValue.requiredString(name: String) = field(name).asString(name)
    private fun JsonValue.ObjectValue.optionalString(name: String): String? =
        fields[name]?.let { if (it is JsonValue.NullValue) null else it.asString(name) }
    private fun JsonValue.ObjectValue.optionalLong(name: String): Long? =
        fields[name]?.let { if (it is JsonValue.NullValue) null else it.asNumber(name).toLongOrNull() }
    private fun JsonValue.ObjectValue.optionalDouble(name: String): Double? =
        fields[name]?.let { if (it is JsonValue.NullValue) null else it.asNumber(name).toDoubleOrNull() }
    private fun JsonValue.ObjectValue.requiredArray(name: String) = field(name).asArray(name)
    private fun JsonValue.ObjectValue.optionalObject(name: String): JsonValue.ObjectValue? =
        fields[name]?.let { if (it is JsonValue.NullValue) null else it.asObject(name) }
    private fun JsonValue.ObjectValue.field(name: String): JsonValue =
        fields[name] ?: throw CaptureSessionMetadataJsonException("missing field: $name")
    private fun JsonValue.asObject(name: String) = this as? JsonValue.ObjectValue
        ?: throw CaptureSessionMetadataJsonException("$name must be an object")
    private fun JsonValue.asArray(name: String) = this as? JsonValue.ArrayValue
        ?: throw CaptureSessionMetadataJsonException("$name must be an array")
    private fun JsonValue.asString(name: String) = (this as? JsonValue.StringValue)?.value
        ?: throw CaptureSessionMetadataJsonException("$name must be a string")
    private fun JsonValue.asNumber(name: String) = (this as? JsonValue.NumberValue)?.raw
        ?: throw CaptureSessionMetadataJsonException("$name must be a number")
    private fun String.toInstant(name: String): Instant = runCatching { Instant.parse(this) }
        .getOrElse { throw CaptureSessionMetadataJsonException("invalid $name") }
    private fun Long.toIntChecked(name: String): Int = toInt().also {
        if (toLong() != this) throw CaptureSessionMetadataJsonException("$name out of range")
    }
}
