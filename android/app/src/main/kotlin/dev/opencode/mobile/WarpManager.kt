package dev.opencode.mobile

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs a verified Cloudflare WARP tunnel for the server's outbound traffic. wireproxy ships as libwireproxy.so (a
 * static Go binary, executed straight from the native library directory) and exposes an HTTP CONNECT proxy on
 * localhost that the Linux environment is pointed at. The tunnel is a network route, not a quota mechanism: the
 * anonymous free-model limits are counted per IP and WARP egress addresses are shared, so nothing here reacts to
 * quota errors - the only honest answers to a spent limit are waiting for the reset or a personal account.
 */
class WarpManager(private val paths: Paths, private val prefs: Prefs) {
    enum class Phase { OFF, PREPARING, RUNNING, FAILED }

    data class Status(
        val phase: Phase = Phase.OFF,
        val detail: String = "",
        val hasAccount: Boolean = false,
    )

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

    @Volatile
    var status: Status = Status()
        private set

    private val lock = Object()

    private var process: Process? = null

    @Volatile
    private var wantRunning = false

    /** Egress IP seen by the last confirmed probe. */
    @Volatile
    private var lastIp: String? = null

    private val dir: File get() = File(paths.files, "warp")
    private val etcDir: File get() = File(dir, "etc")
    private val accountFile: File get() = File(etcDir, "account.json")
    private val confFile: File get() = File(etcDir, "wireproxy.conf")
    private val logFile: File get() = File(dir, "wireproxy.log")
    private val binary: File get() = File(paths.nativeLibDir, "libwireproxy.so")

    private fun update(phase: Phase, detail: String = status.detail) {
        status = Status(phase, detail, accountFile.exists())
        listeners.forEach { it(status) }
    }

    fun listen(listener: (Status) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(status)

        return { listeners.remove(listener) }
    }

    /** WARP is required for a local anonymous profile whenever the (explicitly enabled) setting is on. */
    fun requiredFor(profile: Profile): Boolean = prefs.warpEnabled && profile.account == null

    /** The requirement can only be met when the native tunnel binary is present. */
    fun wantedFor(profile: Profile): Boolean = requiredFor(profile) && binary.exists()

    /** True when the HTTP proxy on localhost accepts connections right now. */
    fun listening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", Warp.HTTP_PORT), 400) }
        true
    }.getOrDefault(false)

    /**
     * Called on the server-start thread: makes sure the tunnel is up when the profile wants it and reports the proxy
     * address for the server environment, or null when the server must reach the internet directly. Never throws.
     */
    fun prepare(profile: Profile): String? {
        if (!wantedFor(profile)) {
            stop("WARP is off for this profile")

            if (requiredFor(profile)) {
                update(Phase.FAILED, tr("В сборке отсутствует бинарник WARP", "The WARP binary is missing from this build"))
            }

            return null
        }

        synchronized(lock) {
            // An open port is not a working tunnel (wireproxy listens even when the handshake never completes, and a
            // stranger may hold the port): only a request Cloudflare confirms as WARP-carried counts.
            if (listening() && Warp.confirmedTrace(Warp.probe())?.also { lastIp = it.ip } != null) {
                update(Phase.RUNNING)

                return Warp.PROXY_URL
            }

            if (process != null) {
                stopLocked()
                Thread.sleep(500)
            }

            update(Phase.PREPARING, tr("Поднимаем туннель WARP...", "Bringing up the WARP tunnel..."))

            try {
                ensureAccount()
                bringUp()

                Log.i("warp", "tunnel up, endpoint=${load()?.endpoint}")
                update(Phase.RUNNING, tr("WARP активен", "WARP is active"))

                return Warp.PROXY_URL
            } catch (e: Exception) {
                Log.w("warp", "prepare failed: ${e.message}")
                update(Phase.FAILED, e.message ?: "failed")
                runCatching { process?.destroy() }
                process = null

                return null
            }
        }
    }

    /** Starts the tunnel if it is not running. Safe to call from the UI; the work happens on a worker thread. */
    fun startAsync(profile: Profile) {
        Thread({ prepare(profile) }, "warp-start").apply { isDaemon = true; start() }
    }

    /** Stops the tunnel; called when a profile with an account goes active or the service shuts down. */
    fun stop(reason: String) {
        synchronized(lock) {
            if (process == null && status.phase == Phase.OFF) return

            stopLocked()
            update(Phase.OFF, reason)
        }
    }

    private fun stopLocked() {
        wantRunning = false

        runCatching { process?.destroy() }

        process = null
    }

    private fun load(): Warp.Account? = Warp.loadAccount(accountFile)

    private fun ensureAccount() {
        val existing = load()

        if (existing != null && confFile.exists()) {
            confFile.writeText(Warp.conf(existing))
            restrictToOwner(confFile)

            return
        }

        etcDir.mkdirs()

        val (privateKey, publicKey) = Warp.keygen()
        val account = Warp.register(Warp.HttpTransport(), privateKey, publicKey)

        Warp.saveAccount(accountFile, account)
        confFile.writeText(Warp.conf(account))
        restrictToOwner(accountFile)
        restrictToOwner(confFile)
        Log.i("warp", "registered identity ${account.id.ifEmpty { "?" }} endpoint=${account.endpoint}")
    }

    /** The WireGuard private key lives in these files; keep them owner-only as an explicit guarantee. */
    private fun restrictToOwner(file: File) {
        runCatching {
            android.system.Os.chmod(file.absolutePath, 384) // 0600
        }.onFailure { Log.w("warp", "could not chmod ${file.name}: ${it.message}") }
    }

    private fun startProcess() {
        etcDir.mkdirs()
        logFile.parentFile?.mkdirs()

        // The log grows forever otherwise; keep it around one megabyte, like the server log.
        runCatching {
            if (logFile.length() > 1_000_000) {
                logFile.renameTo(File(dir, "wireproxy.log.1"))
            }
        }

        if (!binary.exists()) throw IllegalStateException(tr("Бинарник WARP отсутствует в сборке", "The WARP binary is missing from this build"))

        wantRunning = true

        val builder = ProcessBuilder(binary.path, "-c", confFile.path, "--silent")

        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        process = builder.start()

        // Watch for an early death (bad config, port busy) so the status stops pretending - and bring the tunnel
        // back: a dead wireproxy under a live server means every model request dies with connection refused until
        // the next server restart, with nothing in the interface hinting at the real cause.
        val watcher = process

        Thread({
            val code = runCatching { watcher?.waitFor() }.getOrNull() ?: return@Thread

            if (watcher !== process) return@Thread

            synchronized(lock) { if (watcher === process) process = null }

            if (!wantRunning) {
                update(Phase.OFF, tr("Туннель остановлен", "Tunnel stopped"))

                return@Thread
            }

            update(Phase.FAILED, tr("Туннель завершился (код ", "The tunnel exited (code ") + code + ")")

            for (attempt in 1..3) {
                Thread.sleep(if (attempt == 1) 2_000L else 10_000L)

                if (!wantRunning) return@Thread

                synchronized(lock) {
                    if (!wantRunning || process != null) return@synchronized

                    try {
                        update(Phase.PREPARING, tr("Перезапускаю туннель...", "Restarting the tunnel..."))
                        bringUp()
                        update(Phase.RUNNING, tr("WARP активен", "WARP is active"))
                        Log.i("warp", "tunnel respawned (attempt $attempt) after exit code $code")
                    } catch (e: Exception) {
                        Log.w("warp", "tunnel respawn failed (attempt $attempt): ${e.message}")
                        runCatching { process?.destroy() }
                        process = null
                        update(Phase.FAILED, e.message ?: "respawn failed")
                    }
                }

                if (status.phase == Phase.RUNNING) return@Thread
            }
        }, "warp-watch").apply { isDaemon = true; start() }
    }

    private fun bringUp() {
        startProcess()
        waitListening()
        verifyTunnel()
    }

    /** Proves the tunnel carries traffic and remembers its egress IP, so a rotation can tell whether the IP changed. */
    private fun verifyTunnel() {
        var trace: Warp.Trace? = null

        for (attempt in 1..3) {
            trace = Warp.confirmedTrace(Warp.probe())

            if (trace != null) break

            val p = process

            if (p != null && !p.isAlive) throw IllegalStateException(tr("Туннель завершился при запуске", "The tunnel exited while starting"))

            if (attempt < 3) Thread.sleep(2_000)
        }

        val found = trace ?: throw IllegalStateException(
            tr(
                "Туннель поднят, но трафик не подтверждён Cloudflare (возможно, сеть блокирует WireGuard)",
                "The tunnel is up but Cloudflare did not confirm traffic (the network may block WireGuard)",
            ),
        )

        lastIp = found.ip
    }

    private fun waitListening(timeoutMs: Long = 12_000) {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (listening()) return

            val p = process ?: throw IllegalStateException(tr("Туннель не запустился", "The tunnel did not start"))

            if (!p.isAlive) throw IllegalStateException(tr("Туннель завершился при запуске", "The tunnel exited while starting"))

            Thread.sleep(300)
        }

        throw IllegalStateException(tr("Туннель не ответил вовремя", "The tunnel did not answer in time"))
    }
}
