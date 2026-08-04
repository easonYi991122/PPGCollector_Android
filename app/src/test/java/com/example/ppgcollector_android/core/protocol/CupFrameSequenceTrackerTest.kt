package com.example.ppgcollector_android.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CupFrameSequenceTrackerTest {
    @Test
    fun tracksContinuousWrapGapDuplicateAndOutOfOrder() {
        val tracker = CupFrameSequenceTracker()

        assertEquals(CupSequenceEvent.First, tracker.observe(254u))
        assertEquals(CupSequenceEvent.Continuous, tracker.observe(255u))
        assertEquals(CupSequenceEvent.Continuous, tracker.observe(0u))
        assertEquals(CupSequenceEvent.Gap(1), tracker.observe(2u))
        assertEquals(CupSequenceEvent.Duplicate, tracker.observe(2u))
        assertEquals(CupSequenceEvent.OutOfOrder, tracker.observe(1u))

        assertEquals(6, tracker.stats.receivedFrames)
        assertEquals(2u.toUByte(), tracker.stats.previous)
        assertEquals(1, tracker.stats.missingFrames)
        assertEquals(20, tracker.stats.missingSamples)
        assertEquals(1, tracker.stats.duplicateFrames)
        assertEquals(1, tracker.stats.outOfOrderFrames)
    }

    @Test
    fun outOfOrderDoesNotAdvancePreviousAndResetStartsNewSequence() {
        val tracker = CupFrameSequenceTracker()

        tracker.observe(10u)
        tracker.observe(15u)
        assertEquals(4, tracker.stats.missingFrames)
        tracker.observe(14u)
        assertEquals(15u.toUByte(), tracker.stats.previous)

        tracker.reset()
        assertEquals(CupSequenceStats(), tracker.stats)
        assertEquals(CupSequenceEvent.First, tracker.observe(200u))
    }

    @Test
    fun missingSampleCountUsesTheObservedWireLayout() {
        val tracker = CupFrameSequenceTracker()

        tracker.observe(1u, CupBatchProtocolV1.legacySamplesPerFrame)
        assertEquals(
            CupSequenceEvent.Gap(1),
            tracker.observe(3u, CupBatchProtocolV1.legacySamplesPerFrame),
        )

        assertEquals(1, tracker.stats.missingFrames)
        assertEquals(CupBatchProtocolV1.legacySamplesPerFrame, tracker.stats.missingSamples)
    }
}
