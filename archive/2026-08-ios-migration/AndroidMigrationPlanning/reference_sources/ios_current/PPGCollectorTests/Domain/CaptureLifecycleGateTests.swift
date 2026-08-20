import Testing
@testable import PPGCollector

struct CaptureLifecycleGateTests {
    @Test
    func firstPreflightStopReasonWinsUntilReset() {
        var gate = CaptureLifecycleGate()

        let acceptedViewExit = gate.requestPreflightStop(.viewExit)
        let acceptedBackground = gate.requestPreflightStop(
            .sceneBackground
        )
        #expect(acceptedViewExit)
        #expect(!acceptedBackground)
        #expect(gate.preflightStopReason == .viewExit)

        gate.reset()
        #expect(gate.preflightStopReason == nil)
        let acceptedDisconnect = gate.requestPreflightStop(
            .deviceDisconnect
        )
        #expect(acceptedDisconnect)
        #expect(gate.preflightStopReason == .deviceDisconnect)
    }

    @Test
    func onlyOneFinalizationCanBeginForASession() {
        var gate = CaptureLifecycleGate()

        let acceptedUser = gate.beginFinalization(.user)
        let acceptedDisconnect = gate.beginFinalization(
            .deviceDisconnect
        )
        let acceptedTimeout = gate.beginFinalization(.dataTimeout)
        #expect(acceptedUser)
        #expect(!acceptedDisconnect)
        #expect(!acceptedTimeout)
        #expect(gate.finalizationReason == .user)

        gate.reset()
        #expect(gate.finalizationReason == nil)
        let acceptedBackground = gate.beginFinalization(
            .sceneBackground
        )
        #expect(acceptedBackground)
    }
}
