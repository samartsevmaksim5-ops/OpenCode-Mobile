package dev.opencode.mobile

/** Server-sent events, one line at a time. Comments (the server's heartbeat) are ignored. */
class SseParser(private val onEvent: (data: String) -> Unit) {
    private val data = StringBuilder()
    private var has = false

    fun line(line: String) {
        if (line.isEmpty()) {
            if (has) onEvent(data.toString())
            data.setLength(0)
            has = false
            return
        }

        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)

        if (value.startsWith(" ")) value = value.substring(1)

        if (field != "data") return

        if (has) data.append('\n')
        data.append(value)
        has = true
    }
}

/** The part of an OpenCode server event the app reacts to. */
data class OcEvent(val id: String?, val type: String, val sessionId: String?) {
    /** The whole event payload; kept out of equality on purpose. */
    var raw: String = ""
        private set

    companion object {
        const val SESSION_SUCCEEDED = "session.execution.succeeded"
        const val SESSION_FAILED = "session.execution.failed"
        const val PERMISSION_ASKED = "permission.asked"

        fun parse(json: String): OcEvent? {
            val root = MiniJson.obj(json) ?: return null
            val type = root["type"] as? String ?: return null
            val data = root["data"] as? Map<*, *>

            return OcEvent(root["id"] as? String, type, data?.get("sessionID") as? String).also { it.raw = json }
        }
    }
}
