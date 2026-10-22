package com.gate6.jarvis.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Loudness of the user's voice and of Jarvis's voice, 0..1, for the overlay orb. */
object VoiceLevel {
    private const val FULL_SCALE_RMS = 3000.0

    private val _user = MutableStateFlow(0f)
    val user: StateFlow<Float> = _user.asStateFlow()
    private val _jarvis = MutableStateFlow(0f)
    val jarvis: StateFlow<Float> = _jarvis.asStateFlow()

    fun user(pcm16le: ByteArray) {
        _user.value = scale(Speech.rms(pcm16le))
    }

    fun jarvis(pcm16le: ByteArray, offset: Int, length: Int) {
        _jarvis.value = scale(Speech.rms(pcm16le, offset, length))
    }

    fun reset() {
        _user.value = 0f
        _jarvis.value = 0f
    }

    private fun scale(rms: Double) = (rms / FULL_SCALE_RMS).coerceIn(0.0, 1.0).toFloat()
}
