package dev.opencode.mobile

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/** Parses user-supplied VLESS and Hysteria2 links/subscriptions and builds sing-box JSON configurations. */
object VpnSubscriptionParser {
    private const val MAX_TEXT_LENGTH = 2 * 1024 * 1024
    private const val MAX_NODES = 200
    private val linkPattern = Regex("""(?i)(?:vless|hysteria2|hy2)://[^\s\"'<>]+""")

    data class Node(
        val rawUri: String,
        val key: String,
        val name: String,
        val protocol: String,
        val host: String,
        val port: Int,
        val user: String,
        val parameters: Map<String, String>,
    )

    /** Accepts newline/space separated links or the common base64-encoded subscription format. */
    fun parseSubscriptionText(input: String): List<Node> {
        require(input.length <= MAX_TEXT_LENGTH) { "подписка больше 2 МБ" }
        val direct = extractLinks(input)
        val decoded = if (direct.isNotEmpty()) direct else decodeSubscription(input)?.let(::extractLinks).orEmpty()
        val parsed = decoded.mapNotNull { raw -> runCatching { parseUri(raw) }.getOrNull() }
            .distinctBy { it.key }
            .take(MAX_NODES)
        require(parsed.isNotEmpty()) { "не найдено поддерживаемых ссылок VLESS или Hysteria2" }
        return parsed
    }

    fun extractLinks(input: String): List<String> = linkPattern.findAll(input)
        .map { it.value.trimEnd(',', ';', ')', ']', '}', '.') }
        .filter { it.length <= 16_384 }
        .distinct()
        .take(MAX_NODES)
        .toList()

    fun parseUri(value: String): Node {
        val raw = value.trim()
        require(raw.length in 10..16_384) { "некорректная ссылка" }
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("в ссылке нет протокола")
        val protocol = when (scheme) {
            "vless" -> "VLESS"
            "hysteria2", "hy2" -> "Hysteria2"
            else -> throw IllegalArgumentException("поддерживаются только VLESS и Hysteria2")
        }
        val host = uri.host?.trim('[', ']')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("в ссылке отсутствует сервер")
        val port = uri.port.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("в ссылке отсутствует корректный порт")
        val rawUser = uri.rawUserInfo ?: throw IllegalArgumentException("в ссылке отсутствуют учётные данные")
        val params = parseQuery(uri.rawQuery)
        val user = when (protocol) {
            "VLESS" -> decodeComponent(rawUser.substringBefore(':'))
            else -> decodeComponent(rawUser.substringAfterLast(':'))
        }.trim()
        require(user.isNotBlank()) { "в ссылке пустые учётные данные" }
        if (protocol == "VLESS") {
            require(params["encryption"].isNullOrBlank() || params["encryption"] == "none") {
                "VLESS encryption должен быть none"
            }
            val network = (params["type"] ?: params["network"] ?: "tcp").lowercase()
            require(network in setOf("tcp", "ws", "grpc", "httpupgrade")) {
                "транспорт VLESS '$network' пока не поддерживается этой сборкой"
            }
            if ((params["security"] ?: "none").equals("reality", true) || !params["pbk"].isNullOrBlank()) {
                require(!params["pbk"].isNullOrBlank()) { "Reality-ссылка не содержит pbk" }
            }
        }
        val fragment = uri.rawFragment?.let(::decodeComponent).orEmpty()
        val name = fragment.takeIf { it.isNotBlank() } ?: "$protocol · $host:$port"
        return Node(raw, sha256(raw), name, protocol, host, port, user, params)
    }

    /** Builds a local HTTP/SOCKS proxy; no Android-wide VPN permission is requested. */
    fun singBoxConfig(node: Node, listenPort: Int): String {
        require(listenPort in 1024..65535)
        val outbound = when (node.protocol) {
            "VLESS" -> vlessOutbound(node)
            "Hysteria2" -> hysteria2Outbound(node)
            else -> throw IllegalArgumentException("неподдерживаемый протокол ${node.protocol}")
        }
        val config = linkedMapOf<String, Any?>(
            "log" to linkedMapOf("level" to "warn", "timestamp" to true),
            "inbounds" to listOf(
                linkedMapOf(
                    "type" to "mixed",
                    "tag" to "local-in",
                    "listen" to "127.0.0.1",
                    "listen_port" to listenPort,
                ),
            ),
            "outbounds" to listOf(outbound, linkedMapOf("type" to "direct", "tag" to "direct")),
            "route" to linkedMapOf("final" to "proxy", "auto_detect_interface" to true),
        )
        return MiniJson.write(config)
    }

    /**
     * sing-box decodes the Reality public key as unpadded URL-safe base64, while links in the wild carry standard
     * base64 (with "+" and "/" and padding) just as often. Normalize whatever arrived into the form sing-box
     * accepts, or it rejects the config with "decode public_key" over a working server.
     */
    fun realityKey(raw: String): String {
        val trimmed = raw.trim()
        val bytes = runCatching { Base64.getDecoder().decode(pad(trimmed)) }
            .recoverCatching { Base64.getUrlDecoder().decode(pad(trimmed)) }
            .getOrNull() ?: throw IllegalArgumentException("pbk не является base64-ключом")
        require(bytes.size == 32) { "pbk должен быть 32-байтовым ключом" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pad(value: String): String = if (value.length % 4 == 0) value else value + "=".repeat(4 - value.length % 4)

    private fun vlessOutbound(node: Node): Map<String, Any?> {
        val p = node.parameters
        val security = (p["security"] ?: "none").lowercase()
        val outbound = linkedMapOf<String, Any?>(
            "type" to "vless",
            "tag" to "proxy",
            "server" to node.host,
            "server_port" to node.port,
            "uuid" to node.user,
        )
        p["flow"]?.takeIf { it.isNotBlank() }?.let { outbound["flow"] = it }
        if (security == "tls" || security == "reality" || !p["pbk"].isNullOrBlank()) {
            val tls = linkedMapOf<String, Any?>(
                "enabled" to true,
                "server_name" to (p["sni"] ?: p["serverName"] ?: node.host),
            )
            val fingerprint = p["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
            tls["utls"] = linkedMapOf("enabled" to true, "fingerprint" to fingerprint)
            if (security == "reality" || !p["pbk"].isNullOrBlank()) {
                val reality = linkedMapOf<String, Any?>("enabled" to true, "public_key" to realityKey(p["pbk"]!!))
                p["sid"]?.takeIf { it.isNotBlank() }?.let { reality["short_id"] = it }
                tls["reality"] = reality
            }
            if (p["insecure"] == "1" || p["allowInsecure"] == "1") tls["insecure"] = true
            outbound["tls"] = tls
        }
        val transport = (p["type"] ?: p["network"] ?: "tcp").lowercase()
        when (transport) {
            "tcp" -> Unit
            "ws" -> {
                val settings = linkedMapOf<String, Any?>("type" to "ws")
                p["path"]?.takeIf { it.isNotBlank() }?.let { settings["path"] = it }
                p["host"]?.takeIf { it.isNotBlank() }?.let { settings["headers"] = mapOf("Host" to it) }
                outbound["transport"] = settings
            }
            "grpc" -> {
                val settings = linkedMapOf<String, Any?>("type" to "grpc")
                p["serviceName"]?.takeIf { it.isNotBlank() }?.let { settings["service_name"] = it }
                outbound["transport"] = settings
            }
            "httpupgrade" -> {
                val settings = linkedMapOf<String, Any?>("type" to "httpupgrade")
                p["path"]?.takeIf { it.isNotBlank() }?.let { settings["path"] = it }
                p["host"]?.takeIf { it.isNotBlank() }?.let { settings["host"] = it }
                outbound["transport"] = settings
            }
            else -> throw IllegalArgumentException("неподдерживаемый транспорт VLESS: $transport")
        }
        return outbound
    }

    private fun hysteria2Outbound(node: Node): Map<String, Any?> {
        val p = node.parameters
        val tls = linkedMapOf<String, Any?>(
            "enabled" to true,
            "server_name" to (p["sni"] ?: p["serverName"] ?: node.host),
        )
        p["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { tls["alpn"] = it }
        if (p["insecure"] == "1" || p["allowInsecure"] == "1") tls["insecure"] = true
        val outbound = linkedMapOf<String, Any?>(
            "type" to "hysteria2",
            "tag" to "proxy",
            "server" to node.host,
            "server_port" to node.port,
            "password" to node.user,
            "tls" to tls,
        )
        val obfs = p["obfs"]
        val obfsPassword = p["obfs-password"] ?: p["obfs_password"]
        if (!obfs.isNullOrBlank()) {
            require(obfs.equals("salamander", true)) { "поддерживается obfs=salamander" }
            require(!obfsPassword.isNullOrBlank()) { "для obfs=salamander нужен obfs-password" }
            outbound["obfs"] = mapOf("type" to "salamander", "password" to obfsPassword)
        }
        return outbound
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val separator = pair.indexOf('=')
            val key = decodeComponent(if (separator >= 0) pair.substring(0, separator) else pair)
            val value = decodeComponent(if (separator >= 0) pair.substring(separator + 1) else "")
            if (key.isNotBlank()) result[key] = value
        }
        return result
    }

    private fun decodeSubscription(input: String): String? {
        val compact = input.filterNot { it.isWhitespace() }
        if (compact.length < 16 || compact.length > MAX_TEXT_LENGTH) return null
        val bytes = runCatching { Base64.getDecoder().decode(compact) }
            .recoverCatching { Base64.getUrlDecoder().decode(compact) }
            .getOrNull() ?: return null
        return bytes.toString(Charsets.UTF_8).takeIf { it.contains("://") }
    }

    private fun decodeComponent(value: String): String = runCatching {
        // '+' is common in credentials; URLDecoder normally turns it into a space, so preserve literal plus signs.
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
