package com.example.ppgcollector_android.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class FixedLagPpgFilterRuntimeTest {
    @Test
    fun outputHasExplicitOneSecondSourceLagAndBoundedState() {
        val runtime = FixedLagPpgFilterRuntime()
        val outputs = (0L until 1_200L).flatMap { index ->
            runtime.ingest(index, 1_000.0 + sin(index / 12.0), 2_000.0 + sin(index / 15.0))
        }
        assertTrue(outputs.isNotEmpty())
        assertEquals(100L, outputs.first().sourceSampleIndex)
        assertEquals(1_099L, outputs.last().sourceSampleIndex)
        assertTrue(outputs.all { it.red.isFinite() && it.ir.isFinite() })
        assertTrue(runtime.latencySamples == 100)
    }

    @Test
    fun discontinuityDoesNotFilterAcrossTheGap() {
        val runtime = FixedLagPpgFilterRuntime()
        repeat(400) { runtime.ingest(it.toLong(), 1_000.0, 2_000.0) }
        runtime.ingest(1_000L, 1_000.0, 2_000.0)
        val afterGap = (1_001L until 1_300L).flatMap {
            runtime.ingest(it, 1_000.0, 2_000.0)
        }
        assertTrue(afterGap.isNotEmpty())
        assertEquals(1_100L, afterGap.first().sourceSampleIndex)
    }
}

