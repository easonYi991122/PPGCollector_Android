import Combine
import Foundation

@MainActor
final class BLECentralService: ObservableObject {
    @Published private(set) var availability: BluetoothAvailability = .unknown
    @Published private(set) var discoveredDevices: [DiscoveredBLEDevice] = []
    @Published private(set) var connectionPhase: BLEConnectionPhase = .idle
    @Published private(set) var connectionAttemptDiagnostics =
        BLEConnectionAttemptDiagnostics()
    @Published private(set) var isScanning = false
    @Published private(set) var discoveredServiceUUIDs: [String] = []
    @Published private(set) var discoveredCharacteristics: [BLECharacteristicDiagnostic] = []
    @Published private(set) var streamingDiagnostics = CUPStreamingDiagnostics()
    @Published private(set) var liveWaveformSnapshot =
        CUPWaveformSnapshot.empty
    @Published private(set) var liveMetricSnapshot =
        LiveMetricSnapshot.unavailable(
            hasConnectedDevice: false,
            freshness: .unavailable
        )
    @Published private(set) var liveMetricRuntimeDiagnostics =
        PPGLiveMetricRuntimeDiagnostics()
    @Published private(set) var waveformRefreshRateHz =
        CUPWaveformSnapshotScheduler.defaultRefreshRateHz
    @Published private(set) var lastNotificationAt: Date?
    @Published private(set) var lastValidFrameAt: Date?
    @Published private(set) var streamFreshness: StreamFreshness = .unavailable
    @Published private(set) var lastError: String?

    let profile: CUPDeviceProfile
    let streamEvents = PassthroughSubject<CUPStreamChunkEvent, Never>()

    var activeDevice: DiscoveredBLEDevice? {
        guard let deviceID = connectionPhase.deviceID else {
            return nil
        }
        return discoveredDevices.first(where: { $0.id == deviceID })
    }

    var canRetryLastConnection: Bool {
        guard availability == .poweredOn,
              !connectionPhase.isBusy,
              activeDeviceID == nil,
              let lastRequestedDeviceID else {
            return false
        }
        return discoveredDevices.contains {
            $0.id == lastRequestedDeviceID
        }
    }

    var runtimeResourceSnapshot: BLERuntimeResourceSnapshot {
        BLERuntimeResourceSnapshot(
            connectionDeadlineTaskActive:
                connectionTimeoutTask != nil,
            freshnessTaskActive: freshnessTask != nil,
            waveformSnapshotTaskActive:
                waveformSnapshotTask != nil,
            metricComputationTaskActive:
                liveMetricComputationTask != nil,
            metricCompletionTaskActive:
                liveMetricCompletionTask != nil,
            decoderPendingBytes:
                streamingPipeline.diagnostics.pendingDecoderBytes,
            recentSampleCount:
                streamingPipeline.diagnostics.recentSamples.count,
            metricBufferedSampleCount:
                liveMetricWindowScheduler.bufferedSampleCount
        )
    }

    private let transport: any BLETransporting
    private var shouldScanWhenReady = false
    private var activeDeviceID: UUID?
    private var notifyCharacteristicUUID: String?
    private var controlCharacteristicUUID: String?
    private let connectionTimeoutPolicy: BLEConnectionTimeoutPolicy
    private var connectionDeadlineTracker =
        BLEConnectionDeadlineTracker()
    private var connectionTimeoutTask: Task<Void, Never>?
    private var lastRequestedDeviceID: UUID?
    private var streamingPipeline = CUPStreamingPipeline()
    private var pendingLastNotificationAt: Date?
    private var lastDiagnosticsPublishUptime = 0.0
    private var freshnessTracker = CUPStreamFreshnessTracker(timeout: 2)
    private var freshnessTask: Task<Void, Never>?
    private var waveformSnapshotScheduler:
        CUPWaveformSnapshotScheduler
    private var waveformSnapshotTask: Task<Void, Never>?
    private let liveMetricRuntimeProfile =
        PPGLiveMetricRuntimeProfile.iosBaseline01
    private let liveMetricAnalysisRunner: PPGLiveMetricAnalysisRunner
    private var liveMetricWindowScheduler =
        PPGLiveMetricWindowScheduler()
    private var liveMetricComputationTask:
        Task<PPGLiveMetricAnalysisResult?, Never>?
    private var liveMetricCompletionTask: Task<Void, Never>?

    init(
        profile: CUPDeviceProfile = .cupNUSBringUp,
        connectionTimeoutPolicy: BLEConnectionTimeoutPolicy = .iosDefault,
        waveformRefreshRateHz: Double =
            CUPWaveformSnapshotScheduler.defaultRefreshRateHz,
        liveMetricAnalysisRunner: PPGLiveMetricAnalysisRunner = .production,
        transport: (any BLETransporting)? = nil
    ) {
        self.profile = profile
        self.connectionTimeoutPolicy = connectionTimeoutPolicy
        self.liveMetricAnalysisRunner = liveMetricAnalysisRunner
        self.transport = transport ?? CoreBluetoothTransport()
        let scheduler = CUPWaveformSnapshotScheduler(
            refreshRateHz: waveformRefreshRateHz
        )
        self.waveformRefreshRateHz = scheduler.refreshRateHz
        waveformSnapshotScheduler = scheduler
        self.transport.setEventHandler { [weak self] event in
            self?.handleTransportEvent(event)
        }
        self.transport.activate()
    }

    func setWaveformRefreshRateHz(_ requestedRate: Double) {
        let scheduler = CUPWaveformSnapshotScheduler(
            refreshRateHz: requestedRate
        )
        guard scheduler.refreshRateHz != waveformRefreshRateHz else {
            return
        }

        let wasPublishing = waveformSnapshotTask != nil
        waveformRefreshRateHz = scheduler.refreshRateHz
        waveformSnapshotScheduler = scheduler
        if wasPublishing {
            startWaveformSnapshotPublishing()
        }
    }

    func startScanning(clearPreviousResults: Bool = false) {
        shouldScanWhenReady = true
        lastError = nil

        if clearPreviousResults {
            discoveredDevices.removeAll()
        }

        guard availability == .poweredOn else {
            return
        }
        beginScanning()
    }

    func stopScanning() {
        shouldScanWhenReady = false
        guard isScanning else {
            return
        }
        transport.stopScanning()
        isScanning = false
    }

    func connect(to deviceID: UUID) {
        guard availability == .poweredOn else {
            lastError = "蓝牙当前不可用。"
            return
        }
        guard discoveredDevices.contains(
            where: { $0.id == deviceID }
        ) else {
            lastError = "设备已离开扫描范围，请重新扫描。"
            return
        }
        guard !connectionPhase.isBusy else {
            lastError = "当前建链尚未结束，请稍后重试。"
            return
        }

        if let currentDeviceID = activeDeviceID,
           currentDeviceID != deviceID {
            transport.disconnect(from: currentDeviceID)
        }

        stopScanning()
        resetConnectionDiagnostics()
        lastError = nil
        lastRequestedDeviceID = deviceID
        connectionAttemptDiagnostics.attemptCount += 1
        connectionAttemptDiagnostics.lastTimedOutOperation = nil
        activeDeviceID = deviceID
        connectionPhase = .connecting(deviceID)
        startConnectionDeadline(.connect, deviceID: deviceID)
        transport.connect(to: deviceID)
    }

    func retryLastConnection() {
        guard let deviceID = lastRequestedDeviceID,
              canRetryLastConnection else {
            lastError = "设备尚未完成断开或已离开扫描范围，请重新扫描。"
            return
        }
        connect(to: deviceID)
    }

    func disconnect() {
        guard let deviceID = activeDeviceID else {
            stopConnectionDeadline()
            connectionPhase = .idle
            return
        }

        stopConnectionDeadline()
        connectionPhase = .disconnecting(deviceID)
        if let notifyCharacteristicUUID {
            transport.setNotificationsEnabled(
                false,
                characteristicUUID: notifyCharacteristicUUID,
                deviceID: deviceID
            )
        }
        transport.disconnect(from: deviceID)
    }

    func clearError() {
        lastError = nil
        if case .failed = connectionPhase {
            connectionPhase = .idle
        }
    }

    private func beginScanning() {
        guard !isScanning else {
            return
        }
        transport.startScanning()
        isScanning = true
    }

    private func updateDevice(
        _ discovery: BLETransportDiscovery
    ) {
        guard let name = discovery.name,
              name.hasPrefix(profile.advertisedNamePrefix) else {
            return
        }

        let device = DiscoveredBLEDevice(
            id: discovery.deviceID,
            name: name,
            rssi: discovery.rssi,
            isConnectable: discovery.isConnectable,
            lastSeen: discovery.seenAt
        )

        if let index = discoveredDevices.firstIndex(where: { $0.id == device.id }) {
            discoveredDevices[index] = device
        } else {
            discoveredDevices.append(device)
        }
        discoveredDevices.sort {
            if $0.name == $1.name {
                return ($0.rssi ?? Int.min) > ($1.rssi ?? Int.min)
            }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private func resetConnectionDiagnostics() {
        stopConnectionDeadline()
        stopFreshnessMonitoring()
        stopWaveformSnapshotPublishing(clearSnapshot: true)
        discoveredServiceUUIDs = []
        discoveredCharacteristics = []
        notifyCharacteristicUUID = nil
        controlCharacteristicUUID = nil
        streamingPipeline.reset()
        streamingDiagnostics = streamingPipeline.diagnostics
        pendingLastNotificationAt = nil
        lastNotificationAt = nil
        lastValidFrameAt = nil
        lastDiagnosticsPublishUptime = 0
    }

    private func failConnection(
        _ message: String,
        disconnect deviceID: UUID? = nil
    ) {
        stopConnectionDeadline()
        stopFreshnessMonitoring()
        stopWaveformSnapshotPublishing()
        lastError = message
        connectionPhase = .failed(message)
        if let deviceID {
            transport.disconnect(from: deviceID)
        }
    }

    private func startConnectionDeadline(
        _ operation: BLEConnectionOperation,
        deviceID: UUID
    ) {
        connectionTimeoutTask?.cancel()
        let now = ProcessInfo.processInfo.systemUptime
        let timeout = connectionTimeoutPolicy.timeout(for: operation)
        let deadline = connectionDeadlineTracker.arm(
            operation: operation,
            deviceID: deviceID,
            now: now,
            timeout: timeout
        )
        connectionAttemptDiagnostics.activeOperation = operation
        connectionTimeoutTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self,
                      self.connectionDeadlineTracker.isCurrent(deadline) else {
                    return
                }

                let remaining =
                    deadline.deadlineUptime
                    - ProcessInfo.processInfo.systemUptime
                guard remaining > 0 else {
                    self.handleConnectionDeadline(deadline)
                    return
                }

                do {
                    try await Task.sleep(
                        nanoseconds: UInt64(
                            ceil(remaining * 1_000_000_000)
                        )
                    )
                } catch {
                    return
                }
            }
        }
    }

    private func stopConnectionDeadline() {
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        connectionDeadlineTracker.cancel()
        connectionAttemptDiagnostics.activeOperation = nil
    }

    private func handleConnectionDeadline(
        _ deadline: BLEConnectionDeadline
    ) {
        let now = ProcessInfo.processInfo.systemUptime
        guard activeDeviceID == deadline.deviceID,
              connectionDeadlineTracker.consumeExpiration(
                  deadline,
                  now: now
              ) else {
            return
        }

        connectionTimeoutTask = nil
        connectionAttemptDiagnostics.activeOperation = nil
        connectionAttemptDiagnostics.timeoutCount += 1
        connectionAttemptDiagnostics.lastTimedOutOperation =
            deadline.operation
        failConnection(
            "\(deadline.operation.title)超时，请确认设备仍在附近后重试。",
            disconnect: activeDeviceID
        )
    }

    private func acceptsCallback(
        from deviceID: UUID,
        during expectedPhase: BLEConnectionPhase
    ) -> Bool {
        guard activeDeviceID == deviceID,
              connectionPhase == expectedPhase else {
            connectionAttemptDiagnostics
                .ignoredStaleCallbackCount += 1
            return false
        }
        return true
    }

    private func publishStreamingDiagnostics(force: Bool = false) {
        let now = ProcessInfo.processInfo.systemUptime
        guard force || now - lastDiagnosticsPublishUptime >= 0.2 else {
            return
        }

        streamingDiagnostics = streamingPipeline.diagnostics
        lastNotificationAt = pendingLastNotificationAt
        lastDiagnosticsPublishUptime = now
    }

    private func startWaveformSnapshotPublishing() {
        stopWaveformSnapshotPublishing()
        waveformSnapshotScheduler.reset()
        waveformSnapshotTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let delay = self?
                    .publishWaveformSnapshotAndNextDelay() else {
                    return
                }
                let nanoseconds = UInt64(
                    max(0.001, delay) * 1_000_000_000
                )
                do {
                    try await Task.sleep(nanoseconds: nanoseconds)
                } catch {
                    return
                }
            }
        }
    }

    private func stopWaveformSnapshotPublishing(
        clearSnapshot: Bool = false
    ) {
        waveformSnapshotTask?.cancel()
        waveformSnapshotTask = nil
        waveformSnapshotScheduler.reset()
        if clearSnapshot {
            liveWaveformSnapshot = .empty
        }
    }

    private func publishWaveformSnapshotAndNextDelay()
        -> TimeInterval
    {
        let now = ProcessInfo.processInfo.systemUptime
        if waveformSnapshotScheduler.consumeTick(at: now) {
            let diagnostics = streamingPipeline.diagnostics
            liveWaveformSnapshot = CUPWaveformSnapshot(
                samples: diagnostics.recentSamples,
                acceptedSampleCount: diagnostics.acceptedSamples,
                publishedAtUptime: now,
                publicationSequence:
                    waveformSnapshotScheduler.publicationCount
            )
        }
        return waveformSnapshotScheduler.timeUntilNextTick(at: now)
    }

    private func startFreshnessMonitoring() {
        stopFreshnessMonitoring()
        let now = ProcessInfo.processInfo.systemUptime
        freshnessTracker.start(at: now)
        streamFreshness = freshnessTracker.freshness(at: now)
        liveMetricSnapshot = .unavailable(
            hasConnectedDevice: activeDeviceID != nil,
            freshness: streamFreshness
        )
        freshnessTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled else {
                    return
                }
                self?.refreshStreamFreshness()
            }
        }
    }

    private func stopFreshnessMonitoring() {
        freshnessTask?.cancel()
        freshnessTask = nil
        freshnessTracker.reset()
        streamFreshness = .unavailable
        resetLiveMetricRuntime()
    }

    private func markValidFrameReceived() {
        let previousFreshness = streamFreshness
        let now = ProcessInfo.processInfo.systemUptime
        freshnessTracker.observeValidFrame(at: now)
        lastValidFrameAt = Date()
        streamFreshness = freshnessTracker.freshness(at: now)
        if previousFreshness != .fresh {
            liveMetricSnapshot = .warmingUp()
        }
    }

    private func refreshStreamFreshness() {
        let now = ProcessInfo.processInfo.systemUptime
        let updatedFreshness = freshnessTracker.freshness(at: now)
        guard updatedFreshness != streamFreshness else {
            return
        }
        streamFreshness = updatedFreshness
        if updatedFreshness == .stale {
            invalidateLiveMetricContinuity(
                publishing: .unavailable(
                    hasConnectedDevice: activeDeviceID != nil,
                    freshness: .stale
                )
            )
        }
    }

    private func resetLiveMetricRuntime() {
        cancelPendingLiveMetricAnalysis(countAsDiscarded: false)
        liveMetricWindowScheduler.reset()
        liveMetricRuntimeDiagnostics =
            PPGLiveMetricRuntimeDiagnostics()
        liveMetricSnapshot = .unavailable(
            hasConnectedDevice: false,
            freshness: .unavailable
        )
    }

    private func invalidateLiveMetricContinuity(
        publishing snapshot: LiveMetricSnapshot
    ) {
        cancelPendingLiveMetricAnalysis(countAsDiscarded: true)
        liveMetricWindowScheduler.invalidateContinuity()
        liveMetricRuntimeDiagnostics.continuousSampleCount = 0
        liveMetricRuntimeDiagnostics.bufferedSampleCount = 0
        liveMetricSnapshot = snapshot
    }

    private func scheduleLiveMetricAnalysis(
        _ request: PPGLiveMetricAnalysisRequest
    ) {
        cancelPendingLiveMetricAnalysis(countAsDiscarded: true)
        liveMetricRuntimeDiagnostics.scheduledAnalysisCount += 1
        liveMetricRuntimeDiagnostics.latestWindowEndSampleIndex =
            request.windowEndSampleIndex

        let profile = liveMetricRuntimeProfile
        let runner = liveMetricAnalysisRunner
        let computation = Task.detached(
            priority: .utility
        ) { () -> PPGLiveMetricAnalysisResult? in
            guard !Task.isCancelled else {
                return nil
            }
            let result = await runner.run(
                request,
                profile
            )
            return Task.isCancelled ? nil : result
        }
        liveMetricComputationTask = computation
        liveMetricCompletionTask = Task { [weak self] in
            let result = await computation.value
            guard !Task.isCancelled, let self else {
                return
            }
            self.completeLiveMetricAnalysis(
                result,
                request: request
            )
        }
    }

    private func completeLiveMetricAnalysis(
        _ result: PPGLiveMetricAnalysisResult?,
        request: PPGLiveMetricAnalysisRequest
    ) {
        defer {
            if liveMetricWindowScheduler.isCurrent(request) {
                liveMetricComputationTask = nil
                liveMetricCompletionTask = nil
            }
        }
        guard let result,
              streamFreshness == .fresh,
              liveMetricWindowScheduler.isCurrent(request) else {
            liveMetricRuntimeDiagnostics.discardedAnalysisCount += 1
            return
        }

        liveMetricSnapshot = result.snapshot
        liveMetricRuntimeDiagnostics.completedAnalysisCount += 1
        liveMetricRuntimeDiagnostics.latestHeartRateReason =
            result.snapshot.heartRateBPM.unavailableReason?.rawValue
        liveMetricRuntimeDiagnostics.latestProvisionalSQI =
            result.provisionalSignalQuality?.sqi
        liveMetricRuntimeDiagnostics.latestProvisionalSQIGrade =
            result.provisionalSignalQuality?.grade.rawValue
        liveMetricRuntimeDiagnostics.latestProvisionalSQIReason =
            result.provisionalSignalQuality?.reasonCode
    }

    private func cancelPendingLiveMetricAnalysis(
        countAsDiscarded: Bool
    ) {
        let hadPending = liveMetricComputationTask != nil
        liveMetricComputationTask?.cancel()
        liveMetricCompletionTask?.cancel()
        liveMetricComputationTask = nil
        liveMetricCompletionTask = nil
        if countAsDiscarded, hadPending {
            liveMetricRuntimeDiagnostics.discardedAnalysisCount += 1
        }
    }

    private func updateCharacteristicNotificationState(
        uuid: String,
        isNotifying: Bool
    ) {
        guard let index = discoveredCharacteristics.firstIndex(
            where: { uuidsEqual($0.uuid, uuid) }
        ) else {
            return
        }
        discoveredCharacteristics[index].isNotifying = isNotifying
    }

    private func diagnostic(
        for characteristic: BLETransportCharacteristic
    ) -> BLECharacteristicDiagnostic {
        let role: String?
        if uuidsEqual(
            characteristic.uuid,
            profile.notifyCharacteristicUUIDString
        ) {
            role = "TX / notify"
        } else if uuidsEqual(
            characteristic.uuid,
            profile.controlCharacteristicUUIDString
        ) {
            role = "RX / control"
        } else {
            role = nil
        }

        return BLECharacteristicDiagnostic(
            uuid: characteristic.uuid,
            properties: characteristic.properties,
            role: role,
            isNotifying: characteristic.isNotifying
        )
    }

    private func uuidsEqual(
        _ lhs: String,
        _ rhs: String
    ) -> Bool {
        lhs.caseInsensitiveCompare(rhs) == .orderedSame
    }
}

private extension BLECentralService {
    func handleTransportEvent(_ event: BLETransportEvent) {
        switch event {
        case let .availabilityChanged(availability):
            handleAvailabilityChanged(availability)
        case let .discovered(discovery):
            updateDevice(discovery)
        case let .connected(deviceID):
            handleConnected(deviceID: deviceID)
        case let .failedToConnect(deviceID, message):
            handleFailedToConnect(
                deviceID: deviceID,
                message: message
            )
        case let .disconnected(deviceID, message):
            handleDisconnected(
                deviceID: deviceID,
                message: message
            )
        case let .servicesDiscovered(
            deviceID,
            serviceUUIDs,
            errorMessage
        ):
            handleServicesDiscovered(
                deviceID: deviceID,
                serviceUUIDs: serviceUUIDs,
                errorMessage: errorMessage
            )
        case let .characteristicsDiscovered(
            deviceID,
            serviceUUID,
            characteristics,
            errorMessage
        ):
            handleCharacteristicsDiscovered(
                deviceID: deviceID,
                serviceUUID: serviceUUID,
                characteristics: characteristics,
                errorMessage: errorMessage
            )
        case let .notificationStateChanged(
            deviceID,
            characteristicUUID,
            isNotifying,
            errorMessage
        ):
            handleNotificationStateChanged(
                deviceID: deviceID,
                characteristicUUID: characteristicUUID,
                isNotifying: isNotifying,
                errorMessage: errorMessage
            )
        case let .valueReceived(
            deviceID,
            characteristicUUID,
            data,
            errorMessage
        ):
            handleValueReceived(
                deviceID: deviceID,
                characteristicUUID: characteristicUUID,
                data: data,
                errorMessage: errorMessage
            )
        }
    }

    func handleAvailabilityChanged(
        _ availability: BluetoothAvailability
    ) {
        self.availability = availability

        guard availability == .poweredOn else {
            stopConnectionDeadline()
            stopFreshnessMonitoring()
            stopWaveformSnapshotPublishing()
            if activeDeviceID != nil {
                activeDeviceID = nil
                notifyCharacteristicUUID = nil
                controlCharacteristicUUID = nil
                let message =
                    "\(availability.title)，当前连接已结束。"
                lastError = message
                connectionPhase = .failed(message)
            }
            if isScanning {
                transport.stopScanning()
                isScanning = false
            }
            return
        }

        if shouldScanWhenReady {
            beginScanning()
        }
    }

    func handleConnected(deviceID: UUID) {
        guard acceptsCallback(
            from: deviceID,
            during: .connecting(deviceID)
        ) else {
            transport.disconnect(from: deviceID)
            return
        }
        connectionPhase = .discoveringServices(deviceID)
        startConnectionDeadline(
            .serviceDiscovery,
            deviceID: deviceID
        )
        transport.discoverServices(for: deviceID)
    }

    func handleFailedToConnect(
        deviceID: UUID,
        message: String?
    ) {
        guard activeDeviceID == deviceID else {
            connectionAttemptDiagnostics
                .ignoredStaleCallbackCount += 1
            return
        }
        let failureAlreadyPublished: Bool
        if case .failed = connectionPhase {
            failureAlreadyPublished = true
        } else {
            failureAlreadyPublished = false
        }
        stopFreshnessMonitoring()
        stopWaveformSnapshotPublishing()
        activeDeviceID = nil
        connectionAttemptDiagnostics.activeOperation = nil
        if !failureAlreadyPublished {
            failConnection(message ?? "无法连接设备。")
        }
    }

    func handleDisconnected(
        deviceID: UUID,
        message: String?
    ) {
        guard activeDeviceID == deviceID else {
            connectionAttemptDiagnostics
                .ignoredStaleCallbackCount += 1
            return
        }
        let failureAlreadyPublished: Bool
        if case .failed = connectionPhase {
            failureAlreadyPublished = true
        } else {
            failureAlreadyPublished = false
        }
        stopConnectionDeadline()
        activeDeviceID = nil
        connectionAttemptDiagnostics.activeOperation = nil
        notifyCharacteristicUUID = nil
        controlCharacteristicUUID = nil
        stopFreshnessMonitoring()
        stopWaveformSnapshotPublishing()

        if failureAlreadyPublished {
            return
        }
        if let message {
            failConnection("设备连接已中断：\(message)")
        } else {
            connectionPhase = .idle
        }
    }

    func handleServicesDiscovered(
        deviceID: UUID,
        serviceUUIDs: [String],
        errorMessage: String?
    ) {
        guard acceptsCallback(
            from: deviceID,
            during: .discoveringServices(deviceID)
        ) else {
            return
        }
        if let errorMessage {
            failConnection(
                "服务发现失败：\(errorMessage)",
                disconnect: deviceID
            )
            return
        }

        discoveredServiceUUIDs = serviceUUIDs.sorted()
        guard let targetServiceUUID = serviceUUIDs.first(
            where: {
                uuidsEqual(
                    $0,
                    profile.serviceUUIDString
                )
            }
        ) else {
            failConnection(
                "设备未提供 CUP NUS 服务 \(profile.serviceUUIDString)。",
                disconnect: deviceID
            )
            return
        }

        connectionPhase = .discoveringCharacteristics(deviceID)
        startConnectionDeadline(
            .characteristicDiscovery,
            deviceID: deviceID
        )
        transport.discoverCharacteristics(
            [
                profile.notifyCharacteristicUUIDString,
                profile.controlCharacteristicUUIDString
            ],
            for: targetServiceUUID,
            deviceID: deviceID
        )
    }

    func handleCharacteristicsDiscovered(
        deviceID: UUID,
        serviceUUID: String,
        characteristics: [BLETransportCharacteristic],
        errorMessage: String?
    ) {
        guard acceptsCallback(
            from: deviceID,
            during: .discoveringCharacteristics(deviceID)
        ) else {
            return
        }
        guard uuidsEqual(
            serviceUUID,
            profile.serviceUUIDString
        ) else {
            connectionAttemptDiagnostics
                .ignoredStaleCallbackCount += 1
            return
        }
        if let errorMessage {
            failConnection(
                "特征发现失败：\(errorMessage)",
                disconnect: deviceID
            )
            return
        }

        discoveredCharacteristics = characteristics
            .map(diagnostic(for:))
            .sorted { $0.uuid < $1.uuid }

        guard let notify = characteristics.first(
            where: {
                uuidsEqual(
                    $0.uuid,
                    profile.notifyCharacteristicUUIDString
                )
            }
        ) else {
            failConnection(
                "未找到 TX notify 特征 \(profile.notifyCharacteristicUUIDString)。",
                disconnect: deviceID
            )
            return
        }
        guard notify.supportsNotifications else {
            failConnection(
                "TX 特征不支持 notify/indicate。",
                disconnect: deviceID
            )
            return
        }

        notifyCharacteristicUUID = notify.uuid
        controlCharacteristicUUID = characteristics.first(
            where: {
                uuidsEqual(
                    $0.uuid,
                    profile.controlCharacteristicUUIDString
                )
            }
        )?.uuid
        connectionPhase = .subscribing(deviceID)
        startConnectionDeadline(
            .notificationSubscription,
            deviceID: deviceID
        )
        transport.setNotificationsEnabled(
            true,
            characteristicUUID: notify.uuid,
            deviceID: deviceID
        )
    }

    func handleNotificationStateChanged(
        deviceID: UUID,
        characteristicUUID: String,
        isNotifying: Bool,
        errorMessage: String?
    ) {
        guard uuidsEqual(
            characteristicUUID,
            profile.notifyCharacteristicUUIDString
        ) else {
            return
        }
        guard acceptsCallback(
            from: deviceID,
            during: .subscribing(deviceID)
        ) else {
            return
        }
        if let errorMessage {
            failConnection(
                "通知订阅失败：\(errorMessage)",
                disconnect: deviceID
            )
            return
        }
        guard isNotifying else {
            failConnection(
                "设备未启用 TX 通知。",
                disconnect: deviceID
            )
            return
        }

        updateCharacteristicNotificationState(
            uuid: characteristicUUID,
            isNotifying: true
        )
        stopConnectionDeadline()
        connectionPhase = .subscribed(deviceID)
        startFreshnessMonitoring()
        startWaveformSnapshotPublishing()
        publishStreamingDiagnostics(force: true)
    }

    func handleValueReceived(
        deviceID: UUID,
        characteristicUUID: String,
        data: Data?,
        errorMessage: String?
    ) {
        guard uuidsEqual(
            characteristicUUID,
            profile.notifyCharacteristicUUIDString
        ) else {
            return
        }
        guard activeDeviceID == deviceID,
              connectionPhase == .subscribed(deviceID)
                || connectionPhase == .receiving(deviceID)
        else {
            connectionAttemptDiagnostics
                .ignoredStaleCallbackCount += 1
            return
        }

        if let errorMessage {
            streamingPipeline.recordNotificationError()
            lastError = "通知接收错误：\(errorMessage)"
            publishStreamingDiagnostics(force: true)
            return
        }
        guard let data, !data.isEmpty else {
            return
        }

        let hostMonotonicNanoseconds =
            DispatchTime.now().uptimeNanoseconds
        pendingLastNotificationAt = Date()
        let frameEvents = streamingPipeline.receive(data)
        let acceptedSampleCount = frameEvents.reduce(into: 0) {
            if $1.isAccepted {
                $0 += $1.frame.samples.count
            }
        }
        let acceptedSampleStartIndex: UInt64? =
            acceptedSampleCount > 0
            ? UInt64(
                streamingPipeline.diagnostics.acceptedSamples
                    - acceptedSampleCount
            )
            : nil
        if acceptedSampleCount > 0 {
            markValidFrameReceived()
            let previousGeneration =
                liveMetricWindowScheduler.generation
            let request = liveMetricWindowScheduler.ingest(
                decodedFrames: frameEvents,
                acceptedSampleStartIndex: acceptedSampleStartIndex,
                measuredAt: Date()
            )
            if liveMetricWindowScheduler.generation
                != previousGeneration {
                cancelPendingLiveMetricAnalysis(
                    countAsDiscarded: true
                )
                liveMetricSnapshot = .warmingUp()
            }
            liveMetricRuntimeDiagnostics.continuousSampleCount =
                liveMetricWindowScheduler.continuousSamples
            liveMetricRuntimeDiagnostics.bufferedSampleCount =
                liveMetricWindowScheduler.bufferedSampleCount
            if let request {
                scheduleLiveMetricAnalysis(request)
            }
        }
        streamEvents.send(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: hostMonotonicNanoseconds,
                data: data,
                decodedFrames: frameEvents,
                acceptedSampleStartIndex: acceptedSampleStartIndex,
                metrics: liveMetricSnapshot
            )
        )
        if connectionPhase != .receiving(deviceID) {
            connectionPhase = .receiving(deviceID)
        }
        publishStreamingDiagnostics(force: !frameEvents.isEmpty)
    }
}
