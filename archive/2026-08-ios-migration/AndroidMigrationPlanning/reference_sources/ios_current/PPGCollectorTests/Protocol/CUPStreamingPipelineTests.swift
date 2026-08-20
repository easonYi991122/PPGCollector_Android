import Foundation
import Testing
@testable import PPGCollector

struct CUPStreamingPipelineTests {
    @Test
    func fragmentedNotificationsDecodeAndPreviewRemainsBounded() {
        var wire = makeCUPTestFrame(sequence: 254)
        wire.append(makeCUPTestFrame(sequence: 255))
        var pipeline = CUPStreamingPipeline(recentSampleCapacity: 60)
        var offset = 0

        while offset < wire.count {
            let end = min(offset + 17, wire.count)
            pipeline.receive(wire.subdata(in: offset..<end))
            offset = end
        }

        let diagnostics = pipeline.diagnostics
        #expect(diagnostics.receivedBytes == wire.count)
        #expect(diagnostics.decodedFrames == 2)
        #expect(diagnostics.decodedSamples == 100)
        #expect(diagnostics.acceptedSamples == 100)
        #expect(diagnostics.pendingDecoderBytes == 0)
        #expect(diagnostics.recentSamples.count == 60)
        #expect(diagnostics.capturedWirePrefix == wire.prefix(512))
        #expect(diagnostics.lastChunk.count <= 17)
        #expect(diagnostics.sequenceStats.previous == 255)
        #expect(diagnostics.sequenceStats.missingFrames == 0)
        #expect(diagnostics.lastSequenceEvent == .continuous)
    }

    @Test
    func duplicatesAndOutOfOrderFramesAreDiagnosedButNotAccepted() {
        var pipeline = CUPStreamingPipeline()

        let first = pipeline.receive(makeCUPTestFrame(sequence: 10))
        let duplicate = pipeline.receive(makeCUPTestFrame(sequence: 10))
        let outOfOrder = pipeline.receive(makeCUPTestFrame(sequence: 9))
        let gap = pipeline.receive(makeCUPTestFrame(sequence: 12))

        let diagnostics = pipeline.diagnostics
        #expect(first.first?.isAccepted == true)
        #expect(duplicate.first?.isAccepted == false)
        #expect(outOfOrder.first?.isAccepted == false)
        #expect(gap.first?.sequenceEvent == .gap(missingFrames: 1))
        #expect(diagnostics.decodedFrames == 4)
        #expect(diagnostics.decodedSamples == 200)
        #expect(diagnostics.acceptedSamples == 100)
        #expect(diagnostics.recentSamples.count == 100)
        #expect(diagnostics.sequenceStats.duplicateFrames == 1)
        #expect(diagnostics.sequenceStats.outOfOrderFrames == 1)
        #expect(diagnostics.sequenceStats.missingFrames == 1)
        #expect(diagnostics.sequenceStats.missingSamples == 50)
        #expect(diagnostics.lastSequenceEvent == .gap(missingFrames: 1))
    }

    @Test
    func notificationErrorAndResetClearThePipeline() {
        var pipeline = CUPStreamingPipeline()
        pipeline.recordNotificationError()
        pipeline.receive(makeCUPTestFrame(sequence: 1))

        #expect(pipeline.diagnostics.notificationErrors == 1)
        #expect(pipeline.diagnostics.decodedFrames == 1)

        pipeline.reset()
        #expect(pipeline.diagnostics == CUPStreamingDiagnostics())
    }
}
