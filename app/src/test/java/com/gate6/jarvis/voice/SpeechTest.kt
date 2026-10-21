package com.gate6.jarvis.voice

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    /** 50 ms of 16 kHz PCM16 LE: a sine of the given peak amplitude. */
    private fun chunk(amplitude: Int) = ByteArray(1600).also { b ->
        for (i in 0 until 800) {
            val s = (amplitude * sin(2 * PI * 300 * i / 16000.0)).toInt()
            b[2 * i] = s.toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
    }

    @Test
    fun silenceAndRoomNoiseAreNotSpeech() {
        assertEquals(0.0, Speech.rms(ByteArray(1600)), 0.0)
        assertFalse(Speech.isSpeech(chunk(300))) // ~-40 dBFS hum
    }

    @Test
    fun talkingIsSpeech() {
        assertTrue(Speech.isSpeech(chunk(4000)))
        assertEquals(4000 / kotlin.math.sqrt(2.0), Speech.rms(chunk(4000)), 30.0)
    }

    @Test
    fun negativeSamplesAreDecodedSigned() {
        val b = byteArrayOf(0x00, 0x80.toByte()) // -32768
        assertEquals(32768.0, Speech.rms(b), 0.0)
    }
}
