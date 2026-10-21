package com.gate6.jarvis.mcp

/**
 * Code-side gate for tools that change data. The model is told to read the action back and ask
 * "Should I go ahead?", but a prompt is not a guarantee, so the call only runs when all of these hold:
 * the model set `user_confirmed`, its previous turn actually asked for confirmation, and the user's
 * latest reply is a clear yes with no "no / wait / cancel".
 */
object Confirmation {
    const val PARAM = "user_confirmed"

    data class Verdict(val allowed: Boolean, val messageForModel: String = "")

    private val ASKED = Regex(
        """\b(go ahead|shall i|should i|do you want me to|want me to|okay to|ok to|confirm\w*|proceed)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val YES = Regex(
        """\b(yes|yeah|yea|yep|yup|sure|go ahead|do it|confirm(ed)?|okay|ok|please do|absolutely|correct|proceed|send it|haan|haa|theek hai|thik hai|kar do|karo)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NO = Regex(
        """\b(no|nope|nah|don'?t|do not|not|cancel|stop|wait|hold on|never ?mind|nahi|mat)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param confirmedFlag the model's `user_confirmed` argument
     * @param assistantQuestion what the assistant said in the turn just before the user's latest reply
     * @param userReply the user's latest utterance
     */
    fun check(confirmedFlag: Boolean, assistantQuestion: String, userReply: String): Verdict = when {
        !confirmedFlag -> Verdict(
            false,
            "Not executed: this action changes data. Read the exact action back to the user, ask " +
                "\"Should I go ahead?\", and call again with user_confirmed true only after they say yes.",
        )
        !ASKED.containsMatchIn(assistantQuestion) -> Verdict(
            false,
            "Not executed: you have not asked the user to confirm this action yet. Read it back and ask " +
                "\"Should I go ahead?\" first.",
        )
        !isClearYes(userReply) -> Verdict(
            false,
            "Not executed: the user's last reply was not a clear yes. Ask again, or drop the action if they declined.",
        )
        else -> Verdict(true)
    }

    /** A follow-up question ("okay, which branch?") is not consent, even if it starts with "okay". */
    private val QUESTION = Regex("""\?|\b(what|which|who|whom|where|when|why|how|kya|kaun|kaunsa)\b""", RegexOption.IGNORE_CASE)
    private const val MAX_REPLY_WORDS = 15

    fun isClearYes(text: String): Boolean =
        YES.containsMatchIn(text) && !NO.containsMatchIn(text) && !QUESTION.containsMatchIn(text) &&
            text.trim().split(Regex("\\s+")).size <= MAX_REPLY_WORDS
}
