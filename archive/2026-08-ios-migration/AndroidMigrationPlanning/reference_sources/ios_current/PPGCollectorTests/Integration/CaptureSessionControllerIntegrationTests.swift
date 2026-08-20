import Combine
import Foundation
import Testing
@testable import PPGCollector

@MainActor
struct CaptureSessionControllerIntegrationTests {
    @Test
    func writerFactoryFailureIsReportedWithoutStartingARecording()
        async
    {
        let source = TestCaptureStreamSource()
        let factory = CaptureSessionWriterFactory { _ in
            throw TestCaptureFailure.factory
        }
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: factory
        )
        controller.sessionName = "factory_failure"

        controller.startRecording()

        let didFail = await eventually {
            if case .failed = controller.state {
                return true
            }
            return false
        }
        #expect(didFail)
        #expect(controller.lastError == "注入 writer 创建失败")
        #expect(controller.lastSummary == nil)
        #expect(controller.recordingStartedAt == nil)
    }

    @Test
    func insufficientStoragePreflightShowsTheSpecificFailure()
        async
    {
        let source = TestCaptureStreamSource()
        let factory = CaptureSessionWriterFactory { _ in
            throw CaptureSessionWriterError.insufficientStorage
        }
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: factory
        )
        controller.sessionName = "low_storage"

        controller.startRecording()

        let didFail = await eventually {
            if case .failed = controller.state {
                return true
            }
            return false
        }
        #expect(didFail)
        #expect(
            controller.lastError
                == "设备可用空间不足，无法安全开始记录。"
        )
        #expect(controller.lastSummary == nil)
        #expect(controller.recordingStartedAt == nil)
    }

    @Test
    func connectionFailureFinalizesOnceWithDeviceDisconnectAndIntegrity()
        async
    {
        let source = TestCaptureStreamSource()
        source.setIntegrity(
            invalidFunction: 2,
            invalidTail: 1,
            discardedBytes: 17
        )
        let probe = TestCaptureWriterProbe()
        let controller = makeController(
            source: source,
            probe: probe
        )
        controller.sessionName = "disconnect"
        controller.startRecording()
        #expect(await reachesRecording(controller))

        source.sendConnectionPhase(.failed("注入连接失败"))

        let didFinalize = await eventually {
            controller.lastSummary?.stopReason == .deviceDisconnect
        }
        #expect(didFinalize)
        let snapshot = await probe.snapshot()
        #expect(snapshot.finishCalls.count == 1)
        #expect(snapshot.finishCalls.first?.reason == .deviceDisconnect)
        #expect(
            snapshot.finishCalls.first?.integrity
                == CaptureIntegritySnapshot(
                    invalidFrames: 3,
                    discardedBytes: 17
                )
        )
        #expect(snapshot.finishCalls.first?.errorMessage == nil)
    }

    @Test
    func staleStreamFinalizesOnceWithDataTimeout() async {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let controller = makeController(
            source: source,
            probe: probe
        )
        controller.sessionName = "stale_stream"
        controller.startRecording()
        #expect(await reachesRecording(controller))

        source.sendFreshness(.stale)

        let didFinalize = await eventually {
            controller.lastSummary?.stopReason == .dataTimeout
        }
        #expect(didFinalize)
        let snapshot = await probe.snapshot()
        #expect(snapshot.finishCalls.count == 1)
        #expect(snapshot.finishCalls.first?.reason == .dataTimeout)
        #expect(snapshot.finishCalls.first?.errorMessage == nil)
    }

    @Test
    func appendFailureFinalizesAsIncompleteWriteError() async {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let controller = makeController(
            source: source,
            probe: probe,
            appendFailure: .append
        )
        controller.sessionName = "append_failure"
        controller.startRecording()
        #expect(await reachesRecording(controller))

        source.sendChunk()

        let didFail = await eventually {
            if case .failed("注入 append 失败") = controller.state {
                return true
            }
            return false
        }
        #expect(didFail)
        let snapshot = await probe.snapshot()
        #expect(snapshot.appendCount == 1)
        #expect(snapshot.finishCalls.count == 1)
        #expect(snapshot.finishCalls.first?.reason == .writeError)
        #expect(
            snapshot.finishCalls.first?.errorMessage
                == "注入 append 失败"
        )
        #expect(controller.lastSummary?.complete == false)
    }

    @Test
    func preflightDisconnectDiscardsOnlyTheEmptyWriter() async {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let factoryGate = TestAsyncGate()
        let factory = makeWriterFactory(
            probe: probe,
            factoryGate: factoryGate,
            discardResult: true
        )
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: factory
        )
        controller.sessionName = "preflight_disconnect"

        controller.startRecording()
        #expect(controller.state == .preflighting)
        source.sendConnectionPhase(.failed("注入建链失败"))
        let stopWasRequested = await eventually {
            controller.state == .stopping(.deviceDisconnect)
        }
        #expect(stopWasRequested)

        await factoryGate.open()

        let returnedToIdle = await eventually {
            controller.state == .idle
        }
        #expect(returnedToIdle)
        let snapshot = await probe.snapshot()
        #expect(snapshot.discardCount == 1)
        #expect(snapshot.finishCalls.isEmpty)
        #expect(controller.lastSummary == nil)
    }

    @Test
    func sceneBackgroundDuringPreflightDiscardsEmptyWriterWithFirstReason()
        async
    {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let factoryGate = TestAsyncGate()
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: makeWriterFactory(
                probe: probe,
                factoryGate: factoryGate,
                discardResult: true
            )
        )
        controller.sessionName = "background_preflight"

        controller.startRecording()
        #expect(controller.state == .preflighting)
        controller.handleSceneBackground()
        source.sendConnectionPhase(.failed("预检期间断开"))
        controller.handleViewExit()
        #expect(controller.state == .stopping(.sceneBackground))

        await factoryGate.open()

        #expect(
            await eventually {
                controller.state == .idle
            }
        )
        let snapshot = await probe.snapshot()
        #expect(snapshot.discardCount == 1)
        #expect(snapshot.finishCalls.isEmpty)
        #expect(controller.lastSummary == nil)
        #expect(controller.lastError == nil)
    }

    @Test
    func competingLifecycleStopsDrainWritesAndFinalizeOnceWithFirstReason()
        async
    {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let appendGate = TestAsyncGate()
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: makeWriterFactory(
                probe: probe,
                appendGate: appendGate
            )
        )
        controller.sessionName = "competing_lifecycle"
        controller.startRecording()
        #expect(await reachesRecording(controller))

        source.sendChunk()
        #expect(await probe.waitForAppendCount(1))
        controller.handleSceneBackground()
        controller.handleViewExit()
        source.sendConnectionPhase(.failed("同时断开"))
        source.sendFreshness(.stale)
        controller.stopRecording()
        #expect(controller.state == .stopping(.sceneBackground))

        await appendGate.open()

        #expect(
            await eventually {
                controller.lastSummary?.stopReason == .sceneBackground
            }
        )
        let snapshot = await probe.snapshot()
        #expect(snapshot.appendCount == 1)
        #expect(snapshot.finishCalls.count == 1)
        #expect(snapshot.finishCalls.first?.reason == .sceneBackground)
        #expect(snapshot.finishCalls.first?.errorMessage == nil)
        #expect(controller.lastSummary?.complete == true)
    }

    @Test
    func delayedAppendFailureUpgradesUserStopToIncompleteWriteError()
        async
    {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let appendGate = TestAsyncGate()
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: makeWriterFactory(
                probe: probe,
                appendFailure: .append,
                appendGate: appendGate
            )
        )
        controller.sessionName = "delayed_append_failure"
        controller.startRecording()
        #expect(await reachesRecording(controller))

        source.sendChunk()
        #expect(await probe.waitForAppendCount(1))
        controller.stopRecording()
        #expect(controller.state == .stopping(.user))

        await appendGate.open()

        #expect(
            await eventually {
                if case .failed("注入 append 失败") = controller.state {
                    return true
                }
                return false
            }
        )
        let snapshot = await probe.snapshot()
        #expect(snapshot.appendCount == 1)
        #expect(snapshot.finishCalls.count == 1)
        #expect(snapshot.finishCalls.first?.reason == .writeError)
        #expect(
            snapshot.finishCalls.first?.errorMessage
                == "注入 append 失败"
        )
        #expect(controller.lastSummary?.complete == false)
    }

    @Test
    func pendingWriteOverflowWaitsForAcceptedWritesThenFinalizes()
        async
    {
        let source = TestCaptureStreamSource()
        let probe = TestCaptureWriterProbe()
        let appendGate = TestAsyncGate()
        let factory = makeWriterFactory(
            probe: probe,
            appendGate: appendGate
        )
        let controller = CaptureSessionController(
            streamSource: source,
            writerFactory: factory,
            maximumPendingWrites: 2
        )
        controller.sessionName = "write_pressure"
        controller.startRecording()
        #expect(await reachesRecording(controller))

        source.sendChunk(hostTime: 1)
        source.sendChunk(hostTime: 2)
        source.sendChunk(hostTime: 3)

        let pressureWasDetected = await eventually {
            controller.state == .stopping(.resourcePressure)
        }
        #expect(pressureWasDetected)
        await appendGate.open()

        let didFail = await eventually {
            if case .failed = controller.state {
                return true
            }
            return false
        }
        #expect(didFail)
        let snapshot = await probe.snapshot()
        #expect(snapshot.appendCount == 2)
        #expect(snapshot.finishCalls.count == 1)
        #expect(snapshot.finishCalls.first?.reason == .resourcePressure)
        #expect(
            snapshot.finishCalls.first?.errorMessage
                == "写盘队列超过 2 个 chunk，记录已停止。"
        )
    }
}

@MainActor
private final class TestCaptureStreamSource: CaptureStreamSource {
    private let streamEventSubject =
        PassthroughSubject<CUPStreamChunkEvent, Never>()
    private let connectionPhaseSubject:
        CurrentValueSubject<BLEConnectionPhase, Never>
    private let freshnessSubject:
        CurrentValueSubject<StreamFreshness, Never>

    let profile = CUPDeviceProfile.cupNUSBringUp
    var activeDevice: DiscoveredBLEDevice?
    var streamingDiagnostics = CUPStreamingDiagnostics()

    init(
        deviceID: UUID = UUID(
            uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )!
    ) {
        activeDevice = DiscoveredBLEDevice(
            id: deviceID,
            name: "CUP-TEST",
            rssi: -50,
            isConnectable: true,
            lastSeen: Date(timeIntervalSince1970: 0)
        )
        connectionPhaseSubject = CurrentValueSubject(
            .receiving(deviceID)
        )
        freshnessSubject = CurrentValueSubject(.fresh)
    }

    var captureStreamEvents:
        AnyPublisher<CUPStreamChunkEvent, Never>
    {
        streamEventSubject.eraseToAnyPublisher()
    }

    var captureConnectionPhases:
        AnyPublisher<BLEConnectionPhase, Never>
    {
        connectionPhaseSubject.eraseToAnyPublisher()
    }

    var captureStreamFreshness:
        AnyPublisher<StreamFreshness, Never>
    {
        freshnessSubject.eraseToAnyPublisher()
    }

    var connectionPhase: BLEConnectionPhase {
        connectionPhaseSubject.value
    }

    var streamFreshness: StreamFreshness {
        freshnessSubject.value
    }

    func sendConnectionPhase(_ phase: BLEConnectionPhase) {
        connectionPhaseSubject.send(phase)
    }

    func sendFreshness(_ freshness: StreamFreshness) {
        freshnessSubject.send(freshness)
    }

    func sendChunk(hostTime: UInt64 = 1) {
        streamEventSubject.send(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: hostTime,
                data: Data([0x01]),
                decodedFrames: []
            )
        )
    }

    func setIntegrity(
        invalidFunction: Int,
        invalidTail: Int,
        discardedBytes: Int
    ) {
        streamingDiagnostics.decoderStats.invalidFunction =
            invalidFunction
        streamingDiagnostics.decoderStats.invalidTail = invalidTail
        streamingDiagnostics.decoderStats.bytesDiscarded =
            discardedBytes
    }
}

private enum TestCaptureFailure: LocalizedError, Sendable {
    case factory
    case append

    var errorDescription: String? {
        switch self {
        case .factory:
            "注入 writer 创建失败"
        case .append:
            "注入 append 失败"
        }
    }
}

private struct TestFinishCall: Equatable, Sendable {
    let reason: CaptureStopReason
    let integrity: CaptureIntegritySnapshot
    let errorMessage: String?
}

private struct TestWriterProbeSnapshot: Equatable, Sendable {
    let appendCount: Int
    let discardCount: Int
    let finishCalls: [TestFinishCall]
}

private actor TestCaptureWriterProbe {
    private var appendCount = 0
    private var discardCount = 0
    private var finishCalls: [TestFinishCall] = []

    func recordAppend() {
        appendCount += 1
    }

    func recordDiscard() {
        discardCount += 1
    }

    func recordFinish(_ call: TestFinishCall) {
        finishCalls.append(call)
    }

    func snapshot() -> TestWriterProbeSnapshot {
        TestWriterProbeSnapshot(
            appendCount: appendCount,
            discardCount: discardCount,
            finishCalls: finishCalls
        )
    }

    func waitForAppendCount(_ expectedCount: Int) async -> Bool {
        for _ in 0..<200 {
            if appendCount >= expectedCount {
                return appendCount == expectedCount
            }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return appendCount == expectedCount
    }
}

private actor TestCaptureWriter: CaptureSessionWriting {
    private let configuration: CaptureSessionConfiguration
    private let probe: TestCaptureWriterProbe
    private let appendFailure: TestCaptureFailure?
    private let appendGate: TestAsyncGate?
    private let discardResult: Bool
    private var snapshot = CaptureWriterSnapshot()

    init(
        configuration: CaptureSessionConfiguration,
        probe: TestCaptureWriterProbe,
        appendFailure: TestCaptureFailure?,
        appendGate: TestAsyncGate?,
        discardResult: Bool
    ) {
        self.configuration = configuration
        self.probe = probe
        self.appendFailure = appendFailure
        self.appendGate = appendGate
        self.discardResult = discardResult
    }

    func append(
        _ event: CUPStreamChunkEvent
    ) async throws -> CaptureWriterSnapshot {
        await probe.recordAppend()
        if let appendGate {
            await appendGate.waitUntilOpen()
        }
        if let appendFailure {
            throw appendFailure
        }
        snapshot.rawChunkCount += 1
        snapshot.rawPayloadBytes += event.data.count
        return snapshot
    }

    func discardIfEmptyBeforeRecording() async throws -> Bool {
        await probe.recordDiscard()
        return discardResult
    }

    func finish(
        reason: CaptureStopReason,
        integrity: CaptureIntegritySnapshot,
        errorMessage: String?
    ) async throws -> CaptureSessionSummary {
        await probe.recordFinish(
            TestFinishCall(
                reason: reason,
                integrity: integrity,
                errorMessage: errorMessage
            )
        )
        return CaptureSessionSummary(
            sessionID: configuration.sessionID,
            baseName: configuration.baseName,
            directoryURL: URL(
                fileURLWithPath: "/tmp/\(configuration.baseName)"
            ),
            startedUTC: configuration.startedUTC,
            endedUTC: configuration.startedUTC.addingTimeInterval(1),
            stopReason: reason,
            complete: errorMessage == nil,
            writer: snapshot
        )
    }
}

private actor TestAsyncGate {
    private var isOpen = false
    private var continuations: [CheckedContinuation<Void, Never>] = []

    func waitUntilOpen() async {
        guard !isOpen else {
            return
        }
        await withCheckedContinuation { continuation in
            continuations.append(continuation)
        }
    }

    func open() {
        guard !isOpen else {
            return
        }
        isOpen = true
        let waiting = continuations
        continuations.removeAll()
        for continuation in waiting {
            continuation.resume()
        }
    }
}

@MainActor
private func makeController(
    source: TestCaptureStreamSource,
    probe: TestCaptureWriterProbe,
    appendFailure: TestCaptureFailure? = nil
) -> CaptureSessionController {
    CaptureSessionController(
        streamSource: source,
        writerFactory: makeWriterFactory(
            probe: probe,
            appendFailure: appendFailure
        )
    )
}

private func makeWriterFactory(
    probe: TestCaptureWriterProbe,
    appendFailure: TestCaptureFailure? = nil,
    factoryGate: TestAsyncGate? = nil,
    appendGate: TestAsyncGate? = nil,
    discardResult: Bool = false
) -> CaptureSessionWriterFactory {
    CaptureSessionWriterFactory { configuration in
        if let factoryGate {
            await factoryGate.waitUntilOpen()
        }
        return TestCaptureWriter(
            configuration: configuration,
            probe: probe,
            appendFailure: appendFailure,
            appendGate: appendGate,
            discardResult: discardResult
        )
    }
}

@MainActor
private func reachesRecording(
    _ controller: CaptureSessionController
) async -> Bool {
    await eventually {
        controller.state == .recording
    }
}

@MainActor
private func eventually(
    attempts: Int = 200,
    condition: @MainActor () -> Bool
) async -> Bool {
    for _ in 0..<attempts {
        if condition() {
            return true
        }
        try? await Task.sleep(nanoseconds: 5_000_000)
    }
    return condition()
}
