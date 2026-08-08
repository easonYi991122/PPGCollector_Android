package com.example.ppgcollector_android.data.session

/** Editable capture identity form. Text is kept as text until start validation. */
data class CaptureParticipantDraft(
    val sex: String = "",
    val ageYears: String = "",
    val heightCm: String = "",
    val weightKg: String = "",
    val additionalFields: Map<String, String> = emptyMap(),
) {
    val isComplete: Boolean
        get() = sex.isNotBlank() && ageYears.toIntOrNull() != null &&
            heightCm.toDoubleOrNull()?.let { it > 0.0 } == true &&
            weightKg.toDoubleOrNull()?.let { it > 0.0 } == true

    fun validationErrors(): List<String> = buildList {
        if (sex.isBlank()) add("性别未填写")
        val age = ageYears.toIntOrNull()
        if (age == null || age !in 0..150) add("年龄需为 0–150 的整数")
        val height = heightCm.toDoubleOrNull()
        if (height == null || !height.isFinite() || height <= 0.0) add("身高需为正数")
        val weight = weightKg.toDoubleOrNull()
        if (weight == null || !weight.isFinite() || weight <= 0.0) add("体重需为正数")
        if (additionalFields.size > SubjectProfileStore.maximumAdditionalFields) {
            add("扩展资料过多")
        }
    }

    fun toSnapshot(identity: CanonicalSessionIdentity?, revisionId: String? = null): CaptureParticipantSnapshot {
        val age = ageYears.toIntOrNull()?.takeIf { it in 0..150 }
        val height = heightCm.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val weight = weightKg.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        return CaptureParticipantSnapshot(
            subjectId = identity?.subject,
            sequence = identity?.sequence,
            profileRevisionId = revisionId,
            profileComplete = sex.isNotBlank() && age != null && height != null && weight != null,
            sex = sex.trim().takeIf(String::isNotEmpty),
            ageYears = age,
            heightCm = height,
            weightKg = weight,
            additionalFields = additionalFields
                .filterKeys { it.isNotBlank() }
                .mapKeys { it.key.trim() }
                .mapValues { it.value.trim() },
        )
    }

    companion object {
        fun fromSnapshot(snapshot: CaptureParticipantSnapshot?): CaptureParticipantDraft =
            CaptureParticipantDraft(
                sex = snapshot?.sex.orEmpty(),
                ageYears = snapshot?.ageYears?.toString().orEmpty(),
                heightCm = snapshot?.heightCm?.toString().orEmpty(),
                weightKg = snapshot?.weightKg?.toString().orEmpty(),
                additionalFields = snapshot?.additionalFields.orEmpty(),
            )
    }
}

data class CaptureSetupFormState(
    val sessionName: String = "",
    val nameValidation: SessionNameValidation = SessionNamePolicy.validateSyntax(""),
    val participant: CaptureParticipantDraft = CaptureParticipantDraft(),
    val profileSubject: String? = null,
    val profileRevisionId: String? = null,
) {
    val identity: CanonicalSessionIdentity?
        get() = SessionNamePolicy.parseCanonical(sessionName)
}

object CaptureSetupPolicy {
    fun validateName(name: String, sessionsRoot: java.nio.file.Path): SessionNameValidation =
        SessionNamePolicy.validate(name, sessionsRoot)

    fun suggestion(sessionsRoot: java.nio.file.Path): String =
        SessionNamePolicy.suggestedBaseName(sessionsRoot).orEmpty()
}
