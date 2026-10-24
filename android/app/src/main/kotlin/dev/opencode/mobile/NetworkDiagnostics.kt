package dev.opencode.mobile

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/** On-demand network diagnostics. It never changes Android's system DNS settings. */
object NetworkDiagnostics {
    data class IpLocation(
        val ip: String,
        val country: String = "",
        val region: String = "",
        val city: String = "",
        val isp: String = "",
        val warp: String = "",
        val colo: String = "",
    ) {
        fun display(): String = buildList {
            add("IP: $ip")
            val place = listOf(city, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (place.isNotBlank()) add(tr("Местоположение: ", "Location: ") + place)
            if (isp.isNotBlank()) add(tr("Провайдер: ", "ISP: ") + isp)
            if (warp.isNotBlank()) add("Cloudflare WARP: $warp")
            if (colo.isNotBlank()) add(tr("Узел Cloudflare: ", "Cloudflare edge: ") + colo)
        }.joinToString("\n")
    }

    data class Resolver(val name: String, val endpoint: String)

    data class DnsResult(val name: String, val endpoint: String, val ok: Boolean, val elapsedMs: Long, val detail: String) {
        fun display(): String = if (ok) "$name — OK · ${elapsedMs} ${tr("мс", "ms")}" else "$name — ${tr("ошибка", "error")} · $detail"
    }

    // These are public DNS-over-HTTPS endpoints documented by their operators. Testing is manual and opt-in.
    val resolvers = listOf(
        Resolver("Cloudflare DNS", "https://cloudflare-dns.com/dns-query"),
        Resolver("Google Public DNS", "https://dns.google/dns-query"),
        Resolver("AdGuard DNS", "https://dns.adguard-dns.com/dns-query"),
    )

    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** Uses Cloudflare's trace endpoint, then an IP geolocation API for city/region. Called only after user action. */
    fun currentIpLocation(proxy: Proxy? = null): IpLocation {
        val traceText = requestText(URL("https://www.cloudflare.com/cdn-cgi/trace"), proxy, "text/plain")
        val trace = traceText.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
        }.toMap()
        val ip = trace["ip"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(tr("Cloudflare trace не вернул публичный IP", "Cloudflare trace returned no public IP"))

        var country = trace["loc"].orEmpty()
        var region = ""
        var city = ""
        var isp = ""
        try {
            val obj = MiniJson.obj(requestText(URL("https://ipwho.is/${URLEncoder.encode(ip, "UTF-8")}"), proxy, "application/json"))
            if (obj != null && obj["success"] != false) {
                country = (obj["country"] as? String)?.takeIf { it.isNotBlank() } ?: country
                region = (obj["region"] as? String).orEmpty()
                city = (obj["city"] as? String).orEmpty()
                val connection = obj["connection"] as? Map<*, *>
                isp = (connection?.get("isp") as? String).orEmpty()
            }
        } catch (_: Exception) {
            // The IP and country code from Cloudflare remain useful if the geolocation endpoint is unavailable.
        }

        return IpLocation(ip, country, region, city, isp, trace["warp"].orEmpty(), trace["colo"].orEmpty())
    }

    fun currentIpLocationText(proxy: Proxy? = null): String = try {
        currentIpLocation(proxy).display()
    } catch (e: Exception) {
        tr("Не удалось определить IP: ", "Could not determine the IP: ") + (e.message ?: e.javaClass.simpleName)
    }

    /** Tests the three DoH resolvers against a real DNS wire-format query for example.com. */
    fun testDns(proxy: Proxy? = null): List<DnsResult> = resolvers.map { resolver ->
        val started = System.nanoTime()
        try {
            val query = makeDnsQuery("example.com")
            val queryParam = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
            val separator = if (resolver.endpoint.contains('?')) '&' else '?'
            val url = URL("${resolver.endpoint}${separator}dns=$queryParam")
            val connection = (if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/dns-message")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val body = connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) }
                if (!validDnsResponse(body, ((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff))) {
                    throw IllegalStateException(tr("DNS-ответ некорректен или запись A отсутствует", "the DNS answer is invalid or has no A record"))
                }
            } finally {
                connection.disconnect()
            }
            DnsResult(resolver.name, resolver.endpoint, true, elapsedMs(started), "")
        } catch (e: Exception) {
            DnsResult(resolver.name, resolver.endpoint, false, elapsedMs(started), e.message ?: e.javaClass.simpleName)
        }
    }

    /** Visible to JVM tests so the DNS packet format can be checked without a network. */
    fun makeDnsQuery(domain: String, id: Int = SecureRandom().nextInt(65_536)): ByteArray {
        require(domain.isNotBlank() && domain.length <= 253) { "invalid domain" }
        val output = ByteArrayOutputStream()
        write16(output, id)
        write16(output, 0x0100) // recursion desired
        write16(output, 1) // one question
        write16(output, 0)
        write16(output, 0)
        write16(output, 0)
        for (label in domain.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 63 && bytes.all { (it.toInt() and 0xff) in 33..126 }) { "invalid DNS label" }
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        write16(output, 1) // A
        write16(output, 1) // IN
        return output.toByteArray()
    }

    /** Visible to JVM tests. True means a DNS response with a matching ID, NOERROR, and at least one answer. */
    fun validDnsResponse(bytes: ByteArray, expectedId: Int): Boolean {
        if (bytes.size < 12) return false
        val id = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        val flags = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        val answers = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        val qr = flags and 0x8000 != 0
        val rcode = flags and 0x000f
        return id == expectedId && qr && rcode == 0 && answers > 0
    }

    fun cloudflareTrace(proxy: Proxy? = null): Warp.Trace? = runCatching {
        val response = requestText(URL("https://www.cloudflare.com/cdn-cgi/trace"), proxy, "text/plain")
        Warp.parseTrace(response)
    }.getOrNull()

    private fun requestText(url: URL, proxy: Proxy?, accept: String): String {
        val connection = (if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${tr("от", "from")} ${url.host}")
            return connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalStateException(tr("ответ слишком большой", "the response is too large"))
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun write16(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
