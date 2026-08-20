#include "CUPBatchProtocol.h"

namespace {

void writeU16Le(uint8_t *out, uint16_t value) {
  out[0] = static_cast<uint8_t>(value);
  out[1] = static_cast<uint8_t>(value >> 8);
}

void writeU32Le(uint8_t *out, uint32_t value) {
  out[0] = static_cast<uint8_t>(value);
  out[1] = static_cast<uint8_t>(value >> 8);
  out[2] = static_cast<uint8_t>(value >> 16);
  out[3] = static_cast<uint8_t>(value >> 24);
}

}  // namespace

namespace CUPBatchProtocol {

size_t encodeFrame(uint8_t *out,
                   size_t capacity,
                   uint8_t sequence,
                   const Sample *samples,
                   size_t sampleCount) {
  if (out == NULL || samples == NULL || capacity < FrameLength ||
      sampleCount != SamplesPerFrame) {
    return 0U;
  }

  out[0] = Header0;
  out[1] = Header1;
  out[2] = FunctionBatch;
  writeU16Le(&out[3], DataLength);
  out[5] = sequence;

  size_t offset = 6U;
  for (size_t index = 0U; index < SamplesPerFrame; ++index) {
    writeU32Le(&out[offset], samples[index].red);
    writeU32Le(&out[offset + 4U], samples[index].ir);
    offset += SampleWireLength;
  }
  out[offset++] = Tail0;
  out[offset++] = Tail1;
  return offset;
}

}  // namespace CUPBatchProtocol

