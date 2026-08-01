import Combine
import Foundation

@MainActor
final class CaptureSessionController: ObservableObject {
    @Published var sessionName = ""
    @Published private(set) var state: CaptureState = .idle
    @Published private(set) var writerSnapshot = CaptureWriterSnapshot()
    @Published private(set) var lastSummary: CaptureSessionSummary?
    @Published private(set) var lastError: String?
    @Published private(set) var lastWriteError: String?
    @Published private(set) var recordingStartedAt: Date?
    @Published private(set) var pendingWriteCount = 0

    private let streamSource: any CaptureStreamSource
    private let writerFactory: CaptureSessionWriterFactory
    private var subscriptions = Set<AnyCancellable>()
    private var activeWriter: (any CaptureSessionWriting)?
    private var writeTail: Task<Void, Never>?
    private var finalizationTask: Task<Void, Never>?
    private var pendingFatalError: String?
    private var pendingFatalReason: CaptureStopReason?
    private var lifecycleGate = CaptureLifecycleGate()
    private let maximumPendingWrites: Int

    init(
        streamSource: any CaptureStreamSource,
        writerFactory: CaptureSessionWriterFactory = .live,
        maximumPendingWrites: Int = 256
    ) {
        self.streamSource = streamSource
        self.writerFactory = writerFactory
        self.maximumPendingWrites = max(1, maximumPendingWrites)

        streamSource.captureStreamEvents
            .sink { [weak self] event in
                Task { @MainActor in
                    self?.enqueue(event)
                }
            }
            .store(in: &subscriptions)

        streamSource.captureConnectionPhases
            .dropFirst()
            .sink { [weak self] phase in
                Task { @MainActor in
                    self?.handleConnectionPhase(phase)
                }
            }
            .store(in: &subscriptions)

        streamSource.captureStreamFreshness
            .dropFirst()
            .sink { [weak self] freshness in
                Task { @MainActor in
                    self?.handleFreshness(freshness)
                }
            }
            .store(in: &subscriptions)
    }

    convenience init(bluetoothService: BLECentralService) {
        self.init(streamSource: bluetoothService)
    }

    var canStart: Bool {
        guard !state.isRecording, !state.isBusy,
              streamSource.streamFreshness == .fresh,
              (try? SessionNameValidator.validate(sessionName)) != nil else {
            return false
        }
        switch streamSource.connectionPhase {
        case .subscribed, .receiving:
            return streamSource.activeDevice != nil
        default:
            return false
        }
    }

    var recordingDiagnostics: CaptureRecordingDiagnostics {
        CaptureRecordingDiagnostics(
            writer: writerSnapshot,
            pendingWriteCount: pendingWriteCount,
            invalidFrames: streamSource.streamingDiagnostics
                .structurallyInvalidFrames,
            discardedBytes: streamSource.streamingDiagnostics
                .decoderStats.bytesDiscarded,
            writeError: lastWriteError
        )
    }

    var nameValidationMessage: String? {
        guard !sessionName.isEmpty else {
            return nil
        }
        do {
            _ = try SessionNameValidator.validate(sessionName)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func startRecording() {
        guard !state.isRecording, !state.isBusy else {
            return
        }

        let baseName: String
        do {
            baseName = try SessionNameValidator.validate(sessionName)
        } catch {
            lastError = error.localizedDescription
            state = .failed(error.localizedDescription)
            return
        }
        guard streamSource.streamFreshness == .fresh else {
            failPreflight("尚未持续收到合法数据，不能开始记录。")
            return
        }
        guard let device = streamSource.activeDevice else {
            failPreflight("当前没有可记录的已连接设备。")
            return
        }

        let configuration = CaptureSessionConfiguration(
            sessionID: UUID(),
            baseName: baseName,
            startedUTC: Date(),
            softVersion: Self.appVersion,
            algorithmVersion:
                PPGLiveMetricRuntimeProfile.iosBaseline01.identifier,
            preprocessProfile:
                PPGLiveMetricRuntimeProfile.iosBaseline01
                    .preprocessingProfile.identifier,
            protocolProfile: "cup_batch_v1_draft",
            transportProfile: streamSource.profile.identifier,
            device: CaptureDeviceContext(
                name: device.name,
                identifier: device.id,
                serviceUUID: streamSource.profile.serviceUUIDString,
                notifyCharacteristicUUID:
                    streamSource.profile.notifyCharacteristicUUIDString
            )
        )

        state = .preflighting
        writerSnapshot = CaptureWriterSnapshot()
        lastSummary = nil
        lastError = nil
        lastWriteError = nil
        pendingFatalError = nil
        pendingFatalReason = nil
        lifecycleGate.reset()
        pendingWriteCount = 0
        recordingStartedAt = nil

        let writerFactory = self.writerFactory
        Task { [weak self, writerFactory] in
            do {
                let writer = try await writerFactory.makeWriter(
                    configuration
                )
                guard let self else {
                    _ = try? await writer.finish(
                        reason: .unknown,
                        integrity: CaptureIntegritySnapshot(),
                        errorMessage: "采集控制器已释放。"
                    )
                    return
                }

                if let stopReason =
                    self.lifecycleGate.preflightStopReason {
                    if try await writer.discardIfEmptyBeforeRecording() {
                        self.state = .idle
                        return
                    }
                    let message =
                        "创建记录期间采集条件已失效，已按异常会话收尾。"
                    let summary = try await writer.finish(
                        reason: stopReason,
                        integrity: self.currentIntegritySnapshot(),
                        errorMessage: message
                    )
                    self.lastSummary = summary
                    self.lastError = message
                    self.state = .finalized(summary)
                    return
                }

                guard self.streamSource.streamFreshness == .fresh,
                      self.streamSource.activeDevice?.id == device.id else {
                    let summary = try await writer.finish(
                        reason: .dataTimeout,
                        integrity: self.currentIntegritySnapshot(),
                        errorMessage: "创建文件期间数据流已失效。"
                    )
                    self.lastSummary = summary
                    self.state = .finalized(summary)
                    self.lastError = "创建文件期间数据流已失效，已保存空会话并安全结束。"
                    return
                }

                self.activeWriter = writer
                self.sessionName = baseName
                self.recordingStartedAt = configuration.startedUTC
                self.state = .recording
            } catch {
                self?.failPreflight(error.localizedDescription)
            }
        }
    }

    func stopRecording(reason: CaptureStopReason = .user) {
        guard state.isRecording else {
            return
        }
        beginFinalization(reason: reason)
    }

    func handleSceneBackground() {
        if state.isRecording {
            beginFinalization(reason: .sceneBackground)
        } else if case .preflighting = state {
            requestPreflightStop(reason: .sceneBackground)
        }
    }

    func handleViewExit() {
        if state.isRecording {
            beginFinalization(reason: .viewExit)
        } else if case .preflighting = state {
            requestPreflightStop(reason: .viewExit)
        }
    }

    func clearError() {
        lastError = nil
        if case .failed = state {
            state = .idle
        }
    }

    private func enqueue(_ event: CUPStreamChunkEvent) {
        guard state.isRecording, let writer = activeWriter else {
            return
        }
        guard pendingWriteCount < maximumPendingWrites else {
            let message = "写盘队列超过 \(maximumPendingWrites) 个 chunk，记录已停止。"
            pendingFatalError = message
            pendingFatalReason = .resourcePressure
            lastError = message
            beginFinalization(reason: .resourcePressure)
            return
        }

        let previous = writeTail
        pendingWriteCount += 1
        writeTail = Task { @MainActor [weak self, writer] in
            await previous?.value
            defer {
                if let self {
                    self.pendingWriteCount = max(0, self.pendingWriteCount - 1)
                }
            }
            do {
                let snapshot = try await writer.append(event)
                self?.writerSnapshot = snapshot
            } catch {
                self?.handleWriteFailure(error)
            }
        }
    }

    private func handleWriteFailure(_ error: Error) {
        let message = error.localizedDescription
        lastWriteError = message
        pendingFatalError = message
        pendingFatalReason = .writeError
        lastError = message
        if state.isRecording {
            beginFinalization(reason: .writeError)
        }
    }

    private func beginFinalization(reason: CaptureStopReason) {
        guard finalizationTask == nil,
              let writer = activeWriter,
              lifecycleGate.beginFinalization(reason) else {
            return
        }

        state = .stopping(reason)
        activeWriter = nil
        let pendingWrites = writeTail
        writeTail = nil
        let initialIntegrity = currentIntegritySnapshot()

        finalizationTask = Task { @MainActor [weak self, writer] in
            await pendingWrites?.value
            guard let self else {
                _ = try? await writer.finish(
                    reason: .unknown,
                    integrity: initialIntegrity,
                    errorMessage: "采集控制器已释放。"
                )
                return
            }

            let fatalError = self.pendingFatalError
            let effectiveReason = self.pendingFatalReason ?? reason
            self.state = .stopping(effectiveReason)

            do {
                let summary = try await writer.finish(
                    reason: effectiveReason,
                    integrity: self.currentIntegritySnapshot(),
                    errorMessage: fatalError
                )
                self.writerSnapshot = summary.writer
                self.lastSummary = summary
                if let fatalError {
                    self.state = .failed(fatalError)
                } else {
                    self.state = .finalized(summary)
                }
            } catch {
                let message = "记录收尾失败：\(error.localizedDescription)"
                self.lastWriteError = message
                self.lastError = message
                self.state = .failed(message)
            }
            self.pendingFatalError = nil
            self.pendingFatalReason = nil
            self.pendingWriteCount = 0
            self.finalizationTask = nil
        }
    }

    private func handleConnectionPhase(_ phase: BLEConnectionPhase) {
        switch phase {
        case .idle, .disconnecting, .failed:
            if state.isRecording {
                beginFinalization(reason: .deviceDisconnect)
            } else if case .preflighting = state {
                requestPreflightStop(reason: .deviceDisconnect)
            }
        default:
            break
        }
    }

    private func handleFreshness(_ freshness: StreamFreshness) {
        if freshness == .stale || freshness == .unavailable {
            if state.isRecording {
                beginFinalization(reason: .dataTimeout)
            } else if case .preflighting = state {
                requestPreflightStop(reason: .dataTimeout)
            }
        }
    }

    private func requestPreflightStop(reason: CaptureStopReason) {
        if lifecycleGate.requestPreflightStop(reason) {
            state = .stopping(reason)
        }
    }

    private func currentIntegritySnapshot() -> CaptureIntegritySnapshot {
        CaptureIntegritySnapshot(
            invalidFrames: streamSource.streamingDiagnostics
                .structurallyInvalidFrames,
            discardedBytes: streamSource.streamingDiagnostics
                .decoderStats.bytesDiscarded
        )
    }

    private func failPreflight(_ message: String) {
        lastError = message
        state = .failed(message)
    }

    private static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "0"
        let build = info?["CFBundleVersion"] as? String ?? "0"
        return "\(version)+\(build)"
    }
}
