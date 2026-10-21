package com.gate6.jarvis.wake

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real detector (frontend + LiteRT model from the app's assets) on recorded clips.
 * Clips are macOS text-to-speech, so they check the pipeline end to end, not accuracy on real voices.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordDetectorTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun samples(name: String): ShortArray {
        val bytes = testContext.assets.open("wake/$name").use { it.readBytes() }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val size = b.getInt(pos + 4)
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "data") {
                return ShortArray(size / 2) { b.getShort(pos + 8 + 2 * it) }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data in $name")
    }

    /** Feeds the clip in 30 ms reads like the microphone loop; returns how many times it fired. */
    private fun detections(name: String, cutoff: Float = 0.97f): Int {
        val audio = samples(name)
        var hits = 0
        WakeWordDetector(appContext, cutoff).use { d ->
            var i = 0
            while (i < audio.size) {
                val n = minOf(480, audio.size - i)
                if (d.process(audio.copyOfRange(i, i + n), n)) hits++
                i += n
            }
        }
        return hits
    }

    @Test fun detectsWakeWord() = assertEquals(1, detections("pos_samantha.wav"))

    @Test fun detectsWakeWordFollowedByCommand() = assertEquals(1, detections("pos_karen_cmd.wav"))

    @Test fun ignoresOrdinarySpeech() {
        assertEquals(0, detections("neg_talk.wav", cutoff = 0.80f))
        assertEquals(0, detections("neg_marvin.wav", cutoff = 0.80f))
    }

    @Test fun ignoresJarvisMidSentence() = assertEquals(0, detections("neg_sentence_jarvis.wav", cutoff = 0.80f))

    @Test
    fun resetsAfterDetection() {
        // Same clip twice back to back: fires once per utterance, not continuously.
        val clip = samples("pos_samantha.wav")
        val twice = clip + clip
        var hits = 0
        WakeWordDetector(appContext, 0.97f).use { d ->
            var i = 0
            while (i < twice.size) {
                val n = minOf(480, twice.size - i)
                if (d.process(twice.copyOfRange(i, i + n), n)) hits++
                i += n
            }
        }
        assertEquals(2, hits)
    }

    @Test
    fun scoresNeedAFullWindow() {
        val s = WakeScores(5, 0.9f)
        repeat(4) { assertFalse(s.add(1f)) }
        assertTrue(s.add(1f))
        s.clear()
        assertFalse(s.add(1f))
    }
}
