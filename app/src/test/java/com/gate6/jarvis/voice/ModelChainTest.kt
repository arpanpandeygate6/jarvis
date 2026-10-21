package com.gate6.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelChainTest {
    private val now = 10 * ModelChain.COOLDOWN_MS
    private val fallbacks = listOf("b", "c")

    @Test fun preferredFirstWhenNothingFailed() =
        assertEquals(listOf("a", "b", "c"), ModelChain.order("a", fallbacks, emptyMap(), now))

    @Test fun preferredThatIsAlsoAFallbackIsNotRepeated() =
        assertEquals(listOf("b", "c"), ModelChain.order("b", fallbacks, emptyMap(), now))

    @Test fun recentlyFailedModelGoesLast() =
        assertEquals(listOf("b", "c", "a"), ModelChain.order("a", fallbacks, mapOf("a" to now - 1000), now))

    @Test fun failureOlderThanCooldownIsForgotten() =
        assertEquals(listOf("a", "b", "c"), ModelChain.order("a", fallbacks, mapOf("a" to now - ModelChain.COOLDOWN_MS), now))

    @Test fun allCoolingTriesOldestFailureFirst() =
        assertEquals(
            listOf("c", "a", "b"),
            ModelChain.order("a", fallbacks, mapOf("a" to now - 3000, "b" to now - 1000, "c" to now - 5000), now),
        )

    @Test fun encodeDecodeRoundTrip() {
        val m = mapOf("gemini-2.5-flash-native-audio-preview-12-2025" to 123L, "x" to 4L)
        assertEquals(m, ModelChain.decode(ModelChain.encode(m)))
        assertEquals(emptyMap<String, Long>(), ModelChain.decode(null))
        assertEquals(emptyMap<String, Long>(), ModelChain.decode("garbage,=5,a=x"))
    }
}
