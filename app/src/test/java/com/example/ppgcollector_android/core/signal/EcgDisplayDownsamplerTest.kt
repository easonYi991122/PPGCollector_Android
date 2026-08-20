package com.example.ppgcollector_android.core.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgDisplayDownsamplerTest {
    @Test
    fun averagesFiveSamplesAcrossArbitraryBleChunkBoundaries() {
        val runtime = EcgDisplayDownsampler()
        assertArrayEquals(doubleArrayOf(), runtime.ingest(listOf(10u, 20u)), 0.0)
        assertArrayEquals(doubleArrayOf(30.0), runtime.ingest(listOf(30u, 40u, 50u, 60u)), 0.0)
        assertArrayEquals(doubleArrayOf(80.0), runtime.ingest(listOf(70u, 80u, 90u, 100u)), 0.0)
    }

    @Test
    fun displayValueStaysWithinItsFiveRawInputs() {
        val runtime = EcgDisplayDownsampler()
        val source = listOf(100u, 110u, 90u, 105u, 95u)
        val displayed = runtime.ingest(source).single()
        assertTrue(displayed >= source.min().toDouble())
        assertTrue(displayed <= source.max().toDouble())
    }
}
