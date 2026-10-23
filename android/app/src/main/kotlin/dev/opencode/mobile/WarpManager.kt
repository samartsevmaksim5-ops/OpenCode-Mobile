package dev.opencode.mobile

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the Cloudflare WARP tunnel for the server's outbound traffic. wireproxy ships as libwireproxy.so (a static Go
 * binary, executed straight from the native library directory) and exposes an HTTP CONNECT proxy on localhost that the
 * Linux environment is pointed at. A reconnect picks a fresh Cloudflare egress IP, which is what brings the shared
 * anonymous free-model limits back; with a personal account connected the tunnel is pointless and stays off.
 */
class WarpManager(private val paths: Paths, private val prefs: Prefs) {
    enum class Phase { OFF, PREPARING, RUNNING, FAILED }

    data class Status(
        val phase: Phase = Phase.OFF,
        val detail: String = "",
        val rotations: Int = 0,
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

    /** True while a rotation is in progress, so a limit-triggered storm collapses into one restart. */
    @Volatile
    private var rotating = false

    /** When the tunnel was last reconnected; a second limit hit right after means the identity itself is burnt. */
    @Volatile
    private var lastRotateAt = 0L

    private val dir: File get() = File(paths.files, "warp")
    private val etcDir: File get() = File(dir, "etc")
    private val accountFile: File get() = File(etcDir, "account.json")
    private val confFile: File get() = File(etcDir, "wireproxy.conf")
    private val logFile: File get() = File(dir, "wireproxy.log")
    private val binary: File get() = File(paths.nativeLibDir, "libwireproxy.so")

    private fun update(phase: Phase, detail: String = status.detail, rotations: Int = status.rotations) {
        status = Status(phase, detail, rotations, accountFile.exists())
        listeners.forEach { it(status) }
    }

    fun listen(listener: (Status) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(status)

        return { listeners.remove(listener) }
    }

    /** WARP makes sense only for the shared anonymous pool: enabled and no personal account on the profile. */
    fun wantedFor(profile: Profile): Boolean = prefs.warpEnabled && profile.account == null && binary.exists()

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

            return null
        }

        synchronized(lock) {
            if (listening()) {
                update(Phase.RUNNING)

                return Warp.PROXY_URL
            }

            update(Phase.PREPARING, tr("Поднимаем туннель WARP...", "Bringing up the WARP tunnel..."))

            try {
                ensureAccount()
                startProcess()
                waitListening()

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

    /**
     * Reconnects the tunnel so Cloudflare hands out a different egress IP. In-flight requests die; the next message
     * from the person goes through the fresh IP. A reconnect right after the previous one did not lift the limit
     * means the identity itself has burnt limits - then a whole new WARP account is registered instead.
     */
    fun rotate(reason: String, onDone: ((Boolean) -> Unit)? = null) {
        val escalate: Boolean

        synchronized(lock) {
            if (!accountFile.exists() || rotating) {
                onDone?.invoke(false)

                return
            }

            escalate = System.currentTimeMillis() - lastRotateAt < 180_000
            lastRotateAt = System.currentTimeMillis()

            if (escalate) {
                Log.i("warp", "rotation escalated to a new identity ($reason)")
            }

            rotating = true
        }

        if (escalate) {
            Thread({
                var ok = false

                synchronized(lock) {
                    val wasRunning = listening()

                    try {
                        stopLocked()
                        accountFile.delete()
                        confFile.delete()
                        ensureAccount()

                        if (wasRunning) {
                            startProcess()
                            waitListening()
                            update(Phase.RUNNING, tr("Новый IP: учётная запись WARP пересоздана", "New IP: the WARP identity was rebuilt"), status.rotations + 1)
                        } else {
                            update(Phase.OFF, tr("Учётная запись WARP пересоздана", "The WARP identity was rebuilt"), status.rotations + 1)
                        }

                        ok = true

                        Log.i("warp", "rotated via re-register: $reason")
                    } catch (e: Exception) {
                        Log.w("warp", "rotation (re-register) failed: ${e.message}")
                        update(Phase.FAILED, e.message ?: "rotation failed", status.rotations + 1)
                    } finally {
                        rotating = false
                    }
                }

                onDone?.invoke(ok)
            }, "warp-rotate-new").apply { isDaemon = true; start() }

            return
        }

        Thread({
            var ok = false

            synchronized(lock) {
                try {
                    stopLocked()
                    startProcess()
                    waitListening()
                    ok = true

                    Log.i("warp", "rotated: $reason")
                    update(Phase.RUNNING, tr("IP обновлён", "IP refreshed"), status.rotations + 1)
                } catch (e: Exception) {
                    Log.w("warp", "rotation failed: ${e.message}")
                    update(Phase.FAILED, e.message ?: "rotation failed", status.rotations + 1)
                } finally {
                    rotating = false
                }
            }

            onDone?.invoke(ok)
        }, "warp-rotate").apply { isDaemon = true; start() }
    }

    /** Throws away the current identity and registers a new one; restarts the tunnel when it was running. */
    fun reRegister(onDone: ((Boolean) -> Unit)? = null) {
        Thread({
            var ok = false

            synchronized(lock) {
                val wasRunning = listening()

                try {
                    stopLocked()
                    accountFile.delete()
                    confFile.delete()
                    ensureAccount()

                    if (wasRunning) {
                        startProcess()
                        waitListening()
                        update(Phase.RUNNING, tr("Новая учётная запись WARP", "New WARP identity"))
                    } else {
                        update(Phase.OFF, tr("Новая учётная запись WARP", "New WARP identity"))
                    }

                    ok = true
                } catch (e: Exception) {
                    Log.w("warp", "re-register failed: ${e.message}")
                    update(Phase.FAILED, e.message ?: "re-register failed")
                }
            }

            onDone?.invoke(ok)
        }, "warp-reregister").apply { isDaemon = true; start() }
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

            return
        }

        etcDir.mkdirs()

        val (privateKey, publicKey) = Warp.keygen()
        val account = Warp.register(Warp.HttpTransport(), privateKey, publicKey)

        Warp.saveAccount(accountFile, account)
        confFile.writeText(Warp.conf(account))
        Log.i("warp", "registered identity ${account.id.ifEmpty { "?" }} endpoint=${account.endpoint}")
    }

    private fun startProcess() {
        etcDir.mkdirs()
        logFile.parentFile?.mkdirs()

        if (!binary.exists()) throw IllegalStateException(tr("Бинарник WARP отсутствует в сборке", "The WARP binary is missing from this build"))

        wantRunning = true

        val builder = ProcessBuilder(binary.path, "-c", confFile.path, "--silent")

        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        process = builder.start()

        // Watch for an early death (bad config, port busy) so the status stops pretending.
        val watcher = process

        Thread({
            val code = runCatching { watcher?.waitFor() }.getOrNull() ?: return@Thread

            if (watcher === process && wantRunning) {
                update(Phase.FAILED, tr("Туннель завершился (код ", "The tunnel exited (code ") + code + ")")
            }
        }, "warp-watch").apply { isDaemon = true; start() }
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
