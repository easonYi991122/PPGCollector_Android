import Foundation
import Testing
@testable import PPGCollector

struct CUPBatchProtocolTests {
    @Test
    func committedGoldenResourcesMatchMetadataAndDecoder() throws {
        let fixture = try loadProtocolGoldenFixture()
        #expect(
            fixture.wire.count
                == CUPBatchProtocolV1.frameLength
        )
        #expect(
            fixture.metadata.protocolIdentifier
                == "cup_batch_protocol_v1"
        )
        #expect(
            fixture.metadata.sampleRateHz
                == CUPBatchProtocolV1.sampleRateHz
        )
        #expect(
            fixture.metadata.samplesPerFrame
                == CUPBatchProtocolV1.samplesPerFrame
        )
        #expect(
            fixture.metadata.dataLength
                == CUPBatchProtocolV1.dataLength
        )
        #expect(
            fixture.metadata.frameLength
                == CUPBatchProtocolV1.frameLength
        )
        #expect(
            fixture.metadata.frameHex
                == fixture.wire.map {
                    String(format: "%02x", $0)
                }.joined()
        )

        var decoder = CUPBatchStreamDecoder()
        let frames = decoder.feed(fixture.wire)
        #expect(frames.count == 1)
        #expect(
            frames.first?.sequence
                == fixture.metadata.sequence
        )
        #expect(
            frames.first?.samples
                == fixture.metadata.samples.map {
                    CUPPPGSample(red: $0.red, ir: $0.ir)
                }
        )
        #expect(decoder.stats.frames == 1)
        #expect(decoder.pendingByteCount == 0)
        #expect(
            makeCUPTestFrame(
                sequence: fixture.metadata.sequence
            ) == fixture.wire
        )
    }

    @Test
    func generatedTestFrameUsesFrozenLayout() {
        let wire = makeCUPTestFrame(sequence: 42)
        #expect(wire.count == CUPBatchProtocolV1.frameLength)
        #expect(Array(wire.prefix(6)) == [0xAB, 0xBA, 0x15, 0x91, 0x01, 0x2A])
        #expect(Array(wire.suffix(2)) == [0xCD, 0xDC])

        var decoder = CUPBatchStreamDecoder()
        let frames = decoder.feed(wire)
        #expect(frames.count == 1)
        #expect(frames.first?.sequence == 42)
        #expect(frames.first?.samples.first == CUPPPGSample(red: 100_000, ir: 120_000))
        #expect(frames.first?.samples.last == CUPPPGSample(red: 100_833, ir: 121_127))
        #expect(decoder.stats.frames == 1)
        #expect(decoder.pendingByteCount == 0)
    }

    @Test
    func allFragmentSizesProduceTheSameFrames() {
        var wire = makeCUPTestFrame(sequence: 1)
        wire.append(makeCUPTestFrame(sequence: 2))

        for chunkSize in 1...CUPBatchProtocolV1.frameLength {
            var decoder = CUPBatchStreamDecoder()
            var decoded: [CUPBatchFrame] = []
            var offset = 0

            while offset < wire.count {
                let end = min(offset + chunkSize, wire.count)
                decoded.append(contentsOf: decoder.feed(wire.subdata(in: offset..<end)))
                offset = end
            }

            #expect(decoded.map(\.sequence) == [1, 2])
            #expect(decoder.stats.frames == 2)
            #expect(decoder.pendingByteCount == 0)
        }
    }

    @Test
    func noiseAndInvalidTailResynchronize() {
        var broken = makeCUPTestFrame(sequence: 3)
        broken[broken.count - 1] ^= 0xFF

        var stream = Data("noise".utf8)
        stream.append(broken)
        stream.append(makeCUPTestFrame(sequence: 4))

        var decoder = CUPBatchStreamDecoder()
        let frames = decoder.feed(stream)

        #expect(frames.map(\.sequence) == [4])
        #expect(decoder.stats.invalidTail >= 1)
        #expect(decoder.stats.bytesDiscarded >= 5)
        #expect(decoder.pendingByteCount == 0)
    }

    @Test
    func partialHeaderIsRetainedAcrossChunks() {
        let frame = makeCUPTestFrame(sequence: 7)
        var decoder = CUPBatchStreamDecoder()

        let first = decoder.feed(Data([0x00, 0xAB]))
        #expect(first.isEmpty)
        #expect(decoder.pendingByteCount == 1)

        var remainder = Data([0xBA])
        remainder.append(frame.dropFirst(2))
        let frames = decoder.feed(remainder)

        #expect(frames.map(\.sequence) == [7])
        #expect(decoder.stats.bytesDiscarded == 1)
    }

    @Test
    func resetClearsBufferAndStats() {
        var decoder = CUPBatchStreamDecoder()
        _ = decoder.feed(Data([0x01, 0x02, 0x03]))
        #expect(decoder.stats.bytesDiscarded == 3)

        decoder.reset()
        #expect(decoder.stats == CUPDecoderStats())
        #expect(decoder.pendingByteCount == 0)
    }
}

private final class ProtocolFixtureBundleAnchor {}

private struct ProtocolGoldenMetadata: Decodable {
    struct Sample: Decodable {
        let red: UInt32
        let ir: UInt32
    }

    let protocolIdentifier: String
    let sampleRateHz: Int
    let samplesPerFrame: Int
    let dataLength: Int
    let frameLength: Int
    let sequence: UInt8
    let samples: [Sample]
    let frameHex: String

    enum CodingKeys: String, CodingKey {
        case protocolIdentifier = "protocol"
        case sampleRateHz = "sample_rate_hz"
        case samplesPerFrame = "samples_per_frame"
        case dataLength = "data_length"
        case frameLength = "frame_length"
        case sequence
        case samples
        case frameHex = "frame_hex"
    }
}

private func loadProtocolGoldenFixture() throws -> (
    wire: Data,
    metadata: ProtocolGoldenMetadata
) {
    let bundle = Bundle(for: ProtocolFixtureBundleAnchor.self)
    let binaryURL = try #require(
        bundle.url(
            forResource: "golden_seq42",
            withExtension: "bin"
        )
    )
    let metadataURL = try #require(
        bundle.url(
            forResource: "golden_seq42",
            withExtension: "json"
        )
    )
    return (
        wire: try Data(contentsOf: binaryURL),
        metadata: try JSONDecoder().decode(
            ProtocolGoldenMetadata.self,
            from: Data(contentsOf: metadataURL)
        )
    )
}
