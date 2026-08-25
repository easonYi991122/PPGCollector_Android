package com.example.ppgcollector_android.core.signal.combo

import com.example.ppgcollector_android.core.signal.SciPyPeakDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

internal data class ComboOverpressureSnapshot(
    val initSteep: Double = 0.0,
    val lateRebound: Double = 0.0,
    val midMinVel: Double = 0.0,
    val slowFrac: Double = 0.0,
    val p2Height: Double = 1.0,
    val nBeats: Int = 0,
)

internal object ComboOverpressure {
    fun detect(rawIr: DoubleArray, sampleRateHz: Double = 100.0): ComboOverpressureSnapshot? =
        runCatching {
            val filtered = ComboSqiFilters.overpressureInput(rawIr)
            val fiducials = detectFiducials(filtered, sampleRateHz)
            computeRapidDecline(filtered, fiducials, sampleRateHz)
        }.getOrNull()

    internal fun detectFiducials(signal: DoubleArray, fs: Double): ComboFiducials {
        val polarityUp = abs(signal.minOrNull() ?: 0.0) <= (signal.maxOrNull() ?: 0.0)
        var result = findPeaksMethod3(signal, fs)
        if (result.p1.isEmpty() && signal.size >= 10 && polarityUp) {
            result = fallbackFiducials(signal, fs) ?: result
        }
        return result
    }

    internal fun computeRapidDecline(
        sig: DoubleArray,
        fid: ComboFiducials,
        fs: Double,
        dicroticLoMs: Int = 120,
        dicroticHiMs: Int = 350,
        winSamples: Int = 5,
        initSteepMs: Int = 80,
        slowFracAlpha: Double = 0.3,
    ): ComboOverpressureSnapshot {
        val p1 = fid.p1
        if (p1.isEmpty()) return ComboOverpressureSnapshot()
        val first = p1.first()
        val polarityRef = sig[max(0, first - 80)]
        val sign = if (sig[first] >= polarityRef) 1.0 else -1.0
        val s = DoubleArray(sig.size) { sign * sig[it] }
        val deriv = DoubleArray(s.size)
        for (index in 1 until s.size) deriv[index] = s[index] - s[index - 1]
        val lo = max(1, (dicroticLoMs / 1000.0 * fs).toInt())
        val hi = max(lo + winSamples + 1, (dicroticHiMs / 1000.0 * fs).toInt())
        val nInit = max(2, (initSteepMs / 1000.0 * fs).toInt())
        val rebLo = max(nInit, 8)
        val tT = fid.t
        val steepList = ArrayList<Double>()
        val rebList = ArrayList<Double>()
        val midVelList = ArrayList<Double>()
        val slowList = ArrayList<Double>()
        val p2List = ArrayList<Double>()
        var nBeats = 0
        p1.forEachIndexed { beatIndex, peak ->
            val nextT = tT.firstOrNull { it > peak } ?: (s.lastIndex)
            val nextP1 = if (beatIndex + 1 < p1.size) p1[beatIndex + 1] else nextT
            if (nextT - peak < hi) return@forEachIndexed
            nBeats += 1
            val foot = tT.lastOrNull { it < peak } ?: max(0, peak - 80)
            val amp = s[peak] - s[foot]
            if (amp < 1e-6) return@forEachIndexed
            val relLen = nextT - peak
            if (relLen <= 0) return@forEachIndexed
            val rel = DoubleArray(relLen) { sample -> (s[peak + sample] - s[peak]) / amp * 100.0 }
            val segAEnd = min(s.size, nextP1)
            if (segAEnd - peak >= 40) {
                val beat = nextP1 - peak
                val p2Hi = minOf(peak + (0.8 * beat).toInt(), peak + (0.6 * fs).toInt(), nextP1)
                val p2Lo = peak + (0.08 * fs).toInt()
                if (p2Hi > p2Lo) {
                    var vmin = s[peak]
                    for (index in peak until segAEnd) vmin = min(vmin, s[index])
                    val ampBeat = s[peak] - vmin
                    if (ampBeat > 1e-6) {
                        val window = s.copyOfRange(p2Lo, min(s.size, p2Hi))
                        val localPeaks = findPeaksLocal(window)
                        if (localPeaks.isEmpty()) {
                            p2List += 0.0
                        } else {
                            val best = localPeaks.maxBy { window[it] }
                            val p2Index = p2Lo + best
                            p2List += max((s[p2Index] - vmin) / ampBeat, 0.0)
                        }
                    }
                }
            }
            val hiEff = min(hi, rel.size - winSamples)
            if (hiEff > lo) {
                var minRate = Double.POSITIVE_INFINITY
                for (index in lo until hiEff) {
                    val rate = -(rel[index + winSamples] - rel[index]) / winSamples
                    if (rate < minRate) minRate = rate
                }
                if (minRate.isFinite()) midVelList += minRate
            }
            val steepEnd = min(peak + nInit, nextT)
            if (steepEnd > peak) {
                var steepMin = deriv[peak]
                for (index in peak until steepEnd) steepMin = min(steepMin, deriv[index])
                val initSteep = -(steepMin) / amp * 100.0
                steepList += initSteep
                if (initSteep > 1e-6) {
                    var steepCount = 0
                    val beatLen = nextT - peak
                    for (index in peak until nextT) {
                        val down = -(deriv[index] / amp * 100.0)
                        if (down > slowFracAlpha * initSteep) steepCount += 1
                    }
                    if (beatLen > 0) slowList += 1.0 - steepCount.toDouble() / beatLen
                }
            }
            val rebEnd = max(rebLo + 1, rel.size - 3)
            if (rebEnd - rebLo > 1) {
                var runMin = rel[rebLo]
                var maxRise = 0.0
                for (index in rebLo until rebEnd) {
                    runMin = min(runMin, rel[index])
                    maxRise = max(maxRise, rel[index] - runMin)
                }
                rebList += maxRise
            }
        }
        return ComboOverpressureSnapshot(
            initSteep = meanOrZero(steepList),
            lateRebound = meanOrZero(rebList),
            midMinVel = meanOrZero(midVelList),
            slowFrac = meanOrZero(slowList),
            p2Height = if (p2List.isEmpty()) 1.0 else median(p2List),
            nBeats = nBeats,
        )
    }

    internal fun pressureSeverity(snapshot: ComboOverpressureSnapshot): Double? {
        if (snapshot.nBeats < ComboSqi.minimumPressureBeats) return null
        val s1 = ((snapshot.initSteep - 2.4) / (6.0 - 2.4)).coerceIn(0.0, 1.0)
        val s2 = ((10.0 - snapshot.lateRebound) / (10.0 - 2.0)).coerceIn(0.0, 1.0)
        val s3 = ((snapshot.midMinVel - (-1.5)) / (0.0 - (-1.5))).coerceIn(0.0, 1.0)
        val s4 = ((snapshot.slowFrac - 0.35) / (0.7 - 0.35)).coerceIn(0.0, 1.0)
        return (s1 + s2 + s3 + s4) / 4.0
    }

    private fun findPeaksMethod3(signal: DoubleArray, fs: Double): ComboFiducials {
        if (signal.size < 10) return ComboFiducials()
        val diff = DoubleArray(signal.size)
        for (index in 1 until signal.size) diff[index] = signal[index] - signal[index - 1]
        val hrDistance = (0.4 * fs).toInt()
        val minDistance = (0.3 * fs).toInt()
        val mean = diff.average()
        val std = populationStd(diff, mean)
        var r1 = comboFindPeaks(diff, height = mean + std, distance = hrDistance)
            .filter { diff[it] > 0 }
        if (r1.size > 1) {
            val kept = ArrayList<Int>()
            r1.forEach { candidate ->
                if (kept.isEmpty() || candidate - kept.last() >= minDistance) kept += candidate
            }
            r1 = kept
        }
        if (r1.isEmpty()) return ComboFiducials()

        val tPoints = ArrayList<Int>()
        val p1Points = ArrayList<Int>()
        r1.forEach { r1Index ->
            val beforeSigns = IntArray(r1Index) { sign(diff[it]).toInt() }
            val zeroBefore = zeroCrossings(beforeSigns)
            for (zc in zeroBefore.reversed()) {
                if (zc < r1Index - 1 && diff[zc + 1] > 0 && zc != 0) {
                    tPoints += zc + 1
                    break
                }
            }
            val after = diff.copyOfRange(r1Index, diff.size)
            val afterSigns = IntArray(after.size) { sign(after[it]).toInt() }
            val zeroAfter = zeroCrossings(afterSigns)
            var foundP1 = false
            for (zc in zeroAfter) {
                if (zc > 0 && diff[r1Index + zc - 1] > 0) {
                    p1Points += r1Index + zc
                    foundP1 = true
                    break
                }
            }
            if (!foundP1) {
                if (after.isNotEmpty()) {
                    p1Points += r1Index + after.indices.maxBy { after[it] }
                } else {
                    p1Points += r1Index + (0.1 * fs).toInt()
                }
            }
        }
        return ComboFiducials(r1 = r1, t = tPoints, p1 = p1Points)
    }

    private fun fallbackFiducials(signal: DoubleArray, fs: Double): ComboFiducials? {
        val diff = DoubleArray(signal.size)
        for (index in 1 until signal.size) diff[index] = signal[index] - signal[index - 1]
        val maxAbs = diff.maxOf { abs(it) }
        if (maxAbs <= 0.0) return null
        val thr = 0.25 * maxAbs
        val positiveCount = diff.count { it > 0 }
        if (thr <= 0.0 || positiveCount <= 2) return null
        var r1 = comboFindPeaks(diff, height = thr, distance = (0.4 * fs).toInt())
            .filter { diff[it] > 0 }
        if (r1.size > 1) {
            val kept = ArrayList<Int>()
            r1.forEach { candidate ->
                if (kept.isEmpty() || candidate - kept.last() >= (0.3 * fs).toInt()) {
                    kept += candidate
                }
            }
            r1 = kept
        }
        if (r1.isEmpty()) return null
        val tPoints = ArrayList<Int>()
        val p1Points = ArrayList<Int>()
        r1.forEach { r1Index ->
            val beforeSigns = IntArray(r1Index) { sign(diff[it]).toInt() }
            val zeroBefore = zeroCrossings(beforeSigns)
            for (zc in zeroBefore.reversed()) {
                if (zc < r1Index - 1 && diff[zc + 1] > 0 && zc != 0) {
                    tPoints += zc + 1
                    break
                }
            }
            val after = diff.copyOfRange(r1Index, diff.size)
            val zeroAfter = zeroCrossings(IntArray(after.size) { sign(after[it]).toInt() })
            var foundP1 = false
            for (zc in zeroAfter) {
                if (zc > 0 && diff[r1Index + zc - 1] > 0) {
                    p1Points += r1Index + zc
                    foundP1 = true
                    break
                }
            }
            if (!foundP1 && after.isNotEmpty()) {
                p1Points += r1Index + after.indices.maxBy { after[it] }
            }
        }
        return ComboFiducials(r1 = r1, t = tPoints, p1 = p1Points)
    }

    private fun comboFindPeaks(
        values: DoubleArray,
        height: Double? = null,
        distance: Int = 1,
    ): List<Int> = SciPyPeakDetector.findPeaks(
        values = values.toList(),
        distance = max(1, distance),
        minimumHeight = height,
        minimumProminence = 0.0,
    ).indices

    /** Strict local maxima: greater than left neighbour, >= right neighbour. */
    private fun findPeaksLocal(values: DoubleArray): IntArray {
        if (values.size < 3) return intArrayOf()
        val peaks = ArrayList<Int>()
        for (index in 1 until values.lastIndex) {
            if (values[index] > values[index - 1] && values[index] >= values[index + 1]) {
                peaks += index
            }
        }
        return peaks.toIntArray()
    }

    private fun zeroCrossings(signs: IntArray): IntArray {
        if (signs.size < 2) return intArrayOf()
        val crossings = ArrayList<Int>()
        for (index in 0 until signs.lastIndex) {
            if (signs[index + 1] - signs[index] != 0) crossings += index
        }
        return crossings.toIntArray()
    }

    private fun populationStd(values: DoubleArray, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun meanOrZero(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.average()

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            0.5 * (sorted[middle - 1] + sorted[middle])
        } else {
            sorted[middle]
        }
    }
}

internal data class ComboFiducials(
    val r1: List<Int> = emptyList(),
    val t: List<Int> = emptyList(),
    val p1: List<Int> = emptyList(),
)
