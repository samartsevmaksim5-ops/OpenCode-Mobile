package dev.opencode.mobile

import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * The Cloudflare WARP tunnel, without any external tool: an X25519 key pair is generated here (RFC 7748), the WARP
 * account is registered with Cloudflare's client API, and the reply is turned into a wireproxy config. wireproxy (a
 * static Go binary shipped in the APK as libwireproxy.so) connects that config and exposes a local HTTP CONNECT proxy,
 * so the OpenCode server's outbound requests leave through Cloudflare with a fresh IP - which is what resets the
 * shared anonymous free-model limits.
 */
object Warp {
    /** Local ports wireproxy listens on. 25344 is the SOCKS5 spare, 25345 is the HTTP proxy the server is told to use. */
    const val SOCKS_PORT = 25344
    const val HTTP_PORT = 25345
    const val PROXY_URL = "http://127.0.0.1:$HTTP_PORT"
    const val NO_PROXY = "127.0.0.1,localhost,0.0.0.0"

    const val API_BASE = "https://api.cloudflareclient.com"

    /**
     * The User-Agent the real 1.1.1.1 app sends. Without it (Java's default) Cloudflare answers 403 "error code: 1010"
     * - the registration endpoint bans unknown client signatures.
     */
    const val USER_AGENT = "okhttp/3.12.13"

    /** Newer client versions first; older ones stay as fallbacks in case Cloudflare pins an exact version. */
    val CLIENT_VERSIONS = listOf("a-7.210-07210", "a-6.20-0620", "a-6.3-0603")

    /** One registered WARP identity, exactly what a wireproxy config needs. */
    data class Account(
        val id: String,
        val privateKey: String,
        val addressV4: String,
        val addressV6: String,
        val peerKey: String,
        val endpoint: String,
    )

    /** The one HTTP exchange registration needs, split out so tests can fake it. */
    interface Transport {
        fun post(url: String, body: String, headers: Map<String, String>): Reply
    }

    data class Reply(val code: Int, val body: String)

    class RegistrationException(message: String) : Exception(message)

    // X25519 (RFC 7748) over BigInteger: fine for a handful of one-time operations.
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private const val BASE_POINT = 9

    /** Derives the public key of a (already generated) X25519 private scalar. */
    fun publicKey(privateScalar: ByteArray): ByteArray {
        val clamped = privateScalar.copyOf()

        clamp(clamped)

        return scalarMult(clamped, encodeU(BigInteger.valueOf(BASE_POINT.toLong())))
    }

    /** A fresh key pair as base64url strings, ready for the registration call and the WireGuard config. */
    fun keygen(): Pair<String, String> {
        val random = SecureRandom()
        val scalar = ByteArray(32)

        random.nextBytes(scalar)
        clamp(scalar)

        val public = scalarMult(scalar, encodeU(BigInteger.valueOf(BASE_POINT.toLong())))

        return b64(scalar) to b64(public)
    }

    /** Montgomery ladder, straight from RFC 7748 section 5. */
    fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        val k = scalar.copyOf()

        clamp(k)

        val kBits = BooleanArray(255) { t -> k[t / 8].toInt() shr (t % 8) and 1 == 1 }
        var x1 = decodeU(uCoordinate)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false

        for (t in 254 downTo 0) {
            val kt = kBits[t]

            if (swap != kt) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }

            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val t0 = aa.add(BigInteger.valueOf(121665L).multiply(e).mod(P)).mod(P)

            x3 = da.add(cb).mod(P)
            x3 = x3.multiply(x3).mod(P)
            z3 = da.subtract(cb).mod(P)
            z3 = z3.multiply(z3).mod(P)
            z3 = z3.multiply(x1).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(t0).mod(P)
        }

        if (swap) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        return encodeU(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P))
    }

    private fun clamp(scalar: ByteArray) {
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = (scalar[31].toInt() and 127).toByte()
        scalar[31] = (scalar[31].toInt() or 64).toByte()
    }

    private fun decodeU(bytes: ByteArray): BigInteger {
        val copy = bytes.copyOf()

        copy[31] = (copy[31].toInt() and 0x7f).toByte()

        return BigInteger(1, copy.reversedArray())
    }

    private fun encodeU(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val raw = value.toByteArray()

        // BigInteger is big-endian and may add a sign byte; rewrite into fixed little-endian width.
        var i = raw.size - 1
        var o = 0

        while (i >= 0 && o < 32) {
            out[o++] = raw[i--]
        }

        return out
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** WireGuard and the Cloudflare API both speak STANDARD base64 (44 chars, +/ and =), never base64url. */
    fun b64Bytes(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

    // Registration

    /** Registers a fresh WARP identity. Tries every known client version before giving up. */
    fun register(transport: Transport, privateKey: String, publicKey: String): Account {
        val payload = MiniJson.write(
            linkedMapOf<String, Any?>(
                "fcm_token" to "",
                "install_id" to "",
                "key" to publicKey,
                "locale" to "en_US",
                "model" to "PC",
                "tos" to "2024-01-01T00:00:00.000Z",
                "warp_enabled" to true,
            ),
        )

        var lastError = "no answer"

        for (version in CLIENT_VERSIONS) {
            val reply = transport.post(
                "$API_BASE/v0a2158/reg",
                payload,
                mapOf("Content-Type" to "application/json", "CF-Client-Version" to version, "User-Agent" to USER_AGENT),
            )

            if (reply.code in 200..299) return parseRegistration(reply.body, privateKey)

            lastError = "HTTP ${reply.code}"

            val message = (MiniJson.obj(reply.body)?.get("message") as? String)?.takeIf { it.isNotBlank() }

            if (message != null) lastError = "$lastError: $message"
        }

        throw RegistrationException(lastError)
    }

    /** Pulls the WireGuard parameters out of a registration reply; missing pieces fail loudly, never silently. */
    fun parseRegistration(json: String, privateKey: String): Account {
        val root = MiniJson.obj(json) ?: throw RegistrationException("unreadable registration reply")
        val config = root["config"] as? Map<*, *> ?: throw RegistrationException("reply has no config")
        // The API moved the interface around: 6.x kept it in config.client.interface, 7.x puts it in config.interface.
        val client = config["client"] as? Map<*, *>
        val interface_ = (config["interface"] as? Map<*, *>)
            ?: (client?.get("interface") as? Map<*, *>)
            ?: throw RegistrationException("reply has no interface")
        val addresses = interface_["addresses"] as? Map<*, *> ?: throw RegistrationException("reply has no addresses")
        val peer = (config["peers"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw RegistrationException("reply has no peer")
        val peerKey = peer["public_key"] as? String ?: throw RegistrationException("peer has no public key")
        val v4 = (addresses["v4"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw RegistrationException("no IPv4 address in reply")
        val v6 = (addresses["v6"] as? String)?.takeIf { it.isNotBlank() } ?: ""
        val endpoint = endpointOf(peer)

        return Account(
            id = (root["id"] as? String).orEmpty(),
            privateKey = privateKey,
            addressV4 = v4,
            addressV6 = v6,
            peerKey = peerKey,
            endpoint = endpoint,
        )
    }

    /**
     * The endpoint must be an IP literal: the Android host has no /etc/resolv.conf, and wireproxy's pure Go resolver
     * would not find the engage hostname. The reply's v4/v6 endpoints carry port 0, so the real port comes from the
     * host field or the ports list - 2408 is the default WARP port.
     */
    private fun endpointOf(peer: Map<*, *>): String {
        val endpoint = peer["endpoint"] as? Map<*, *> ?: return "162.159.193.10:2408"
        val ports = (endpoint["ports"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toInt() }.orEmpty()
        val fallbackPort = ports.firstOrNull { it in 1..65535 } ?: 2408

        for (key in listOf("host", "v4", "v6")) {
            val value = (endpoint[key] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val withoutPort = value.substringBeforeLast(':')
            val host = withoutPort.removePrefix("[").removeSuffix("]").takeIf { it.isNotBlank() } ?: continue
            val port = value.substringAfterLast(':').toIntOrNull() ?: 0

            return resolve(host) + ":" + (if (port in 1..65535) port else fallbackPort)
        }

        return "162.159.193.10:$fallbackPort"
    }

    private fun resolve(host: String): String = runCatching {
        InetAddress.getByName(host).hostAddress ?: host
    }.getOrDefault(host)

    /** The wireproxy config for an account: the WireGuard tunnel plus local SOCKS5 and HTTP proxies. */
    fun conf(account: Account): String = buildString {
        append("[Interface]\n")
        append("PrivateKey = ${account.privateKey}\n")
        // wireproxy is strict about the prefix; older replies send bare addresses, newer ones send /32 and /128.
        append("Address = ")
        append(if (account.addressV4.contains('/')) account.addressV4 else "${account.addressV4}/32")
        if (account.addressV6.isNotEmpty()) {
            append(", ")
            append(if (account.addressV6.contains('/')) account.addressV6 else "${account.addressV6}/128")
        }
        append("\n")
        append("DNS = 1.1.1.1\n")
        append("MTU = 1420\n\n")
        append("[Peer]\n")
        append("PublicKey = ${account.peerKey}\n")
        append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        append("Endpoint = ${account.endpoint}\n\n")
        append("[Socks5]\n")
        append("BindAddress = 127.0.0.1:$SOCKS_PORT\n\n")
        append("[http]\n")
        append("BindAddress = 127.0.0.1:$HTTP_PORT\n")
    }

    /** Saves an account (its WireGuard key included) so the same identity survives restarts. */
    fun saveAccount(file: File, account: Account) {
        file.parentFile?.mkdirs()
        file.writeText(
            MiniJson.write(
                linkedMapOf(
                    "id" to account.id,
                    "privateKey" to account.privateKey,
                    "addressV4" to account.addressV4,
                    "addressV6" to account.addressV6,
                    "peerKey" to account.peerKey,
                    "endpoint" to account.endpoint,
                ),
            ),
        )
    }

    fun loadAccount(file: File): Account? {
        if (!file.exists()) return null

        val map = MiniJson.obj(file.readText()) ?: return null

        return Account(
            id = map["id"] as? String ?: "",
            privateKey = map["privateKey"] as? String ?: return null,
            addressV4 = map["addressV4"] as? String ?: return null,
            addressV6 = map["addressV6"] as? String ?: "",
            peerKey = map["peerKey"] as? String ?: return null,
            endpoint = map["endpoint"] as? String ?: return null,
        )
    }

    /** The HTTP transport used on device. */
    class HttpTransport(private val timeoutMs: Int = 15_000) : Transport {
        override fun post(url: String, body: String, headers: Map<String, String>): Reply {
            val connection = URL(url).openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs

                for ((k, v) in headers) connection.setRequestProperty(k, v)

                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

                return Reply(code, text)
            } finally {
                connection.disconnect()
            }
        }
    }
}
