import Foundation
import Testing
@testable import PPGCollector

struct LiveMetricModelsTests {
    @Test
    func disconnectedMetricsAreTypedUnavailableValues() {
        let snapshot = LiveMetricSnapshot.unavailable(
            hasConnectedDevice: false,
            freshness: .unavailable
        )

        #expect(!snapshot.heartRateBPM.isValid)
        #expect(snapshot.heartRateBPM.value == nil)
        #expect(
            snapshot.heartRateBPM.unavailableReason == .noDevice
        )
        #expect(
            snapshot.oxygenSaturationPercent.unavailableReason
                == .noDevice
        )
        #expect(snapshot.signalQuality.unavailableReason == .noDevice)
        #expect(snapshot.bloodPressure.unavailableReason == .noDevice)
    }

    @Test
    func freshStreamStillExposesEachExternalBlocker() {
        let snapshot = LiveMetricSnapshot.unavailable(
            hasConnectedDevice: true,
            freshness: .fresh
        )

        #expect(
            snapshot.heartRateBPM.unavailableReason
                == .algorithmUnavailable
        )
        #expect(
            snapshot.oxygenSaturationPercent.unavailableReason
                == .calibrationUnavailable
        )
        #expect(
            snapshot.signalQuality.unavailableReason
                == .referenceParityPending
        )
        #expect(
            snapshot.bloodPressure.unavailableReason
                == .modelUnavailable
        )
    }

    @Test
    func waitingAndStaleDataDoNotLeakPlaceholderValues() {
        let waiting = LiveMetricSnapshot.unavailable(
            hasConnectedDevice: true,
            freshness: .waiting
        )
        let stale = LiveMetricSnapshot.unavailable(
            hasConnectedDevice: true,
            freshness: .stale
        )

        #expect(waiting.signalQuality.value == nil)
        #expect(
            waiting.signalQuality.unavailableReason
                == .waitingForData
        )
        #expect(stale.heartRateBPM.value == nil)
        #expect(
            stale.heartRateBPM.unavailableReason == .staleData
        )
    }

    @Test
    func validFactoryCarriesValueVersionAndTimestamp() {
        let measuredAt = Date(timeIntervalSince1970: 1_800_000_000)
        let result = MetricResult<Double>.valid(
            72,
            measuredAt: measuredAt,
            algorithmVersion: "test-1",
            sourceSampleIndex: 799,
            sourceTimeSeconds: 7.99
        )

        #expect(result.value == 72)
        #expect(result.isValid)
        #expect(!result.isProvisional)
        #expect(result.unavailableReason == nil)
        #expect(result.measuredAt == measuredAt)
        #expect(result.sourceSampleIndex == 799)
        #expect(result.sourceTimeSeconds == 7.99)
        #expect(result.algorithmVersion == "test-1")
    }

    @Test
    func warmingStateAndProvisionalSQIAreExplicit() {
        let warming = LiveMetricSnapshot.warmingUp()
        #expect(
            warming.heartRateBPM.unavailableReason
                == .insufficientData
        )
        #expect(
            warming.signalQuality.unavailableReason
                == .insufficientData
        )

        let runtime = LiveMetricSnapshot.runtime(
            heartRateBPM: .valid(
                72,
                measuredAt: Date(timeIntervalSince1970: 1_800_000_000),
                algorithmVersion: "ppg-ios-hr-0.1"
            ),
            signalQuality: .valid(
                0.812,
                measuredAt: Date(timeIntervalSince1970: 1_800_000_000),
                algorithmVersion: "ppg-ios-sqi-0.1",
                isProvisional: true
            )
        )
        #expect(runtime.heartRateBPM.value == 72)
        #expect(runtime.heartRateBPM.isValid)
        #expect(runtime.signalQuality.isValid)
        #expect(runtime.signalQuality.value == 0.812)
        #expect(runtime.signalQuality.isProvisional)
        #expect(runtime.signalQuality.unavailableReason == nil)
        #expect(
            runtime.oxygenSaturationPercent.unavailableReason
                == .calibrationUnavailable
        )
        #expect(
            runtime.bloodPressure.unavailableReason
                == .modelUnavailable
        )
    }
}
