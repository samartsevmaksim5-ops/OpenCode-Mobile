package dev.opencode.mobile

import android.os.Handler
import android.os.Looper
import android.os.Process as AndroidProcess
import java.io.File
import java.io.RandomAccessFile
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

    /** Status changes reach the UI through this, whatever thread moved the tunnel forward. */
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var status: Status = Status()
        private set

    private val lock = Object()

    private var process: Process? = null

    /** Bumped by every start: only the newest process's watcher owns the respawn, so two dead processes cannot race. */
    private var generation = 0L

    /** Where wireproxy's log stood when the current process started, so a failure quotes only that process. */
    private var logMark = 0L

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

        // Settings renders its whole screen on every change, so it must run on the UI thread - and a listener that
        // throws must never take the tunnel lifecycle (or the server start that called prepare) down with it. That
        // is exactly how "Can't create handler inside Thread[server-worker]" killed the first start of 1.0.7.
        for (listener in listeners) {
            val snapshot = status

            main.post {
                runCatching { listener(snapshot) }.onFailure { Log.w("warp", "status listener failed: ${it.message}") }
            }
        }
    }

    fun listen(listener: (Status) -> Unit): () -> Unit {
        listeners.add(listener)

        runCatching { listener(status) }.onFailure { Log.w("warp", "status listener failed: ${it.message}") }

        return { listeners.remove(listener) }
    }

    /** WARP is required for a local anonymous profile whenever the (explicitly enabled) setting is on. */
    fun requiredFor(profile: Profile): Boolean = prefs.warpEnabled && profile.account == null

    /** The requirement can only be met when the native tunnel binary is present. */
    fun wantedFor(profile: Profile): Boolean = requiredFor(profile) && binary.exists()

    /** True when the HTTP proxy on localhost accepts connections right now. */
    fun listening(): Boolean = portOpen(Warp.HTTP_PORT)

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
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
            // Our own live tunnel that still carries confirmed WARP traffic is adopted as is; anything else on the
            // port is replaced. An open port alone proves nothing (wireproxy listens even when the handshake never
            // completes, and a stranger may hold it).
            val own = process

            if (own != null && own.isAlive && listening() && Warp.confirmedTrace(Warp.probe())?.also { lastIp = it.ip } != null) {
                update(Phase.RUNNING)

                return Warp.PROXY_URL
            }

            // The port holder is not a working tunnel of ours: kill it. An orphaned wireproxy of an earlier app run
            // (the app died without cleanup) keeps listening forever, and every new process dies on "address already
            // in use" the moment it starts - the "tunnel exited while starting" loop of 1.0.7.
            stopAndSweep()

            update(Phase.PREPARING, tr("Поднимаем туннель WARP...", "Bringing up the WARP tunnel..."))

            try {
                ensureAccount()
                bringUp()

                Log.i("warp", "tunnel up, endpoint=${load()?.endpoint}")
                update(Phase.RUNNING, tr("WARP активен", "WARP is active"))

                return Warp.PROXY_URL
            } catch (e: Exception) {
                val detail = failureDetail(e.message ?: "failed")

                Log.w("warp", "prepare failed: $detail")
                update(Phase.FAILED, detail)
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
            if (process == null && status.phase == Phase.OFF && !portOpen(Warp.HTTP_PORT) && !portOpen(Warp.SOCKS_PORT)) return

            stopAndSweep(waitForPorts = false)
            update(Phase.OFF, reason)
        }
    }

    private fun stopLocked() {
        wantRunning = false

        runCatching { process?.destroy() }

        process = null
    }

    /** Stops the tracked tunnel and every leftover wireproxy of this installation, then waits for the ports. */
    private fun stopAndSweep(waitForPorts: Boolean = true) {
        stopLocked()

        val killed = sweep()

        if (killed > 0) Log.i("warp", "stopped $killed leftover wireproxy process(es) from earlier runs")

        if (waitForPorts) awaitPortsFree()
    }

    /** Kills every wireproxy of this app it can find in /proc, tracked or orphaned by an earlier app run. */
    private fun sweep(): Int {
        val me = AndroidProcess.myPid()

        val entries = sequence {
            for (entry in File("/proc").listFiles().orEmpty()) {
                val pid = entry.name.toIntOrNull() ?: continue
                val cmd = try {
                    File(entry, "cmdline").readBytes().toString(Charsets.ISO_8859_1)
                } catch (_: Exception) {
                    continue
                }

                yield(pid to cmd)
            }
        }

        val pids = Warp.wireproxyPids(entries, me)

        for (pid in pids) runCatching { AndroidProcess.killProcess(pid) }

        return pids.size
    }

    /** Waits until both tunnel ports are actually released; a kill is asynchronous and the bind would fail otherwise. */
    private fun awaitPortsFree(timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (!portOpen(Warp.SOCKS_PORT) && !portOpen(Warp.HTTP_PORT)) return

            Thread.sleep(150)
        }
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

        // Only what this process prints is the evidence for a failure; earlier runs stay behind the mark.
        logMark = logFile.length()

        val builder = ProcessBuilder(binary.path, "-c", confFile.path, "--silent")

        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        val started = builder.start()

        process = started

        val myGeneration = ++generation

        // Watch for an early death (bad config, port busy) so the status stops pretending - and bring the tunnel
        // back: a dead wireproxy under a live server means every model request dies with connection refused until
        // the next server restart, with nothing in the interface hinting at the real cause.
        Thread({
            val code = runCatching { started.waitFor() }.getOrNull() ?: return@Thread

            synchronized(lock) {
                if (process !== started || generation != myGeneration) return@Thread

                process = null
            }

            if (!wantRunning) {
                update(Phase.OFF, tr("Туннель остановлен", "Tunnel stopped"))

                return@Thread
            }

            val reason = tr("Туннель завершился (код ", "The tunnel exited (code ") + code + ")"
            val detail = failureDetail(reason)

            Log.w("warp", detail)
            update(Phase.FAILED, detail)

            for (attempt in 1..3) {
                Thread.sleep(if (attempt == 1) 2_000L else 10_000L)

                if (!wantRunning) return@Thread

                synchronized(lock) {
                    // A newer start owns recovery now (another respawn chain or a fresh prepare); this watcher retires.
                    if (!wantRunning || process != null || generation != myGeneration) return@Thread

                    try {
                        update(Phase.PREPARING, tr("Перезапускаю туннель...", "Restarting the tunnel..."))
                        bringUp()
                        update(Phase.RUNNING, tr("WARP активен", "WARP is active"))
                        Log.i("warp", "tunnel respawned (attempt $attempt) after exit code $code")
                    } catch (e: Exception) {
                        val failed = failureDetail(e.message ?: "respawn failed")

                        Log.w("warp", "tunnel respawn failed (attempt $attempt): $failed")
                        runCatching { process?.destroy() }
                        process = null
                        update(Phase.FAILED, failed)
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
            val p = process ?: throw IllegalStateException(tr("Туннель не запустился", "The tunnel did not start"))

            // The process is checked before the port: a stranger (or an orphan of an earlier run) holding the port
            // must not masquerade as this tunnel coming up while our process already died on the bind.
            if (!p.isAlive) throw IllegalStateException(tr("Туннель завершился при запуске", "The tunnel exited while starting"))

            if (listening()) return

            Thread.sleep(300)
        }

        throw IllegalStateException(tr("Туннель не ответил вовремя", "The tunnel did not answer in time"))
    }

    /** A failure the person can act on: the reason plus wireproxy's own words about it, from this run's log. */
    private fun failureDetail(reason: String): String {
        val tail = tailSinceMark()

        return if (tail.isBlank()) reason else "$reason\n$tail"
    }

    /** What the current process printed after [logMark], capped to a few lines from the end. */
    private fun tailSinceMark(): String = runCatching {
        if (!logFile.exists() || logFile.length() <= logMark) return ""

        RandomAccessFile(logFile, "r").use { raf ->
            raf.seek(logMark)

            val bytes = ByteArray((raf.length() - logMark).toInt())

            raf.readFully(bytes)

            Warp.logTail(bytes.toString(Charsets.UTF_8))
        }
    }.getOrDefault("")
}
