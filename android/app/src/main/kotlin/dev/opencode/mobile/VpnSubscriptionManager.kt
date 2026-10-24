package dev.opencode.mobile

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled sing-box CLI (libsingbox.so, a static build pinned in runtime/fetch-sing-box.sh) as a localhost
 * mixed HTTP/SOCKS proxy that carries only the local OpenCode server's outbound traffic. Android's own network is
 * untouched: no VpnService permission is requested, nothing outside the app is routed through the proxy.
 */
class VpnSubscriptionManager(private val paths: Paths, private val prefs: Prefs) {
    enum class Phase { OFF, PREPARING, RUNNING, FAILED }

    data class Status(val phase: Phase = Phase.OFF, val detail: String = "", val selected: String = "")

    @Volatile
    var status: Status = Status()
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    private var activeKey: String? = null

    private val lock = Any()

    private val binary: File get() = File(paths.nativeLibDir, "libsingbox.so")

    val proxyUrl: String get() = "http://127.0.0.1:$PORT"

    fun isRunning(): Boolean = status.phase == Phase.RUNNING && listening()

    fun listening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 350) }
        true
    }.getOrDefault(false)

    fun nodes(): List<VpnSubscriptionParser.Node> = runCatching {
        if (!paths.vpnNodesFile.exists()) emptyList()
        else VpnSubscriptionParser.parseSubscriptionText(paths.vpnNodesFile.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    fun selectedNode(): VpnSubscriptionParser.Node? {
        val all = nodes()
        return all.firstOrNull { it.key == prefs.vpnSelectedNode } ?: all.firstOrNull()
    }

    fun select(key: String) {
        val node = nodes().firstOrNull { it.key == key } ?: return
        prefs.vpnSelectedNode = node.key
    }

    /** Imports either a single link, a multi-link text block, or a base64 subscription body. */
    fun importText(text: String): Int {
        val imported = VpnSubscriptionParser.parseSubscriptionText(text)
        synchronized(lock) {
            val existing = nodes()
            val merged = (existing + imported).distinctBy { it.key }.take(200)
            paths.networkDir.mkdirs()
            paths.vpnNodesFile.writeText(merged.joinToString("\n") { it.rawUri } + "\n", Charsets.UTF_8)
            if (merged.none { it.key == prefs.vpnSelectedNode }) prefs.vpnSelectedNode = imported.first().key
            return imported.size
        }
    }

    /** Fetches an HTTPS subscription with bounded size, timeout, and HTTPS-only redirects. */
    fun importSubscription(address: String): Int {
        var current = URL(address.trim())
        require(current.protocol.equals("https", true) && current.host.isNotBlank() && current.userInfo == null) {
            "Укажи HTTPS-ссылку подписки без логина и пароля в адресе"
        }
        for (redirect in 0..3) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "text/plain, application/octet-stream, */*")
                connection.setRequestProperty("User-Agent", "OpenCode-Mobile network settings")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("redirect без Location")
                    val next = URL(current, location)
                    if (!next.protocol.equals("https", true)) throw IOException("HTTP-перенаправление запрещено")
                    current = next
                    continue
                }
                if (code !in 200..299) throw IOException("подписка ответила HTTP $code")
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SUBSCRIPTION_BYTES) throw IOException("подписка больше 2 МБ")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                return importText(bytes.toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("слишком много перенаправлений подписки")
    }

    /**
     * Called by the local-server worker before OpenCode starts. Null when no subscription is selected; otherwise the
     * proxy URL, or an exception the caller must surface instead of silently falling back to a direct connection.
     */
    fun prepare(): String? {
        if (!prefs.vpnSubscriptionEnabled) return null
        synchronized(lock) {
            val node = selectedNode() ?: throw IOException(tr("Сначала импортируй VPN-подписку или VLESS/Hysteria2-ссылку", "Import a VPN subscription or a VLESS/Hysteria2 link first"))
            if (!binary.isFile) throw IOException(tr("В сборке отсутствует sing-box; пересобери APK с сетевым модулем", "sing-box is missing from this build; rebuild the APK with the network module"))
            if (process?.isAlive == true && activeKey == node.key && listening() && probeProxy()) {
                status = Status(Phase.RUNNING, tr("Туннель активен", "The tunnel is active"), node.name)
                return proxyUrl
            }
            stopLocked()
            status = Status(Phase.PREPARING, tr("Проверяю конфигурацию...", "Checking the configuration..."), node.name)
            paths.networkDir.mkdirs()
            paths.vpnConfigFile.writeText(VpnSubscriptionParser.singBoxConfig(node, PORT), Charsets.UTF_8)
            paths.vpnLogFile.parentFile?.mkdirs()

            // sing-box itself validates the config first: a bad node fails here with its own diagnosis instead of
            // as an opaque dead proxy later.
            val checker = ProcessBuilder(binary.absolutePath, "check", "-c", paths.vpnConfigFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.vpnLogFile))
                .start()
            if (!checker.waitFor(15, TimeUnit.SECONDS)) {
                checker.destroyForcibly()
                status = Status(Phase.FAILED, tr("Проверка конфигурации заняла слишком много времени", "The configuration check took too long"), node.name)
                throw IOException(status.detail)
            }
            if (checker.exitValue() != 0) {
                status = Status(Phase.FAILED, tr("sing-box отклонил конфигурацию; подробности в журнале приложения", "sing-box rejected the configuration; see the app log for details"), node.name)
                throw IOException(status.detail)
            }

            val started = ProcessBuilder(binary.absolutePath, "run", "-c", paths.vpnConfigFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.vpnLogFile))
                .start()
            process = started
            activeKey = node.key
            val deadline = System.currentTimeMillis() + 15_000
            var connected = false
            while (System.currentTimeMillis() < deadline) {
                if (!started.isAlive) break
                if (listening() && probeProxy()) {
                    connected = true
                    break
                }
                Thread.sleep(250)
            }
            if (!connected) {
                runCatching { started.destroy() }
                process = null
                activeKey = null
                status = Status(Phase.FAILED, tr("Прокси не ответил или не смог подключиться к серверу", "The proxy did not answer or could not reach the server"), node.name)
                throw IOException(status.detail)
            }
            status = Status(Phase.RUNNING, tr("Подключено через ", "Connected via ") + node.protocol, node.name)
            Thread({
                val exitCode = runCatching { started.waitFor() }.getOrNull() ?: return@Thread
                synchronized(lock) {
                    if (process === started) {
                        process = null
                        activeKey = null
                        status = Status(Phase.FAILED, tr("sing-box завершился (код ", "sing-box exited (code ") + exitCode + ")", node.name)
                    }
                }
            }, "sing-box-watch").apply { isDaemon = true; start() }
            return proxyUrl
        }
    }

    fun stop(reason: String = tr("Остановлено", "Stopped")) {
        synchronized(lock) {
            stopLocked()
            status = Status(Phase.OFF, reason)
        }
    }

    /** The proxy the diagnostics screens should use, when the subscription is the active route. */
    fun proxy(): Proxy? = if (isRunning()) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", PORT)) else null

    private fun probeProxy(): Boolean = NetworkDiagnostics.cloudflareTrace(
        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", PORT)),
    )?.ip?.isNotBlank() == true

    private fun stopLocked() {
        val current = process
        process = null
        activeKey = null
        if (current != null) {
            runCatching { current.destroy() }
            runCatching {
                if (!current.waitFor(1, TimeUnit.SECONDS)) current.destroyForcibly()
            }
        }
    }

    companion object {
        const val PORT = 25346
        private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
    }
}
