package dev.opencode.mobile

/** Recognises "the free-model limit is used up" in what the server reports, so the WARP tunnel can be rotated. */
object Limits {
    private val TEXT = Regex(
        "rate[\\s_-]?limit|too[\\s_-]?many[\\s_-]?requests|\\b429\\b|usage[\\s_-]?limit|free[\\s_-]?usage|" +
            "insufficient[\\s_-]?quota|quota[\\s_-]?exceeded|freeusagelimit|gousagelimit|creditlimit",
        RegexOption.IGNORE_CASE,
    )

    private val FAILURE_EVENTS = setOf("session.step.failed", OcEvent.SESSION_FAILED)

    fun isLimit(text: String): Boolean = TEXT.containsMatchIn(text)

    /** True for a failure event, or a retry status, whose payload talks about a limit. */
    fun carriesLimit(type: String, raw: String): Boolean {
        val relevant = type in FAILURE_EVENTS || (type == "session.status" && raw.contains("retry", ignoreCase = true))

        return relevant && isLimit(raw)
    }
}
