#ifndef CUP_BATCH_PROTOCOL_H
#define CUP_BATCH_PROTOCOL_H

// Migration-only reference for the wire format shown in the iOS App
// requirements. This file is not part of the current nRF52840 firmware.

#include <stddef.h>
#include <stdint.h>

namespace CUPBatchProtocol {

static const uint8_t Header0 = 0xABU;
static const uint8_t Header1 = 0xBAU;
static const uint8_t FunctionBatch = 0x15U;
static const uint8_t Tail0 = 0xCDU;
static const uint8_t Tail1 = 0xDCU;

static const uint16_t SampleRateHz = 100U;
static const size_t SamplesPerFrame = 50U;
static const size_t SampleWireLength = 8U;
// v1 defines the data area as sequence + 50 interleaved RED/IR pairs.
static const uint16_t DataLength =
    static_cast<uint16_t>(1U + SamplesPerFrame * SampleWireLength);
static const size_t FrameLength = 2U + 1U + 2U + DataLength + 2U;

struct Sample {
  uint32_t red;
  uint32_t ir;
};

size_t encodeFrame(uint8_t *out,
                   size_t capacity,
                   uint8_t sequence,
                   const Sample *samples,
                   size_t sampleCount);

}  // namespace CUPBatchProtocol

#endif

