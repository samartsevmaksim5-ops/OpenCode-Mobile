package dev.opencode.mobile

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Follows the server's event stream from native code, so finished tasks and permission requests can reach the person
 * as notifications even while the web page is frozen in the background.
 */
class EventWatcher(
    private val context: Context,
    private val baseUrl: String,
    private val password: String,
    private val enabled: () -> Boolean,
    private val inForeground: () -> Boolean,
) {
    @Volatile
    private var running = false

    @Volatile
    private var connection: HttpURLConnection? = null

    private var thread: Thread? = null

    fun start() {
        if (running) return

        running = true
        thread = Thread({ loop() }, "event-watcher").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { connection?.disconnect() }
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var delay = 2_000L
        val seen = LinkedHashSet<String>()

        while (running) {
            val connectedAt = System.currentTimeMillis()

            try {
                val http = URL(Urls.withoutTrailingSlash(baseUrl) + "/api/event").openConnection() as HttpURLConnection

                connection = http
                http.connectTimeout = 5_000
                // The server sends heartbeats; a silent minute and a half means the link is dead.
                http.readTimeout = 90_000
                http.setRequestProperty("Authorization", "Basic " + Urls.basicCredential(password))
                http.setRequestProperty("Accept", "text/event-stream")

                if (http.responseCode != 200) throw java.io.IOException("HTTP ${http.responseCode}")

                val parser = SseParser { data ->
                    // A reconnect replays recent events; only react to ones that arrive live.
                    if (System.currentTimeMillis() - connectedAt > 1_500) handle(data, seen)
                }

                BufferedReader(InputStreamReader(http.inputStream, Charsets.UTF_8)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break

                        parser.line(line)
                    }
                }

                delay = 2_000L
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // Log only the FIRST failure of an outage: a dead server is retried with backoff every few seconds,
                // and one warning per reconnect would spam five identical lines before the watchdog reacts.
                if (running && delay == 2_000L) Log.w("events", "stream lost: ${e.message}")
            } finally {
                runCatching { connection?.disconnect() }
            }

            if (!running) return

            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }

            delay = minOf(delay * 2, 30_000L)
        }
    }

    private fun handle(data: String, seen: MutableSet<String>) {
        val event = OcEvent.parse(data) ?: return

        if (event.id != null) {
            if (!seen.add(event.id)) return
            if (seen.size > 200) seen.remove(seen.first())
        }

        // A used-up limit rotates the tunnel whether or not the page is open: the page cannot notice anything while
        // the app sits in the background, which is exactly when long tasks run into the limit.
        if (Limits.carriesLimit(event.type, event.raw)) {
            val app = context.applicationContext as? App

            if (app != null) runCatching { app.warp.onLimit("event ${event.type}") }
        }

        if (!enabled() || inForeground()) return

        val key = event.sessionId ?: event.type

        when (event.type) {
            OcEvent.SESSION_SUCCEEDED -> Notifier.activity(
                context,
                key + "ok",
                tr("Задача завершена", "Task finished"),
                tr("OpenCode закончил работу. Откройте приложение, чтобы посмотреть результат.", "OpenCode has finished. Open the app to see the result."),
            )
            OcEvent.SESSION_FAILED -> Notifier.activity(
                context,
                key + "err",
                tr("Задача завершилась ошибкой", "Task failed"),
                tr("Откройте приложение, чтобы посмотреть, что случилось.", "Open the app to see what happened."),
            )
            OcEvent.PERMISSION_ASKED -> Notifier.activity(
                context,
                key + "perm",
                tr("Нужно ваше разрешение", "Permission needed"),
                tr("Агент ждёт ответа на запрос разрешения.", "The agent is waiting for you to answer a permission request."),
            )
        }
    }
}
