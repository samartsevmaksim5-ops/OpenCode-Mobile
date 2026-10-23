package dev.opencode.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Signs in to the OpenCode Console (the account behind Zen and the free quota). The sign-in page opens in the system
 * browser, where the person picks Google and then the Google account.
 *
 * The screen is deliberate about timing: the confirmation code is minted only when the person actually presses the
 * button, and the button itself appears only once the Linux environment is unpacked and the server is running. A code
 * minted earlier can expire while the environment is still preparing, and the console page then answers that it cannot
 * verify it; a code minted on tap never has that problem. The same screen adds a second account to a profile that
 * already has one - it opens the page in an ephemeral browser window, so Google asks which account to use instead of
 * silently reusing the first one.
 */
class LoginActivity : Activity() {
    private val app: App get() = application as App
    private lateinit var palette: Palette
    private lateinit var status: TextView
    private lateinit var note: TextView
    private lateinit var codeBox: LinearLayout
    private lateinit var code: TextView
    private lateinit var buttons: LinearLayout

    private var unsubscribe: (() -> Unit)? = null
    private var attempt: LoginAttempt? = null

    /** The server generation the live attempt was minted against; a newer generation makes the attempt stale. */
    private var attemptGeneration = -1

    /** Whether the live attempt's page was opened in an ephemeral window (the add-another-account path). */
    private var attemptPrivate = false

    /** True while a code is being minted, so a state update cannot start a second one. */
    @Volatile
    private var minting = false

    @Volatile
    private var alive = true

    /** Bumped when the person asks for a retry so a sleeping backoff ends at once. */
    @Volatile
    private var wakeup = 0

    /** Bumped whenever the current attempt is replaced; a ghost poll from an older attempt sees the change and quits. */
    @Volatile
    private var attemptSerial = 0

    /** True when the screen was opened to add another account to a profile that already has one. */
    private val adding: Boolean
        get() = app.prefs.signedIn || app.profiles.active.account != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        palette = Palette(this)

        val column = Ui.vertical(this).apply {
            setPadding(Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 40), Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        column.addView(Ui.icon(this, R.drawable.ic_user, palette.muted, 40), Ui.params(Ui.dp(this, 40), Ui.dp(this, 40), bottom = Ui.dp(this, 14)))

        column.addView(
            Ui.text(
                this,
                if (adding) tr("Добавить аккаунт", "Add an account") else tr("Вход в OpenCode", "Sign in to OpenCode"),
                22f,
                palette.text,
                bold = true,
            ),
            Ui.params(bottom = Ui.dp(this, 6)),
        )

        column.addView(
            Ui.text(
                this,
                if (adding) {
                    tr(
                        "У профиля уже есть аккаунт. Добавьте ещё один - Google спросит, каким войти.",
                        "This profile already has an account. Add another one - Google will ask which to use.",
                    )
                } else {
                    tr(
                        "Аккаунт даёт вашу личную квоту бесплатных моделей, а также Zen и платные модели. Можно пропустить и войти позже в настройках.",
                        "An account brings your own free-model quota, plus Zen and paid models. You can skip this and sign in later in settings.",
                    )
                },
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

        status = Ui.text(this, tr("Готовим Linux-окружение...", "Preparing the Linux environment..."), 14f, palette.muted).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(status, Ui.params(bottom = Ui.dp(this, 6)))

        note = Ui.text(this, "", 13f, palette.faint).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        column.addView(note, Ui.params(bottom = Ui.dp(this, 10)))

        buttons = Ui.vertical(this)
        column.addView(buttons, Ui.params(Ui.MATCH, Ui.WRAP))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            addView(column)
        })

        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        // The start button waits for the server on purpose: until Linux is unpacked and the server runs, a minted code
        // can go stale before the browser even opens, and the console page then refuses to verify it.
        showWaiting()
        ServerService.send(this)
        unsubscribe = ServerState.listen { onState(it) }

        // Rotations recreate the activity without state events; sync with the current snapshot at once, otherwise the
        // screen would claim the Linux environment is still being prepared on a server that already runs.
        onState(ServerState.snapshot)
    }

    override fun onResume() {
        super.onResume()

        // The person may have approved the code in the browser while the screen was backgrounded (or the activity was
        // recreated by a rotation); pull the account in so the sign-in completes without reopening the screen.
        if (!minting) app.syncAccountAsync()
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
        if (attempt == null && !minting && !adding) {
            app.prefs.loginSkipped = true
            finish()
        } else {
            finish()
        }
    }

    private fun onState(s: Snapshot) {
        // The listener runs on the main thread; nothing here may begin a second mint while one is in flight.
        when (s.phase) {
            Phase.RUNNING -> if (attempt == null && !minting) {
                status.text = tr("Готово к входу", "Ready")
                showStartButtons()
            }

            Phase.FAILED -> {
                if (attempt == null && !minting) {
                    status.text = tr("Сервер не запустился: ", "The server did not start: ") + s.error.orEmpty()
                    showServerRetry()
                }
            }

            else -> if (attempt == null && !minting) showWaiting(s)
        }

        // A server restart throws away the attempt the console page is verifying against; start over.
        if (attempt != null && s.phase == Phase.RUNNING && s.generation != attemptGeneration) {
            attempt = null
            attemptSerial++
            hideNote()
            status.text = tr(
                "Сервер перезапустился. Код со старой страницы больше не действует - начните вход заново.",
                "The server restarted. The code on the old page no longer works - start the sign-in again.",
            )
            showStartButtons()
        }
    }

    /** The screen before the server is up: progress text and the way out, no code minting yet. */
    private fun showWaiting(s: Snapshot = ServerState.snapshot) {
        buttons.removeAllViews()

        if (attempt == null && !minting && s.phase != Phase.RUNNING && s.phase != Phase.FAILED) {
            status.text = if (s.message.isNotEmpty()) s.message else tr("Готовим Linux-окружение...", "Preparing the Linux environment...")
        }

        addSkip()
    }

    /** The server is running: the sign-in button appears, and pressing it mints a fresh code. */
    private fun showStartButtons() {
        buttons.removeAllViews()

        buttons.addView(
            Ui.button(
                this,
                if (adding) tr("Добавить аккаунт Google", "Add a Google account") else tr("Войти через Google", "Sign in with Google"),
                palette,
                primary = true,
            ) {
                val s = ServerState.snapshot

                if (s.phase == Phase.RUNNING) begin(s, private = adding) else ServerService.send(this, ServerService.ACTION_START)
            },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        addSkip()
    }

    /** Start failed or the attempt expired: retry (and restart the server when it is down) plus the way out. */
    private fun showServerRetry() {
        buttons.removeAllViews()

        buttons.addView(
            Ui.button(this, tr("Повторить", "Try again"), palette, primary = true) {
                attempt = null
                attemptSerial++
                hideNote()

                val s = ServerState.snapshot

                if (s.phase == Phase.RUNNING) begin(s, private = adding) else ServerService.send(this, ServerService.ACTION_RESTART)
            },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        addSkip()
    }

    /** Buttons while the browser page is waiting for the person to approve the code. */
    private fun showAttemptButtons() {
        val current = attempt ?: return
        buttons.removeAllViews()

        buttons.addView(
            Ui.button(this, tr("Открыть страницу входа", "Open the sign-in page"), palette, primary = true) {
                Browser.open(this, current.url, private = attemptPrivate)
            },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        // The console page refuses a code when it went stale (a restart, a long Google sign-in, an old tab). A fresh
        // code always fixes it, so the way to get one stays one tap away.
        buttons.addView(
            Ui.button(this, tr("Выдать новый код", "Issue a new code"), palette) {
                attempt = null
                attemptSerial++
                hideNote()

                val s = ServerState.snapshot

                if (s.phase == Phase.RUNNING) begin(s, private = attemptPrivate) else ServerService.send(this, ServerService.ACTION_RESTART)
            },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        if (!attemptPrivate) {
            buttons.addView(
                Ui.button(this, tr("Другой аккаунт", "Another account"), palette) { Browser.open(this, current.url, private = true) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        addSkip()
    }

    /** Skip stays available until a sign-in completes; in the add-another mode it simply closes the screen. */
    private fun addSkip() {
        buttons.addView(
            Ui.button(this, if (adding) tr("Готово", "Done") else tr("Пропустить", "Skip"), palette) {
                if (!adding) app.prefs.loginSkipped = true
                alive = false
                wakeup++
                finish()
            },
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        buttons.gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun begin(s: Snapshot, private: Boolean) {
        minting = true
        attemptPrivate = private
        val serial = ++attemptSerial
        val pwd = if (app.prefs.remote) app.prefs.remotePassword else app.prefs.password
        hideNote()
        status.text = tr("Запрашиваю страницу входа...", "Requesting the sign-in page...")
        buttons.removeAllViews()
        addSkip()

        val login = ConsoleLogin(HttpTransport(s.baseUrl, pwd))

        Thread({
            var lastError: String? = null

            // The first attempt often lands while the server's outbound network is still warming up inside the Linux
            // environment; the server then answers with a transport error. A few patient retries make the first
            // launch work without the person ever seeing the failure.
            for (n in 1..ATTEMPTS) {
                if (!alive) {
                    minting = false

                    return@Thread
                }

                try {
                    val started = login.start()
                    val mintedAt = System.currentTimeMillis()

                    runOnUiThread {
                        if (!alive || serial != attemptSerial) return@runOnUiThread

                        minting = false
                        attempt = started
                        attemptGeneration = s.generation
                        code.text = started.code.orEmpty()
                        codeBox.visibility = if (started.code != null) View.VISIBLE else View.GONE
                        status.text = tr("Ждём, пока вы войдёте в браузере...", "Waiting for you to sign in in the browser...")
                        showAttemptButtons()
                        Browser.open(this, started.url, private = private)
                    }

                    poll(login, started, mintedAt, serial)

                    return@Thread
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w("login", "start attempt $n failed: $lastError")

                    if (n == ATTEMPTS || !LoginFault.isRetryable(lastError)) break

                    runOnUiThread {
                        if (!alive || serial != attemptSerial) return@runOnUiThread

                        status.text = tr("Сервер ещё готовится, попытка ", "The server is warming up, attempt ") + (n + 1) + tr(" из ", " of ") + ATTEMPTS + "..."
                    }
                    pause(n)
                }
            }

            runOnUiThread {
                if (!alive || serial != attemptSerial) return@runOnUiThread

                minting = false
                attempt = null
                codeBox.visibility = View.GONE
                status.text = tr("Не удалось начать вход: ", "Could not start the sign-in: ") + LoginFault.friendly(lastError ?: "")
                showServerRetry()
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

    private fun poll(login: ConsoleLogin, started: LoginAttempt, mintedAt: Long, serial: Int) {
        var errors = 0
        var hinted = false

        while (alive) {
            // A newer attempt took over ("issue a new code", retry, a server restart): this poll is a ghost now.
            if (serial != attemptSerial) return

            try {
                Thread.sleep(2000)

                if (serial != attemptSerial) return

                if (!hinted && System.currentTimeMillis() - mintedAt > HINT_AFTER_MS) {
                    hinted = true

                    runOnUiThread {
                        if (!alive || attempt == null || serial != attemptSerial) return@runOnUiThread

                        note.text = tr(
                            "Если страница пишет, что код не подтверждается, нажмите «Выдать новый код» и откройте её заново.",
                            "If the page says the code cannot be verified, tap \"Issue a new code\" and open it again.",
                        )
                        note.visibility = View.VISIBLE
                    }
                }

                when (val result = login.status(started)) {
                    LoginStatus.Complete -> {
                        if (serial == attemptSerial) runOnUiThread { done() }

                        return
                    }
                    LoginStatus.Expired -> {
                        runOnUiThread {
                            if (!alive || serial != attemptSerial) return@runOnUiThread

                            attempt = null
                            codeBox.visibility = View.GONE
                            hideNote()
                            status.text = tr("Время входа истекло. Начните заново.", "The sign-in timed out. Start again.")
                            showServerRetry()
                        }

                        return
                    }
                    is LoginStatus.Failed -> {
                        runOnUiThread {
                            if (!alive || serial != attemptSerial) return@runOnUiThread

                            attempt = null
                            codeBox.visibility = View.GONE
                            hideNote()
                            status.text = if (result.message.contains("access_denied")) {
                                tr("Вход отклонён на странице.", "The sign-in was declined on the page.")
                            } else {
                                tr("Вход не удался: ", "Sign-in failed: ") + result.message
                            }
                            showServerRetry()
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
                        if (!alive || serial != attemptSerial) return@runOnUiThread

                        attempt = null
                        status.text = tr("Потеряна связь с сервером: ", "Lost the connection to the server: ") + (e.message ?: "")
                        showServerRetry()
                    }

                    return
                }
            }
        }
    }

    private fun hideNote() {
        note.visibility = View.GONE
    }

    private fun done() {
        val wasAdding = adding

        app.prefs.signedIn = true
        UiBus.reloadRequested = true
        app.syncAccountAsync()
        Ui.toast(this, if (wasAdding) tr("Аккаунт добавлен", "Account added") else tr("Вход выполнен", "Signed in"))
        reportConnectedAccount()
        finish()
    }

    /**
     * After the browser approves the code the person still does not know WHICH account the server now spends quota
     * from - the previous one could silently stay active. The connection label (the email) is fetched and shown, so
     * the mismatch "created a new account, quota still gone" is visible immediately instead of a mystery.
     */
    private fun reportConnectedAccount() {
        val s = ServerState.snapshot

        if (s.phase != Phase.RUNNING) return

        val pwd = if (app.prefs.remote) app.prefs.remotePassword else app.prefs.password

        Thread({
            runCatching {
                Thread.sleep(1500)

                val label = ConsoleLogin(HttpTransport(s.baseUrl, pwd)).signedInAccounts().lastOrNull()?.label
                    ?: return@runCatching

                runOnUiThread {
                    Ui.toast(applicationContext, tr("Теперь активен аккаунт: ", "The active account is now: ") + label)
                }
            }
        }, "login-report").apply { isDaemon = true; start() }
    }

    companion object {
        private const val ATTEMPTS = 4

        /** How long a pending attempt waits before the screen offers the way out of a refusing page. */
        private const val HINT_AFTER_MS = 4 * 60 * 1000L
    }
}
