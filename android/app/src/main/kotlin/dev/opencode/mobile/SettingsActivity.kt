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
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.DateFormat
import java.util.Date

/** Native settings: profiles and sign-in, server, updates, permissions, diagnostics. */
class SettingsActivity : Activity() {
    private val app: App get() = application as App
    private lateinit var palette: Palette
    private lateinit var column: LinearLayout
    private var unsubscribeState: (() -> Unit)? = null
    private var unsubscribeUpdates: (() -> Unit)? = null
    private var unsubscribeWarp: (() -> Unit)? = null
    private var networkIpStatus = tr("Нажми «Обновить IP и геолокацию», чтобы проверить текущий маршрут.", "Tap Refresh IP and location to check the current route.")
    private var dnsTestStatus = tr("DNS-тесты выключены. Включи их, чтобы вручную проверить резолверы.", "DNS tests are off. Enable them to check resolvers manually.")
    private var networkTaskActive = false

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
        unsubscribeWarp = app.warp.listen { render() }
    }

    override fun onStop() {
        unsubscribeState?.invoke()
        unsubscribeUpdates?.invoke()
        unsubscribeWarp?.invoke()
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
        warpSection()
        networkSection()
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
                            "Без своего аккаунта лимиты бесплатных моделей считаются по IP-адресу и общие для всех, кто сидит на нём. Подключите Gmail — квота станет вашей личной. Аккаунт, созданный в браузере, начнёт работать только после входа в приложении.",
                            "Without your own account the free-model limits are counted per IP address and shared with everyone behind it. Connect Gmail to get a quota of your own. An account created in a browser only works after signing in inside the app.",
                        ),
                        12f,
                        palette.faint,
                    ),
                    Ui.params(bottom = Ui.dp(this, 8)),
                )
            }
            // The active profile's Console session is what decides whose quota the server spends. Signing out is the only
            // way to stop using an exhausted account; a new account created anywhere else does nothing until this happens.
            if (current) card.action(tr("Выйти из аккаунта", "Sign out of the account"), danger = true) { signOut() }
        }

        card.divide()
        card.action(tr("Добавить профиль", "Add a profile"), primary = true) { addProfile() }
        card.action(tr("Добавить ещё один аккаунт в этот профиль", "Add another account to this profile")) {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    /** The Cloudflare WARP tunnel: an opt-in network route for the server's outbound traffic. */
    private fun warpSection() {
        val card = section(tr("Cloudflare WARP", "Cloudflare WARP"))
        val status = app.warp.status

        card.addView(
            Ui.text(
                this,
                tr(
                    "WARP маршрутизирует исходящие запросы сервера через Cloudflare. По умолчанию выключено: квота бесплатных моделей считается по IP, а адреса выхода WARP общие для миллионов пользователей, поэтому туннель с первого же сообщения может попасть на исчерпанную квоту. Включайте, если ваш собственный адрес заблокирован или его квота уже израсходована. Это сетевой маршрут, а не способ сбросить лимит. С подключённым аккаунтом WARP не используется.",
                    "WARP routes the server's outbound requests through Cloudflare. It is off by default: the free-model quota is counted per IP, and WARP egress addresses are shared by millions of users, so a tunnel can land on a drained quota from the very first message. Enable it when your own address is blocked or its quota is spent. This is a network route, not a way to reset a limit. With a connected account WARP stays idle.",
                ),
                12f,
                palette.muted,
            ),
            Ui.params(bottom = Ui.dp(this, 8)),
        )

        card.toggle(
            tr("Использовать WARP", "Use WARP"),
            tr("Туннель поднимается вместе с сервером. Работает, только пока аккаунт не подключён.", "The tunnel starts with the server. It only runs while no account is connected."),
            app.prefs.warpEnabled,
        ) { value ->
            app.prefs.warpEnabled = value

            if (!value) app.warp.stop("turned off in settings")

            // The server reads its proxy environment once, at spawn: a toggle while it already runs silently leaves
            // the old route in place (with WARP off - requests into a dead port), so restart to apply it for real.
            if (!app.prefs.remote && ServerState.snapshot.phase == Phase.RUNNING) {
                Ui.toast(this, tr("Перезапускаю сервер, чтобы применить WARP...", "Restarting the server to apply WARP..."))
                ServerService.send(this, ServerService.ACTION_RESTART)
            }

            render()
        }

        val label = when (status.phase) {
            WarpManager.Phase.RUNNING -> tr("Туннель активен", "Tunnel active")
            WarpManager.Phase.PREPARING -> tr("Поднимается...", "Coming up...")
            WarpManager.Phase.FAILED -> tr("Ошибка", "Failed")
            WarpManager.Phase.OFF ->
                when {
                    !app.prefs.warpEnabled -> tr("Выключен", "Off")
                    app.profiles.active.account != null -> tr("Не используется: подключён аккаунт", "Not used: an account is connected")
                    !File(app.paths.nativeLibDir, "libwireproxy.so").exists() -> tr("Недоступен в этой сборке", "Not available in this build")
                    else -> tr("Поднимется при запуске сервера", "Will come up with the server")
                }
        }
        val detail = when {
            status.phase == WarpManager.Phase.FAILED && status.detail.isNotEmpty() -> "$label: ${status.detail}"
            else -> label
        }
        val badgeText = when (status.phase) {
            WarpManager.Phase.RUNNING -> tr("Вкл", "On")
            WarpManager.Phase.PREPARING -> tr("...", "...")
            WarpManager.Phase.FAILED -> tr("Ошибка", "Failed")
            WarpManager.Phase.OFF -> tr("Нет", "Off")
        }

        card.line(tr("Состояние", "State"), detail, state(badgeText, status.phase == WarpManager.Phase.RUNNING || status.phase == WarpManager.Phase.OFF))
    }

    /** Current egress and DNS diagnostics live in Settings, separate from the floating shortcut menu. */
    private fun diagnosticsProxy(): Proxy? {
        app.vpn.proxy()?.let { return it }
        if (app.warp.status.phase == WarpManager.Phase.RUNNING && app.warp.listening()) {
            return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", Warp.HTTP_PORT))
        }
        return null
    }

    private fun networkSection() {
        val card = section(tr("Сеть, IP и VPN", "Network, IP and VPN"))
        card.addView(
            Ui.text(
                this,
                tr(
                    "Проверка IP выполняется только по нажатию. Для города и региона IP отправляется в ipwho.is; DNS-тесты проверяют доступность трёх DoH-серверов, но не меняют DNS Android и по умолчанию выключены.",
                    "IP lookup runs only when requested. The public IP is sent to ipwho.is for city and region data; DNS tests check three DoH resolvers but do not change Android DNS and are off by default.",
                ),
                12f,
                palette.muted,
            ),
            Ui.params(bottom = Ui.dp(this, 8)),
        )
        card.line(tr("Текущий публичный IP и расположение", "Current public IP and location"), networkIpStatus)
        card.action(tr("Обновить IP и геолокацию", "Refresh IP and location"), primary = true) {
            if (networkTaskActive) return@action
            networkTaskActive = true
            networkIpStatus = tr("Проверяю текущий маршрут...", "Checking the current route...")
            render()
            val proxy = diagnosticsProxy()
            Thread({
                val result = NetworkDiagnostics.currentIpLocationText(proxy)
                runOnUiThread {
                    networkIpStatus = result
                    networkTaskActive = false
                    render()
                }
            }, "network-ip-check").apply { isDaemon = true; start() }
        }

        card.divide()
        card.toggle(
            tr("Разрешить ручной DNS-тест", "Enable manual DNS tests"),
            tr("По умолчанию выключено. Проверяет DNS-over-HTTPS, не меняя системные DNS-настройки.", "Off by default. Tests DNS-over-HTTPS without changing system DNS settings."),
            app.prefs.dnsDiagnosticsEnabled,
        ) { enabled ->
            app.prefs.dnsDiagnosticsEnabled = enabled
            dnsTestStatus = if (enabled) tr("Готово к проверке. Нажми кнопку ниже.", "Ready. Press the test button below.") else tr("DNS-тесты выключены.", "DNS tests are disabled.")
            render()
        }
        card.addView(
            Ui.text(
                this,
                "Cloudflare · cloudflare-dns.com\nGoogle Public DNS · dns.google\nAdGuard DNS · dns.adguard-dns.com",
                12f,
                palette.faint,
            ),
            Ui.params(top = Ui.dp(this, 5), bottom = Ui.dp(this, 5)),
        )
        card.action(tr("Проверить DNS-серверы", "Test DNS resolvers")) {
            if (!app.prefs.dnsDiagnosticsEnabled) {
                Ui.toast(this, tr("Сначала включи ручной DNS-тест", "Enable manual DNS tests first"))
                return@action
            }
            if (networkTaskActive) return@action
            networkTaskActive = true
            dnsTestStatus = tr("Проверяю резолверы...", "Testing resolvers...")
            render()
            val proxy = diagnosticsProxy()
            Thread({
                val report = NetworkDiagnostics.testDns(proxy).joinToString("\n") { it.display() }
                runOnUiThread {
                    dnsTestStatus = report
                    networkTaskActive = false
                    render()
                }
            }, "network-dns-check").apply { isDaemon = true; start() }
        }
        card.addView(Ui.text(this, dnsTestStatus, 12f, palette.muted), Ui.params(top = Ui.dp(this, 6)))
        card.addView(
            Ui.text(
                this,
                tr(
                    "Если DNS доступен, а сайты всё равно не открываются, причина может быть в маршрутизации, TLS, блокировке провайдера или самом сервисе — один DNS-тест это не исправляет.",
                    "Working DNS does not guarantee site access: routing, TLS, ISP filtering or the service itself may still cause errors.",
                ),
                12f,
                palette.faint,
            ),
            Ui.params(top = Ui.dp(this, 6)),
        )

        card.divide()
        card.toggle(
            tr("Использовать VPN-подписку", "Use VPN subscription"),
            tr("VLESS/Hysteria2 через sing-box только для локального OpenCode-сервера. По умолчанию выключено.", "VLESS/Hysteria2 via sing-box for the local OpenCode server only. Off by default."),
            app.prefs.vpnSubscriptionEnabled,
        ) { enabled ->
            if (enabled && app.prefs.remote) {
                Ui.toast(this, tr("VPN-подписка применяется только к локальному серверу", "VPN subscriptions apply only to the local server"))
                render()
                return@toggle
            }
            if (enabled && app.vpn.nodes().isEmpty()) {
                Ui.toast(this, tr("Сначала импортируй подписку или ссылку сервера", "Import a subscription or server link first"))
                render()
                return@toggle
            }
            app.prefs.vpnSubscriptionEnabled = enabled
            if (enabled) app.warp.stop("VPN subscription selected") else app.vpn.stop("VPN subscription disabled")
            if (!app.prefs.remote && ServerState.snapshot.phase == Phase.RUNNING) {
                Ui.toast(this, tr("Перезапускаю локальный сервер для применения маршрута...", "Restarting the local server to apply the route..."))
                ServerService.send(this, ServerService.ACTION_RESTART)
            }
            render()
        }
        val vpnStatus = app.vpn.status
        val vpnLabel = when (vpnStatus.phase) {
            VpnSubscriptionManager.Phase.OFF -> tr("Отключён", "Off")
            VpnSubscriptionManager.Phase.PREPARING -> tr("Подключается...", "Connecting...")
            VpnSubscriptionManager.Phase.RUNNING -> tr("Подключён", "Connected")
            VpnSubscriptionManager.Phase.FAILED -> tr("Ошибка", "Failed")
        }
        card.line(tr("Состояние VPN-подписки", "Subscription VPN status"), listOf(vpnLabel, vpnStatus.selected, vpnStatus.detail).filter { it.isNotBlank() }.distinct().joinToString(" · "), state(vpnLabel, vpnStatus.phase == VpnSubscriptionManager.Phase.RUNNING))
        card.action(tr("Импортировать HTTPS-подписку", "Import HTTPS subscription")) {
            Ui.input(this, tr("URL подписки (HTTPS)", "Subscription URL (HTTPS)"), "https://", app.prefs.vpnSubscriptionUrl) { address ->
                if (!address.startsWith("https://", ignoreCase = true)) {
                    Ui.toast(this, tr("Разрешены только HTTPS-ссылки подписки", "Only HTTPS subscription URLs are allowed"))
                    return@input
                }
                app.prefs.vpnSubscriptionUrl = address
                Ui.toast(this, tr("Загружаю подписку...", "Downloading the subscription..."))
                Thread({
                    val outcome = runCatching { app.vpn.importSubscription(address) }
                    runOnUiThread {
                        outcome.onSuccess { count -> Ui.toast(this, tr("Импортировано серверов: $count", "Imported servers: $count")) }
                            .onFailure { Ui.toast(this, tr("Импорт не удался: ${it.message}", "Import failed: ${it.message}")) }
                        render()
                    }
                }, "vpn-subscription-import").apply { isDaemon = true; start() }
            }
        }
        card.action(tr("Добавить VLESS/Hysteria2-ссылку", "Add a VLESS/Hysteria2 link")) {
            Ui.input(this, tr("Ссылка или текст подписки", "Link or subscription text"), "vless://... hysteria2://...", "") { content ->
                val outcome = runCatching { app.vpn.importText(content) }
                outcome.onSuccess { count -> Ui.toast(this, tr("Импортировано серверов: $count", "Imported servers: $count")) }
                    .onFailure { Ui.toast(this, tr("Не удалось разобрать: ${it.message}", "Could not parse: ${it.message}")) }
                render()
            }
        }

        val nodes = app.vpn.nodes()
        card.line(tr("Серверы в подписке", "Servers in subscription"), tr("Всего: ${nodes.size}; поддерживаются VLESS и Hysteria2", "Total: ${nodes.size}; VLESS and Hysteria2 are supported"))
        val selectedKey = app.prefs.vpnSelectedNode
        if (nodes.isNotEmpty()) card.action(tr("Выбрать сервер из всей подписки", "Choose from all subscription servers")) {
            Ui.choose(this, tr("VPN-серверы", "VPN servers"), nodes.map { "${it.name} (${it.protocol} · ${it.host}:${it.port})" }) { index ->
                app.vpn.select(nodes[index].key)
                if (app.prefs.vpnSubscriptionEnabled && !app.prefs.remote && ServerState.snapshot.phase == Phase.RUNNING) {
                    ServerService.send(this, ServerService.ACTION_RESTART)
                }
                render()
            }
        }
        for (node in nodes.take(12)) {
            val selected = node.key == selectedKey || (selectedKey.isBlank() && node == nodes.firstOrNull())
            card.line(node.name, "${node.protocol} · ${node.host}:${node.port}", state(if (selected) tr("Выбран", "Selected") else tr("Выбрать", "Select"), selected)) {
                app.vpn.select(node.key)
                if (app.prefs.vpnSubscriptionEnabled && !app.prefs.remote && ServerState.snapshot.phase == Phase.RUNNING) {
                    ServerService.send(this, ServerService.ACTION_RESTART)
                }
                render()
            }
        }
        if (nodes.size > 12) card.addView(Ui.text(this, tr("Показаны первые 12 серверов. Остальные сохранены.", "Showing the first 12 servers. The others are saved."), 12f, palette.faint), Ui.params(top = Ui.dp(this, 5)))
        card.action(tr("Проверить выбранный VPN-сервер", "Test the selected VPN server")) {
            if (!app.prefs.vpnSubscriptionEnabled) {
                Ui.toast(this, tr("Включи VPN-подписку, чтобы подключить и проверить сервер", "Enable the VPN subscription to connect and test a server"))
                return@action
            }
            if (networkTaskActive) return@action
            networkTaskActive = true
            networkIpStatus = tr("Подключаю выбранный сервер и проверяю внешний IP...", "Connecting and checking the egress IP...")
            render()
            Thread({
                val result = runCatching {
                    val address = app.vpn.prepare()
                    val port = address?.substringAfterLast(':')?.toIntOrNull() ?: VpnSubscriptionManager.PORT
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                    NetworkDiagnostics.currentIpLocationText(proxy)
                }.getOrElse { tr("Проверка не удалась", "Test failed") + ": " + (it.message ?: it.javaClass.simpleName) }
                runOnUiThread {
                    networkIpStatus = result
                    networkTaskActive = false
                    render()
                }
            }, "vpn-node-test").apply { isDaemon = true; start() }
        }
    }

    /**
     * Disconnects the Console account the active profile is signed in with: the stored OAuth session is wiped from
     * auth.json, the server restarts so no old token stays in memory, and the person is offered to sign in with a
     * different account at once. Pasted API keys (connectors) are kept.
     */
    private fun signOut() {
        val active = app.profiles.active
        val who = active.account

        Ui.confirm(
            this,
            tr("Выйти из аккаунта", "Sign out of the account"),
            if (who == null) {
                tr(
                    "Сервер забудет сохранённый вход, и его квота перестанет действовать. Ключи коннекторов сохранятся, сервер перезапустится.",
                    "The server will forget the stored sign-in and its quota will stop applying. Connector keys are kept, the server restarts.",
                )
            } else {
                tr(
                    "Аккаунт $who отключится: его квота и модели Zen перестанут работать. Ключи коннекторов сохранятся, сервер перезапустится.",
                    "The account $who will be disconnected: its quota and Zen models stop working. Connector keys are kept, the server restarts.",
                )
            },
            tr("Выйти", "Sign out"),
        ) {
            val home = Profiles.homeDir(app.paths.profilesDir, active)

            // Write auth.json synchronously BEFORE the restart: the fresh server must not read the old session back,
            // or the account would reappear from the still-valid tokens and the sign-out would look like a no-op.
            runCatching { OpencodeStore.signOut(home) }
            app.prefs.signedIn = false
            app.profiles.setAccount(active.id, null)
            app.prefs.loginSkipped = true
            ServerService.send(this, ServerService.ACTION_RESTART)
            Ui.toast(this, tr("Аккаунт отключён", "Account signed out"))
            render()

            Ui.confirm(
                this,
                tr("Войти другим аккаунтом", "Sign in with another account"),
                tr(
                    "Откроется вход через Google в чистом окне - войдите тем аккаунтом, чья квота нужна. Без этого приложение продолжит работать без квоты.",
                    "Google sign-in opens in a clean window - sign in with the account whose quota you need. Otherwise the app keeps working without a quota.",
                ),
                tr("Войти", "Sign in"),
            ) { startActivity(Intent(this, LoginActivity::class.java)) }
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
                    Fs.deleteTree(app.paths.networkDir)
                    app.paths.rootfsStamp.delete()
                    app.paths.profilesFile.delete()
                    runCatching { app.vpn.stop("erase all app data") }
                    runCatching { app.warp.stop("erase all app data") }
                    // The dialog promises a full wipe; leftover prefs would silently restore remote mode, compat,
                    // banner state and the rest of it on the next launch.
                    app.prefs.clearAll()
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
