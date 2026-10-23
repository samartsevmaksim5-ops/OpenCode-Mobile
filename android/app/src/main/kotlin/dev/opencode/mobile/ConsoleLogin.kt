package dev.opencode.mobile

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** One HTTP exchange with the local OpenCode server. Split out so the login flow can be tested against a fake. */
interface Transport {
    fun request(method: String, path: String, body: String? = null): Reply
}

data class Reply(val code: Int, val body: String)

class HttpTransport(private val base: String, password: String, private val timeoutMs: Int = 15_000) : Transport {
    private val auth = "Basic " + Urls.basicCredential(password)

    override fun request(method: String, path: String, body: String?): Reply {
        val connection = URL(Urls.withoutTrailingSlash(base) + path).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Authorization", auth)
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

            return Reply(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

/** A sign-in the server started: the page to open and the code that page asks for. */
data class LoginAttempt(val id: String, val url: String, val code: String?, val expiresAt: Long?)

sealed interface LoginStatus {
    data object Pending : LoginStatus

    data object Complete : LoginStatus

    data object Expired : LoginStatus

    data class Failed(val message: String) : LoginStatus
}

data class ConsoleAccount(val id: String, val label: String, val method: String)

class LoginException(message: String) : Exception(message)

/**
 * Decides whether a failed sign-in start is worth trying again. Right after the server comes up its outbound network
 * is often not warm yet, so the first attempt can end in a transport error even when the phone is online; a short
 * retry loop turns that into a working first launch. Wrong replies from the local server are not retried.
 */
object LoginFault {
    private val serverDown = listOf(
        "transport error",
        "fetch failed",
        "network",
        "unreachable",
        "timed out",
        "timeout",
        "econnrefused",
        "econnreset",
        "enotfound",
        "eai_again",
        "ehostunreach",
        "enetunreach",
        "socket hang up",
        "interrupted",
        "connection refused",
        "connection reset",
        "unable to resolve host",
    )

    fun isRetryable(message: String?): Boolean {
        val text = message?.lowercase() ?: return false

        if (serverDown.any { it in text }) return true

        return Regex("http 5\\d\\d").containsMatchIn(text)
    }

    /** A short human explanation the login screen can show instead of the raw server text. */
    fun friendly(message: String): String {
        val text = message.lowercase()

        return when {
            "transport error" in text || "fetch failed" in text || "enotfound" in text || "eai_again" in text -> tr(
                "Сервер не смог связаться с opencode.ai. Проверьте интернет на телефоне; обычно помогает новая попытка.",
                "The server could not reach opencode.ai. Check the phone's internet; another try usually helps.",
            )
            "timed out" in text || "timeout" in text -> tr(
                "opencode.ai не ответил вовремя. Проверьте интернет и попробуйте ещё раз.",
                "opencode.ai did not answer in time. Check the internet and try again.",
            )
            "method" in text && "sign-in" in text || "no account sign-in" in text -> tr(
                "Сервер не сообщил способ входа. Перезапустите сервер в настройках и попробуйте снова.",
                "The server did not report a sign-in method. Restart the server in settings and try again.",
            )
            "integration" in text -> tr(
                "Сервер ответил нестандартно на запрос входа. Попробуйте ещё раз или перезапустите сервер.",
                "The server replied unexpectedly to the sign-in request. Try again or restart the server.",
            )
            else -> message
        }
    }
}

/**
 * Starts and follows the OpenCode Console sign-in (the account behind Zen and Go) using the same server API the web
 * UI uses. The page that gets opened lets the person pick Google or GitHub, and Google shows its account chooser.
 */
class ConsoleLogin(private val transport: Transport, private val integration: String = "opencode") {
    fun start(): LoginAttempt {
        val info = get("/api/integration/$integration")
        val data = (info["data"] as? Map<*, *>) ?: throw LoginException("Unexpected integration reply")
        val methods = data["methods"] as? List<*> ?: emptyList<Any?>()
        val oauth = methods.firstNotNullOfOrNull { (it as? Map<*, *>)?.takeIf { m -> m["type"] == "oauth" } }
        val methodId = oauth?.get("id") as? String ?: throw LoginException("The server has no account sign-in method")

        val reply = transport.request(
            "POST",
            "/api/integration/$integration/connect/oauth",
            "{\"methodID\":${MiniJson.quote(methodId)}}",
        )

        if (reply.code !in 200..299) throw LoginException(failure(reply))

        val attempt = (MiniJson.obj(reply.body)?.get("data") as? Map<*, *>) ?: throw LoginException("Unexpected sign-in reply")
        val id = attempt["attemptID"] as? String ?: throw LoginException("The sign-in reply has no attempt id")
        val url = attempt["url"] as? String ?: throw LoginException("The sign-in reply has no page address")
        val time = attempt["time"] as? Map<*, *>

        return LoginAttempt(id, url, code(url), (time?.get("expires") as? Number)?.toLong())
    }

    fun status(attempt: LoginAttempt): LoginStatus {
        val reply = transport.request("GET", "/api/integration/$integration/connect/oauth/${attempt.id}")

        if (reply.code == 404) return LoginStatus.Expired

        if (reply.code !in 200..299) throw LoginException(failure(reply))

        val data = MiniJson.obj(reply.body)?.get("data") as? Map<*, *> ?: throw LoginException("Unexpected status reply")

        return when (data["status"]) {
            "complete" -> LoginStatus.Complete
            "expired" -> LoginStatus.Expired
            "failed" -> LoginStatus.Failed((data["message"] as? String).orEmpty())
            else -> LoginStatus.Pending
        }
    }

    /** The accounts stored for this integration, newest connection last. Empty when nobody has signed in. */
    fun accounts(): List<ConsoleAccount> {
        val data = get("/api/integration/$integration")["data"] as? Map<*, *> ?: return emptyList()
        val connections = data["connections"] as? List<*> ?: return emptyList()

        return connections.mapNotNull {
            val c = it as? Map<*, *> ?: return@mapNotNull null

            if (c["type"] != "credential") return@mapNotNull null

            ConsoleAccount(c["id"] as? String ?: return@mapNotNull null, c["label"] as? String ?: "", c["method"] as? String ?: "")
        }
    }

    /** Only accounts that came from the Console sign-in, as opposed to a pasted API key. */
    fun signedInAccounts(): List<ConsoleAccount> = accounts().filter { it.method == "oauth" }

    private fun get(path: String): Map<String, Any?> {
        val reply = transport.request("GET", path)

        if (reply.code !in 200..299) throw LoginException(failure(reply))

        return MiniJson.obj(reply.body) ?: throw LoginException("The server sent an unreadable reply")
    }

    private fun failure(reply: Reply): String {
        val message = (MiniJson.obj(reply.body)?.get("message") as? String)?.takeIf { it.isNotBlank() }

        return message ?: "HTTP ${reply.code}"
    }

    companion object {
        /** The verification page carries the code as `user_code`, which is what the person must recognise. */
        fun code(url: String): String? {
            val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null

            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')

                if (eq > 0 && pair.substring(0, eq) == "user_code") {
                    return runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
                }
            }

            return null
        }
    }
}
