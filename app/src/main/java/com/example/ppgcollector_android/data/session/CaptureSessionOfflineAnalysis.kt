package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.signal.OfflineAverageCycle
import com.example.ppgcollector_android.core.signal.OfflinePpgAnalysis
import com.example.ppgcollector_android.core.signal.OfflinePpgAnalyzer
import com.example.ppgcollector_android.core.signal.OfflinePpgInput
import com.example.ppgcollector_android.core.signal.OfflinePpgPreview
import com.example.ppgcollector_android.core.signal.OfflinePulseWindow
import com.example.ppgcollector_android.core.signal.OfflineSignalSegment
import com.example.ppgcollector_android.core.signal.OfflineSpectrum
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.stream.Collectors

enum class CaptureAnalysisStage(val title: String) {
    HASHING("校验 raw"),
    REPLAYING("重放 raw"),
    ANALYZING("稳定段分析"),
    SAVING("保存版本化结果"),
}

data class CaptureSessionAnalysisProgress(
    val stage: CaptureAnalysisStage,
    val fractionCompleted: Double,
    val processedSampleCount: Int,
    val completedWindowCount: Int,
    val totalWindowCount: Int,
)

data class CaptureSessionAnalysisInputReport(
    val rawRecordCount: Long,
    val rawPayloadBytes: Long,
    val decodedFrameCount: Int,
    val acceptedFrameCount: Int,
    val acceptedSampleCount: Long,
    val missingFrameCount: Int,
    val duplicateFrameCount: Int,
    val outOfOrderFrameCount: Int,
    val structurallyInvalidFrameCount: Int,
    val leadingAlignmentByteCount: Int,
    val structuralDiscardedByteCount: Int,
    val pendingDecoderByteCount: Int,
    val trailingRawByteCount: Long,
)

data class CaptureSessionAnalysisMetrics(
    val segmentCount: Int,
    val stableSampleRatio: Double,
    val windowCount: Int,
    val acceptedWindowCount: Int,
    val rejectionCounts: Map<String, Int>,
    val selectedChannel: String?,
    val selectedPolarity: String?,
    val peakCount: Int,
    val heartRateBpm: Double?,
    val spectralHeartRateBpm: Double?,
    val confidence: Double,
    val snrDb: Double?,
    val rrMadSeconds: Double?,
    val averageCycleCount: Int,
)

data class CaptureSessionAnalysisPeak(
    val sampleIndex: Int,
    val timeSeconds: Double,
)

data class CaptureSessionAnalysisReport(
    val schemaVersion: String,
    val analysisId: String,
    val sourceSessionId: String,
    val sourceBaseName: String,
    val sourceRawSha256: String,
    val analysisProfile: String,
    val algorithmVersion: String,
    val preprocessProfile: String,
    val startedUtc: Instant,
    val endedUtc: Instant,
    val warnings: List<String>,
    val input: CaptureSessionAnalysisInputReport,
    val metrics: CaptureSessionAnalysisMetrics,
    val segments: List<OfflineSignalSegment>,
    val windows: List<OfflinePulseWindow>,
    val peaks: List<CaptureSessionAnalysisPeak>,
    val spectrum: OfflineSpectrum,
    val averageCycle: OfflineAverageCycle,
    val preview: OfflinePpgPreview,
)

data class CaptureSessionAnalysisArtifact(
    val path: Path,
    val report: CaptureSessionAnalysisReport,
)

data class CaptureSessionSignalTrace(
    val timeSeconds: DoubleArray,
    val rawRed: DoubleArray,
    val rawIr: DoubleArray,
    val filteredRed: DoubleArray,
    val filteredIr: DoubleArray,
    val breakIndices: IntArray,
    val replay: CupRawReplayReport,
    val preprocessProfile: String,
)

object CaptureSessionOfflineAnalysisService {
    const val schemaVersion = "ppgcollector_analysis_v1"
    private const val maximumAcceptedSamples = 1_500_000
    private val fileTimestamp = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    fun analyzeAndSave(
        session: StoredCaptureSession,
        progress: (CaptureSessionAnalysisProgress) -> Unit = {},
        cancellationCheck: () -> Unit = {},
        now: () -> Instant = Instant::now,
    ): CaptureSessionAnalysisArtifact {
        val files = CaptureSessionRepository.expectedFiles(session.directory)
        require(Files.isRegularFile(files.raw)) { "会话缺少 raw 文件" }
        val started = now()
        progress(CaptureSessionAnalysisProgress(CaptureAnalysisStage.HASHING, 0.01, 0, 0, 0))
        val rawSha = sha256(files.raw, cancellationCheck)
        cancellationCheck()

        val replayed = loadRawInput(
            path = files.raw,
            expectedSampleCount = session.metadata?.sampleCount,
            progress = progress,
            cancellationCheck = cancellationCheck,
        )
        cancellationCheck()
        var lastCompletedWindows = 0
        var totalWindows = 0
        val analysis = OfflinePpgAnalyzer.analyze(
            replayed.input,
            progress = { completed, total ->
                cancellationCheck()
                lastCompletedWindows = completed
                totalWindows = total
                val fraction = if (total == 0) 0.90 else 0.40 + 0.50 * completed / total.toDouble()
                progress(
                    CaptureSessionAnalysisProgress(
                        CaptureAnalysisStage.ANALYZING,
                        fraction.coerceIn(0.40, 0.90),
                        replayed.input.red.size,
                        completed,
                        total,
                    ),
                )
            },
            cancellationCheck = cancellationCheck,
        )
        cancellationCheck()
        val report = buildReport(
            session = session,
            rawSha = rawSha,
            replay = replayed.replay,
            input = replayed.input,
            analysis = analysis,
            started = started,
            ended = now(),
        )
        progress(
            CaptureSessionAnalysisProgress(
                CaptureAnalysisStage.SAVING,
                0.95,
                replayed.input.red.size,
                lastCompletedWindows,
                totalWindows,
            ),
        )
        cancellationCheck()
        val artifact = saveImmutable(session.directory, report, cancellationCheck)
        progress(
            CaptureSessionAnalysisProgress(
                CaptureAnalysisStage.SAVING,
                1.0,
                replayed.input.red.size,
                lastCompletedWindows,
                totalWindows,
            ),
        )
        return artifact
    }

    fun listArtifacts(session: StoredCaptureSession): List<CaptureSessionAnalysisArtifact> =
        listArtifacts(session.directory)

    fun listArtifacts(sessionDirectory: Path): List<CaptureSessionAnalysisArtifact> {
        val analysisDirectory = sessionDirectory.resolve("analysis")
        if (!Files.isDirectory(analysisDirectory)) return emptyList()
        Files.list(analysisDirectory).use { paths ->
            val artifacts = paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .map { path -> runCatching { readArtifact(path) }.getOrNull() }
                .collect(Collectors.toList())
                .filterNotNull()
            return artifacts.sortedWith(
                compareByDescending<CaptureSessionAnalysisArtifact> { it.report.endedUtc }
                    .thenByDescending { it.path.fileName.toString() },
            )
        }
    }

    fun readArtifact(path: Path): CaptureSessionAnalysisArtifact =
        CaptureSessionAnalysisArtifact(path, CaptureSessionAnalysisCodec.decode(readBounded(path)))

    /** Loads the complete accepted signal for interactive visualization only. */
    fun loadSignalTrace(
        session: StoredCaptureSession,
        cancellationCheck: () -> Unit = {},
    ): CaptureSessionSignalTrace {
        val files = CaptureSessionRepository.expectedFiles(session.directory)
        require(Files.isRegularFile(files.raw)) { "会话缺少 raw 文件" }
        val loaded = loadRawInput(
            path = files.raw,
            expectedSampleCount = session.metadata?.sampleCount,
            progress = {},
            cancellationCheck = cancellationCheck,
        )
        cancellationCheck()
        val filtered = OfflinePpgAnalyzer.filterFullSignal(loaded.input, cancellationCheck)
        return CaptureSessionSignalTrace(
            timeSeconds = loaded.input.timeSeconds,
            rawRed = loaded.input.red,
            rawIr = loaded.input.ir,
            filteredRed = filtered.red,
            filteredIr = filtered.ir,
            breakIndices = loaded.input.breakIndices,
            replay = loaded.replay,
            preprocessProfile = OfflinePpgAnalyzer.preprocessProfile,
        )
    }

    private fun loadRawInput(
        path: Path,
        expectedSampleCount: Long?,
        progress: (CaptureSessionAnalysisProgress) -> Unit,
        cancellationCheck: () -> Unit,
    ): LoadedRawInput {
        val expected = expectedSampleCount
            ?.coerceIn(0L, maximumAcceptedSamples.toLong())
            ?.toInt()
            ?: 8_192
        val red = DoubleArrayBuilder(expected)
        val ir = DoubleArrayBuilder(expected)
        val time = DoubleArrayBuilder(expected)
        val breaks = IntArrayBuilder()
        var logicalSampleIndex = 0L
        var previousFrameSequence: UByte? = null
        var lastPublishedFrame = 0
        val replay = CupRawReplayEngine.replay(path) { sample ->
            cancellationCheck()
            if (sample.sampleInFrame == 0) {
                previousFrameSequence?.let { previous ->
                    val delta = (sample.frameSequence.toInt() - previous.toInt()) and 0xFF
                    if (delta in 2 until 128) {
                        logicalSampleIndex += (delta - 1L) * sample.samplesPerFrame
                        breaks.add(red.size)
                    }
                }
                previousFrameSequence = sample.frameSequence
            }
            if (red.size >= maximumAcceptedSamples) {
                error("离线分析样本超过 $maximumAcceptedSamples 上限")
            }
            red.add(sample.sample.red.toDouble())
            ir.add(sample.sample.ir.toDouble())
            time.add(logicalSampleIndex / CupBatchProtocolV1.sampleRateHz.toDouble())
            logicalSampleIndex += 1
            if (sample.sampleInFrame == sample.samplesPerFrame - 1) {
                val completedFrames = red.size / sample.samplesPerFrame
                if (completedFrames - lastPublishedFrame >= 16) {
                    lastPublishedFrame = completedFrames
                    val expectedSamples = maxOf(red.size.toLong(), expectedSampleCount ?: red.size.toLong())
                    progress(
                        CaptureSessionAnalysisProgress(
                            CaptureAnalysisStage.REPLAYING,
                            (0.05 + 0.35 * red.size / expectedSamples.toDouble()).coerceIn(0.05, 0.40),
                            red.size,
                            0,
                            0,
                        ),
                    )
                }
            }
        }
        progress(CaptureSessionAnalysisProgress(CaptureAnalysisStage.REPLAYING, 0.40, red.size, 0, 0))
        return LoadedRawInput(
            input = OfflinePpgInput(time.toArray(), red.toArray(), ir.toArray(), breaks.toArray()),
            replay = replay,
        )
    }

    private fun buildReport(
        session: StoredCaptureSession,
        rawSha: String,
        replay: CupRawReplayReport,
        input: OfflinePpgInput,
        analysis: OfflinePpgAnalysis,
        started: Instant,
        ended: Instant,
    ): CaptureSessionAnalysisReport {
        val warnings = buildList {
            if (replay.leadingAlignmentBytes > 0) {
                add("raw 从通知帧中部开始，已跳过 ${replay.leadingAlignmentBytes} 个前导字节后对齐")
            }
            if (replay.pendingDecoderBytes > 0) {
                add("录制结束时保留 ${replay.pendingDecoderBytes} 个未凑满下一协议帧的尾部字节")
            }
            if (replay.tailIssue != null) add("raw 存在可恢复尾部，仅分析完整记录前缀")
            if (replay.structurallyInvalidFrames > 0 || replay.structuralDiscardedBytes > 0) {
                add("raw 结构异常：invalid=${replay.structurallyInvalidFrames}, discarded=${replay.structuralDiscardedBytes}")
            }
            if (replay.missingFrames > 0 || replay.duplicateFrames > 0 || replay.outOfOrderFrames > 0) {
                add("序号异常：missing=${replay.missingFrames}, duplicate=${replay.duplicateFrames}, out_of_order=${replay.outOfOrderFrames}")
            }
            if (analysis.segments.isEmpty()) add("没有达到 8 秒门槛的稳定段")
            if (analysis.windows.none(OfflinePulseWindow::accepted)) add("没有通过质量与主 BPM 聚类的窗口")
            if (analysis.bpm == null) add("证据不足，未发布会话级心率")
            add("IMU 未纳入：当前 CUP raw 没有已冻结的 IMU 数据契约")
        }
        return CaptureSessionAnalysisReport(
            schemaVersion = schemaVersion,
            analysisId = UUID.randomUUID().toString(),
            sourceSessionId = session.metadata?.sessionId ?: session.baseName,
            sourceBaseName = session.baseName,
            sourceRawSha256 = rawSha,
            analysisProfile = OfflinePpgAnalyzer.analysisProfile,
            algorithmVersion = OfflinePpgAnalyzer.algorithmVersion,
            preprocessProfile = OfflinePpgAnalyzer.preprocessProfile,
            startedUtc = started,
            endedUtc = ended,
            warnings = warnings,
            input = CaptureSessionAnalysisInputReport(
                rawRecordCount = replay.rawRecordCount,
                rawPayloadBytes = replay.rawPayloadBytes,
                decodedFrameCount = replay.decodedFrames,
                acceptedFrameCount = replay.acceptedFrames,
                acceptedSampleCount = replay.acceptedSamples,
                missingFrameCount = replay.missingFrames,
                duplicateFrameCount = replay.duplicateFrames,
                outOfOrderFrameCount = replay.outOfOrderFrames,
                structurallyInvalidFrameCount = replay.structurallyInvalidFrames,
                leadingAlignmentByteCount = replay.leadingAlignmentBytes,
                structuralDiscardedByteCount = replay.structuralDiscardedBytes,
                pendingDecoderByteCount = replay.pendingDecoderBytes,
                trailingRawByteCount = replay.trailingRawBytes,
            ),
            metrics = CaptureSessionAnalysisMetrics(
                segmentCount = analysis.segments.size,
                stableSampleRatio = analysis.stableSampleRatio,
                windowCount = analysis.windows.size,
                acceptedWindowCount = analysis.windows.count(OfflinePulseWindow::accepted),
                rejectionCounts = analysis.rejectionCounts,
                selectedChannel = analysis.selectedChannel,
                selectedPolarity = analysis.selectedPolarity,
                peakCount = analysis.peakIndices.size,
                heartRateBpm = analysis.bpm,
                spectralHeartRateBpm = analysis.spectralBpm,
                confidence = analysis.confidence,
                snrDb = analysis.snrDb,
                rrMadSeconds = analysis.rrMadSeconds,
                averageCycleCount = analysis.averageCycle.cycleCount,
            ),
            segments = analysis.segments,
            windows = analysis.windows,
            peaks = analysis.peakIndices.map { index ->
                CaptureSessionAnalysisPeak(index, input.timeSeconds[index])
            },
            spectrum = analysis.spectrum,
            averageCycle = analysis.averageCycle,
            preview = analysis.preview,
        )
    }

    private fun saveImmutable(
        sessionDirectory: Path,
        report: CaptureSessionAnalysisReport,
        cancellationCheck: () -> Unit,
    ): CaptureSessionAnalysisArtifact {
        val directory = sessionDirectory.resolve("analysis")
        Files.createDirectories(directory)
        val filename = "${fileTimestamp.format(report.endedUtc)}_${report.analysisProfile}_${report.analysisId}.json"
        val destination = directory.resolve(filename)
        val temporary = directory.resolve(".${report.analysisId}.tmp")
        try {
            Files.write(
                temporary,
                CaptureSessionAnalysisCodec.encode(report).toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            cancellationCheck()
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return CaptureSessionAnalysisArtifact(destination, report)
    }

    private fun sha256(path: Path, cancellationCheck: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancellationCheck()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readBounded(path: Path): String {
        val maximumBytes = 8 * 1024 * 1024
        val bytes = ByteArrayOutputStream()
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > maximumBytes) error("analysis JSON exceeds 8 MiB")
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private data class LoadedRawInput(
        val input: OfflinePpgInput,
        val replay: CupRawReplayReport,
    )

    private class DoubleArrayBuilder(initialCapacity: Int) {
        private var values = DoubleArray(maxOf(16, initialCapacity))
        var size: Int = 0
            private set

        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): DoubleArray = values.copyOf(size)
    }

    private class IntArrayBuilder(initialCapacity: Int = 16) {
        private var values = IntArray(initialCapacity)
        private var size = 0

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): IntArray = values.copyOf(size)
    }
}

internal object CaptureSessionAnalysisCodec {
    fun encode(report: CaptureSessionAnalysisReport): String = JsonWriter.write(toJson(report))

    fun decode(json: String): CaptureSessionAnalysisReport = fromJson(JsonParser(json).parse())

    private fun toJson(report: CaptureSessionAnalysisReport): JsonValue.ObjectValue = obj(
        "schema_version" to string(report.schemaVersion),
        "analysis_id" to string(report.analysisId),
        "source_session_id" to string(report.sourceSessionId),
        "source_base_name" to string(report.sourceBaseName),
        "source_raw_sha256" to string(report.sourceRawSha256),
        "analysis_profile" to string(report.analysisProfile),
        "algorithm_version" to string(report.algorithmVersion),
        "preprocess_profile" to string(report.preprocessProfile),
        "started_utc" to string(report.startedUtc.toString()),
        "ended_utc" to string(report.endedUtc.toString()),
        "warnings" to array(report.warnings.map(::string)),
        "input" to inputJson(report.input),
        "metrics" to metricsJson(report.metrics),
        "segments" to array(report.segments.map(::segmentJson)),
        "windows" to array(report.windows.map(::windowJson)),
        "peaks" to array(report.peaks.map(::peakJson)),
        "spectrum" to obj(
            "frequencies_hz" to numberArray(report.spectrum.frequenciesHz),
            "power" to numberArray(report.spectrum.power),
        ),
        "average_cycle" to cycleJson(report.averageCycle),
        "preview" to previewJson(report.preview),
    )

    private fun inputJson(value: CaptureSessionAnalysisInputReport) = obj(
        "raw_record_count" to number(value.rawRecordCount),
        "raw_payload_bytes" to number(value.rawPayloadBytes),
        "decoded_frame_count" to number(value.decodedFrameCount),
        "accepted_frame_count" to number(value.acceptedFrameCount),
        "accepted_sample_count" to number(value.acceptedSampleCount),
        "missing_frame_count" to number(value.missingFrameCount),
        "duplicate_frame_count" to number(value.duplicateFrameCount),
        "out_of_order_frame_count" to number(value.outOfOrderFrameCount),
        "structurally_invalid_frame_count" to number(value.structurallyInvalidFrameCount),
        "leading_alignment_byte_count" to number(value.leadingAlignmentByteCount),
        "structural_discarded_byte_count" to number(value.structuralDiscardedByteCount),
        "pending_decoder_byte_count" to number(value.pendingDecoderByteCount),
        "trailing_raw_byte_count" to number(value.trailingRawByteCount),
    )

    private fun metricsJson(value: CaptureSessionAnalysisMetrics) = obj(
        "segment_count" to number(value.segmentCount),
        "stable_sample_ratio" to number(value.stableSampleRatio),
        "window_count" to number(value.windowCount),
        "accepted_window_count" to number(value.acceptedWindowCount),
        "rejection_counts" to JsonValue.ObjectValue(
            value.rejectionCounts.mapValues { number(it.value) },
        ),
        "selected_channel" to nullableString(value.selectedChannel),
        "selected_polarity" to nullableString(value.selectedPolarity),
        "peak_count" to number(value.peakCount),
        "heart_rate_bpm" to nullableNumber(value.heartRateBpm),
        "spectral_heart_rate_bpm" to nullableNumber(value.spectralHeartRateBpm),
        "confidence" to number(value.confidence),
        "snr_db" to nullableNumber(value.snrDb),
        "rr_mad_seconds" to nullableNumber(value.rrMadSeconds),
        "average_cycle_count" to number(value.averageCycleCount),
    )

    private fun segmentJson(value: OfflineSignalSegment) = obj(
        "index" to number(value.index),
        "start_index" to number(value.startIndex),
        "stop_index" to number(value.stopIndex),
        "start_seconds" to number(value.startSeconds),
        "stop_seconds" to number(value.stopSeconds),
    )

    private fun windowJson(value: OfflinePulseWindow) = obj(
        "segment_index" to number(value.segmentIndex),
        "start_index" to number(value.startIndex),
        "stop_index" to number(value.stopIndex),
        "start_seconds" to number(value.startSeconds),
        "stop_seconds" to number(value.stopSeconds),
        "best_channel" to string(value.bestChannel),
        "used_channel" to nullableString(value.usedChannel),
        "red_peak_bpm" to nullableNumber(value.redPeakBpm),
        "ir_peak_bpm" to nullableNumber(value.irPeakBpm),
        "peak_bpm" to nullableNumber(value.peakBpm),
        "spectral_bpm" to nullableNumber(value.spectralBpm),
        "confidence" to number(value.confidence),
        "snr_db" to nullableNumber(value.snrDb),
        "polarity" to nullableString(value.polarity),
        "red_ac_dc_percent" to number(value.redAcDcPercent),
        "ir_ac_dc_percent" to number(value.irAcDcPercent),
        "accepted" to JsonValue.BooleanValue(value.accepted),
        "rejection_reason" to nullableString(value.rejectionReason),
    )

    private fun peakJson(value: CaptureSessionAnalysisPeak) = obj(
        "sample_index" to number(value.sampleIndex),
        "time_seconds" to number(value.timeSeconds),
    )

    private fun cycleJson(value: OfflineAverageCycle) = obj(
        "phase" to numberArray(value.phase),
        "mean" to numberArray(value.mean),
        "standard_deviation" to numberArray(value.standardDeviation),
        "ci95" to numberArray(value.ci95),
        "cycle_count" to number(value.cycleCount),
    )

    private fun previewJson(value: OfflinePpgPreview) = obj(
        "start_seconds" to number(value.startSeconds),
        "time_seconds" to numberArray(value.timeSeconds),
        "raw_red" to numberArray(value.rawRed),
        "raw_ir" to numberArray(value.rawIr),
        "filtered_red" to numberArray(value.filteredRed),
        "filtered_ir" to numberArray(value.filteredIr),
        "peak_indices" to array(value.peakIndices.map { number(it) }),
    )

    private fun fromJson(value: JsonValue): CaptureSessionAnalysisReport {
        val root = value.objectValue("analysis")
        val input = root.objectField("input")
        val metrics = root.objectField("metrics")
        val spectrum = root.objectField("spectrum")
        val cycle = root.objectField("average_cycle")
        val preview = root.objectField("preview")
        return CaptureSessionAnalysisReport(
            schemaVersion = root.stringField("schema_version"),
            analysisId = root.stringField("analysis_id"),
            sourceSessionId = root.stringField("source_session_id"),
            sourceBaseName = root.stringField("source_base_name"),
            sourceRawSha256 = root.stringField("source_raw_sha256"),
            analysisProfile = root.stringField("analysis_profile"),
            algorithmVersion = root.stringField("algorithm_version"),
            preprocessProfile = root.stringField("preprocess_profile"),
            startedUtc = Instant.parse(root.stringField("started_utc")),
            endedUtc = Instant.parse(root.stringField("ended_utc")),
            warnings = root.arrayField("warnings").map { it.stringValue("warning") },
            input = CaptureSessionAnalysisInputReport(
                input.longField("raw_record_count"), input.longField("raw_payload_bytes"),
                input.intField("decoded_frame_count"), input.intField("accepted_frame_count"),
                input.longField("accepted_sample_count"), input.intField("missing_frame_count"),
                input.intField("duplicate_frame_count"), input.intField("out_of_order_frame_count"),
                input.intField("structurally_invalid_frame_count"),
                input.intField("leading_alignment_byte_count"),
                input.intField("structural_discarded_byte_count"),
                input.intField("pending_decoder_byte_count"), input.longField("trailing_raw_byte_count"),
            ),
            metrics = CaptureSessionAnalysisMetrics(
                metrics.intField("segment_count"), metrics.doubleField("stable_sample_ratio"),
                metrics.intField("window_count"), metrics.intField("accepted_window_count"),
                metrics.objectField("rejection_counts").fields.mapValues { it.value.longValue(it.key).toInt() },
                metrics.nullableStringField("selected_channel"),
                metrics.nullableStringField("selected_polarity"), metrics.intField("peak_count"),
                metrics.nullableDoubleField("heart_rate_bpm"),
                metrics.nullableDoubleField("spectral_heart_rate_bpm"),
                metrics.doubleField("confidence"), metrics.nullableDoubleField("snr_db"),
                metrics.nullableDoubleField("rr_mad_seconds"), metrics.intField("average_cycle_count"),
            ),
            segments = root.arrayField("segments").map(::segmentFromJson),
            windows = root.arrayField("windows").map(::windowFromJson),
            peaks = root.arrayField("peaks").map { peak ->
                val item = peak.objectValue("peak")
                CaptureSessionAnalysisPeak(item.intField("sample_index"), item.doubleField("time_seconds"))
            },
            spectrum = OfflineSpectrum(
                spectrum.doubleArrayField("frequencies_hz"), spectrum.doubleArrayField("power"),
            ),
            averageCycle = OfflineAverageCycle(
                cycle.doubleArrayField("phase"), cycle.doubleArrayField("mean"),
                cycle.doubleArrayField("standard_deviation"), cycle.doubleArrayField("ci95"),
                cycle.intField("cycle_count"),
            ),
            preview = OfflinePpgPreview(
                preview.doubleField("start_seconds"), preview.doubleArrayField("time_seconds"),
                preview.doubleArrayField("raw_red"), preview.doubleArrayField("raw_ir"),
                preview.doubleArrayField("filtered_red"), preview.doubleArrayField("filtered_ir"),
                preview.arrayField("peak_indices").map { it.longValue("peak_index").toInt() }.toIntArray(),
            ),
        )
    }

    private fun segmentFromJson(value: JsonValue): OfflineSignalSegment {
        val item = value.objectValue("segment")
        return OfflineSignalSegment(
            item.intField("index"), item.intField("start_index"), item.intField("stop_index"),
            item.doubleField("start_seconds"), item.doubleField("stop_seconds"),
        )
    }

    private fun windowFromJson(value: JsonValue): OfflinePulseWindow {
        val item = value.objectValue("window")
        return OfflinePulseWindow(
            item.intField("segment_index"), item.intField("start_index"), item.intField("stop_index"),
            item.doubleField("start_seconds"), item.doubleField("stop_seconds"),
            item.stringField("best_channel"), item.nullableStringField("used_channel"),
            item.nullableDoubleField("red_peak_bpm"), item.nullableDoubleField("ir_peak_bpm"),
            item.nullableDoubleField("peak_bpm"), item.nullableDoubleField("spectral_bpm"),
            item.doubleField("confidence"), item.nullableDoubleField("snr_db"),
            item.nullableStringField("polarity"), item.doubleField("red_ac_dc_percent"),
            item.doubleField("ir_ac_dc_percent"), item.booleanField("accepted"),
            item.nullableStringField("rejection_reason"),
        )
    }

    private fun obj(vararg fields: Pair<String, JsonValue>) =
        JsonValue.ObjectValue(linkedMapOf(*fields))
    private fun array(values: List<JsonValue>) = JsonValue.ArrayValue(values)
    private fun string(value: String) = JsonValue.StringValue(value)
    private fun nullableString(value: String?) = value?.let(::string) ?: JsonValue.NullValue
    private fun number(value: Number) = JsonValue.NumberValue(value.toString())
    private fun nullableNumber(value: Double?) = value?.takeIf(Double::isFinite)?.let(::number)
        ?: JsonValue.NullValue
    private fun numberArray(values: DoubleArray) = array(values.map(::number))

    private fun JsonValue.objectValue(name: String) = this as? JsonValue.ObjectValue
        ?: error("$name must be an object")
    private fun JsonValue.stringValue(name: String) = (this as? JsonValue.StringValue)?.value
        ?: error("$name must be a string")
    private fun JsonValue.longValue(name: String) = (this as? JsonValue.NumberValue)?.raw?.toLongOrNull()
        ?: error("$name must be an integer")
    private fun JsonValue.doubleValue(name: String) = (this as? JsonValue.NumberValue)?.raw?.toDoubleOrNull()
        ?: error("$name must be a number")
    private fun JsonValue.ObjectValue.field(name: String) = fields[name] ?: error("missing $name")
    private fun JsonValue.ObjectValue.stringField(name: String) = field(name).stringValue(name)
    private fun JsonValue.ObjectValue.nullableStringField(name: String) = field(name).let {
        if (it is JsonValue.NullValue) null else it.stringValue(name)
    }
    private fun JsonValue.ObjectValue.longField(name: String) = field(name).longValue(name)
    private fun JsonValue.ObjectValue.intField(name: String) = longField(name).toInt()
    private fun JsonValue.ObjectValue.doubleField(name: String) = field(name).doubleValue(name)
    private fun JsonValue.ObjectValue.nullableDoubleField(name: String) = field(name).let {
        if (it is JsonValue.NullValue) null else it.doubleValue(name)
    }
    private fun JsonValue.ObjectValue.booleanField(name: String) =
        (field(name) as? JsonValue.BooleanValue)?.value ?: error("$name must be boolean")
    private fun JsonValue.ObjectValue.objectField(name: String) = field(name).objectValue(name)
    private fun JsonValue.ObjectValue.arrayField(name: String) =
        (field(name) as? JsonValue.ArrayValue)?.values ?: error("$name must be an array")
    private fun JsonValue.ObjectValue.doubleArrayField(name: String) =
        arrayField(name).map { it.doubleValue(name) }.toDoubleArray()
}
