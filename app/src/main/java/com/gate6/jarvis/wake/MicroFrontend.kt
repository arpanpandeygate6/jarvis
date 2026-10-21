package com.gate6.jarvis.wake

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kotlin port of the TFLite Micro audio frontend (tensorflow/lite/experimental/microfrontend),
 * configured exactly as microWakeWord expects: 16 kHz, 30 ms window, 10 ms step, 40 mel channels
 * (125–7500 Hz), noise reduction, PCAN gain control and log scale. Fixed-point arithmetic is kept
 * bit-for-bit, including the 16-bit kissfft, because the wake word model was trained on these exact
 * features. Verified against the reference C implementation in unit tests.
 *
 * Not thread-safe; one instance per audio stream.
 */
class MicroFrontend {
    // ---- window ----
    private val windowSize = WINDOW_MS * SAMPLE_RATE / 1000 // 480
    private val windowStep = STEP_MS * SAMPLE_RATE / 1000 // 160
    private val coefficients = IntArray(windowSize).also { c ->
        val arg = Math.PI.toFloat() * 2.0f / windowSize.toFloat()
        for (i in c.indices) {
            val v = 0.5f - 0.5f * cos(arg * (i + 0.5f))
            c[i] = floor(v * (1 shl WINDOW_BITS) + 0.5f).toInt()
        }
    }
    private val windowInput = IntArray(windowSize)
    private var inputUsed = 0
    private val windowOutput = IntArray(windowSize)
    private var maxAbsOutput = 0

    // ---- fft ----
    private val fft = Kiss16Fftr(nextPow2(windowSize)) // 512
    private val fftSize = fft.nfft
    private val fftInput = IntArray(fftSize)
    private val fftRe = IntArray(fftSize / 2 + 1)
    private val fftIm = IntArray(fftSize / 2 + 1)

    // ---- filterbank ----
    private val numChannels = NUM_CHANNELS
    private val spectrumSize = fftSize / 2 + 1
    private val chanFreqStarts = IntArray(numChannels + 1)
    private val chanWeightStarts = IntArray(numChannels + 1)
    private val chanWidths = IntArray(numChannels + 1)
    private val weights: IntArray
    private val unweights: IntArray
    private val startIndex: Int
    private var endIndex = 0
    // Channels are padded to blocks of 4 and may read a few bins past the spectrum (zero weights there).
    private val energy = IntArray(fftSize)
    private val work = LongArray(numChannels + 1)
    private val signal = LongArray(numChannels) // uint32 values

    // ---- noise reduction ----
    private val evenSmoothing = (0.025f * (1 shl NR_BITS)).toInt()
    private val oddSmoothing = (0.06f * (1 shl NR_BITS)).toInt()
    private val minSignalRemaining = (0.05f * (1 shl NR_BITS)).toInt()
    private val estimate = LongArray(numChannels) // uint32 values

    // ---- pcan ----
    private val correctionBits = msb32(fftSize.toLong()) - 1 - (FILTERBANK_BITS / 2) // 3
    private val snrShift = PCAN_GAIN_BITS - correctionBits - PCAN_SNR_BITS // 6
    private val gainLut = IntArray(4 * WDF_BITS - 3)

    init {
        // Filterbank setup (FilterbankPopulateState).
        val centerMel = FloatArray(numChannels + 1)
        val melLow = freqToMel(LOWER_HZ)
        val melHi = freqToMel(UPPER_HZ)
        val spacing = (melHi - melLow) / (numChannels + 1).toFloat()
        for (i in centerMel.indices) centerMel[i] = melLow + spacing * (i + 1)

        val hzPerSbin = 0.5f * SAMPLE_RATE / (spectrumSize.toFloat() - 1)
        startIndex = (1.5f + LOWER_HZ / hzPerSbin).toInt()
        val actualStarts = IntArray(numChannels + 1)
        val actualWidths = IntArray(numChannels + 1)
        val indexAlignment = 2 // kFilterbankIndexAlignment (4 bytes) / sizeof(int16_t)
        var chanFreqIndexStart = startIndex
        var weightIndexStart = 0
        var needsZeros = false
        for (chan in 0..numChannels) {
            var freqIndex = chanFreqIndexStart
            while (freqToMel(freqIndex * hzPerSbin) <= centerMel[chan]) freqIndex++
            val width = freqIndex - chanFreqIndexStart
            actualStarts[chan] = chanFreqIndexStart
            actualWidths[chan] = width
            if (width == 0) {
                chanFreqStarts[chan] = 0
                chanWeightStarts[chan] = 0
                chanWidths[chan] = BLOCK
                if (!needsZeros) {
                    needsZeros = true
                    for (j in 0 until chan) chanWeightStarts[j] += BLOCK
                    weightIndexStart += BLOCK
                }
            } else {
                val alignedStart = (chanFreqIndexStart / indexAlignment) * indexAlignment
                val alignedWidth = chanFreqIndexStart - alignedStart + width
                val paddedWidth = ((alignedWidth - 1) / BLOCK + 1) * BLOCK
                chanFreqStarts[chan] = alignedStart
                chanWeightStarts[chan] = weightIndexStart
                chanWidths[chan] = paddedWidth
                weightIndexStart += paddedWidth
            }
            chanFreqIndexStart = freqIndex
        }
        weights = IntArray(weightIndexStart)
        unweights = IntArray(weightIndexStart)
        for (chan in 0..numChannels) {
            var frequency = actualStarts[chan]
            val frequencyOffset = frequency - chanFreqStarts[chan]
            val weightStart = chanWeightStarts[chan]
            val denom = if (chan == 0) melLow else centerMel[chan - 1]
            for (j in 0 until actualWidths[chan]) {
                val w = (centerMel[chan] - freqToMel(frequency * hzPerSbin)) / (centerMel[chan] - denom)
                val idx = weightStart + frequencyOffset + j
                weights[idx] = s16(floor(w * (1 shl FILTERBANK_BITS) + 0.5f).toInt())
                unweights[idx] = s16(floor((1.0f - w) * (1 shl FILTERBANK_BITS) + 0.5f).toInt())
                frequency++
            }
            if (frequency > endIndex) endIndex = frequency
        }
        check(endIndex < spectrumSize) { "Filterbank end_index above spectrum size" }

        // PCAN gain LUT (PcanGainControlPopulateState).
        val inputBits = NR_SMOOTHING_BITS - correctionBits
        gainLut[0] = pcanGain(inputBits, 0)
        gainLut[1] = pcanGain(inputBits, 1)
        for (interval in 2..WDF_BITS) {
            val x0 = 1L shl (interval - 1)
            val x1 = x0 + (x0 shr 1)
            val x2 = if (interval == WDF_BITS) x0 + (x0 - 1) else 2 * x0
            val y0 = pcanGain(inputBits, x0)
            val y1 = pcanGain(inputBits, x1)
            val y2 = pcanGain(inputBits, x2)
            val diff1 = y1 - y0
            val diff2 = y2 - y0
            val a1 = 4 * diff1 - diff2
            val a2 = diff2 - a1
            val base = 4 * interval - 6
            gainLut[base] = y0
            gainLut[base + 1] = s16(a1)
            gainLut[base + 2] = s16(a2)
        }
    }

    /**
     * Feeds samples starting at [offset]; consumes at most enough to complete one window.
     * Returns the number of samples consumed; when a window completed, [output] (40 values,
     * uint16 range) is filled and [lastHadOutput] is true.
     */
    fun process(samples: ShortArray, offset: Int, count: Int, output: IntArray): Int {
        lastHadOutput = false
        val toCopy = minOf(windowSize - inputUsed, count)
        for (i in 0 until toCopy) windowInput[inputUsed + i] = samples[offset + i].toInt()
        inputUsed += toCopy
        if (inputUsed < windowSize) return toCopy

        // Window.
        var maxAbs = 0
        for (i in 0 until windowSize) {
            val v = s16((windowInput[i] * coefficients[i]) shr WINDOW_BITS)
            windowOutput[i] = v
            val a = if (v < 0) s16(-v) else v
            if (a > maxAbs) maxAbs = a
        }
        System.arraycopy(windowInput, windowStep, windowInput, 0, windowSize - windowStep)
        inputUsed -= windowStep
        maxAbsOutput = maxAbs

        // FFT.
        val inputShift = 15 - msb32(maxAbsOutput.toLong())
        for (i in 0 until windowSize) fftInput[i] = s16((windowOutput[i] and 0xFFFF) shl inputShift)
        for (i in windowSize until fftSize) fftInput[i] = 0
        fft.forward(fftInput, fftRe, fftIm)

        // Energy (uint32 written into int32 storage, as in C).
        for (i in startIndex until endIndex) {
            energy[i] = fftRe[i] * fftRe[i] + fftIm[i] * fftIm[i]
        }

        // Accumulate channels (uint64 arithmetic; Long wraps identically).
        var weightAcc = 0L
        var unweightAcc = 0L
        for (i in 0..numChannels) {
            var mag = chanFreqStarts[i]
            var w = chanWeightStarts[i]
            for (j in 0 until chanWidths[i]) {
                val m = energy[mag].toLong() // int32 -> uint64 sign-extends, matching C
                weightAcc += weights[w] * m
                unweightAcc += unweights[w] * m
                mag++
                w++
            }
            work[i] = weightAcc
            weightAcc = unweightAcc
            unweightAcc = 0
        }

        // Sqrt and scale down.
        for (i in 0 until numChannels) signal[i] = (sqrt64(work[i + 1]) ushr inputShift) and U32

        // Noise reduction.
        for (i in 0 until numChannels) {
            val smoothing = if (i and 1 == 0) evenSmoothing.toLong() else oddSmoothing.toLong()
            val oneMinus = (1L shl NR_BITS) - smoothing
            val scaledUp = (signal[i] shl NR_SMOOTHING_BITS) and U32
            var est = ((scaledUp * smoothing + estimate[i] * oneMinus) ushr NR_BITS) and U32
            estimate[i] = est
            if (est > scaledUp) est = scaledUp
            val floorV = ((signal[i] * minSignalRemaining) ushr NR_BITS) and U32
            val subtracted = ((scaledUp - est) and U32) ushr NR_SMOOTHING_BITS
            signal[i] = if (subtracted > floorV) subtracted else floorV
        }

        // PCAN gain control.
        for (i in 0 until numChannels) {
            val gain = wideDynamicFunction(estimate[i]).toLong() and U32 // int16 -> uint32
            val snr = (mulU64Shr(signal[i], gain, snrShift)) and U32
            signal[i] = pcanShrink(snr)
        }

        // Log scale.
        for (i in 0 until numChannels) {
            var value = signal[i]
            value = if (correctionBits < 0) value ushr -correctionBits else (value shl correctionBits) and U32
            value = if (value > 1) log(value, LOG_SCALE_SHIFT) else 0
            output[i] = if (value < 0xFFFF) value.toInt() else 0xFFFF
        }
        lastHadOutput = true
        return toCopy
    }

    /** True when the last [process] call produced a feature frame. */
    var lastHadOutput = false
        private set

    fun reset() {
        windowInput.fill(0)
        windowOutput.fill(0)
        inputUsed = 0
        maxAbsOutput = 0
        work.fill(0)
        estimate.fill(0)
    }

    private fun wideDynamicFunction(x: Long): Int {
        if (x <= 2) return gainLut[x.toInt()]
        val interval = msb32(x)
        val base = 4 * interval - 6
        val frac = (if (interval < 11) (x shl (11 - interval)) else (x ushr (interval - 11))).toInt() and 0x3FF
        var result = (gainLut[base + 2] * frac) shr 5
        result += gainLut[base + 1] shl 5
        result *= frac
        result = (result + (1 shl 14)) shr 15
        result += gainLut[base]
        return s16(result)
    }

    private fun pcanShrink(x: Long): Long =
        if (x < (2L shl PCAN_SNR_BITS)) ((x * x) and U32) ushr (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS)
        else ((x ushr (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1L shl PCAN_OUTPUT_BITS)) and U32

    private fun pcanGain(inputBits: Int, x: Long): Int {
        val xf = x.toFloat() / (1L shl inputBits).toFloat()
        val gain = (1L shl PCAN_GAIN_BITS).toFloat() * (xf + PCAN_OFFSET).toDouble().pow(-PCAN_STRENGTH.toDouble()).toFloat()
        if (gain > 0x7FFF) return 0x7FFF
        return s16((gain + 0.5f).toInt())
    }

    private fun log(x: Long, scaleShift: Int): Long {
        val integer = msb32(x) - 1
        val fraction = log2FractionPart(x, integer)
        val log2 = ((integer.toLong() shl LOG_SCALE_LOG2) + fraction) and U32
        val round = (LOG_SCALE / 2).toLong()
        val loge = ((LOG_COEFF * log2 + round) ushr LOG_SCALE_LOG2) and U32
        return ((((loge shl scaleShift) and U32) + round) and U32) ushr LOG_SCALE_LOG2
    }

    private fun log2FractionPart(x: Long, log2x: Int): Long {
        var frac = (x - (1L shl log2x)).toInt()
        frac = if (log2x < LOG_SCALE_LOG2) frac shl (LOG_SCALE_LOG2 - log2x) else frac shr (log2x - LOG_SCALE_LOG2)
        val baseSeg = frac ushr (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2)
        val segUnit = (1 shl LOG_SCALE_LOG2) shr LOG_SEGMENTS_LOG2
        val c0 = LOG_LUT[baseSeg]
        val c1 = LOG_LUT[baseSeg + 1]
        val segBase = segUnit * baseSeg
        val relPos = ((c1 - c0) * (frac - segBase)) shr LOG_SCALE_LOG2
        return (frac + c0 + relPos).toLong() and U32
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val NUM_CHANNELS = 40
        const val STEP_MS = 10
        const val SAMPLES_PER_STEP = SAMPLE_RATE * STEP_MS / 1000
        private const val WINDOW_MS = 30
        private const val WINDOW_BITS = 12
        private const val FILTERBANK_BITS = 12
        private const val BLOCK = 4
        private const val LOWER_HZ = 125.0f
        private const val UPPER_HZ = 7500.0f
        private const val NR_BITS = 14
        private const val NR_SMOOTHING_BITS = 10
        private const val PCAN_SNR_BITS = 12
        private const val PCAN_OUTPUT_BITS = 6
        private const val PCAN_GAIN_BITS = 21
        private const val PCAN_STRENGTH = 0.95f
        private const val PCAN_OFFSET = 80.0f
        private const val WDF_BITS = 32
        private const val LOG_SCALE_SHIFT = 6
        private const val LOG_SCALE = 65536
        private const val LOG_SCALE_LOG2 = 16
        private const val LOG_SEGMENTS_LOG2 = 7
        private const val LOG_COEFF = 45426L
        private const val U32 = 0xFFFFFFFFL

        private val LOG_LUT = intArrayOf(
            0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163,
            2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
            3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
            5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
            5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
            5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
            5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
            4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
            3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
            2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
            1094, 963, 830, 695, 559, 421, 282, 142, 0, 0,
        )

        internal fun s16(x: Int): Int = x.toShort().toInt()

        /** Number of significant bits of an unsigned 32-bit value (0 for 0). */
        internal fun msb32(n: Long): Int = 32 - java.lang.Integer.numberOfLeadingZeros(n.toInt())

        private fun nextPow2(n: Int): Int {
            var s = 1
            while (s < n) s = s shl 1
            return s
        }

        private fun freqToMel(freq: Float): Float = 1127.0f * ln1p((freq / 700.0f).toDouble()).toFloat()

        /** (a * b) >> shift for uint32 a, b without overflowing 64 bits. */
        private fun mulU64Shr(a: Long, b: Long, shift: Int): Long = (a * b) ushr shift

        private fun sqrt32(numIn: Long): Long {
            var num = numIn
            if (num == 0L) return 0
            var res = 0L
            var maxBit = 32 - msb32(num)
            maxBit = maxBit or 1
            var bit = 1L shl (31 - maxBit)
            var iterations = (31 - maxBit) / 2 + 1
            while (iterations-- > 0) {
                if (num >= res + bit) {
                    num -= res + bit
                    res = (res ushr 1) + bit
                } else {
                    res = res ushr 1
                }
                bit = bit ushr 2
            }
            if (num > res && res != 0xFFFFL) res++
            return res and 0xFFFF // returns uint16
        }

        internal fun sqrt64(numIn: Long): Long {
            if ((numIn ushr 32) == 0L) return sqrt32(numIn)
            var num = numIn
            var res = 0L
            var maxBit = 64 - (64 - java.lang.Long.numberOfLeadingZeros(num))
            maxBit = maxBit or 1
            var bit = 1L shl (63 - maxBit)
            var iterations = (63 - maxBit) / 2 + 1
            while (iterations-- > 0) {
                // Unsigned compare: num >= res + bit
                if (java.lang.Long.compareUnsigned(num, res + bit) >= 0) {
                    num -= res + bit
                    res = (res ushr 1) + bit
                } else {
                    res = res ushr 1
                }
                bit = bit ushr 2
            }
            if (java.lang.Long.compareUnsigned(num, res) > 0 && res != 0xFFFFFFFFL) res++
            return res and U32
        }
    }
}

/** kissfft real FFT in 16-bit fixed point (FIXED_POINT=16), forward only. Values are int16 held in Ints. */
internal class Kiss16Fftr(val nfft: Int) {
    private val ncfft = nfft / 2
    private val twR = IntArray(ncfft)
    private val twI = IntArray(ncfft)
    private val superR = IntArray(ncfft / 2)
    private val superI = IntArray(ncfft / 2)
    private val factors: IntArray
    private val tmpR = IntArray(ncfft)
    private val tmpI = IntArray(ncfft)

    init {
        require(nfft % 2 == 0)
        for (i in 0 until ncfft) {
            val phase = -2 * PI * i / ncfft
            twR[i] = fixCos(phase)
            twI[i] = fixSin(phase)
        }
        for (i in 0 until ncfft / 2) {
            val phase = -3.14159265358979323846264338327 * ((i + 1).toDouble() / ncfft + .5)
            superR[i] = fixCos(phase)
            superI[i] = fixSin(phase)
        }
        // kf_factor
        val f = mutableListOf<Int>()
        var n = ncfft
        var p = 4
        val floorSqrt = floor(kotlin.math.sqrt(n.toDouble()))
        do {
            while (n % p != 0) {
                p = when (p) { 4 -> 2; 2 -> 3; else -> p + 2 }
                if (p > floorSqrt) p = n
            }
            n /= p
            f += p
            f += n
        } while (n > 1)
        factors = f.toIntArray()
        var i = 0
        while (i < factors.size) {
            require(factors[i] == 2 || factors[i] == 4) { "Only radix 2/4 supported, got ${factors[i]}" }
            i += 2
        }
    }

    /** [input] has [nfft] int16 samples; writes nfft/2+1 bins. */
    fun forward(input: IntArray, outR: IntArray, outI: IntArray) {
        // Complex FFT of the input viewed as ncfft complex pairs.
        work(0, input, 0, 1, 0)
        var tdcR = tmpR[0]
        var tdcI = tmpI[0]
        tdcR = fixDiv(tdcR, 2)
        tdcI = fixDiv(tdcI, 2)
        outR[0] = s16(tdcR + tdcI)
        outR[ncfft] = s16(tdcR - tdcI)
        outI[0] = 0
        outI[ncfft] = 0
        for (k in 1..ncfft / 2) {
            var fpkR = tmpR[k]
            var fpkI = tmpI[k]
            var fpnkR = tmpR[ncfft - k]
            var fpnkI = s16(-tmpI[ncfft - k])
            fpkR = fixDiv(fpkR, 2); fpkI = fixDiv(fpkI, 2)
            fpnkR = fixDiv(fpnkR, 2); fpnkI = fixDiv(fpnkI, 2)
            val f1kR = s16(fpkR + fpnkR)
            val f1kI = s16(fpkI + fpnkI)
            val f2kR = s16(fpkR - fpnkR)
            val f2kI = s16(fpkI - fpnkI)
            val twr = sround(f2kR * superR[k - 1] - f2kI * superI[k - 1])
            val twi = sround(f2kR * superI[k - 1] + f2kI * superR[k - 1])
            outR[k] = s16((f1kR + twr) shr 1)
            outI[k] = s16((f1kI + twi) shr 1)
            outR[ncfft - k] = s16((f1kR - twr) shr 1)
            outI[ncfft - k] = s16((twi - f1kI) shr 1)
        }
    }

    /** kf_work: output into tmp at [outOff]; input complex index [inOff] (pairs in [input]). */
    private fun work(outOff: Int, input: IntArray, inOff: Int, fstride: Int, fi: Int) {
        val p = factors[fi]
        val m = factors[fi + 1]
        if (m == 1) {
            for (j in 0 until p) {
                val src = inOff + j * fstride
                tmpR[outOff + j] = input[2 * src]
                tmpI[outOff + j] = input[2 * src + 1]
            }
        } else {
            for (j in 0 until p) work(outOff + j * m, input, inOff + j * fstride, fstride * p, fi + 2)
        }
        when (p) {
            2 -> bfly2(outOff, fstride, m)
            4 -> bfly4(outOff, fstride, m)
        }
    }

    private fun bfly2(off: Int, fstride: Int, m: Int) {
        for (k in 0 until m) {
            val a = off + k
            val b = a + m
            tmpR[a] = fixDiv(tmpR[a], 2); tmpI[a] = fixDiv(tmpI[a], 2)
            tmpR[b] = fixDiv(tmpR[b], 2); tmpI[b] = fixDiv(tmpI[b], 2)
            val tw = k * fstride
            val tR = sround(tmpR[b] * twR[tw] - tmpI[b] * twI[tw])
            val tI = sround(tmpR[b] * twI[tw] + tmpI[b] * twR[tw])
            tmpR[b] = s16(tmpR[a] - tR); tmpI[b] = s16(tmpI[a] - tI)
            tmpR[a] = s16(tmpR[a] + tR); tmpI[a] = s16(tmpI[a] + tI)
        }
    }

    private fun bfly4(off: Int, fstride: Int, m: Int) {
        for (k in 0 until m) {
            val i0 = off + k
            val i1 = i0 + m
            val i2 = i0 + 2 * m
            val i3 = i0 + 3 * m
            for (idx in intArrayOf(i0, i1, i2, i3)) {
                tmpR[idx] = fixDiv(tmpR[idx], 4)
                tmpI[idx] = fixDiv(tmpI[idx], 4)
            }
            val t1 = k * fstride
            val t2 = 2 * k * fstride
            val t3 = 3 * k * fstride
            val s0R = sround(tmpR[i1] * twR[t1] - tmpI[i1] * twI[t1])
            val s0I = sround(tmpR[i1] * twI[t1] + tmpI[i1] * twR[t1])
            val s1R = sround(tmpR[i2] * twR[t2] - tmpI[i2] * twI[t2])
            val s1I = sround(tmpR[i2] * twI[t2] + tmpI[i2] * twR[t2])
            val s2R = sround(tmpR[i3] * twR[t3] - tmpI[i3] * twI[t3])
            val s2I = sround(tmpR[i3] * twI[t3] + tmpI[i3] * twR[t3])
            val s5R = s16(tmpR[i0] - s1R)
            val s5I = s16(tmpI[i0] - s1I)
            tmpR[i0] = s16(tmpR[i0] + s1R)
            tmpI[i0] = s16(tmpI[i0] + s1I)
            val s3R = s16(s0R + s2R)
            val s3I = s16(s0I + s2I)
            val s4R = s16(s0R - s2R)
            val s4I = s16(s0I - s2I)
            tmpR[i2] = s16(tmpR[i0] - s3R)
            tmpI[i2] = s16(tmpI[i0] - s3I)
            tmpR[i0] = s16(tmpR[i0] + s3R)
            tmpI[i0] = s16(tmpI[i0] + s3I)
            tmpR[i1] = s16(s5R + s4I)
            tmpI[i1] = s16(s5I - s4R)
            tmpR[i3] = s16(s5R - s4I)
            tmpI[i3] = s16(s5I + s4R)
        }
    }

    private companion object {
        const val PI = 3.141592653589793238462643383279502884197169399375105820974944
        const val SAMP_MAX = 32767
        fun s16(x: Int) = x.toShort().toInt()
        fun sround(x: Int) = s16((x + (1 shl 14)) shr 15)
        fun fixDiv(x: Int, k: Int) = sround(x * (SAMP_MAX / k))
        fun fixCos(phase: Double) = s16(floor(.5 + SAMP_MAX * kotlin.math.cos(phase)).toInt())
        fun fixSin(phase: Double) = s16(floor(.5 + SAMP_MAX * sin(phase)).toInt())
    }
}
