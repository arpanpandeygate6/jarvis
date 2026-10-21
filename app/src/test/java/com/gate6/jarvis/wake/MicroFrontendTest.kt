package com.gate6.jarvis.wake

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compares the Kotlin frontend with feature dumps from the reference C implementation
 * (pymicro-features, i.e. TFLM microfrontend) for the same clips.
 */
class MicroFrontendTest {
    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("wake/$name")!!.use { it.readBytes() }

    /** PCM16 samples from a WAV file, walking chunks to find "data". */
    private fun wavSamples(name: String): ShortArray {
        val bytes = ByteBuffer.wrap(resource(name)).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.limit()) {
            val id = String(bytes.array(), pos, 4, Charsets.US_ASCII)
            val size = bytes.getInt(pos + 4)
            if (id == "data") {
                val out = ShortArray(size / 2)
                for (i in out.indices) out[i] = bytes.getShort(pos + 8 + 2 * i)
                return out
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }

    private fun referenceFrames(name: String): List<IntArray> {
        val b = ByteBuffer.wrap(resource(name)).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until b.limit() / 80).map { f -> IntArray(40) { i -> b.getShort(f * 80 + i * 2).toInt() and 0xFFFF } }
    }

    private fun kotlinFrames(samples: ShortArray): List<IntArray> {
        val fe = MicroFrontend()
        val frames = mutableListOf<IntArray>()
        var idx = 0
        val out = IntArray(40)
        // Same chunking as the reference: 160-sample calls.
        while (idx + MicroFrontend.SAMPLES_PER_STEP <= samples.size) {
            val read = fe.process(samples, idx, MicroFrontend.SAMPLES_PER_STEP, out)
            idx += read
            if (fe.lastHadOutput) frames += out.copyOf()
        }
        return frames
    }

    private fun check(clip: String) {
        val expected = referenceFrames("$clip.features")
        val actual = kotlinFrames(wavSamples("$clip.wav"))
        assertEquals("frame count for $clip", expected.size, actual.size)
        var mismatches = 0
        var maxDiff = 0
        for (f in expected.indices) for (c in 0 until 40) {
            val d = kotlin.math.abs(expected[f][c] - actual[f][c])
            if (d != 0) mismatches++
            if (d > maxDiff) maxDiff = d
        }
        // Filterbank weights come from float log1p, whose last bit differs between math libraries
        // (the C reference itself differs between glibc, newlib and Apple). One weight here lands on a
        // rounding boundary, which moves about 1 value in 20k by a few log units. Anything beyond
        // that is a porting bug.
        val total = expected.size * 40
        assertTrue("$clip: $mismatches of $total values differ", mismatches <= total / 5000)
        assertTrue("$clip: max diff $maxDiff", maxDiff <= 8)
    }

    @Test fun matchesReferenceOnWakeWord() = check("pos_samantha")
    @Test fun matchesReferenceOnCommand() = check("pos_karen_cmd")
    @Test fun matchesReferenceOnOtherSpeech() = check("neg_talk")

    @Test
    fun chunkSizeDoesNotChangeFeatures() {
        val samples = wavSamples("pos_samantha.wav")
        val fe = MicroFrontend()
        val out = IntArray(40)
        val frames = mutableListOf<IntArray>()
        var idx = 0
        while (idx < samples.size) { // ragged 37-sample reads, like an AudioRecord buffer would give
            val n = minOf(37, samples.size - idx)
            var used = 0
            while (used < n) {
                used += fe.process(samples, idx + used, n - used, out)
                if (fe.lastHadOutput) frames += out.copyOf()
            }
            idx += n
        }
        val stepped = kotlinFrames(samples) // same audio fed in exact 10 ms steps
        assertTrue(frames.size >= stepped.size)
        for (f in stepped.indices) assertTrue("frame $f", stepped[f].contentEquals(frames[f]))
    }

    @Test
    fun silenceAndFullScaleDoNotCrash() {
        val fe = MicroFrontend()
        val out = IntArray(40)
        val loud = ShortArray(16000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val quiet = ShortArray(16000)
        for (buf in listOf(quiet, loud, quiet)) {
            var i = 0
            while (i < buf.size) i += fe.process(buf, i, buf.size - i, out)
        }
        assertTrue(out.all { it in 0..0xFFFF })
    }
}
