import Foundation
import Testing
@testable import PPGCollector

struct BLEConnectionDeadlineTrackerTests {
    @Test
    func stageTransitionsInvalidateEarlierDeadlines() {
        let deviceID = UUID(
            uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )!
        var tracker = BLEConnectionDeadlineTracker()
        let connecting = tracker.arm(
            operation: .connect,
            deviceID: deviceID,
            now: 10,
            timeout: 12
        )
        let serviceDiscovery = tracker.arm(
            operation: .serviceDiscovery,
            deviceID: deviceID,
            now: 11,
            timeout: 8
        )

        #expect(!tracker.isCurrent(connecting))
        #expect(tracker.isCurrent(serviceDiscovery))
        let obsoleteDidExpire = tracker.consumeExpiration(
            connecting,
            now: connecting.deadlineUptime
        )
        #expect(!obsoleteDidExpire)
        #expect(tracker.isCurrent(serviceDiscovery))
    }

    @Test
    func expirationIsConsumedOnceAtTheExactBoundary() {
        let deviceID = UUID(
            uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )!
        var tracker = BLEConnectionDeadlineTracker()
        let deadline = tracker.arm(
            operation: .notificationSubscription,
            deviceID: deviceID,
            now: 20,
            timeout: 8
        )

        let expiredBeforeBoundary = tracker.consumeExpiration(
            deadline,
            now: deadline.deadlineUptime - 0.001
        )
        #expect(!expiredBeforeBoundary)
        let expiredAtBoundary = tracker.consumeExpiration(
            deadline,
            now: deadline.deadlineUptime
        )
        #expect(expiredAtBoundary)
        let expiredTwice = tracker.consumeExpiration(
            deadline,
            now: deadline.deadlineUptime + 1
        )
        #expect(!expiredTwice)
        #expect(tracker.activeDeadline == nil)
    }

    @Test
    func cancelMakesAQueuedTimeoutObsolete() {
        let deviceID = UUID(
            uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )!
        var tracker = BLEConnectionDeadlineTracker()
        let deadline = tracker.arm(
            operation: .characteristicDiscovery,
            deviceID: deviceID,
            now: 30,
            timeout: 8
        )

        tracker.cancel()

        #expect(!tracker.isCurrent(deadline))
        let canceledDidExpire = tracker.consumeExpiration(
            deadline,
            now: 100
        )
        #expect(!canceledDidExpire)
    }

    @Test
    func aNewDeviceAttemptCannotBeFailedByTheOldDeviceTimeout() {
        let firstDeviceID = UUID(
            uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )!
        let secondDeviceID = UUID(
            uuidString: "11111111-2222-3333-4444-555555555555"
        )!
        var tracker = BLEConnectionDeadlineTracker()
        let firstAttempt = tracker.arm(
            operation: .connect,
            deviceID: firstDeviceID,
            now: 0,
            timeout: 12
        )
        let secondAttempt = tracker.arm(
            operation: .connect,
            deviceID: secondDeviceID,
            now: 2,
            timeout: 12
        )

        let oldAttemptExpired = tracker.consumeExpiration(
            firstAttempt,
            now: 20
        )

        #expect(!oldAttemptExpired)
        #expect(tracker.isCurrent(secondAttempt))
        #expect(
            tracker.activeDeadline?.deviceID == secondDeviceID
        )
    }

    @Test
    func policyNormalizesInvalidDurationsWithoutChangingDefaults() {
        let defaults = BLEConnectionTimeoutPolicy.iosDefault
        #expect(defaults.timeout(for: .connect) == 12)
        #expect(defaults.timeout(for: .serviceDiscovery) == 8)
        #expect(
            defaults.timeout(for: .characteristicDiscovery) == 8
        )
        #expect(
            defaults.timeout(for: .notificationSubscription) == 8
        )

        let invalid = BLEConnectionTimeoutPolicy(
            connectSeconds: 0,
            serviceDiscoverySeconds: -1,
            characteristicDiscoverySeconds: 0.05,
            notificationSubscriptionSeconds: 0.1
        )
        #expect(invalid.timeout(for: .connect) == 0.1)
        #expect(invalid.timeout(for: .serviceDiscovery) == 0.1)
        #expect(
            invalid.timeout(for: .characteristicDiscovery) == 0.1
        )
        #expect(
            invalid.timeout(for: .notificationSubscription) == 0.1
        )
    }
}
