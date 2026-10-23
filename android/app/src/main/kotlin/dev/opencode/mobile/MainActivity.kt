package dev.opencode.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class MainActivity : Activity(), UiHost {
    lateinit var palette: Palette
        private set

    lateinit var web: WebHost
        private set

    lateinit var keys: KeysBar
        private set

    val app: App get() = application as App

    private lateinit var content: LinearLayout
    private lateinit var previewLayer: FrameLayout
    private lateinit var startup: StartupView
    private lateinit var previews: PreviewManager
    private lateinit var handle: ImageView
    private lateinit var root: FrameLayout

    /** The surface overlays (the update banner) are added to, above everything else. */
    fun overlaySurface(): FrameLayout = root

    private var unsubscribe: (() -> Unit)? = null
    private var loadedGeneration = -1
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    /** The address the app's own page is served from. Links to it stay in the main web view. */
    var origin: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.setStage("MainActivity.onCreate: loading palette")

        palette = Palette(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        runCatching { applyBars(Ui.isNight(this), String.format("#%06X", 0xFFFFFF and palette.bg)) }

        Log.setStage("MainActivity.onCreate: creating WebView")
        web = WebHost(this)
        Log.setStage("MainActivity.onCreate: creating keyboard bar")
        keys = KeysBar(this, palette) { web.view }

        root = FrameLayout(this).apply { setBackgroundColor(palette.bg) }
        content = Ui.vertical(this)
        content.addView(web.view, Ui.params(Ui.MATCH, 0, weight = 1f))
        content.addView(keys, Ui.params(Ui.MATCH, Ui.WRAP))
        root.addView(content, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        previewLayer = FrameLayout(this)
        root.addView(previewLayer, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        Log.setStage("MainActivity.onCreate: creating preview manager")
        previews = PreviewManager(this, previewLayer, palette)

        Log.setStage("MainActivity.onCreate: creating floating menu")
        handle = buildHandle()
        root.addView(handle, FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
        handle.visibility = if (app.prefs.floatingMenu) View.VISIBLE else View.GONE

        Log.setStage("MainActivity.onCreate: creating startup screen")
        startup = StartupView(this, palette, object : StartupView.Actions {
            override fun retry() = this@MainActivity.retry()

            override fun enableCompat() {
                app.prefs.compat = true
                ServerService.send(this@MainActivity, ServerService.ACTION_RESTART)
            }

            override fun showLogs() = LogsDialog.show(this@MainActivity)

            override fun openSettings() = startActivity(Intent(this@MainActivity, SettingsActivity::class.java))

            override fun start() = ServerService.send(this@MainActivity, ServerService.ACTION_START)
        })
        root.addView(startup, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        startup.progress(tr("Подготовка...", "Preparing..."), StartupView.INDETERMINATE)

        Log.setStage("MainActivity.onCreate: attaching UI")
        setContentView(root)
        UiBus.host = this

        Log.setStage("MainActivity.onCreate: requesting permissions")
        requestFirstPermissions()
        handleIntent(intent)
        Log.setStage("MainActivity.onCreate: starting server service")
        ServerService.send(this)
        unsubscribe = ServerState.listen { onState(it) }

        if (!app.prefs.signedIn && !app.prefs.loginSkipped) startActivity(Intent(this, LoginActivity::class.java))
        Log.setStage("Application active")

        // If Android refused to launch the crash screen while the previous process was dying, retry now that
        // a foreground Activity exists. CrashActivity clears the saved report only after it has displayed it.
        Log.pendingCrashReport(this)?.let { CrashActivity.show(this, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        web.view.onResume()
    }

    override fun onResume() {
        super.onResume()
        handle.visibility = if (app.prefs.floatingMenu) View.VISIBLE else View.GONE

        if (UiBus.reloadRequested) {
            UiBus.reloadRequested = false
            val snap = ServerState.snapshot

            if (snap.phase == Phase.RUNNING) web.open(snap.baseUrl, currentPassword())
        }

        app.syncAccountAsync()
    }

    override fun onStop() {
        web.view.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        unsubscribe?.invoke()

        if (UiBus.host === this) UiBus.host = null

        previews.destroy()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (previews.onBack()) return

        if (startup.visibility != View.VISIBLE && web.view.canGoBack()) {
            web.view.goBack()

            return
        }

        // Leaving with Back keeps the server running in the background.
        moveTaskToBack(true)
    }

    // Server state
    private fun onState(s: Snapshot) {
        // The listener runs on the main thread; an error while applying a state must not take the app down.
        try {
            applyState(s)
        } catch (t: Throwable) {
            Log.e("main", "could not apply the server state", t)

            runCatching { startup.failed(tr("Внутренняя ошибка приложения: ", "Internal app error: ") + (t.message ?: t.javaClass.simpleName), false) }
        }
    }

    private fun applyState(s: Snapshot) {
        when (s.phase) {
            Phase.IDLE -> startup.progress(tr("Подготовка...", "Preparing..."), StartupView.INDETERMINATE)
            Phase.INSTALLING -> startup.progress(s.message, if (s.progress >= 0) s.progress else StartupView.INDETERMINATE)
            Phase.STARTING -> startup.progress(s.message, StartupView.INDETERMINATE)
            Phase.FAILED -> startup.failed(s.error.orEmpty(), s.suggestCompat)
            Phase.STOPPED -> startup.stopped()
            Phase.RUNNING -> {
                origin = s.baseUrl

                if (!app.prefs.signedIn && !app.prefs.loginSkipped) {
                    startActivity(Intent(this, LoginActivity::class.java))
                }

                if (s.generation != loadedGeneration) {
                    loadedGeneration = s.generation
                    startup.loading()
                    web.open(s.baseUrl, currentPassword())

                    if (app.prefs.signedIn) firstRun()
                }
            }
        }
    }

    private fun retry() {
        val s = ServerState.snapshot

        if (s.phase == Phase.RUNNING) {
            startup.loading()
            web.open(s.baseUrl, currentPassword())
        } else {
            ServerService.send(this, ServerService.ACTION_RESTART)
        }
    }

    /** Prompt shown once for keeping the app running with the screen off. Sign-in is never forced. */
    private fun firstRun() {
        if (!app.prefs.seen("battery") && !Permissions.batteryUnrestricted(this)) {
            app.prefs.markSeen("battery")
            Ui.confirm(
                this,
                tr("Работа в фоне", "Running in the background"),
                tr(
                    "Чтобы OpenCode не останавливался, когда вы выходите из приложения, разрешите ему работать без ограничений батареи.",
                    "To keep OpenCode running when you leave the app, let it run without battery restrictions.",
                ),
                tr("Разрешить", "Allow"),
            ) { Permissions.requestBatteryUnrestricted(this) }
        }
    }

    fun currentPassword(): String = if (app.prefs.remote) app.prefs.remotePassword else app.prefs.password

    // Page callbacks
    fun onPageReady() {
        if (ServerState.snapshot.phase == Phase.RUNNING) startup.hide()

        // The emergency-update card appears once the person is actually looking at the interface.
        if (UpdateBanner.shouldShow(this)) {
            UpdateBanner.show(this) { }
        }
    }

    fun onPageProgress(percent: Int) {
        if (percent >= 70 && ServerState.snapshot.phase == Phase.RUNNING && web.view.url?.let { isInternal(it) } == true) startup.hide()
    }

    fun onPageError(message: String) {
        startup.failed(tr("Страница не загрузилась: ", "The page did not load: ") + message, false)
    }

    fun rebuildWebView() {
        content.removeView(web.view)

        val fresh = web.recreate()

        content.addView(fresh, 0, Ui.params(Ui.MATCH, 0, weight = 1f))
        startup.loading()
        web.open(ServerState.snapshot.baseUrl, currentPassword())
    }

    // Links
    private fun reservedPorts(): Set<Int> =
        app.profiles.all.map { it.port }.toSet() + setOfNotNull(UiBus.bridgePort.takeIf { it > 0 }) + 4090

    fun isReserved(url: String): Boolean {
        val uri = Urls.parse(url) ?: return true

        return Urls.isLoopback(uri.host) && Urls.port(uri) in reservedPorts()
    }

    fun isInternal(url: String) = Urls.classify(url, origin, reservedPorts()) == Target.INTERNAL

    fun isInternalHost(host: String): Boolean {
        val own = Urls.parse(origin)?.host ?: return false

        return host.equals(own, true) || (Urls.isLoopback(host) && Urls.isLoopback(own))
    }

    /** Decides where a link goes. Returns true when it has been taken care of, false to let the main page load it. */
    fun route(url: String): Boolean = when (Urls.classify(url, origin, reservedPorts())) {
        Target.INTERNAL -> false
        Target.PREVIEW -> {
            showPreview(url)
            true
        }
        Target.EXTERNAL -> {
            openExternal(url)
            true
        }
        Target.IGNORE -> true
    }

    override fun showPreview(url: String) {
        val target = Urls.normalizePreview(url) ?: return

        if (isReserved(target)) return

        previews.show(target)
    }

    override fun openExternal(url: String) {
        val scheme = Urls.parse(url)?.scheme

        if (scheme == "http" || scheme == "https") {
            Browser.open(this, url)

            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w("main", "nothing can open $url")
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return

        intent.getStringExtra(EXTRA_PREVIEW)?.let {
            showPreview(it)
            intent.removeExtra(EXTRA_PREVIEW)
        }

        intent.getStringExtra(EXTRA_EXTERNAL)?.let {
            openExternal(it)
            intent.removeExtra(EXTRA_EXTERNAL)
        }

        if (intent.action == ServerService.ACTION_RESTART) ServerService.send(this, ServerService.ACTION_RESTART)
    }

    // Files and permissions
    fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>) {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback

        try {
            startActivityForResult(params.createIntent(), REQ_FILES)
        } catch (e: Exception) {
            fileCallback = null
            callback.onReceiveValue(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_FILES) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    /** The page may use the microphone (voice input). Nothing else is ever granted. */
    fun onWebPermission(request: PermissionRequest) {
        val ownPage = isInternal(request.origin.toString().trimEnd('/') + "/")
        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        if (!ownPage || !wantsMic) {
            request.deny()

            return
        }

        if (Permissions.microphoneGranted(this)) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingPermission?.deny()
            pendingPermission = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_AUDIO) {
            val request = pendingPermission

            pendingPermission = null

            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                request?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request?.deny()
            }
        }
    }

    private fun requestFirstPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && !app.prefs.seen("notifications") && !Permissions.notificationsGranted(this)) {
            app.prefs.markSeen("notifications")
            Permissions.requestNotifications(this, REQ_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT < 29 && !app.prefs.seen("storage") &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            app.prefs.markSeen("storage")
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    // Appearance
    /** Colours the system bars like the page, so the app looks like one surface. */
    fun applyBars(dark: Boolean, hex: String) {
        val color = try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            return
        }

        window.statusBarColor = color
        window.navigationBarColor = color

        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

            flags = if (dark) flags and light.inv() else flags or light

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    // The floating menu button
    @SuppressLint("ClickableViewAccessibility")
    private fun buildHandle(): ImageView {
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(palette.muted)
            val pad = Ui.dp(this@MainActivity, 9)

            setPadding(pad, pad, pad, pad)
            background = Ui.rounded(this@MainActivity, palette.layer, 20f, palette.border)
            alpha = 0.85f
            elevation = Ui.dp(this@MainActivity, 4).toFloat()
            contentDescription = tr("Меню приложения", "App menu")
        }

        var startX = 0f
        var startY = 0f
        var originX = 0f
        var originY = 0f
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = v.x
                    originY = v.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - startX) + Math.abs(event.rawY - startY) > Ui.dp(this, 8)) moved = true

                    if (moved) {
                        v.x = (originX + event.rawX - startX).coerceIn(0f, (root.width - v.width).toFloat())
                        v.y = (originY + event.rawY - startY).coerceIn(0f, (root.height - v.height).toFloat())
                    }

                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // Stick to the nearer side edge and remember where it was left.
                        v.animate().x(if (v.x + v.width / 2f < root.width / 2f) 0f else (root.width - v.width).toFloat()).setDuration(120).start()
                        app.prefs.setMenuPosition(if (v.x + v.width / 2f < root.width / 2f) 0f else 1f, (v.y / root.height).coerceIn(0f, 1f))
                    } else {
                        showMenu()
                    }

                    true
                }
                else -> true
            }
        }

        view.post {
            val (px, py) = app.prefs.menuPosition()

            view.x = if (px <= 0.5f) 0f else (root.width - view.width).toFloat()
            view.y = (root.height * py).coerceIn(0f, (root.height - view.height).toFloat())
        }

        return view
    }

    private fun showMenu() {
        val profileTitle = app.profiles.active.title
        val items = ArrayList<Pair<String, () -> Unit>>()

        items += tr("Профиль: ", "Profile: ") + profileTitle to { chooseProfile() }

        val account = app.profiles.active.account

        if (account.isNullOrBlank()) {
            items += tr("Войти через Google", "Sign in with Google") to { startActivity(Intent(this, LoginActivity::class.java)) }
        } else {
            items += tr("Аккаунт: ", "Account: ") + account to { startActivity(Intent(this, SettingsActivity::class.java)) }
        }

        items += tr("Коннекторы и модели", "Connectors and models") to { startActivity(Intent(this, ConnectorsActivity::class.java)) }
        items += tr("Плагины и правила", "Plugins and rules") to { startActivity(Intent(this, PluginsActivity::class.java)) }
        items += tr("Открыть адрес в окне предпросмотра", "Open an address in a preview window") to {
            Ui.input(this, tr("Адрес или порт", "Address or port"), "localhost:5173") { value -> if (value.isNotEmpty()) showPreview(value) }
        }

        if (previews.isOpen) items += tr("Показать окно предпросмотра", "Show the preview window") to { previews.restore() }

        items += (if (keys.showing()) tr("Скрыть клавиши терминала", "Hide terminal keys") else tr("Показать клавиши терминала", "Show terminal keys")) to {
            keys.force(if (keys.showing()) false else true)
        }
        items += tr("Обновить страницу", "Reload the page") to { retry() }
        items += tr("Перезапустить сервер", "Restart the server") to { ServerService.send(this, ServerService.ACTION_RESTART) }
        items += tr("Настройки", "Settings") to { startActivity(Intent(this, SettingsActivity::class.java)) }

        Ui.choose(this, "OpenCode", items.map { it.first }) { items[it].second() }
    }

    private fun chooseProfile() {
        val list = app.profiles.all
        val labels = list.map { (if (it.id == app.profiles.active.id) "• " else "   ") + it.title }.toMutableList()

        labels += tr("Профили и вход через Google...", "Profiles and Google sign-in...")

        Ui.choose(this, tr("Профили", "Profiles"), labels) { index ->
            if (index == list.size) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else if (list[index].id != app.profiles.active.id) {
                Ui.confirm(
                    this,
                    tr("Сменить профиль", "Switch profile"),
                    tr("Сервер текущего профиля остановится, выполняемые задачи прервутся.", "The current profile's server will stop and running tasks will be interrupted."),
                    tr("Сменить", "Switch"),
                ) { ServerService.send(this, ServerService.ACTION_SWITCH, list[index].id) }
            }
        }
    }

    companion object {
        const val EXTRA_PREVIEW = "preview_url"
        const val EXTRA_EXTERNAL = "external_url"

        private const val REQ_FILES = 1
        private const val REQ_AUDIO = 2
        private const val REQ_NOTIFICATIONS = 3
        private const val REQ_STORAGE = 4
    }
}
