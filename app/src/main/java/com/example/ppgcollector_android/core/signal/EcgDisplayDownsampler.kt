package com.example.ppgcollector_android.core.signal

/**
 * Display-only 500 Hz -> 100 Hz boxcar downsampler.
 *
 * The source UInt values are neither signed-converted nor written back. Keeping
 * the accumulator across calls makes the output independent of BLE chunking.
 */
class EcgDisplayDownsampler(
    private val sourceSamplesPerDisplaySample: Int = 5,
) {
    init {
        require(sourceSamplesPerDisplaySample > 0)
    }

    private var sum = 0.0
    private var count = 0

    fun ingest(samples: List<UInt>): DoubleArray {
        if (samples.isEmpty()) return doubleArrayOf()
        val output = ArrayList<Double>((samples.size + count) / sourceSamplesPerDisplaySample)
        samples.forEach { sample ->
            sum += sample.toDouble()
            count++
            if (count == sourceSamplesPerDisplaySample) {
                output += sum / sourceSamplesPerDisplaySample.toDouble()
                sum = 0.0
                count = 0
            }
        }
        return output.toDoubleArray()
    }

    fun reset() {
        sum = 0.0
        count = 0
    }
}
