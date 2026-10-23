package dev.opencode.mobile

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Keeps OpenCode alive when the app is not on screen: it owns the Linux server process, the loopback bridge for
 * commands inside it, the event stream that produces notifications, and the periodic update check.
 */
class ServerService : Service() {
    private val app: App get() = application as App
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "server-worker") }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var server: ServerProcess

    private var bridge: BridgeServer? = null
    private var watcher: EventWatcher? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val crashes = ArrayDeque<Long>()

    /** Bumped on every start and stop so a watchdog or timer from an earlier run can tell it is stale. */
    @Volatile
    private var epoch = 0

    private val updateTick = object : Runnable {
        override fun run() {
            Updates.auto(app)
            main.postDelayed(this, 6 * 60 * 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.setStage("ServerService.onCreate: creating process controller")

        server = ServerProcess(app.paths)
        running = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Everything the worker thread runs goes through here: an uncaught error there would kill the whole process, so
     * it is turned into a failed state the screen can show instead.
     */
    private fun run(work: () -> Unit) {
        worker.execute {
            Log.setStage("ServerService: background worker")
            try {
                work()
            } catch (t: Throwable) {
                Log.e("service", "fatal error inside the service", t)

                val profile = runCatching { app.profiles.active }.getOrElse { null }

                runCatching { fail(tr("Внутренняя ошибка приложения: ", "Internal app error: ") + (t.message ?: t.javaClass.simpleName), false, profile) }
            } finally {
                Log.setStage("Application active")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.setStage("ServerService.onStartCommand: foreground notification")
        try {
            val notification = Notifier.server(this, "OpenCode", tr("Запуск...", "Starting..."))

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_SERVER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_SERVER, notification)
            }
        } catch (e: Exception) {
            // Android refuses to start a foreground service from the background; the next visit to the app starts it.
            Log.w("service", "could not enter the foreground: ${e.message}")
            stopSelf()

            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> run {
                shutdown()
                main.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_SWITCH -> {
                intent.getStringExtra(EXTRA_PROFILE)?.let { app.profiles.setActive(it) }
                run { begin(apply = false) }
            }
            ACTION_RESTART -> run { begin(apply = false) }
            ACTION_APPLY -> run { begin(apply = true) }
            else -> run { ensureRunning() }
        }

        Log.setStage("Application active")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        epoch++
        main.removeCallbacksAndMessages(null)
        watcher?.stop()
        bridge?.stop()
        releaseLocks()
        worker.shutdownNow()

        // The server belongs to this service; leaving it running without a notification would be a hidden process.
        Thread {
            runCatching { server.stop() }.onFailure { Log.w("service", "could not stop the server: ${it.message}") }
        }.start()

        super.onDestroy()
    }

    // Lifecycle
    private fun baseFor(profile: Profile) = "http://127.0.0.1:${profile.port}"

    private fun begin(apply: Boolean) {
        if (app.prefs.remote) startRemote() else startLocal(apply)
    }

    private fun ensureRunning() {
        val snap = ServerState.snapshot

        if (snap.phase == Phase.RUNNING || snap.phase == Phase.STARTING || snap.phase == Phase.INSTALLING) return

        if (app.prefs.remote) {
            startRemote()

            return
        }

        val profile = app.profiles.active

        // The service can be recreated while the server is still up; take it over instead of starting a second one.
        if (server.health(baseFor(profile), app.prefs.password) == 200 && server.prootPids().isNotEmpty()) {
            Log.i("service", "adopting a running server for ${profile.id}")
            bridge = bridge ?: startBridge()
            onRunning(profile, baseFor(profile), app.prefs.password, local = true)
        } else {
            startLocal(apply = false)
        }
    }

    private fun startLocal(apply: Boolean) {
        val my = ++epoch
        var profile = app.profiles.active
        val password = app.prefs.password

        quiet()
        publish(Phase.STARTING, tr("Подготовка...", "Preparing..."), profile)

        try {
            if (app.runtime.needsInstall()) {
                publish(Phase.INSTALLING, tr("Распаковка Linux-окружения. Это нужно один раз.", "Unpacking the Linux runtime. This happens once."), profile, 0)
                app.runtime.install { p -> publish(Phase.INSTALLING, tr("Распаковка Linux-окружения. Это нужно один раз.", "Unpacking the Linux runtime. This happens once."), profile, p) }
            }

            if (!app.runtime.ready()) {
                throw IOException(tr("Linux-окружение не установлено. Соберите приложение через GitHub Actions (см. README).", "The Linux runtime is not installed. Build the app with GitHub Actions (see the README)."))
            }
        } catch (e: Exception) {
            Log.e("service", "runtime install failed", e)
            fail(e.message ?: e.javaClass.simpleName, false, profile)

            return
        }

        if (my != epoch) return

        app.runtime.writeFakeProc()

        val bridgeUrl = (bridge ?: startBridge()).also { bridge = it }.url

        server.killOrphans()

        // Every profile gets a port of its own. When something else on the phone already sits on it, the next
        // free port is taken and remembered, so the server starts anyway and the port stays individual.
        var port = pickPort(profile)

        if (port == null) {
            fail(
                tr(
                    "Не нашлось свободного порта для сервера (искали от ${profile.port} и выше). Похоже, другое приложение занимает соседние порты.",
                    "No free port was left for the server (looked from ${profile.port} upward). Another app seems to hold the ports near it.",
                ),
                false,
                profile,
            )

            return
        }

        if (port != profile.port) profile = rememberPort(profile, port)

        var base = baseFor(profile)

        var otaDir = app.ota.prepareStart(profile, apply)
        var attempt = 0
        var shifts = 0

        while (true) {
            val config = server.config(app.prefs, profile, bridgeUrl, otaDir)
            val process = try {
                server.spawn(config)
            } catch (e: Exception) {
                Log.e("service", "could not start the server", e)
                fail(tr("Не удалось запустить сервер: ", "Could not start the server: ") + (e.message ?: ""), false, profile)

                return
            }

            publish(Phase.STARTING, tr("Запуск сервера...", "Starting the server..."), profile)

            val healthy = server.waitHealthy(base, password, 180_000, { process.isAlive }) { ms ->
                if (ms > 20_000 && ms % 5_000 < 600) {
                    publish(Phase.STARTING, tr("Сервер запускается, это может занять до минуты...", "The server is starting, this can take up to a minute..."), profile)
                }
            }

            if (my != epoch) return

            if (healthy) break

            val exit = if (process.isAlive) null else process.exitValue()

            server.stop()

            // Someone took the port between the check and the bind; move on to the next free one instead of failing.
            if (server.portWasBusy() && shifts < MAX_PORT_SHIFTS) {
                val next = pickPort(profile, port)

                if (next != null) {
                    Log.w("service", "port $port was taken during startup, moving to $next")

                    shifts++
                    port = next
                    profile = rememberPort(profile, port)
                    base = baseFor(profile)

                    continue
                }
            }

            val (hint, compat) = server.failureHint(exit)

            Log.w("service", "server did not become healthy (exit=$exit): ${hint.lines().firstOrNull().orEmpty()}")

            if (app.ota.trial && attempt == 0) {
                val bad = app.ota.failTrial(profile)

                Log.w("service", "rolled back $bad")
                Notifier.update(this, tr("Новая версия сервера не запустилась, возвращена предыдущая.", "The new server build did not start, the previous one was restored."))
                otaDir = app.ota.prepareStart(profile, apply = false)
                attempt++

                continue
            }

            fail(hint, compat, profile)

            return
        }

        onRunning(profile, base, password, local = true)
    }

    /**
     * The port this profile's server listens on: its own saved one, or the next free port when something else on the
     * phone already holds it. Ports saved by other profiles and the bridge's own are never taken. Null when every
     * port nearby is busy.
     */
    private fun pickPort(profile: Profile, after: Int? = null): Int? {
        val taken = app.profiles.all.filter { it.id != profile.id }.map { it.port }.toHashSet()
        val bridgePort = UiBus.bridgePort

        if (bridgePort > 0) taken += bridgePort

        return PortPicker.pick(after?.plus(1) ?: profile.port) { candidate -> candidate in taken || !PortPicker.free(candidate) }
    }

    /** Records a moved port as this profile's own, so it stays individual across restarts. */
    private fun rememberPort(profile: Profile, port: Int): Profile {
        if (port != profile.port) {
            app.profiles.setPort(profile.id, port)
            Log.i("service", "profile ${profile.id} listens on port $port now")
        }

        return profile.copy(port = port)
    }

    private fun startRemote() {
        val my = ++epoch
        val profile = Profile("remote", "Remote", null, 0, 0)

        quiet()
        server.stop()

        val base = Urls.normalizeServer(app.prefs.remoteUrl)

        if (base == null) {
            fail(tr("Адрес удалённого сервера не задан или неверен", "The remote server address is missing or invalid"), false, profile)

            return
        }

        publish(Phase.STARTING, tr("Подключение к ", "Connecting to ") + Urls.displayHost(base), profile)

        var code = -1

        repeat(8) {
            if (code != 200 && code != 401) {
                code = server.health(base, app.prefs.remotePassword)

                if (code != 200 && code != 401) Thread.sleep(1500)
            }
        }

        if (my != epoch) return

        when (code) {
            200 -> onRunning(profile, base, app.prefs.remotePassword, local = false)
            401 -> fail(tr("Сервер отклонил пароль", "The server rejected the password"), false, profile)
            else -> fail(tr("Сервер недоступен: ", "The server is unreachable: ") + Urls.displayHost(base), false, profile)
        }
    }

    private fun onRunning(profile: Profile, base: String, password: String, local: Boolean) {
        val my = epoch
        val next = ServerState.snapshot.generation + 1

        ServerState.update {
            it.copy(
                phase = Phase.RUNNING,
                message = "",
                progress = -1,
                profileId = profile.id,
                port = Urls.parse(base)?.let { uri -> Urls.port(uri) } ?: 0,
                baseUrl = base,
                generation = next,
                error = null,
                suggestCompat = false,
            )
        }

        status(if (local) tr("Сервер работает: ", "Server is running: ") + profile.title else tr("Подключено к ", "Connected to ") + Urls.displayHost(base))
        acquireLocks()

        watcher = EventWatcher(this, base, password, { app.prefs.notifyEnabled }, { app.inForeground }).also { it.start() }

        if (local) {
            watchdog(my, base, password)

            // A new build only becomes the trusted one after it has stayed up for a while.
            main.postDelayed({
                if (epoch == my) run { if (epoch == my && server.health(base, password) == 200) app.ota.confirm() }
            }, 25_000)

            app.syncAccountAsync()
            main.removeCallbacks(updateTick)
            main.post(updateTick)
        }
    }

    private fun watchdog(my: Int, base: String, password: String) {
        Thread({
            var misses = 0

            while (epoch == my) {
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                if (epoch != my) return@Thread

                if (server.health(base, password) == 200) {
                    misses = 0
                } else if (++misses >= 3) {
                    run { onCrash(my) }

                    return@Thread
                }
            }
        }, "server-watchdog").apply { isDaemon = true; start() }
    }

    private fun onCrash(my: Int) {
        if (epoch != my) return

        val now = System.currentTimeMillis()

        crashes.addLast(now)

        while (crashes.isNotEmpty() && now - crashes.first() > 5 * 60_000) crashes.removeFirst()

        val profile = app.profiles.active

        Log.w("service", "the server stopped answering (${crashes.size} in 5 minutes)")

        if (app.ota.trial) {
            app.ota.failTrial(profile)
            Notifier.update(this, tr("Новая версия сервера работала нестабильно, возвращена предыдущая.", "The new server build was unstable, the previous one was restored."))
        }

        if (crashes.size > 3) {
            val (hint, compat) = server.failureHint(null)

            epoch++
            quiet()
            server.stop()
            fail(tr("Сервер постоянно падает. ", "The server keeps crashing. ") + hint, compat, profile)

            return
        }

        startLocal(apply = false)
    }

    private fun shutdown() {
        epoch++
        quiet()
        server.stop()
        bridge?.stop()
        bridge = null
        releaseLocks()
        ServerState.update { it.copy(phase = Phase.STOPPED, message = "", progress = -1, error = null) }
    }

    /** Stops what belongs to one run of the server without touching the bridge. */
    private fun quiet() {
        watcher?.stop()
        watcher = null
        main.removeCallbacks(updateTick)
        server.stop()
    }

    // State and notification
    private fun publish(phase: Phase, message: String, profile: Profile, progress: Int = -1) {
        ServerState.update { it.copy(phase = phase, message = message, progress = progress, profileId = profile.id, error = null, suggestCompat = false) }
        status(message)
    }

    private fun fail(message: String, suggestCompat: Boolean, profile: Profile?) {
        ServerState.update {
            it.copy(phase = Phase.FAILED, message = "", progress = -1, profileId = profile?.id.orEmpty(), error = message, suggestCompat = suggestCompat)
        }
        status(tr("Ошибка запуска", "Failed to start"))
        releaseLocks()
    }

    private fun status(text: String) {
        // A notification the device refuses must never take the process down.
        runCatching { getSystemService(NotificationManager::class.java).notify(Notifier.ID_SERVER, Notifier.server(this, "OpenCode", text)) }
            .onFailure { Log.w("service", "could not update the notification: ${it.message}") }
    }

    private fun acquireLocks() {
        if (!app.prefs.keepAwake) return

        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wifi == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF

            wifi = applicationContext.getSystemService(Context.WIFI_SERVICE).let { it as WifiManager }.createWifiLock(mode, "opencode:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        wake = null
        wifi = null
    }

    // Bridge to the Linux side
    private fun startBridge(): BridgeServer {
        val handler = object : BridgeHandler {
            override fun preview(url: String): String? {
                val host = UiBus.host

                if (host != null) main.post { host.showPreview(url) }

                if (host == null || !app.inForeground) Notifier.openLink(this@ServerService, url, preview = true)

                return null
            }

            override fun open(url: String): String? {
                val host = UiBus.host

                if (host != null && app.inForeground) main.post { host.openExternal(url) } else Notifier.openLink(this@ServerService, url, preview = false)

                return null
            }

            override fun notify(title: String, text: String) {
                Notifier.activity(this@ServerService, title + text, title.ifEmpty { "OpenCode" }, text)
            }

            override fun save(path: String, name: String): String {
                val profile = app.profiles.active
                val shared = if (app.prefs.bindStorage) File("/storage/emulated/0") else null
                val guest = GuestPaths(app.paths.rootfs, Profiles.homeDir(app.paths.profilesDir, profile), shared)
                val file = guest.resolve(path) ?: throw IOException(tr("Файл не найден: ", "File not found: ") + path)
                val display = name.ifEmpty { file.name }
                val ext = display.substringAfterLast('.', "").lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
                val writer = Downloads.Writer(this@ServerService, display, mime)

                try {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)

                        while (true) {
                            val n = input.read(buffer)

                            if (n < 0) break

                            writer.write(buffer, 0, n)
                        }
                    }

                    return writer.finish()
                } catch (e: Exception) {
                    writer.abort()

                    throw if (e is IOException) e else IOException(e.message ?: "could not save the file")
                }
            }

            override fun version() = BuildConfig.VERSION_NAME

            override fun widgetSet(name: String, title: String, text: String, url: String, pin: Boolean, src: String, w: Int, h: Int): String =
                Widgets.set(this@ServerService, WidgetSpec(name, title, text, url, src, w, h), pin)

            override fun widgetRemove(name: String): Boolean = Widgets.remove(this@ServerService, name)

            override fun widgetList(): String = Widgets.describe(this@ServerService)
        }

        return BridgeServer(app.prefs.bridgeToken, handler, 4090).also {
            it.start()
            UiBus.bridgePort = it.port
        }
    }

    companion object {
        const val ACTION_START = "dev.opencode.mobile.START"

        /** How many times a start may move to the next port after the server found its port taken. */
        private const val MAX_PORT_SHIFTS = 4
        const val ACTION_STOP = "dev.opencode.mobile.STOP"
        const val ACTION_RESTART = "dev.opencode.mobile.RESTART"
        const val ACTION_APPLY = "dev.opencode.mobile.APPLY_UPDATE"
        const val ACTION_SWITCH = "dev.opencode.mobile.SWITCH"
        const val EXTRA_PROFILE = "profile"

        @Volatile
        var running = false
            private set

        fun send(context: Context, action: String = ACTION_START, profileId: String? = null) {
            val intent = Intent(context, ServerService::class.java).setAction(action)

            if (profileId != null) intent.putExtra(EXTRA_PROFILE, profileId)

            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("service", "could not start the service: ${e.message}")
            }
        }
    }
}
