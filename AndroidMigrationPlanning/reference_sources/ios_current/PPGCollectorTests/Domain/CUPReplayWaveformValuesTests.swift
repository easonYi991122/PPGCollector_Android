import Testing
@testable import PPGCollector

struct CUPReplayWaveformValuesTests {
    @Test
    func rawValuesPreserveEveryRecordedChannelValue() {
        let samples = [
            CUPPPGSample(red: 1, ir: 11),
            CUPPPGSample(red: 2, ir: 12),
            CUPPPGSample(red: 4_294_967_295, ir: 13),
        ]

        let values = CUPReplayWaveformValues.raw(samples: samples)

        #expect(values.red == [1, 2, 4_294_967_295])
        #expect(values.ir == [11, 12, 13])
    }

    @Test
    func preprocessedValuesMatchACompleteCausalChannelPass() {
        var samples: [CUPPPGSample] = []
        for index in 0..<200 {
            let red = UInt32(700_000 + (index * 97) % 1_000)
            let ir = UInt32(900_000 + (index * 53) % 1_500)
            samples.append(CUPPPGSample(red: red, ir: ir))
        }

        let values = CUPReplayWaveformValues.causallyPreprocessed(
            samples: samples
        )
        var expectedRedPreprocessor = PPGPreprocessor()
        var expectedIRPreprocessor = PPGPreprocessor()
        let expectedRed = samples.map {
            expectedRedPreprocessor.process(raw: Double($0.red)).sample!
                .bandpassed
        }
        let expectedIR = samples.map {
            expectedIRPreprocessor.process(raw: Double($0.ir)).sample!
                .bandpassed
        }

        #expect(values.red == expectedRed)
        #expect(values.ir == expectedIR)
        #expect(values.red.count == samples.count)
        #expect(values.ir.count == samples.count)
    }
}
