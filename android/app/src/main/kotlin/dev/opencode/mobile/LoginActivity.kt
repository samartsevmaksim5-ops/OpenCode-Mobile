package dev.opencode.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Signs in to the OpenCode Console (the account behind Zen and Go). The sign-in page opens in the system browser, where
 * the person picks Google and then the Google account, or adds another one. The screen keeps the attempt simple: one
 * button, an optional code, and it may be skipped entirely - signing in is never required to use the app.
 */
class LoginActivity : Activity() {
    private val app: App get() = application as App
    private lateinit var palette: Palette
    private lateinit var status: TextView
    private lateinit var codeBox: LinearLayout
    private lateinit var code: TextView
    private lateinit var buttons: LinearLayout

    private var unsubscribe: (() -> Unit)? = null
    private var attempt: LoginAttempt? = null

    @Volatile
    private var alive = true

    @Volatile
    private var running = false

    /** Bumped when the person asks for a retry so a sleeping backoff ends at once. */
    @Volatile
    private var wakeup = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        palette = Palette(this)

        val column = Ui.vertical(this).apply {
            setPadding(Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 40), Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        column.addView(Ui.icon(this, R.drawable.ic_user, palette.muted, 40), Ui.params(Ui.dp(this, 40), Ui.dp(this, 40), bottom = Ui.dp(this, 14)))
        column.addView(Ui.text(this, tr("Вход в OpenCode", "Sign in to OpenCode"), 22f, palette.text, bold = true), Ui.params(bottom = Ui.dp(this, 6)))

        column.addView(
            Ui.text(
                this,
                tr("Аккаунт открывает Zen и платные модели. Можно пропустить и войти позже в настройках.", "An account unlocks Zen and paid models. You can skip this and sign in later in settings."),
                14f,
                palette.muted,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL },
            Ui.params(bottom = Ui.dp(this, 20)),
        )

        codeBox = Ui.card(this, palette).apply { visibility = View.GONE }
        codeBox.addView(Ui.text(this, tr("Код подтверждения", "Confirmation code"), 12f, palette.faint))
        code = Ui.text(this, "", 28f, palette.text, bold = true, mono = true).apply { setPadding(0, Ui.dp(this@LoginActivity, 6), 0, Ui.dp(this@LoginActivity, 6)) }
        codeBox.addView(code)
        codeBox.addView(Ui.text(this, tr("Если страница спросит код, он совпадает с этим.", "If the page asks for a code, it is this one."), 12f, palette.muted))
        column.addView(codeBox, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(this, 16)))

        status = Ui.text(this, tr("Готов к входу", "Ready"), 14f, palette.muted).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(status, Ui.params(bottom = Ui.dp(this, 16)))

        buttons = Ui.vertical(this)
        column.addView(buttons, Ui.params(Ui.MATCH, Ui.WRAP))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            addView(column)
        })

        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        showButtons()
        ServerService.send(this)
        unsubscribe = ServerState.listen { onState(it) }
    }

    override fun onDestroy() {
        alive = false
        wakeup++
        unsubscribe?.invoke()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Closing the screen is allowed; the app simply works without an account.
        if (attempt == null) {
            app.prefs.loginSkipped = true
            finish()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun onState(s: Snapshot) {
        when (s.phase) {
            Phase.RUNNING -> if (!running && attempt == null) begin(s)
            Phase.FAILED -> {
                status.text = tr("Сервер не запустился: ", "The server did not start: ") + s.error.orEmpty()
                showButtons(retry = true)
            }
            else -> if (attempt == null && !running) status.text = if (s.message.isNotEmpty()) s.message else tr("Ожидание сервера...", "Waiting for the server...")
        }
    }

    private fun begin(s: Snapshot) {
        running = true
        status.text = tr("Запрашиваю страницу входа...", "Requesting the sign-in page...")
        showButtons()

        val login = ConsoleLogin(HttpTransport(s.baseUrl, app.prefs.password))

        Thread({
            var lastError: String? = null

            // The first attempt often lands while the server's outbound network is still warming up inside the Linux
            // environment; the server then answers with a transport error. A few patient retries make the first
            // launch work without the person ever seeing the failure.
            for (n in 1..ATTEMPTS) {
                if (!alive) {
                    running = false

                    return@Thread
                }

                try {
                    val started = login.start()

                    runOnUiThread {
                        if (!alive) return@runOnUiThread

                        attempt = started
                        code.text = started.code.orEmpty()
                        codeBox.visibility = if (started.code != null) View.VISIBLE else View.GONE
                        status.text = tr("Ждём, пока вы войдёте в браузере...", "Waiting for you to sign in in the browser...")
                        showButtons()
                        Browser.open(this, started.url)
                    }

                    poll(login, started)

                    return@Thread
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w("login", "start attempt $n failed: $lastError")

                    if (n == ATTEMPTS || !LoginFault.isRetryable(lastError)) break

                    runOnUiThread {
                        if (alive) status.text = tr("Сервер ещё готовится, попытка ", "The server is warming up, attempt ") + (n + 1) + tr(" из ", " of ") + ATTEMPTS + "..."
                    }
                    pause(n)
                }
            }

            runOnUiThread {
                if (!alive) return@runOnUiThread

                status.text = tr("Не удалось начать вход: ", "Could not start the sign-in: ") + LoginFault.friendly(lastError ?: "")
                showButtons(retry = true)
            }
        }, "login").apply { isDaemon = true; start() }
    }

    /** Waits between retries; a retry press or closing the screen ends the wait at once. */
    private fun pause(step: Int) {
        val my = wakeup
        val seconds = longArrayOf(2, 4, 6).getOrElse(step - 1) { 6 }

        repeat((seconds * 2).toInt()) {
            if (!alive || wakeup != my) return

            Thread.sleep(500)
        }
    }

    private fun poll(login: ConsoleLogin, started: LoginAttempt) {
        var errors = 0

        while (alive) {
            try {
                Thread.sleep(2000)

                when (val result = login.status(started)) {
                    LoginStatus.Complete -> {
                        runOnUiThread { done() }

                        return
                    }
                    LoginStatus.Expired -> {
                        runOnUiThread {
                            attempt = null
                            codeBox.visibility = View.GONE
                            status.text = tr("Время входа истекло. Начните заново.", "The sign-in timed out. Start again.")
                            showButtons(retry = true)
                        }

                        return
                    }
                    is LoginStatus.Failed -> {
                        runOnUiThread {
                            attempt = null
                            codeBox.visibility = View.GONE
                            status.text = if (result.message.contains("access_denied")) {
                                tr("Вход отклонён на странице.", "The sign-in was declined on the page.")
                            } else {
                                tr("Вход не удался: ", "Sign-in failed: ") + result.message
                            }
                            showButtons(retry = true)
                        }

                        return
                    }
                    LoginStatus.Pending -> errors = 0
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // A few failed polls in a row are normal while the phone switches apps; keep trying.
                if (++errors > 15) {
                    runOnUiThread {
                        attempt = null
                        status.text = tr("Потеряна связь с сервером: ", "Lost the connection to the server: ") + (e.message ?: "")
                        showButtons(retry = true)
                    }

                    return
                }
            }
        }
    }

    private fun done() {
        app.prefs.signedIn = true
        UiBus.reloadRequested = true
        app.syncAccountAsync()
        Ui.toast(this, tr("Вход выполнен", "Signed in"))
        finish()
    }

    private fun showButtons(retry: Boolean = false) {
        buttons.removeAllViews()

        val current = attempt

        if (retry) {
            buttons.addView(
                Ui.button(this, tr("Повторить", "Try again"), palette, primary = true) {
                    attempt = null

                    val s = ServerState.snapshot

                    if (s.phase == Phase.RUNNING) begin(s) else ServerService.send(this, ServerService.ACTION_RESTART)
                },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        if (current != null) {
            buttons.addView(
                Ui.button(this, tr("Открыть страницу входа", "Open the sign-in page"), palette, primary = true) { Browser.open(this, current.url) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
            buttons.addView(
                Ui.button(this, tr("Другой аккаунт", "Another account"), palette) { Browser.open(this, current.url, private = true) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        } else if (!running) {
            buttons.addView(
                Ui.button(this, tr("Войти через Google", "Sign in with Google"), palette, primary = true) {
                    attempt = null

                    val s = ServerState.snapshot

                    if (s.phase == Phase.RUNNING) begin(s) else ServerService.send(this, ServerService.ACTION_START)
                },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        // The skip button stays until a sign-in is actually in flight, so the first launch can always be passed over.
        if (current == null) {
            buttons.addView(
                Ui.button(this, tr("Пропустить", "Skip"), palette) {
                    app.prefs.loginSkipped = true
                    alive = false
                    wakeup++
                    finish()
                },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        buttons.gravity = Gravity.CENTER_HORIZONTAL
    }

    companion object {
        private const val ATTEMPTS = 4
    }
}
