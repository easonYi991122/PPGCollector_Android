package com.example.ppgcollector_android.core.protocol

/** A decoded frame plus the sequence gate decision that controls sample acceptance. */
data class CupDecodedFrameEvent(
    val frame: CupBatchFrame,
    val sequenceEvent: CupSequenceEvent,
    val isAccepted: Boolean,
)
