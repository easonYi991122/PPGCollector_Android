import Combine
import Foundation
import Testing
@testable import PPGCollector

@MainActor
struct BLECentralServiceIntegrationTests {
    @Test
    func scanCommandsFilterAndRefreshTransportDiscoveries() async {
        let transport = TestBLETransport()
        let service = BLECentralService(transport: transport)
        #expect(transport.commands == [.activate])

        service.startScanning(clearPreviousResults: true)
        #expect(!service.isScanning)
        #expect(transport.commands == [.activate])

        transport.send(.availabilityChanged(.poweredOn))
        #expect(
            await eventuallyBLE {
                service.availability
                    == BluetoothAvailability.poweredOn
                    && service.isScanning
            }
        )
        #expect(transport.commands.contains(.startScanning))

        transport.send(
            .discovered(
                BLETransportDiscovery(
                    deviceID: UUID(),
                    name: "OTHER-DEVICE",
                    rssi: -40,
                    isConnectable: true,
                    seenAt: Date(timeIntervalSince1970: 1)
                )
            )
        )
        let deviceID = testBLEDeviceID
        transport.send(
            .discovered(
                BLETransportDiscovery(
                    deviceID: deviceID,
                    name: "CUP-SIM-TEST",
                    rssi: -70,
                    isConnectable: true,
                    seenAt: Date(timeIntervalSince1970: 2)
                )
            )
        )
        #expect(
            await eventuallyBLE {
                service.discoveredDevices.count == 1
            }
        )
        #expect(service.discoveredDevices.first?.id == deviceID)

        transport.send(
            .discovered(
                BLETransportDiscovery(
                    deviceID: deviceID,
                    name: "CUP-SIM-RENAMED",
                    rssi: -48,
                    isConnectable: false,
                    seenAt: Date(timeIntervalSince1970: 3)
                )
            )
        )
        #expect(
            await eventuallyBLE {
                service.discoveredDevices.first?.rssi == -48
            }
        )
        #expect(
            service.discoveredDevices.first?.name
                == "CUP-SIM-RENAMED"
        )
        #expect(
            service.discoveredDevices.first?.isConnectable == false
        )

        service.stopScanning()
        #expect(!service.isScanning)
        #expect(transport.commands.last == .stopScanning)
    }

    @Test
    func delegateHappyPathPublishesTheUnmodifiedRawChunk() async {
        let fixture = await makeConnectingBLEFixture()
        await advanceToSubscribed(fixture)
        #expect(
            fixture.service.connectionPhase
                == .subscribed(fixture.deviceID)
        )
        #expect(fixture.service.streamFreshness == .waiting)

        var receivedEvents: [CUPStreamChunkEvent] = []
        let subscription = fixture.service.streamEvents.sink {
            receivedEvents.append($0)
        }
        let frame = makeCUPTestFrame(sequence: 7)
        fixture.transport.send(
            .valueReceived(
                deviceID: fixture.deviceID,
                characteristicUUID:
                    fixture.service.profile
                    .notifyCharacteristicUUIDString,
                data: frame,
                errorMessage: nil
            )
        )

        #expect(
            await eventuallyBLE {
                receivedEvents.count == 1
                    && fixture.service.connectionPhase
                        == .receiving(fixture.deviceID)
            }
        )
        #expect(receivedEvents.first?.data == frame)
        #expect(receivedEvents.first?.decodedFrames.count == 1)
        #expect(
            receivedEvents.first?.decodedFrames.first?
                .frame.sequence == 7
        )
        #expect(
            fixture.service.streamingDiagnostics.decodedFrames == 1
        )
        #expect(
            fixture.service.streamingDiagnostics.acceptedSamples
                == CUPBatchProtocolV1.samplesPerFrame
        )
        #expect(fixture.service.streamFreshness == .fresh)
        withExtendedLifetime(subscription) {}
    }

    @Test
    func slowMetricAnalysisDoesNotBlockBLEReceptionOrRecording()
        async
    {
        let slowAnalyzer = DeferredMetricAnalysisRunner()
        let fixture = await makeConnectingBLEFixture(
            liveMetricAnalysisRunner: PPGLiveMetricAnalysisRunner {
                request,
                profile in
                await slowAnalyzer.run(request, profile: profile)
            }
        )
        await advanceToSubscribed(fixture)

        let writerProbe = AlgorithmPressureWriterProbe()
        let controller = CaptureSessionController(
            streamSource: fixture.service,
            writerFactory: CaptureSessionWriterFactory { configuration in
                AlgorithmPressureWriter(
                    configuration: configuration,
                    probe: writerProbe
                )
            }
        )

        sendFrame(sequence: 0, to: fixture)
        #expect(
            await eventuallyBLE {
                fixture.service.streamFreshness == .fresh
            }
        )
        controller.sessionName = "algorithm_pressure"
        controller.startRecording()
        #expect(
            await eventuallyBLE {
                controller.state == .recording
            }
        )

        for sequence in 1...15 {
            sendFrame(sequence: UInt8(sequence), to: fixture)
        }
        #expect(await slowAnalyzer.waitForStartedCount(1))

        for sequence in 16..<32 {
            sendFrame(sequence: UInt8(sequence), to: fixture)
        }
        #expect(
            await eventuallyBLE {
                fixture.service.streamingDiagnostics.acceptedSamples
                    == 1_600
                    && fixture.service.liveMetricRuntimeDiagnostics
                    .scheduledAnalysisCount == 9
            }
        )
        #expect(
            await writerProbe.waitForAppendCount(31)
        )
        #expect(
            fixture.service.connectionPhase
                == .receiving(fixture.deviceID)
        )
        #expect(
            fixture.service.runtimeResourceSnapshot.recentSampleCount == 800
        )
        #expect(
            fixture.service.runtimeResourceSnapshot.metricBufferedSampleCount
                == 800
        )

        let beforeRelease = await writerProbe.snapshot()
        #expect(beforeRelease.appendCount == 31)
        #expect(beforeRelease.rawPayloadBytes == 31 * 408)
        #expect(beforeRelease.acceptedFrames == 31)
        #expect(beforeRelease.acceptedSamples == 1_550)

        await slowAnalyzer.release()
        #expect(
            await eventuallyBLE {
                fixture.service.liveMetricRuntimeDiagnostics
                    .completedAnalysisCount == 1
                    && fixture.service.liveMetricRuntimeDiagnostics
                    .discardedAnalysisCount >= 8
                    && fixture.service.liveMetricRuntimeDiagnostics
                    .latestWindowEndSampleIndex == 1_599
            }
        )
        #expect(
            fixture.service.runtimeResourceSnapshot.activeTaskCount == 2
        )

        controller.stopRecording()
        #expect(
            await eventuallyBLE {
                controller.lastSummary?.stopReason == .user
            }
        )
        let finalized = await writerProbe.snapshot()
        #expect(finalized.finishReasons == [.user])
        #expect(controller.lastSummary?.complete == true)
    }

    @Test
    func serviceDiscoveryErrorDisconnectsAndRetryWaitsForRelease()
        async
    {
        let fixture = await makeConnectingBLEFixture()
        fixture.transport.send(
            .connected(deviceID: fixture.deviceID)
        )
        #expect(
            await eventuallyBLE {
                fixture.service.connectionPhase
                    == .discoveringServices(fixture.deviceID)
            }
        )

        fixture.transport.send(
            .servicesDiscovered(
                deviceID: fixture.deviceID,
                serviceUUIDs: [],
                errorMessage: "注入服务错误"
            )
        )
        #expect(
            await eventuallyBLE {
                fixture.service.lastError
                    == "服务发现失败：注入服务错误"
            }
        )
        #expect(
            fixture.transport.commands.contains(
                .disconnect(fixture.deviceID)
            )
        )
        #expect(!fixture.service.canRetryLastConnection)

        fixture.transport.send(
            .disconnected(
                deviceID: fixture.deviceID,
                message: nil
            )
        )
        #expect(
            await eventuallyBLE {
                fixture.service.canRetryLastConnection
            }
        )
        #expect(
            fixture.service.connectionPhase
                == .failed("服务发现失败：注入服务错误")
        )

        fixture.service.retryLastConnection()
        #expect(
            fixture.service.connectionPhase
                == .connecting(fixture.deviceID)
        )
        #expect(
            fixture.service.connectionAttemptDiagnostics
                .attemptCount == 2
        )
        #expect(
            fixture.transport.commands.filter {
                $0 == .connect(fixture.deviceID)
            }.count == 2
        )
    }

    @Test
    func failedConnectReleasesTheAttemptAndAllowsRetry() async {
        let fixture = await makeConnectingBLEFixture()

        fixture.transport.send(
            .failedToConnect(
                deviceID: fixture.deviceID,
                message: "注入连接失败"
            )
        )
        #expect(
            fixture.service.connectionPhase
                == .failed("注入连接失败")
        )
        #expect(fixture.service.lastError == "注入连接失败")
        #expect(fixture.service.canRetryLastConnection)
        #expect(
            fixture.service.connectionAttemptDiagnostics
                .activeOperation == nil
        )

        fixture.service.retryLastConnection()
        #expect(
            fixture.service.connectionPhase
                == .connecting(fixture.deviceID)
        )
        #expect(
            fixture.service.connectionAttemptDiagnostics
                .attemptCount == 2
        )
    }

    @Test
    func missingTargetServiceEndsTheAttempt() async {
        let fixture = await makeConnectingBLEFixture()
        fixture.transport.send(
            .connected(deviceID: fixture.deviceID)
        )
        #expect(
            await eventuallyBLE {
                fixture.service.connectionPhase
                    == .discoveringServices(fixture.deviceID)
            }
        )
        fixture.transport.send(
            .servicesDiscovered(
                deviceID: fixture.deviceID,
                serviceUUIDs: [
                    "0000180D-0000-1000-8000-00805F9B34FB"
                ],
                errorMessage: nil
            )
        )

        #expect(
            await eventuallyBLE {
                fixture.service.lastError?.hasPrefix(
                    "设备未提供 CUP NUS 服务"
                ) == true
            }
        )
        #expect(
            fixture.transport.commands.last
                == .disconnect(fixture.deviceID)
        )
    }

    @Test
    func missingOrUnsupportedNotifyCharacteristicEndsTheAttempt()
        async
    {
        let missingFixture = await makeConnectingBLEFixture()
        await advanceToCharacteristicDiscovery(missingFixture)
        missingFixture.transport.send(
            .characteristicsDiscovered(
                deviceID: missingFixture.deviceID,
                serviceUUID:
                    missingFixture.service.profile
                    .serviceUUIDString,
                characteristics: [
                    controlCharacteristic(
                        profile: missingFixture.service.profile
                    )
                ],
                errorMessage: nil
            )
        )
        #expect(
            await eventuallyBLE {
                missingFixture.service.lastError?.hasPrefix(
                    "未找到 TX notify 特征"
                ) == true
            }
        )

        let unsupportedFixture = await makeConnectingBLEFixture()
        await advanceToCharacteristicDiscovery(unsupportedFixture)
        unsupportedFixture.transport.send(
            .characteristicsDiscovered(
                deviceID: unsupportedFixture.deviceID,
                serviceUUID:
                    unsupportedFixture.service.profile
                    .serviceUUIDString,
                characteristics: [
                    BLETransportCharacteristic(
                        uuid:
                            unsupportedFixture.service.profile
                            .notifyCharacteristicUUIDString,
                        properties: ["read"],
                        supportsNotifications: false,
                        isNotifying: false
                    )
                ],
                errorMessage: nil
            )
        )
        #expect(
            await eventuallyBLE {
                unsupportedFixture.service.lastError
                    == "TX 特征不支持 notify/indicate。"
            }
        )
    }

    @Test
    func notificationEnableErrorOrFalseStateEndsTheAttempt() async {
        let errorFixture = await makeConnectingBLEFixture()
        await advanceToSubscribing(errorFixture)
        errorFixture.transport.send(
            .notificationStateChanged(
                deviceID: errorFixture.deviceID,
                characteristicUUID:
                    errorFixture.service.profile
                    .notifyCharacteristicUUIDString,
                isNotifying: false,
                errorMessage: "注入通知错误"
            )
        )
        #expect(
            await eventuallyBLE {
                errorFixture.service.lastError
                    == "通知订阅失败：注入通知错误"
            }
        )

        let falseFixture = await makeConnectingBLEFixture()
        await advanceToSubscribing(falseFixture)
        falseFixture.transport.send(
            .notificationStateChanged(
                deviceID: falseFixture.deviceID,
                characteristicUUID:
                    falseFixture.service.profile
                    .notifyCharacteristicUUIDString,
                isNotifying: false,
                errorMessage: nil
            )
        )
        #expect(
            await eventuallyBLE {
                falseFixture.service.lastError
                    == "设备未启用 TX 通知。"
            }
        )
    }

    @Test
    func poweredOffAndUnauthorizedEndAnActiveAttempt() async {
        for availability in [
            BluetoothAvailability.poweredOff,
            .unauthorized
        ] {
            let fixture = await makeConnectingBLEFixture()
            fixture.transport.send(
                .availabilityChanged(availability)
            )

            #expect(
                await eventuallyBLE {
                    fixture.service.availability == availability
                        && fixture.service.connectionPhase
                            == .failed(
                                "\(availability.title)，当前连接已结束。"
                            )
                }
            )
            #expect(
                fixture.service.connectionAttemptDiagnostics
                    .activeOperation == nil
            )
            #expect(!fixture.service.canRetryLastConnection)
            #expect(fixture.service.streamFreshness == .unavailable)
        }
    }

    @Test
    func activeDisconnectPreservesReasonAndAllowsRetry() async {
        let fixture = await makeConnectingBLEFixture()
        await advanceToSubscribed(fixture)

        fixture.transport.send(
            .disconnected(
                deviceID: fixture.deviceID,
                message: "注入链路丢失"
            )
        )
        #expect(
            await eventuallyBLE {
                fixture.service.connectionPhase
                    == .failed(
                        "设备连接已中断：注入链路丢失"
                    )
            }
        )
        #expect(fixture.service.canRetryLastConnection)
        #expect(fixture.service.streamFreshness == .unavailable)

        fixture.service.retryLastConnection()
        #expect(
            fixture.service.connectionPhase
                == .connecting(fixture.deviceID)
        )
        #expect(
            fixture.service.connectionAttemptDiagnostics
                .attemptCount == 2
        )
    }

    @Test
    func twentyConnectionCyclesReleaseRuntimeResourcesAndResetData()
        async
    {
        let fixture = await makeConnectingBLEFixture()
        var receivedEvents: [CUPStreamChunkEvent] = []
        let subscription = fixture.service.streamEvents.sink {
            receivedEvents.append($0)
        }

        for cycle in 0..<20 {
            if cycle > 0 {
                fixture.service.connect(to: fixture.deviceID)
            }
            #expect(
                fixture.service.connectionPhase
                    == .connecting(fixture.deviceID)
            )
            #expect(
                fixture.service.connectionAttemptDiagnostics
                    .attemptCount == cycle + 1
            )
            #expect(
                fixture.service.streamingDiagnostics
                    == CUPStreamingDiagnostics()
            )
            #expect(
                fixture.service.liveMetricRuntimeDiagnostics
                    == PPGLiveMetricRuntimeDiagnostics()
            )
            #expect(
                fixture.service.runtimeResourceSnapshot
                    .connectionDeadlineTaskActive
            )
            #expect(
                fixture.service.runtimeResourceSnapshot
                    .recentSampleCount == 0
            )
            #expect(
                fixture.service.runtimeResourceSnapshot
                    .metricBufferedSampleCount == 0
            )

            await advanceToSubscribed(fixture)
            var resources =
                fixture.service.runtimeResourceSnapshot
            #expect(!resources.connectionDeadlineTaskActive)
            #expect(resources.freshnessTaskActive)
            #expect(resources.waveformSnapshotTaskActive)
            #expect(!resources.metricComputationTaskActive)
            #expect(!resources.metricCompletionTaskActive)
            #expect(resources.activeTaskCount == 2)

            let frame = makeCUPTestFrame(
                sequence: UInt8(200 + cycle)
            )
            fixture.transport.send(
                .valueReceived(
                    deviceID: fixture.deviceID,
                    characteristicUUID:
                        fixture.service.profile
                        .notifyCharacteristicUUIDString,
                    data: frame,
                    errorMessage: nil
                )
            )
            #expect(
                await eventuallyBLE {
                    receivedEvents.count == cycle + 1
                        && fixture.service.connectionPhase
                            == .receiving(fixture.deviceID)
                }
            )
            let event = receivedEvents[cycle]
            #expect(event.data == frame)
            #expect(event.acceptedSampleStartIndex == 0)
            #expect(event.decodedFrames.count == 1)
            #expect(
                event.decodedFrames.first?.sequenceEvent == .first
            )
            #expect(
                event.decodedFrames.first?.frame.sequence
                    == UInt8(200 + cycle)
            )
            #expect(
                fixture.service.streamingDiagnostics.decodedFrames
                    == 1
            )
            #expect(
                fixture.service.streamingDiagnostics.acceptedSamples
                    == 50
            )
            #expect(fixture.service.streamFreshness == .fresh)
            resources = fixture.service.runtimeResourceSnapshot
            #expect(resources.decoderPendingBytes == 0)
            #expect(resources.recentSampleCount == 50)
            #expect(resources.metricBufferedSampleCount == 50)
            #expect(resources.activeTaskCount == 2)

            fixture.service.disconnect()
            #expect(
                fixture.service.connectionPhase
                    == .disconnecting(fixture.deviceID)
            )
            fixture.transport.send(
                .disconnected(
                    deviceID: fixture.deviceID,
                    message: nil
                )
            )
            #expect(
                await eventuallyBLE {
                    fixture.service.connectionPhase == .idle
                }
            )
            #expect(fixture.service.canRetryLastConnection)
            #expect(
                fixture.service.streamFreshness == .unavailable
            )
            #expect(
                fixture.service.liveMetricRuntimeDiagnostics
                    == PPGLiveMetricRuntimeDiagnostics()
            )
            resources = fixture.service.runtimeResourceSnapshot
            #expect(resources.activeTaskCount == 0)
            #expect(resources.decoderPendingBytes == 0)
            #expect(resources.recentSampleCount == 50)
            #expect(resources.metricBufferedSampleCount == 0)

            fixture.transport.send(
                .servicesDiscovered(
                    deviceID: fixture.deviceID,
                    serviceUUIDs: [
                        fixture.service.profile.serviceUUIDString
                    ],
                    errorMessage: nil
                )
            )
            fixture.transport.send(
                .valueReceived(
                    deviceID: fixture.deviceID,
                    characteristicUUID:
                        fixture.service.profile
                        .notifyCharacteristicUUIDString,
                    data: frame,
                    errorMessage: nil
                )
            )
            #expect(fixture.service.connectionPhase == .idle)
            #expect(receivedEvents.count == cycle + 1)
            #expect(
                fixture.service.connectionAttemptDiagnostics
                    .ignoredStaleCallbackCount == (cycle + 1) * 2
            )
            resources = fixture.service.runtimeResourceSnapshot
            #expect(resources.activeTaskCount == 0)
            #expect(resources.decoderPendingBytes == 0)
            #expect(resources.recentSampleCount == 50)
            #expect(resources.metricBufferedSampleCount == 0)
        }

        #expect(
            fixture.transport.commands.filter {
                $0 == .connect(fixture.deviceID)
            }.count == 20
        )
        #expect(
            fixture.transport.commands.filter {
                $0 == .discoverServices(fixture.deviceID)
            }.count == 20
        )
        #expect(
            fixture.transport.commands.filter {
                if case .discoverCharacteristics = $0 {
                    return true
                }
                return false
            }.count == 20
        )
        #expect(
            fixture.transport.commands.filter {
                $0 == .setNotifications(
                    enabled: true,
                    deviceID: fixture.deviceID,
                    characteristicUUID:
                        fixture.service.profile
                        .notifyCharacteristicUUIDString
                )
            }.count == 20
        )
        #expect(
            fixture.transport.commands.filter {
                $0 == .setNotifications(
                    enabled: false,
                    deviceID: fixture.deviceID,
                    characteristicUUID:
                        fixture.service.profile
                        .notifyCharacteristicUUIDString
                )
            }.count == 20
        )
        #expect(
            fixture.transport.commands.filter {
                $0 == .disconnect(fixture.deviceID)
            }.count == 20
        )
        #expect(receivedEvents.count == 20)
        #expect(
            fixture.service.connectionAttemptDiagnostics
                .attemptCount == 20
        )
        #expect(
            fixture.service.runtimeResourceSnapshot.activeTaskCount
                == 0
        )
        withExtendedLifetime(subscription) {}
    }

    @Test
    func staleDelegateCallbacksCannotAdvanceTheCurrentAttempt() async {
        let fixture = await makeConnectingBLEFixture()
        let unrelatedDeviceID = UUID()

        fixture.transport.send(
            .connected(deviceID: unrelatedDeviceID)
        )
        #expect(
            await eventuallyBLE {
                fixture.service.connectionAttemptDiagnostics
                    .ignoredStaleCallbackCount == 1
            }
        )
        #expect(
            fixture.service.connectionPhase
                == .connecting(fixture.deviceID)
        )
        #expect(
            fixture.transport.commands.contains(
                .disconnect(unrelatedDeviceID)
            )
        )

        fixture.transport.send(
            .servicesDiscovered(
                deviceID: fixture.deviceID,
                serviceUUIDs: [
                    fixture.service.profile.serviceUUIDString
                ],
                errorMessage: nil
            )
        )
        #expect(
            await eventuallyBLE {
                fixture.service.connectionAttemptDiagnostics
                    .ignoredStaleCallbackCount == 2
            }
        )
        #expect(
            fixture.service.connectionPhase
                == .connecting(fixture.deviceID)
        )
    }
}

private enum TestBLETransportCommand: Equatable {
    case activate
    case startScanning
    case stopScanning
    case connect(UUID)
    case disconnect(UUID)
    case discoverServices(UUID)
    case discoverCharacteristics(
        deviceID: UUID,
        serviceUUID: String,
        characteristicUUIDs: [String]
    )
    case setNotifications(
        enabled: Bool,
        deviceID: UUID,
        characteristicUUID: String
    )
}

@MainActor
private final class TestBLETransport: BLETransporting {
    private var eventHandler:
        (@MainActor (BLETransportEvent) -> Void)?
    private(set) var commands: [TestBLETransportCommand] = []

    func setEventHandler(
        _ handler: @escaping @MainActor (BLETransportEvent) -> Void
    ) {
        eventHandler = handler
    }

    func activate() {
        commands.append(.activate)
    }

    func startScanning() {
        commands.append(.startScanning)
    }

    func stopScanning() {
        commands.append(.stopScanning)
    }

    func connect(to deviceID: UUID) {
        commands.append(.connect(deviceID))
    }

    func disconnect(from deviceID: UUID) {
        commands.append(.disconnect(deviceID))
    }

    func discoverServices(for deviceID: UUID) {
        commands.append(.discoverServices(deviceID))
    }

    func discoverCharacteristics(
        _ characteristicUUIDs: [String],
        for serviceUUID: String,
        deviceID: UUID
    ) {
        commands.append(
            .discoverCharacteristics(
                deviceID: deviceID,
                serviceUUID: serviceUUID,
                characteristicUUIDs: characteristicUUIDs
            )
        )
    }

    func setNotificationsEnabled(
        _ enabled: Bool,
        characteristicUUID: String,
        deviceID: UUID
    ) {
        commands.append(
            .setNotifications(
                enabled: enabled,
                deviceID: deviceID,
                characteristicUUID: characteristicUUID
            )
        )
    }

    func send(_ event: BLETransportEvent) {
        eventHandler?(event)
    }
}

@MainActor
private struct BLEIntegrationFixture {
    let service: BLECentralService
    let transport: TestBLETransport
    let deviceID: UUID
}

private let testBLEDeviceID =
    UUID(uuidString: "80600824-940B-633D-EFE7-205D771ECDD4")!

@MainActor
private func makeConnectingBLEFixture(
    liveMetricAnalysisRunner: PPGLiveMetricAnalysisRunner = .production
)
    async -> BLEIntegrationFixture
{
    let transport = TestBLETransport()
    let service = BLECentralService(
        connectionTimeoutPolicy: BLEConnectionTimeoutPolicy(
            connectSeconds: 60,
            serviceDiscoverySeconds: 60,
            characteristicDiscoverySeconds: 60,
            notificationSubscriptionSeconds: 60
        ),
        liveMetricAnalysisRunner: liveMetricAnalysisRunner,
        transport: transport
    )
    transport.send(.availabilityChanged(.poweredOn))
    _ = await eventuallyBLE {
        service.availability
            == BluetoothAvailability.poweredOn
    }
    transport.send(
        .discovered(
            BLETransportDiscovery(
                deviceID: testBLEDeviceID,
                name: "CUP-SIM-INTEGRATION",
                rssi: -52,
                isConnectable: true,
                seenAt: Date()
            )
        )
    )
    _ = await eventuallyBLE {
        service.discoveredDevices.contains {
            $0.id == testBLEDeviceID
        }
    }
    service.connect(to: testBLEDeviceID)
    #expect(
        transport.commands.last == .connect(testBLEDeviceID)
    )
    return BLEIntegrationFixture(
        service: service,
        transport: transport,
        deviceID: testBLEDeviceID
    )
}

@MainActor
private func advanceToCharacteristicDiscovery(
    _ fixture: BLEIntegrationFixture
) async {
    fixture.transport.send(
        .connected(deviceID: fixture.deviceID)
    )
    _ = await eventuallyBLE {
        fixture.service.connectionPhase
            == .discoveringServices(fixture.deviceID)
    }
    #expect(
        fixture.transport.commands.last
            == .discoverServices(fixture.deviceID)
    )
    fixture.transport.send(
        .servicesDiscovered(
            deviceID: fixture.deviceID,
            serviceUUIDs: [
                fixture.service.profile.serviceUUIDString
            ],
            errorMessage: nil
        )
    )
    _ = await eventuallyBLE {
        fixture.service.connectionPhase
            == .discoveringCharacteristics(fixture.deviceID)
    }
    #expect(
        fixture.transport.commands.last
            == .discoverCharacteristics(
                deviceID: fixture.deviceID,
                serviceUUID:
                    fixture.service.profile.serviceUUIDString,
                characteristicUUIDs: [
                    fixture.service.profile
                        .notifyCharacteristicUUIDString,
                    fixture.service.profile
                        .controlCharacteristicUUIDString
                ]
            )
    )
}

@MainActor
private func advanceToSubscribing(
    _ fixture: BLEIntegrationFixture
) async {
    await advanceToCharacteristicDiscovery(fixture)
    fixture.transport.send(
        .characteristicsDiscovered(
            deviceID: fixture.deviceID,
            serviceUUID:
                fixture.service.profile.serviceUUIDString,
            characteristics: [
                notifyCharacteristic(
                    profile: fixture.service.profile
                ),
                controlCharacteristic(
                    profile: fixture.service.profile
                )
            ],
            errorMessage: nil
        )
    )
    _ = await eventuallyBLE {
        fixture.service.connectionPhase
            == .subscribing(fixture.deviceID)
    }
    #expect(
        fixture.transport.commands.last
            == .setNotifications(
                enabled: true,
                deviceID: fixture.deviceID,
                characteristicUUID:
                    fixture.service.profile
                    .notifyCharacteristicUUIDString
            )
    )
}

@MainActor
private func advanceToSubscribed(
    _ fixture: BLEIntegrationFixture
) async {
    await advanceToSubscribing(fixture)
    fixture.transport.send(
        .notificationStateChanged(
            deviceID: fixture.deviceID,
            characteristicUUID:
                fixture.service.profile
                .notifyCharacteristicUUIDString,
            isNotifying: true,
            errorMessage: nil
        )
    )
    _ = await eventuallyBLE {
        fixture.service.connectionPhase
            == .subscribed(fixture.deviceID)
    }
}

private func notifyCharacteristic(
    profile: CUPDeviceProfile
) -> BLETransportCharacteristic {
    BLETransportCharacteristic(
        uuid: profile.notifyCharacteristicUUIDString,
        properties: ["notify"],
        supportsNotifications: true,
        isNotifying: false
    )
}

private func controlCharacteristic(
    profile: CUPDeviceProfile
) -> BLETransportCharacteristic {
    BLETransportCharacteristic(
        uuid: profile.controlCharacteristicUUIDString,
        properties: ["write", "writeWithoutResponse"],
        supportsNotifications: false,
        isNotifying: false
    )
}

@MainActor
private func eventuallyBLE(
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

@MainActor
private func sendFrame(
    sequence: UInt8,
    to fixture: BLEIntegrationFixture
) {
    fixture.transport.send(
        .valueReceived(
            deviceID: fixture.deviceID,
            characteristicUUID:
                fixture.service.profile.notifyCharacteristicUUIDString,
            data: makeCUPTestFrame(sequence: sequence),
            errorMessage: nil
        )
    )
}

private actor DeferredMetricAnalysisRunner {
    private var isReleased = false
    private var startedCount = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func run(
        _ request: PPGLiveMetricAnalysisRequest,
        profile: PPGLiveMetricRuntimeProfile
    ) async -> PPGLiveMetricAnalysisResult? {
        startedCount += 1
        if !isReleased {
            await withCheckedContinuation { continuation in
                waiting.append(continuation)
            }
        }
        return PPGLiveMetricAnalyzer.analyze(request, profile: profile)
    }

    func waitForStartedCount(_ expectedCount: Int) async -> Bool {
        for _ in 0..<200 {
            if startedCount >= expectedCount {
                return true
            }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return startedCount >= expectedCount
    }

    func release() {
        guard !isReleased else {
            return
        }
        isReleased = true
        let continuations = waiting
        waiting.removeAll()
        for continuation in continuations {
            continuation.resume()
        }
    }
}

private struct AlgorithmPressureWriterSnapshot: Equatable, Sendable {
    let appendCount: Int
    let rawPayloadBytes: Int
    let acceptedFrames: Int
    let acceptedSamples: Int
    let finishReasons: [CaptureStopReason]
}

private actor AlgorithmPressureWriterProbe {
    private var appendCount = 0
    private var rawPayloadBytes = 0
    private var acceptedFrames = 0
    private var acceptedSamples = 0
    private var finishReasons: [CaptureStopReason] = []

    func recordAppend(_ event: CUPStreamChunkEvent) {
        appendCount += 1
        rawPayloadBytes += event.data.count
        for frame in event.decodedFrames where frame.isAccepted {
            acceptedFrames += 1
            acceptedSamples += frame.frame.samples.count
        }
    }

    func recordFinish(_ reason: CaptureStopReason) {
        finishReasons.append(reason)
    }

    func snapshot() -> AlgorithmPressureWriterSnapshot {
        AlgorithmPressureWriterSnapshot(
            appendCount: appendCount,
            rawPayloadBytes: rawPayloadBytes,
            acceptedFrames: acceptedFrames,
            acceptedSamples: acceptedSamples,
            finishReasons: finishReasons
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

private actor AlgorithmPressureWriter: CaptureSessionWriting {
    private let configuration: CaptureSessionConfiguration
    private let probe: AlgorithmPressureWriterProbe
    private var snapshot = CaptureWriterSnapshot()

    init(
        configuration: CaptureSessionConfiguration,
        probe: AlgorithmPressureWriterProbe
    ) {
        self.configuration = configuration
        self.probe = probe
    }

    func append(
        _ event: CUPStreamChunkEvent
    ) async throws -> CaptureWriterSnapshot {
        await probe.recordAppend(event)
        snapshot.rawChunkCount += 1
        snapshot.rawPayloadBytes += event.data.count
        for frame in event.decodedFrames where frame.isAccepted {
            snapshot.acceptedFrames += 1
            snapshot.acceptedSamples += frame.frame.samples.count
        }
        return snapshot
    }

    func discardIfEmptyBeforeRecording() async throws -> Bool {
        false
    }

    func finish(
        reason: CaptureStopReason,
        integrity: CaptureIntegritySnapshot,
        errorMessage: String?
    ) async throws -> CaptureSessionSummary {
        await probe.recordFinish(reason)
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
