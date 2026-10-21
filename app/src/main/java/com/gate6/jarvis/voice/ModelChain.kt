package com.gate6.jarvis.voice

/**
 * Order in which to try Live models. Each model has its own free-tier quota, so when one is out of
 * quota (or retired) the next one usually still works. A model that failed recently goes to the back
 * of the line for [COOLDOWN_MS], so we don't pay a failed round trip on every conversation.
 */
object ModelChain {
    const val COOLDOWN_MS = 60 * 60 * 1000L

    fun order(preferred: String, fallbacks: List<String>, failedAt: Map<String, Long>, now: Long): List<String> {
        val all = (listOf(preferred) + fallbacks).filter { it.isNotBlank() }.distinct()
        val (cooling, ready) = all.partition { m -> failedAt[m]?.let { now - it < COOLDOWN_MS } == true }
        // Among cooling models, the one that failed longest ago is the best bet.
        return ready + cooling.sortedBy { failedAt[it] }
    }

    /** "model=epochMs,model=epochMs" <-> map, for SharedPreferences. */
    fun decode(s: String?): Map<String, Long> = s.orEmpty().split(',').mapNotNull { e ->
        val i = e.lastIndexOf('=')
        if (i <= 0) null else e.substring(i + 1).toLongOrNull()?.let { e.substring(0, i) to it }
    }.toMap()

    fun encode(m: Map<String, Long>): String = m.entries.joinToString(",") { "${it.key}=${it.value}" }
}
