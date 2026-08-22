package com.example.ppgcollector_android.data.session

/** Editable capture identity form. Text is kept as text until start validation. */
enum class CaptureParticipantValidationCategory {
    PROFILE,
    REFERENCE_BLOOD_PRESSURE,
}

const val SESSION_NOTES_FIELD = "notes"

fun Map<String, String>.withoutSessionScopedParticipantFields(): Map<String, String> =
    filterKeys { it != SESSION_NOTES_FIELD }

fun Map<String, String>.sessionScopedParticipantFields(): Map<String, String> =
    filterKeys { it == SESSION_NOTES_FIELD }

data class CaptureParticipantValidationIssue(
    val category: CaptureParticipantValidationCategory,
    val message: String,
)

data class CaptureParticipantDraft(
    val sex: String = "",
    val ageYears: String = "",
    val heightCm: String = "",
    val weightKg: String = "",
    val smokingFreq: String = "",
    val drinkingFreq: String = "",
    val systolicBp: String = "",
    val diastolicBp: String = "",
    val additionalFields: Map<String, String> = emptyMap(),
) {
    companion object {
        val smokingOptions = listOf("", "不吸烟", "偶尔", "经常", "每天")
        val drinkingOptions = listOf("", "不饮酒", "偶尔", "经常", "每天")
        val genderOptions = listOf("男", "女")
        fun fromSnapshot(snapshot: CaptureParticipantSnapshot?): CaptureParticipantDraft =
            CaptureParticipantDraft(
                sex = snapshot?.sex?.takeIf { it in genderOptions }.orEmpty(),
                ageYears = snapshot?.ageYears?.toString().orEmpty(),
                heightCm = snapshot?.heightCm?.toString().orEmpty(),
                weightKg = snapshot?.weightKg?.toString().orEmpty(),
                smokingFreq = snapshot?.smokingFreq.orEmpty(),
                drinkingFreq = snapshot?.drinkingFreq.orEmpty(),
                additionalFields = snapshot?.additionalFields.orEmpty()
                    .withoutSessionScopedParticipantFields(),
            )
    }

    val isComplete: Boolean
        get() = sex.isNotBlank() && ageYears.toIntOrNull() != null &&
            heightCm.toDoubleOrNull()?.let { it > 0.0 } == true &&
            weightKg.toDoubleOrNull()?.let { it > 0.0 } == true

    fun validationIssues(): List<CaptureParticipantValidationIssue> = buildList {
        fun profile(message: String) = add(
            CaptureParticipantValidationIssue(
                CaptureParticipantValidationCategory.PROFILE,
                message,
            ),
        )
        if (sex !in genderOptions) profile("性别需选择男或女")
        val age = ageYears.toIntOrNull()
        if (age == null || age !in 0..150) profile("年龄需为 0–150 的整数")
        val height = heightCm.toDoubleOrNull()
        if (height == null || !height.isFinite() || height <= 0.0) profile("身高需为正数")
        val weight = weightKg.toDoubleOrNull()
        if (weight == null || !weight.isFinite() || weight <= 0.0) profile("体重需为正数")
        if (additionalFields.size > SubjectProfileStore.maximumAdditionalFields) {
            profile("扩展资料过多")
        }
        if (smokingFreq !in smokingOptions) profile("吸烟频率取值无效")
        if (drinkingFreq !in drinkingOptions) profile("饮酒频率取值无效")
        bloodPressureValidationError(systolicBp, diastolicBp)?.let {
            add(
                CaptureParticipantValidationIssue(
                    CaptureParticipantValidationCategory.REFERENCE_BLOOD_PRESSURE,
                    it,
                ),
            )
        }
    }

    fun validationErrors(): List<String> = validationIssues().map { it.message }

    /** Start gating intentionally excludes optional/manual reference BP issues. */
    fun blockingValidationErrors(): List<String> = validationIssues()
        .filter { it.category != CaptureParticipantValidationCategory.REFERENCE_BLOOD_PRESSURE }
        .map { it.message }

    fun clearSessionScopedFields(): CaptureParticipantDraft = copy(
        systolicBp = "",
        diastolicBp = "",
        additionalFields = additionalFields.withoutSessionScopedParticipantFields(),
    )

    fun toSnapshot(
        identity: CanonicalSessionIdentity?,
        revisionId: String? = null,
    ): CaptureParticipantSnapshot {
        val age = ageYears.toIntOrNull()?.takeIf { it in 0..150 }
        val height = heightCm.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val weight = weightKg.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        return CaptureParticipantSnapshot(
            subjectId = identity?.subject,
            sequence = identity?.sequence,
            profileRevisionId = revisionId,
            profileComplete = sex in genderOptions && age != null && height != null && weight != null,
            sex = sex.takeIf { it in genderOptions },
            genderCode = genderCodeFor(sex),
            smokingFreq = smokingFreq.takeIf { it in smokingOptions && it.isNotEmpty() }.orEmpty(),
            drinkingFreq = drinkingFreq.takeIf { it in drinkingOptions && it.isNotEmpty() }.orEmpty(),
            ageYears = age,
            heightCm = height,
            weightKg = weight,
            additionalFields = additionalFields
                .filterKeys { it.isNotBlank() }
                .mapKeys { it.key.trim() }
                .mapValues { it.value.trim() },
        )
    }

}

fun genderCodeFor(sex: String): Int? = when (sex) {
    "男" -> 1
    "女" -> 0
    else -> null
}

fun bloodPressureValidationError(systolic: String, diastolic: String): String? {
    if (systolic.isBlank() && diastolic.isBlank()) return null
    val sbp = systolic.toIntOrNull()
    val dbp = diastolic.toIntOrNull()
    return when {
        sbp == null || dbp == null -> "血压需同时填写正整数"
        sbp !in 20..300 || dbp !in 10..250 -> "血压范围：收缩压 20–300、舒张压 10–250 mmHg"
        sbp <= dbp -> "收缩压须高于舒张压"
        else -> null
    }
}

fun CaptureParticipantDraft.referenceBloodPressure(): Pair<Int, Int>? =
    if (systolicBp.isBlank() && diastolicBp.isBlank()) {
        null
    } else {
        val error = bloodPressureValidationError(systolicBp, diastolicBp)
        if (error != null) null else systolicBp.toInt() to diastolicBp.toInt()
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
        SessionNamePolicy.suggestedBaseNameOrExample(sessionsRoot)
}
