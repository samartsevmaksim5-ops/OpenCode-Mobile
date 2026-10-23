package dev.opencode.mobile

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Native settings: profiles and sign-in, server, updates, permissions, diagnostics. */
class SettingsActivity : Activity() {
    private val app: App get() = application as App
    private lateinit var palette: Palette
    private lateinit var column: LinearLayout
    private var unsubscribeState: (() -> Unit)? = null
    private var unsubscribeUpdates: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        palette = Palette(this)
        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        column = Ui.vertical(this).apply { setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 8), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 32)) }

        val top = Ui.horizontal(this).apply { setPadding(Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 4)) }

        top.addView(Ui.iconButton(this, R.drawable.ic_back, tr("Назад", "Back"), palette, tint = palette.text) { finish() })
        top.addView(Ui.text(this, tr("Настройки", "Settings"), 20f, palette.text, bold = true), Ui.params(Ui.WRAP, Ui.WRAP, left = Ui.dp(this, 8)))

        val page = Ui.vertical(this)

        page.addView(top, Ui.params())
        page.addView(column, Ui.params())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            addView(page)
        })
    }

    override fun onStart() {
        super.onStart()
        unsubscribeState = ServerState.listen { render() }
        unsubscribeUpdates = Updates.listen { render() }
    }

    override fun onStop() {
        unsubscribeState?.invoke()
        unsubscribeUpdates?.invoke()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // Building blocks
    private fun section(title: String): LinearLayout {
        column.addView(
            Ui.text(this, title.uppercase(), 12f, palette.faint, bold = true).apply { letterSpacing = 0.08f },
            Ui.params(top = Ui.dp(this, 22), bottom = Ui.dp(this, 8), left = Ui.dp(this, 4)),
        )

        val card = Ui.card(this, palette)

        column.addView(card, Ui.params())

        return card
    }

    private fun LinearLayout.line(title: String, subtitle: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null) {
        val row = Ui.horizontal(this@SettingsActivity).apply {
            minimumHeight = Ui.dp(this@SettingsActivity, 48)

            if (onClick != null) {
                background = Ui.ripple(this@SettingsActivity, Ui.rounded(this@SettingsActivity, android.graphics.Color.TRANSPARENT, 8f), palette)
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        val texts = Ui.vertical(this@SettingsActivity)

        texts.addView(Ui.text(this@SettingsActivity, title, 15f, palette.text))

        if (!subtitle.isNullOrEmpty()) texts.addView(Ui.text(this@SettingsActivity, subtitle, 12f, palette.muted).apply { setPadding(0, Ui.dp(this@SettingsActivity, 2), 0, 0) })

        row.addView(texts, Ui.params(0, Ui.WRAP, weight = 1f))

        if (trailing != null) row.addView(trailing)

        addView(row, Ui.params(bottom = Ui.dp(this@SettingsActivity, 2)))
    }

    private fun LinearLayout.toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val box = Switch(this@SettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onChange(value) }
        }

        line(title, subtitle, box) { box.toggle() }
    }

    private fun LinearLayout.action(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
        addView(Ui.button(this@SettingsActivity, label, palette, primary, danger, onClick), Ui.params(top = Ui.dp(this@SettingsActivity, 8)))
    }

    private fun LinearLayout.divide() = addView(Ui.divider(this@SettingsActivity, palette))

    private fun state(text: String, good: Boolean) = Ui.text(this, text, 12f, if (good) palette.ok else palette.faint, bold = true)

    // Screen
    private fun render() {
        column.removeAllViews()

        profilesSection()
        extensionsSection()
        serverSection()
        updatesSection()
        permissionsSection()
        diagnosticsSection()
        aboutSection()
    }

    private fun profilesSection() {
        val card = section(tr("Профили и вход", "Profiles and sign-in"))
        val active = app.profiles.active

        card.addView(
            Ui.text(
                this,
                tr(
                    "Каждый профиль хранит свои сессии, ключи, коннекторы и плагины отдельно. Обычно профиль соответствует одному аккаунту Google.",
                    "Each profile keeps its own sessions, keys, connectors and plugins. A profile usually matches one Google account.",
                ),
                12f,
                palette.muted,
            ),
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        for (profile in app.profiles.all) {
            val current = profile.id == active.id && !app.prefs.remote
            val badge = if (current) state(tr("Активен", "Active"), true) else null

            card.line(profile.title, if (profile.account != null) profile.name else tr("Аккаунт не подключён", "No account connected"), badge) {
                profileMenu(profile)
            }

            // A profile without an account rides the shared anonymous pool: one heavy user anywhere drains it for
            // everyone. The way out is a personal account, so say so right where the empty state is.
            if (current && profile.account == null) {
                card.addView(
                    Ui.text(
                        this,
                        tr(
                            "Без своего аккаунта лимиты бесплатных моделей общие для всех, и их можно израсходовать. Подключите Gmail — квота станет вашей личной.",
                            "Without your own account the free-model limits are shared by everyone and can run out. Connect Gmail to get a quota of your own.",
                        ),
                        12f,
                        palette.faint,
                    ),
                    Ui.params(bottom = Ui.dp(this, 8)),
                )
            }
        }

        card.divide()
        card.action(tr("Добавить профиль", "Add a profile"), primary = true) { addProfile() }
        card.action(tr("Добавить ещё один аккаунт в этот профиль", "Add another account to this profile")) {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun profileMenu(profile: Profile) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val isActive = profile.id == app.profiles.active.id

        if (!isActive) items += tr("Переключиться", "Switch to it") to { switchTo(profile) }

        items += tr("Войти через Google в этом профиле", "Sign in with Google in this profile") to {
            if (isActive) startActivity(Intent(this, LoginActivity::class.java)) else switchTo(profile, thenLogin = true)
        }
        items += tr("Переименовать", "Rename") to {
            Ui.input(this, tr("Название профиля", "Profile name"), profile.name, profile.name) { name ->
                app.profiles.rename(profile.id, name)
                render()
            }
        }

        if (app.profiles.all.size > 1 && !isActive) items += tr("Удалить вместе с данными", "Delete with its data") to { deleteProfile(profile) }

        Ui.choose(this, profile.title, items.map { it.first }) { items[it].second() }
    }

    private fun addProfile() {
        if (app.profiles.all.size >= Profiles.MAX_PROFILES) {
            Ui.toast(this, tr("Достигнут предел числа профилей", "The profile limit is reached"))

            return
        }

        Ui.input(this, tr("Название нового профиля", "New profile name"), tr("Например, Работа", "For example, Work"), tr("Профиль ", "Profile ") + (app.profiles.all.size + 1)) { name ->
            val profile = app.profiles.create(name)

            // Start the new environment first; signing in is optional and available from the profile menu afterwards.
            switchTo(profile, confirm = false)
        }
    }

    private fun switchTo(profile: Profile, thenLogin: Boolean = false, confirm: Boolean = true) {
        val go = {
            ServerService.send(this, ServerService.ACTION_SWITCH, profile.id)

            if (thenLogin) startActivity(Intent(this, LoginActivity::class.java))

            finish()
        }

        if (!confirm || ServerState.snapshot.phase != Phase.RUNNING) {
            go()

            return
        }

        Ui.confirm(
            this,
            tr("Сменить профиль", "Switch profile"),
            tr("Сервер текущего профиля остановится, выполняемые задачи прервутся.", "The current profile's server will stop and running tasks will be interrupted."),
            tr("Сменить", "Switch"),
        ) { go() }
    }

    private fun deleteProfile(profile: Profile) {
        Ui.confirm(
            this,
            tr("Удалить профиль", "Delete profile"),
            tr("Сессии, ключи и файлы профиля «${profile.name}» будут удалены без возможности восстановления.", "Sessions, keys and files of \"${profile.name}\" will be deleted for good."),
            tr("Удалить", "Delete"),
        ) {
            if (app.profiles.remove(profile.id)) {
                Thread { Fs.deleteTree(File(app.paths.profilesDir, profile.id)) }.apply { isDaemon = true }.start()
                render()
            }
        }
    }

    private fun extensionsSection() {
        val card = section(tr("Коннекторы и плагины", "Connectors and plugins"))

        card.line(
            tr("Коннекторы", "Connectors"),
            tr("Ключи провайдеров и своё API", "Provider keys and custom APIs"),
            null,
        ) { startActivity(Intent(this, ConnectorsActivity::class.java)) }
        card.line(
            tr("Плагины", "Plugins"),
            tr("Из файлов, по ссылкам и npm", "From files, links and npm"),
            null,
        ) { startActivity(Intent(this, PluginsActivity::class.java)) }
        card.line(
            tr("Правила (README.md)", "Rules (README.md)"),
            tr("Инструкции для всех моделей или для одной", "Instructions for all models or for one"),
            null,
        ) { startActivity(Intent(this, RulesActivity::class.java)) }
    }

    private fun serverSection() {
        val card = section(tr("Сервер", "Server"))
        val snap = ServerState.snapshot
        val label = when (snap.phase) {
            Phase.RUNNING -> tr("Работает", "Running")
            Phase.STARTING, Phase.INSTALLING -> tr("Запускается", "Starting")
            Phase.FAILED -> tr("Ошибка запуска", "Failed to start")
            Phase.STOPPED -> tr("Остановлен", "Stopped")
            Phase.IDLE -> tr("Не запущен", "Not started")
        }

        card.line(
            if (app.prefs.remote) tr("Удалённый сервер", "Remote server") else tr("Локальный сервер", "Local server"),
            if (snap.baseUrl.isNotEmpty()) Urls.displayHost(snap.baseUrl) else null,
            state(label, snap.phase == Phase.RUNNING),
        )
        card.toggle(
            tr("Подключаться к удалённому серверу", "Connect to a remote server"),
            tr("Вместо сервера на этом телефоне используется ваш сервер по адресу и паролю.", "Use your own server by address and password instead of the one on this phone."),
            app.prefs.remote,
        ) { value ->
            app.prefs.remote = value
            ServerService.send(this, ServerService.ACTION_RESTART)
            render()
        }

        if (app.prefs.remote) {
            card.line(tr("Адрес сервера", "Server address"), app.prefs.remoteUrl.ifEmpty { tr("не задан", "not set") }) {
                Ui.input(this, tr("Адрес сервера", "Server address"), "192.168.1.10:4096", app.prefs.remoteUrl) { value ->
                    app.prefs.remoteUrl = value
                    render()
                }
            }
            card.line(tr("Пароль сервера", "Server password"), if (app.prefs.remotePassword.isEmpty()) tr("не задан", "not set") else "••••••") {
                Ui.input(this, tr("Пароль сервера", "Server password"), "", "", password = true) { value ->
                    app.prefs.remotePassword = value
                    render()
                }
            }
        }

        card.divide()
        card.toggle(
            tr("Режим совместимости", "Compatibility mode"),
            tr("Включите, если сервер падает при запуске. Работает медленнее.", "Turn on if the server crashes on start. Slower."),
            app.prefs.compat,
        ) { app.prefs.compat = it }
        card.toggle(
            tr("Доступ к общей памяти", "Shared storage access"),
            tr("Папки /sdcard и /storage/emulated/0/Download внутри Linux. Применяется при перезапуске.", "Folders /sdcard and /storage/emulated/0/Download inside Linux. Applies on restart."),
            app.prefs.bindStorage,
        ) { app.prefs.bindStorage = it }
        card.toggle(
            tr("Не засыпать при работе в фоне", "Stay awake in the background"),
            tr("Удерживает процессор, пока работает сервер. Расходует заряд.", "Keeps the CPU on while the server runs. Uses battery."),
            app.prefs.keepAwake,
        ) { app.prefs.keepAwake = it }
        card.toggle(
            tr("Уведомления о задачах", "Task notifications"),
            tr("Сообщать, когда задача завершилась или нужно разрешение.", "Tell me when a task finishes or needs permission."),
            app.prefs.notifyEnabled,
        ) { app.prefs.notifyEnabled = it }
        card.toggle(
            tr("Плавающая кнопка меню", "Floating menu button"),
            tr("Быстрый доступ к профилям, предпросмотру и настройкам.", "Quick access to profiles, preview and settings."),
            app.prefs.floatingMenu,
        ) { app.prefs.floatingMenu = it }

        card.action(tr("Перезапустить сервер", "Restart the server")) {
            ServerService.send(this, ServerService.ACTION_RESTART)
            finish()
        }
        card.action(tr("Остановить сервер", "Stop the server")) {
            ServerService.send(this, ServerService.ACTION_STOP)
            render()
        }
    }

    private fun updatesSection() {
        val card = section(tr("Обновления", "Updates"))
        val u = Updates.snapshot
        val staged = u.stagedVersion ?: app.ota.stagedVersion

        card.line(
            "OpenCode " + app.ota.current,
            tr("Приложение ", "App ") + BuildConfig.VERSION_NAME + if (BuildConfig.UPSTREAM_REF != "unknown") "  ·  " + BuildConfig.UPSTREAM_REF.take(7) else "",
        )

        val message = when {
            u.phase == UpdatePhase.CHECKING -> tr("Проверка...", "Checking...")
            u.phase == UpdatePhase.DOWNLOADING -> tr("Загрузка и проверка ", "Downloading and verifying ") + u.message + if (u.progress >= 0) " (${u.progress}%)" else ""
            u.phase == UpdatePhase.ERROR -> u.message
            staged != null -> tr("OpenCode $staged загружен и проверен. Включится при перезапуске сервера.", "OpenCode $staged is downloaded and verified. It turns on when the server restarts.")
            u.available != null -> availableText(u.available)
            u.checked -> tr("Установлена последняя версия.", "You are up to date.")
            app.ota.lastCheck > 0 -> tr("Последняя проверка: ", "Last checked: ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(app.ota.lastCheck))
            else -> tr("Обновления ещё не проверялись.", "Updates have not been checked yet.")
        }

        card.addView(
            Ui.text(this, message, 13f, if (u.phase == UpdatePhase.ERROR) palette.danger else palette.muted),
            Ui.params(top = Ui.dp(this, 8)),
        )

        val busy = u.phase == UpdatePhase.CHECKING || u.phase == UpdatePhase.DOWNLOADING
        val found = u.available

        if (!busy) {
            card.action(tr("Проверить обновления", "Check for updates"), primary = found == null && staged == null) { Updates.check(app) }

            if (found != null && found.runtime != null && staged == null) {
                card.action(tr("Скачать сейчас", "Download now"), primary = true) { Updates.fetchRuntime(app, force = true) }
            }

            if (found != null && found.app != null) {
                card.action(tr("Обновить приложение", "Update the app"), primary = found.runtime == null) { Updates.installApp(app) }
            }

            if (staged != null) {
                card.action(tr("Перезапустить сервер и применить", "Restart the server and apply"), primary = true) {
                    Ui.confirm(
                        this,
                        tr("Применить обновление", "Apply the update"),
                        tr("Сервер перезапустится, выполняемые задачи прервутся. Данные профиля сохраняются, при сбое версия откатится.", "The server restarts and running tasks are interrupted. Profile data is saved first, and a failed start rolls back."),
                        tr("Применить", "Apply"),
                    ) {
                        ServerService.send(this, ServerService.ACTION_APPLY)
                        finish()
                    }
                }
            }
        }

        card.divide()
        card.toggle(
            tr("Загружать обновления автоматически", "Download updates automatically"),
            tr("Проверка раз в 6 часов, пока сервер работает. Включаются только при следующем запуске сервера.", "Checked every 6 hours while the server runs. They only turn on at the next server start."),
            app.prefs.otaAuto,
        ) { app.prefs.otaAuto = it }
        card.toggle(tr("Только по Wi-Fi", "Wi-Fi only"), null, app.prefs.otaWifiOnly) { app.prefs.otaWifiOnly = it }
        card.line(tr("Источник обновлений", "Update source"), app.prefs.otaRepo.ifEmpty { tr("не задан", "not set") }) {
            Ui.input(this, tr("Репозиторий GitHub (владелец/имя)", "GitHub repository (owner/name)"), "owner/opencode-android", app.prefs.otaRepoOverride) { value ->
                if (value.isEmpty() || GithubReleases.valid(value)) {
                    app.prefs.otaRepoOverride = value
                    render()
                } else {
                    Ui.toast(this, tr("Нужен формат владелец/имя", "Use the form owner/name"))
                }
            }
        }
    }

    private fun availableText(a: CheckResult.Available): String {
        val version = a.runtime?.manifest?.upstreamVersion

        return when {
            version != null && a.app != null -> tr("Доступны OpenCode $version и новая версия приложения.", "OpenCode $version and a new app version are available.")
            version != null -> tr("Доступна версия OpenCode $version.", "OpenCode $version is available.")
            else -> tr("Доступна новая версия приложения.", "A new app version is available.")
        }
    }

    private fun permissionsSection() {
        val card = section(tr("Разрешения", "Permissions"))
        val notifications = Permissions.notificationsGranted(this)
        val battery = Permissions.batteryUnrestricted(this)
        val files = Permissions.allFilesGranted()
        val install = Permissions.canInstallPackages(this)

        card.line(tr("Уведомления", "Notifications"), tr("О завершении задач и запросах разрешений", "Finished tasks and permission requests"), state(if (notifications) tr("Вкл", "On") else tr("Выкл", "Off"), notifications)) {
            if (Build.VERSION.SDK_INT >= 33 && !notifications) Permissions.requestNotifications(this, 30) else Permissions.openNotificationSettings(this)
        }
        card.line(tr("Работа в фоне", "Background work"), tr("Без ограничений батареи сервер не засыпает", "Without battery limits the server does not sleep"), state(if (battery) tr("Вкл", "On") else tr("Выкл", "Off"), battery)) {
            Permissions.requestBatteryUnrestricted(this)
        }
        card.line(tr("Доступ ко всем файлам", "All files access"), tr("Нужен, чтобы агент писал в /storage/emulated/0/Download напрямую", "Lets the agent write to /storage/emulated/0/Download directly"), state(if (files) tr("Вкл", "On") else tr("Выкл", "Off"), files)) {
            Permissions.requestAllFiles(this)
        }
        card.line(tr("Установка обновлений приложения", "Installing app updates"), null, state(if (install) tr("Вкл", "On") else tr("Выкл", "Off"), install)) {
            Permissions.requestInstallPackages(this)
        }
    }

    private fun diagnosticsSection() {
        val card = section(tr("Диагностика", "Diagnostics"))

        card.action(tr("Показать журнал", "Show logs")) { LogsDialog.show(this) }
        card.action(tr("Переустановить Linux-окружение", "Reinstall the Linux runtime")) {
            Ui.confirm(
                this,
                tr("Переустановить окружение", "Reinstall the runtime"),
                tr("Пакеты, установленные внутри Linux (apt, pip), будут удалены. Профили и файлы проектов в профилях сохранятся.", "Packages installed inside Linux (apt, pip) are removed. Profiles and their project files are kept."),
                tr("Переустановить", "Reinstall"),
            ) {
                ServerService.send(this, ServerService.ACTION_STOP)
                Thread {
                    Thread.sleep(1500)
                    Fs.deleteTree(app.paths.rootfs)
                    app.paths.rootfsStamp.delete()
                    runOnUiThread {
                        ServerService.send(this, ServerService.ACTION_START)
                        finish()
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        card.action(tr("Стереть все данные приложения", "Erase all app data"), danger = true) {
            Ui.confirm(
                this,
                tr("Стереть всё", "Erase everything"),
                tr("Будут удалены все профили, сессии, ключи, загруженные обновления и Linux-окружение. Файлы в Download останутся.", "All profiles, sessions, keys, downloaded updates and the Linux runtime are deleted. Files in Download stay."),
                tr("Стереть", "Erase"),
            ) {
                ServerService.send(this, ServerService.ACTION_STOP)
                Thread {
                    Thread.sleep(1500)
                    Fs.deleteTree(app.paths.profilesDir)
                    Fs.deleteTree(app.paths.rootfs)
                    Fs.deleteTree(app.paths.ota)
                    app.paths.rootfsStamp.delete()
                    app.paths.profilesFile.delete()
                    runOnUiThread { finishAffinity() }
                }.apply { isDaemon = true }.start()
            }
        }
    }

    private fun aboutSection() {
        val card = section(tr("О приложении", "About"))

        card.addView(
            Ui.text(
                this,
                tr(
                    "Интерфейс и сервер OpenCode (лицензия MIT) работают на этом телефоне внутри Linux-окружения на основе proot (лицензия GPL). Сборка идёт в GitHub Actions из исходников OpenCode.",
                    "The OpenCode interface and server (MIT license) run on this phone inside a Linux environment built on proot (GPL license). The app is built by GitHub Actions from the OpenCode sources.",
                ),
                12f,
                palette.muted,
            ),
            Ui.params(),
        )
        card.addView(
            Ui.text(this, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  ·  ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}", 12f, palette.faint).apply { gravity = Gravity.START },
            Ui.params(top = Ui.dp(this, 8)),
        )
    }

    @Suppress("unused")
    private fun TextView.dim() = setTextColor(palette.faint)
}
