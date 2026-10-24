package dev.opencode.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

var passed = 0
val failures = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) passed++ else failures += "$name ${detail()}".trim()
}

fun <T> eq(name: String, actual: T, expected: T) = check(name, actual == expected) { "expected <$expected> got <$actual>" }

fun throws(name: String, block: () -> Unit) {
    val failed = try { block(); false } catch (_: Throwable) { true }

    check(name, failed) { "did not throw" }
}

object NioFs : PlatformFs {
    override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target)) }

    override fun hardlink(existing: File, link: File) { Files.createLink(link.toPath(), existing.toPath()) }

    override fun chmod(file: File, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        val all = PosixFilePermission.values() // OWNER_READ .. OTHERS_EXECUTE, in mode-bit order from the top

        for (i in 0 until 9) if (mode and (1 shl (8 - i)) != 0) perms += all[i]

        runCatching { Files.setPosixFilePermissions(file.toPath(), perms) }
    }
}

fun testJson() {
    val v = MiniJson.parse("""{"a":[1,2.5,-3,true,null,"x\né\ud83d\ude00"],"b":{"c":"d"}}""") as Map<*, *>
    val list = v["a"] as List<*>

    eq("json long", list[0], 1L)
    eq("json double", list[1], 2.5)
    eq("json neg", list[2], -3L)
    eq("json bool", list[3], true)
    eq("json null", list[4], null)
    eq("json escapes", list[5], "x\né\uD83D\uDE00")
    eq("json nested", ((v["b"] as Map<*, *>)["c"]), "d")
    eq("json invalid", MiniJson.parseOrNull("{oops"), null)
    eq("json trailing", MiniJson.parseOrNull("{} x"), null)
    eq("json obj of array", MiniJson.obj("[1]"), null)
    eq("json deep", MiniJson.parseOrNull("[".repeat(200) + "]".repeat(200)), null)
    eq("json quote", MiniJson.quote("a\"b\\c\n\u0001"), "\"a\\\"b\\\\c\\n\\u0001\"")
    eq("json roundtrip", MiniJson.parse(MiniJson.quote("tab\there  ")), "tab\there  ")
}

fun testSse() {
    val got = ArrayList<String>()
    val p = SseParser { got += it }

    for (l in listOf(": hb", "", "data: one", "", "id: 4", "data: a", "data:b", "", "event: x", "")) p.line(l)

    eq("sse events", got, listOf("one", "a\nb"))

    val e = OcEvent.parse("""{"id":"e1","type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}""")

    eq("oc event", e, OcEvent("e1", OcEvent.SESSION_SUCCEEDED, "ses_1"))
    eq("oc event none", OcEvent.parse("{}"), null)
}

fun testUrls() {
    eq("port only", Urls.normalizePreview("5173"), "http://localhost:5173")
    eq("bad port", Urls.normalizePreview("70000"), null)
    eq("0.0.0.0", Urls.normalizePreview("http://0.0.0.0:8000/a?b=1#c"), "http://localhost:8000/a?b=1#c")
    eq("127", Urls.normalizePreview("127.0.0.1:3000"), "http://localhost:3000/")
    eq("bare host", Urls.normalizePreview("example.com/x"), "http://example.com/x")
    eq("js scheme", Urls.normalizePreview("javascript:alert(1)"), null)
    eq("file scheme", Urls.normalizePreview("file:///etc/passwd"), null)
    eq("empty", Urls.normalizePreview("  "), null)

    val origin = "http://127.0.0.1:4096"
    val reserved = setOf(4096, 4090)

    eq("internal", Urls.classify("http://127.0.0.1:4096/session/1", origin, reserved), Target.INTERNAL)
    eq("internal localhost alias", Urls.classify("http://localhost:4096/x", origin, reserved), Target.INTERNAL)
    eq("preview", Urls.classify("http://localhost:5173/", origin, reserved), Target.PREVIEW)
    eq("reserved bridge", Urls.classify("http://127.0.0.1:4090/preview", origin, reserved), Target.IGNORE)
    eq("external", Urls.classify("https://accounts.google.com/o/oauth2", origin, reserved), Target.EXTERNAL)
    eq("mailto", Urls.classify("mailto:a@b.c", origin, reserved), Target.EXTERNAL)
    eq("blob", Urls.classify("blob:http://127.0.0.1:4096/x", origin, reserved), Target.IGNORE)
    eq("intent", Urls.classify("intent://x#Intent;end", origin, reserved), Target.IGNORE)
    eq("basic", Urls.basicCredential("pw"), "b3BlbmNvZGU6cHc=")
    eq("server", Urls.normalizeServer("192.168.1.5:4096/"), "http://192.168.1.5:4096")
    eq("server bad", Urls.normalizeServer("ftp://x"), null)
}

fun testTar(fixtures: File) {
    val out = Files.createTempDirectory("tar-out").toFile()
    val ex = TarGzExtractor(out, NioFs)

    File(fixtures, "good.tar.gz").inputStream().use { ex.extract(it) }

    eq("tar file", File(out, "a/b/hello.txt").readText(), "hello\n")
    eq("tar unicode", File(out, "a/привет.txt").readText(), "privet\n")
    eq("tar long path", File(out, "d".repeat(60) + "/" + "e".repeat(60) + "/" + "f".repeat(60) + ".txt").readText(), "long\n")
    check("tar exec bit", File(out, "bin/run").canExecute())
    check("tar symlink", Files.isSymbolicLink(File(out, "bin/run-link").toPath()))
    eq("tar symlink target", Files.readSymbolicLink(File(out, "bin/run-link").toPath()).toString(), "run")
    eq("tar hardlink", File(out, "a/b/hard.txt").readText(), "hello\n")
    check("tar empty dir", File(out, "emptydir").isDirectory)
    eq("tar big file", File(out, "big.bin").length(), 3_000_000L)
    eq("tar skipped none", ex.skipped, 0)

    val evil = Files.createTempDirectory("tar-evil").toFile()
    val ex2 = TarGzExtractor(File(evil, "root"), NioFs)

    File(fixtures, "evil.tar.gz").inputStream().use { ex2.extract(it) }

    check("tar traversal blocked", !File(evil, "evil.txt").exists() && !File(evil, "root/../evil.txt").exists())
    check("tar abs blocked", !File("/tmp/oc-evil-abs.txt").exists())
    eq("tar safe still extracted", File(evil, "root/ok.txt").readText(), "ok\n")
    check("tar skipped counted", ex2.skipped >= 3) { "skipped=${ex2.skipped}" }
    check("tar symlink attack blocked", !File("/tmp/oc-evil-dir/pwned.txt").exists())
    check("tar dangling symlink not followed", !File("/tmp/oc-evil-dangling.txt").exists())
    eq("tar dangling replaced by file", File(evil, "root/dl").readText(), "x\n")
    check("tar hardlink escape blocked", !File(evil, "root/hl").exists())

    val pax = Files.createTempDirectory("tar-pax").toFile()

    File(fixtures, "pax.tar.gz").inputStream().use { TarGzExtractor(pax, NioFs).extract(it) }
    eq("tar pax long path", File(pax, "p".repeat(90) + "/" + "q".repeat(90) + "/файл.txt").readText(), "pax\n")

    throws("tar truncated") {
        val bytes = File(fixtures, "good.tar.gz").readBytes()

        TarGzExtractor(Files.createTempDirectory("tar-trunc").toFile(), NioFs).extract(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)))
    }

    eq("sanitize", TarGzExtractor.sanitize("./a//b/./c"), "a/b/c")
    eq("sanitize dotdot", TarGzExtractor.sanitize("a/../b"), null)
    eq("sanitize root", TarGzExtractor.sanitize("/"), null)
}

fun cfg(compat: Boolean = false, ota: String? = null, proxy: String? = null) = ProotConfig(
    prootBin = "/data/app/lib/libproot.so", loader = "/data/app/lib/libproot-loader.so", nativeLibDir = "/data/app/lib",
    rootfs = "/data/files/rootfs", home = "/data/files/profiles/main/home", fakeProc = "/data/files/fakeproc",
    prootTmp = "/data/files/tmp", port = 4096, password = "s3cr\"et", bridgeUrl = "http://127.0.0.1:4090",
    bridgeToken = "tok", compat = compat, bindSharedStorage = true, timeZone = "Asia/Irkutsk", otaBinDir = ota,
    proxy = proxy,
)

fun testFs() {
    val base = Files.createTempDirectory("fs").toFile()
    val outside = File(base, "outside").apply { mkdirs() }
    val keep = File(outside, "keep.txt").apply { writeText("keep") }
    val tree = File(base, "tree").apply { mkdirs() }

    File(tree, "a/b").mkdirs()
    File(tree, "a/b/f.txt").writeText("x")
    Files.createSymbolicLink(File(tree, "a/link").toPath(), outside.toPath())
    Files.createSymbolicLink(File(tree, "dangling").toPath(), File(base, "nowhere").toPath())
    File(tree, "a/b").setWritable(false)
    File(tree, "a").setWritable(false)
    Fs.deleteTree(tree)

    check("tree deleted", !tree.exists())
    check("symlink target survived", keep.exists() && keep.readText() == "keep")
    Fs.deleteTree(File(base, "missing"))

    val data = File(base, "data").apply { mkdirs() }

    File(data, "opencode.db").writeText("db")
    File(data, "opencode.db-wal").writeText("wal")
    File(data, "auth.json").writeText("{}")
    File(data, "big.log").writeText("log")
    File(data, "snapshots").mkdirs()

    val copy = File(base, "copy")
    val n = Fs.copyFlat(data, copy) { it.endsWith(".db") || it.endsWith(".db-wal") || it.endsWith(".json") }

    eq("copyFlat bytes", n, 7L)
    eq("copyFlat files", copy.list()!!.sorted(), listOf("auth.json", "opencode.db", "opencode.db-wal"))
}

fun testProot() {
    val l = ProotCommand.server(cfg())
    val c = l.command

    eq("proot bin first", c.first(), "/data/app/lib/libproot.so")
    check("proot rootfs", c.zipWithNext().any { it == "-r" to "/data/files/rootfs" })
    check("proot home bind", c.contains("/data/files/profiles/main/home:/root"))
    check("proot downloads bind", c.contains("/storage/emulated/0/Download"))
    check("proot fake proc bind", c.contains("/data/files/fakeproc/loadavg:/proc/loadavg"))
    check("proot env -i", c.indexOf("-i") > c.indexOf("/usr/bin/env"))
    check("proot password env", c.contains("OPENCODE_PASSWORD=s3cr\"et"))
    check("proot config env", c.any { it.startsWith("OPENCODE_CONFIG_CONTENT={\"instructions\":[\"/etc/opencode-android/AGENTS.md\"]}") })
    eq("proot serve tail", c.takeLast(5), listOf("serve", "--hostname", "127.0.0.1", "--port", "4096"))
    eq("proot default binary", c[c.size - 6], "/usr/local/bin/opencode")
    eq("host env loader", l.env["PROOT_LOADER"], "/data/app/lib/libproot-loader.so")
    check("no seccomp off by default", "PROOT_NO_SECCOMP" !in l.env)

    val compat = ProotCommand.server(cfg(compat = true))

    eq("compat seccomp", compat.env["PROOT_NO_SECCOMP"], "1")
    check("compat fff", compat.command.contains("OPENCODE_DISABLE_FFF=1"))

    val ota = ProotCommand.server(cfg(ota = "/data/files/ota/versions/x1"))

    check("ota bind", ota.command.contains("/data/files/ota/versions/x1:/opt/opencode-ota"))
    eq("ota binary", ota.command[ota.command.size - 6], "/opt/opencode-ota/opencode")
    eq("exec version", ProotCommand.exec(cfg(ota = "/o"), "/opt/opencode-ota/opencode", "--version").command.takeLast(2), listOf("/opt/opencode-ota/opencode", "--version"))

    val fp = FakeProc.contents(4)

    check("fakeproc keys", fp.keys == FakeProc.targets.map { it.first }.toSet())
    eq("fakeproc cpus", fp.getValue("stat").lines().count { it.startsWith("cpu") && it[3].isDigit() }, 4)
}

fun testPortPicker() {
    eq("port kept when free", PortPicker.pick(4096) { false }, 4096)
    eq("busy port takes the next", PortPicker.pick(4096) { it == 4096 }, 4097)
    eq("busy run skipped", PortPicker.pick(4096) { it < 4100 }, 4100)
    eq("span exhausted", PortPicker.pick(4096) { true }, null)
    eq("port below range", PortPicker.pick(0) { false }, null)
    eq("port above range", PortPicker.pick(65536) { false }, null)
    eq("port at the top", PortPicker.pick(65535) { false }, 65535)
    eq("top neighbor", PortPicker.pick(65534) { it == 65534 }, 65535)
    eq("top span exhausted", PortPicker.pick(65535) { true }, null)

    val live = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    try {
        check("a live listener is busy", !PortPicker.free(live.localPort))
        check("pick walks past a live listener", (PortPicker.pick(live.localPort) { !PortPicker.free(it) } ?: -1) > live.localPort)
    } finally {
        live.close()
    }

    val probe = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val idle = probe.localPort

    probe.close()

    check("a port nobody listens on is free", PortPicker.free(idle))
}

fun testProfiles() {
    val dir = Files.createTempDirectory("prof").toFile()
    val file = File(dir, "profiles.json")
    val p = Profiles(file)

    eq("profiles default", p.all.map { it.id }, listOf("main"))
    eq("profiles port", p.active.port, 4096)

    val a = p.create("Work")
    val b = p.create("  ")

    eq("profile slot a", a.slot, 1)
    eq("profile slot b", b.slot, 2)
    eq("profile default name", b.name, "Profile 3")
    check("profile id shape", Regex("^[a-z0-9]{10}$").matches(a.id))

    p.setAccount(a.id, " me@gmail.com ")
    eq("account trimmed", p.find(a.id)?.account, "me@gmail.com")
    eq("title uses account", p.find(a.id)?.title, "me@gmail.com")
    p.rename(a.id, "Renamed")
    p.setActive(a.id)

    val again = Profiles(file)

    eq("profiles persisted", again.all.size, 3)
    eq("active persisted", again.active.id, a.id)
    eq("name persisted", again.find(a.id)?.name, "Renamed")
    check("remove active", again.remove(a.id))
    eq("active falls back", again.active.id, "main")

    val c = again.create("C")

    eq("slot reused", c.slot, 1)

    // Every profile has a port of its own, remembered once it had to move.
    eq("profile port from slot", again.find(b.id)?.port, 4098)

    again.setPort(b.id, 4200)

    eq("port set", again.find(b.id)?.port, 4200)

    again.setPort(b.id, 80)

    eq("port below range ignored", again.find(b.id)?.port, 4200)

    again.setPort(b.id, 70000)

    eq("port above range ignored", again.find(b.id)?.port, 4200)
    eq("port persisted", Profiles(file).find(b.id)?.port, 4200)

    check("remove b", again.remove(b.id))
    check("remove c", again.remove(c.id))
    check("last profile kept", !again.remove("main"))
    check("setActive unknown", !again.setActive("nope"))
    eq("home dir", Profiles.homeDir(File("/p"), again.active).path, "/p/main/home")

    file.writeText("{ broken")
    eq("corrupt file recovers", Profiles(file).all.map { it.id }, listOf("main"))

    file.writeText("""{"active":"zz","profiles":[{"id":"../x","slot":1},{"id":"ok1","name":"N","slot":9},{"id":"ok2","name":"N2","slot":2},{"id":"ok3","name":"dup","slot":2}]}""")

    val sane = Profiles(file)

    eq("hostile ids rejected", sane.all.map { it.id }, listOf("ok2"))
    eq("active fallback", sane.active.id, "ok2")

    file.writeText("""{"active":"ok2","profiles":[{"id":"ok2","name":"N2","slot":2,"port":70000},{"id":"ok5","name":"N5","slot":3,"port":4200}]}""")

    val ported = Profiles(file)

    eq("corrupt port falls back to slot", ported.find("ok2")?.port, 4098)
    eq("saved port kept", ported.find("ok5")?.port, 4200)

    for (i in 1 until Profiles.MAX_PROFILES) sane.create("p$i")

    throws("profile cap") { sane.create("one too many") }
}

class FakeTransport : Transport {
    val calls = ArrayList<String>()
    var status = "pending"
    var methods = """[{"id":"console","type":"oauth","label":"OpenCode Console account"},{"id":"key","type":"key","label":"API key"}]"""
    var connections = "[]"

    override fun request(method: String, path: String, body: String?): Reply {
        calls += "$method $path ${body ?: ""}".trim()

        return when {
            method == "GET" && path == "/api/integration/opencode" ->
                Reply(200, """{"location":{},"data":{"id":"opencode","name":"OpenCode","methods":$methods,"connections":$connections}}""")
            method == "POST" && path == "/api/integration/opencode/connect/oauth" ->
                Reply(200, """{"location":{},"data":{"attemptID":"con_1","url":"https://opencode.ai/auth/device?user_code=ABCD-EFGH&x=1","instructions":"Enter code: ABCD-EFGH","mode":"auto","time":{"created":1,"expires":99}}}""")
            method == "GET" && path == "/api/integration/opencode/connect/oauth/con_1" ->
                Reply(200, if (status == "failed") """{"data":{"status":"failed","message":"access_denied","time":{"created":1,"expires":2}}}""" else """{"data":{"status":"$status","time":{"created":1,"expires":2}}}""")
            path.endsWith("/gone") -> Reply(404, "{}")
            else -> Reply(500, """{"message":"boom"}""")
        }
    }
}

fun testLogin() {
    val t = FakeTransport()
    val login = ConsoleLogin(t)
    val attempt = login.start()

    eq("attempt id", attempt.id, "con_1")
    eq("attempt code", attempt.code, "ABCD-EFGH")
    eq("attempt expires", attempt.expiresAt, 99L)
    check("start picks oauth method", t.calls.any { it == """POST /api/integration/opencode/connect/oauth {"methodID":"console"}""" })
    eq("status pending", login.status(attempt), LoginStatus.Pending)
    t.status = "complete"
    eq("status complete", login.status(attempt), LoginStatus.Complete)
    t.status = "expired"
    eq("status expired", login.status(attempt), LoginStatus.Expired)
    t.status = "failed"
    eq("status failed", login.status(attempt), LoginStatus.Failed("access_denied"))
    eq("status 404 is expired", login.status(attempt.copy(id = "gone")), LoginStatus.Expired)

    t.connections = """[{"type":"credential","id":"cred1","label":"me@gmail.com","method":"oauth"},{"type":"credential","id":"cred2","label":"key","method":"key"},{"type":"env","name":"X"}]"""
    eq("accounts", login.accounts().map { it.id }, listOf("cred1", "cred2"))
    eq("signed in accounts", login.signedInAccounts().map { it.label }, listOf("me@gmail.com"))

    t.methods = """[{"id":"key","type":"key","label":"API key"}]"""
    throws("no oauth method") { login.start() }
    eq("code none", ConsoleLogin.code("https://x/y"), null)
    eq("code encoded", ConsoleLogin.code("https://x/y?user_code=AB%2DCD"), "AB-CD")
}

fun testHttpTransport() {
    val seen = ArrayList<String>()
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)

    server.createContext("/") { ex ->
        seen += ex.requestMethod + " " + ex.requestURI + " " + ex.requestHeaders.getFirst("Authorization") + " " + ex.requestBody.readBytes().toString(Charsets.UTF_8)

        val body = if (ex.requestURI.path == "/bad") "{\"message\":\"nope\"}" else "{\"data\":1}"

        ex.sendResponseHeaders(if (ex.requestURI.path == "/bad") 400 else 200, body.length.toLong())
        ex.responseBody.use { it.write(body.toByteArray()) }
    }

    server.start()

    try {
        val t = HttpTransport("http://127.0.0.1:${server.address.port}/", "pw")
        val ok = t.request("POST", "/api/x", "{\"a\":1}")

        eq("http ok", ok, Reply(200, "{\"data\":1}"))
        eq("http auth", seen[0], "POST /api/x Basic b3BlbmNvZGU6cHc= {\"a\":1}")
        eq("http error body", t.request("GET", "/bad"), Reply(400, "{\"message\":\"nope\"}"))
    } finally {
        server.stop(0)
    }
}

fun testOta(fixtures: File) {
    val manifest = File(fixtures, "manifest.json").readBytes()
    val sig = File(fixtures, "manifest.json.sig").readBytes()
    val ks = KeyStore.getInstance("PKCS12").apply { File(fixtures, "ota.p12").inputStream().use { load(it, "passw0rd".toCharArray()) } }
    val cert = ks.getCertificate("opencode-dev")
    val keys = listOf(cert.publicKey)

    check("openssl signature verifies (RSA, DER)", OtaVerifier.verifySignature(manifest, sig, keys))
    check("openssl signature as base64", OtaVerifier.verifySignature(manifest, java.util.Base64.getMimeEncoder().encode(sig), keys))
    check("tampered manifest rejected", !OtaVerifier.verifySignature(manifest + " ".toByteArray(), sig, keys))
    check("flipped signature rejected", !OtaVerifier.verifySignature(manifest, sig.copyOf().also { it[10] = (it[10] + 1).toByte() }, keys))
    check("empty signature rejected", !OtaVerifier.verifySignature(manifest, ByteArray(0), keys))

    val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    check("foreign key rejected", !OtaVerifier.verifySignature(manifest, sig, listOf(other.public)))
    check("any of several keys", OtaVerifier.verifySignature(manifest, sig, listOf(other.public, cert.publicKey)))

    val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val ecSig = Signature.getInstance("SHA256withECDSA").run { initSign(ec.private); update(manifest); sign() }

    check("ecdsa verifies", OtaVerifier.verifySignature(manifest, ecSig, listOf(ec.public)))

    val m = OtaManifest.parse(manifest)

    eq("manifest id", m.id, "2.0.33-1")
    eq("manifest seq", m.seq, 1760000000L)
    eq("manifest upstream", m.upstreamVersion, "2.0.33")
    eq("manifest runtime name", m.runtime?.name, "opencode-linux-arm64.tar.gz")
    eq("manifest min app", m.minAppVersionCode, 3)
    check("manifest app optional", m.app != null && m.app!!.versionCode == 7)

    fun bad(name: String, mutate: (String) -> String) = throws("manifest $name") { OtaManifest.parse(mutate(manifest.toString(Charsets.UTF_8)).toByteArray()) }

    bad("schema") { it.replace("\"schema\": 1", "\"schema\": 2") }
    bad("id traversal") { it.replace("2.0.33-1", "../x") }
    bad("sha") { it.replaceFirst(Regex("[0-9a-f]{64}"), "zz") }
    bad("name traversal") { it.replace("opencode-linux-arm64.tar.gz", "../opencode.tgz") }
    bad("size") { it.replace("\"size\": 4096", "\"size\": 99999999999") }
    bad("not json") { "nope" }

    eq("policy ok", OtaPolicy.check(m, 100, 3, emptySet()), null)
    eq("policy not newer", OtaPolicy.check(m, m.seq, 3, emptySet()), Skip.NOT_NEWER)
    eq("policy needs app", OtaPolicy.check(m, 100, 2, emptySet()), Skip.NEEDS_NEWER_APP)
    eq("policy blocked", OtaPolicy.check(m, 100, 3, setOf(m.id)), Skip.BLOCKED)
    eq("policy abi", OtaPolicy.check(m, 100, 3, emptySet(), abi = "x86_64"), Skip.WRONG_ABI)
    eq("policy channel", OtaPolicy.check(m, 100, 3, emptySet(), channel = "beta"), Skip.WRONG_CHANNEL)

    // Picking: the newest server build and the newest app build are chosen independently of each other.
    fun offer(id: String, seq: Long, runtime: Boolean, appCode: Int?, min: Int = 1, abi: String = "arm64-v8a"): Offer {
        val rt = if (runtime) "\"runtime\": { \"name\": \"r.tar.gz\", \"size\": 4096, \"sha256\": \"${"c".repeat(64)}\", \"entry\": \"opencode\" }" else null
        val ap = if (appCode != null) "\"app\": { \"name\": \"a.apk\", \"size\": 4096, \"sha256\": \"${"d".repeat(64)}\", \"versionCode\": $appCode }" else null
        val json = "{ \"schema\": 1, \"channel\": \"stable\", \"id\": \"$id\", \"seq\": $seq, \"abi\": \"$abi\", \"minAppVersionCode\": $min, " +
            "\"upstream\": { \"repo\": \"o/r\", \"version\": \"2.0.1\", \"ref\": \"\" }" +
            listOfNotNull(rt, ap).joinToString("") { ", $it" } + " }"

        return Offer(OtaManifest.parse(json.toByteArray()), ReleaseInfo("ota-$id", "2026-01-01T00:00:00Z", emptyMap()))
    }

    val app7 = offer("app-7", 10, runtime = false, appCode = 7)
    val rtOld = offer("rt-a", 200, runtime = true, appCode = null)
    val rtNew = offer("rt-b", 300, runtime = true, appCode = null)
    val picks = OtaPolicy.pick(listOf(rtNew, rtOld, app7), installedSeq = 100, appVersionCode = 5, blocked = emptySet())

    eq("pick newest runtime", picks.runtime?.manifest?.id, "rt-b")
    eq("pick app that a later runtime release does not hide", picks.app?.manifest?.id, "app-7")
    eq("pick nothing newer", OtaPolicy.pick(listOf(rtNew, app7), installedSeq = 300, appVersionCode = 7, blocked = emptySet()), Picks(null, null))
    eq("pick skips blocked runtime", OtaPolicy.pick(listOf(rtNew, rtOld), 100, 5, setOf("rt-b")).runtime?.manifest?.id, "rt-a")
    eq("pick skips blocked app", OtaPolicy.pick(listOf(app7), 100, 5, setOf("app-7")).app, null)
    eq("pick keeps runtime needing a newer app out", OtaPolicy.pick(listOf(offer("rt-c", 400, true, null, min = 9)), 100, 5, emptySet()).runtime, null)
    eq("pick other abi", OtaPolicy.pick(listOf(offer("rt-d", 400, true, 8, abi = "x86_64")), 100, 5, emptySet()), Picks(null, null))
    eq("pick one release with both", OtaPolicy.pick(listOf(offer("both", 500, true, 9)), 100, 5, emptySet()).let { it.runtime?.manifest?.id to it.app?.manifest?.id }, "both" to "both")
    eq("pick the higher app code", OtaPolicy.pick(listOf(offer("app-8", 1, false, 8), offer("app-9", 1, false, 9)), 100, 5, emptySet()).app?.manifest?.id, "app-9")

    val data = ByteArray(10_000) { it.toByte() }
    val sink = ByteArrayOutputStream()
    val (hash, n) = OtaVerifier.copyHashed(ByteArrayInputStream(data), sink, 20_000)

    eq("hash", hash, java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
    eq("hash length", n, 10_000L)
    throws("download limit") { OtaVerifier.copyHashed(ByteArrayInputStream(data), ByteArrayOutputStream(), 5_000) }

    val dir = Files.createTempDirectory("otastate").toFile()
    val f = File(dir, "state.json")
    val s = OtaState(f)

    eq("state fresh effective", s.effectiveSeq(50), 50L)
    check("promote without staged", !s.promote())
    s.stage("v1", 100, "1.0")
    check("promote v1", s.promote())
    eq("trial v1", s.trial, "v1")
    s.confirm()
    eq("effective ota newer", s.effectiveSeq(50), 100L)
    eq("effective bundled newer", s.effectiveSeq(200), 200L)
    check("superseded", s.supersededBy(100))
    s.stage("v2", 200, "2.0")
    check("promote v2", s.promote())
    eq("previous is v1", s.previous, "v1")
    eq("keep", s.keep(), setOf("v1", "v2"))

    val reloaded = OtaState(f)

    eq("state persisted", reloaded.current, "v2")
    eq("trial persisted", reloaded.trial, "v2")
    eq("rollback returns bad", reloaded.rollback(), "v2")
    eq("rolled back to v1", reloaded.current, "v1")
    eq("rolled back version", reloaded.currentVersion, "1.0")
    eq("rolled back seq", reloaded.currentSeq, 100L)
    check("v2 blocked", "v2" in reloaded.blocked)
    eq("rollback without trial", reloaded.rollback(), null)

    reloaded.stage("v3", 300, "3.0")
    reloaded.promote()
    reloaded.confirm()
    eq("confirmed", OtaState(f).trial, null)
    File(dir, "state.json").writeText("garbage")
    eq("garbage state", OtaState(f).current, null)

    val releases = GithubReleases.parse(
        """[
        {"tag_name":"ota-3","draft":false,"prerelease":false,"published_at":"2026-10-03T00:00:00Z","assets":[
            {"name":"manifest.json","browser_download_url":"https://github.com/o/r/releases/download/ota-3/manifest.json"},
            {"name":"evil","browser_download_url":"https://evil.example/x"},
            {"name":"http","browser_download_url":"http://github.com/x"}]},
        {"tag_name":"ota-4","draft":true,"prerelease":false,"published_at":"2026-10-04T00:00:00Z","assets":[]},
        {"tag_name":"ota-5","draft":false,"prerelease":true,"published_at":"2026-10-05T00:00:00Z","assets":[]},
        {"tag_name":"v1.0","draft":false,"prerelease":false,"published_at":"2026-10-06T00:00:00Z","assets":[]},
        {"tag_name":"ota-2","draft":false,"prerelease":false,"published_at":"2026-10-02T00:00:00Z","assets":[]}
        ]""",
    )

    eq("releases filtered and sorted", releases.map { it.tag }, listOf("ota-3", "ota-2"))
    eq("releases keep only trusted assets", releases[0].assets.keys, setOf("manifest.json"))
    check("repo valid", GithubReleases.valid("anomalyco/opencode") && !GithubReleases.valid("a/b/c") && !GithubReleases.valid("../x"))
}

fun http(port: Int, request: String): String {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 3000
        s.getOutputStream().apply { write(request.toByteArray()); flush() }

        return s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}

fun testBridge() {
    val shown = ArrayList<String>()
    val notified = ArrayList<String>()
    val saved = ArrayList<String>()
    val widgets = ArrayList<String>()
    val handler = object : BridgeHandler {
        override fun preview(url: String): String? { shown += url; return if (url.contains("fail")) "no window" else null }
        override fun open(url: String): String? { shown += "open:$url"; return null }
        override fun notify(title: String, text: String) { notified += "$title|$text" }
        override fun save(path: String, name: String): String {
            if (path.contains("missing")) throw java.io.IOException("File not found: $path")

            saved += "$path|$name"

            return "Download/" + name.ifEmpty { "file" }
        }
        override fun widgetSet(name: String, title: String, text: String, url: String, pin: Boolean, src: String, w: Int, h: Int): String { widgets += "$name|$title|$text|$url|$pin|$src|$w|$h"; return "widget $name: saved" }
        override fun widgetRemove(name: String): Boolean = widgets.removeAll { it.startsWith("$name|") }
        override fun widgetList() = widgets.joinToString("\n") { it.substringBefore('|') }
        override fun version() = "1.2.3"
    }
    val server = BridgeServer("secret", handler)
    val port = server.start()

    try {
        val h = "Host: 127.0.0.1:$port\r\n"

        check("ping ok", http(port, "GET /ping HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").let { it.startsWith("HTTP/1.1 200") && it.endsWith("pong 1.2.3") })
        check("no token", http(port, "GET /ping HTTP/1.1\r\n$h\r\n").startsWith("HTTP/1.1 401"))
        check("wrong token", http(port, "GET /ping HTTP/1.1\r\n${h}X-OC-Token: nope\r\n\r\n").startsWith("HTTP/1.1 401"))
        check("origin refused", http(port, "GET /ping HTTP/1.1\r\n${h}Origin: http://evil\r\nX-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 403"))
        check("foreign host refused", http(port, "GET /ping HTTP/1.1\r\nHost: evil.example\r\nX-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 403"))

        val body = "url=5173"

        check("preview port", http(port, "POST /preview HTTP/1.1\r\n${h}X-OC-Token: secret\r\nContent-Length: ${body.length}\r\n\r\n$body").contains("ok http://localhost:5173"))
        check("preview query", http(port, "GET /preview?url=http%3A%2F%2F0.0.0.0%3A8080%2Fx HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").contains("ok http://localhost:8080/x"))
        check("preview bad url", http(port, "GET /preview?url=javascript%3Aalert HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 400"))
        check("preview handler error", http(port, "GET /preview?url=fail.test HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").contains("no window"))
        check("open", http(port, "GET /open?url=https%3A%2F%2Fexample.com HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 200"))
        check("open rejects file", http(port, "GET /open?url=file%3A%2F%2F%2Fetc HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 400"))
        check("notify", http(port, "GET /notify?title=T&text=Done HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 200") && notified == listOf("T|Done"))
        check("widget set", http(port, "GET /widget/set?name=w1&title=Hi&text=There&url=5173&pin=0 HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 200") && widgets == listOf("w1|Hi|There|http://localhost:5173|false||0|0"))
        check("widget bad name", http(port, "GET /widget/set?name=a%20b&title=x HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 400"))
        check("widget bad url", http(port, "GET /widget/set?name=w2&url=ftp://x HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 400"))
        check("widget list", http(port, "GET /widget/list HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").endsWith("w1"))
        check("widget remove", http(port, "GET /widget/remove?name=w1 HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 200") && widgets.isEmpty())
        check("widget remove missing", http(port, "GET /widget/remove?name=w1 HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 404"))
        val rich = "name=w3&src_b64=PGgxPmhpPC9oMT4%3D&w=400&h=300"
        check(
            "widget rich set",
            http(port, "POST /widget/set HTTP/1.1\r\n${h}X-OC-Token: secret\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${rich.length}\r\n\r\n$rich")
                .startsWith("HTTP/1.1 200") && widgets.lastOrNull() == "w3||||true|<h1>hi</h1>|400|300",
        )
        val bad64 = "name=w4&src_b64=%23%23%23"
        check("widget bad base64", http(port, "POST /widget/set HTTP/1.1\r\n${h}X-OC-Token: secret\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bad64.length}\r\n\r\n$bad64").startsWith("HTTP/1.1 400"))
        check("save", http(port, "GET /save?path=%2Froot%2Fa%20b.txt&name=x.txt HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").let { it.startsWith("HTTP/1.1 200") && it.endsWith("Download/x.txt") } && saved == listOf("/root/a b.txt|x.txt"))
        check("save needs a path", http(port, "GET /save HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 400"))
        check("save error is reported", http(port, "GET /save?path=%2Fmissing HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").let { it.startsWith("HTTP/1.1 400") && it.contains("File not found") })
        check("save needs the token", http(port, "GET /save?path=%2Fa HTTP/1.1\r\n$h\r\n").startsWith("HTTP/1.1 401"))
        check("unknown route", http(port, "GET /nope HTTP/1.1\r\n${h}X-OC-Token: secret\r\n\r\n").startsWith("HTTP/1.1 404"))
        check("oversized body", http(port, "POST /preview HTTP/1.1\r\n${h}X-OC-Token: secret\r\nContent-Length: 99999999\r\n\r\n").startsWith("HTTP/1.1 400"))
        check("garbage", http(port, "hello\r\n\r\n").startsWith("HTTP/1.1 400"))
        eq("handler saw", shown.first(), "http://localhost:5173")
    } finally {
        server.stop()
    }
}

fun testOtaPackage(fixtures: File) {
    val ks = KeyStore.getInstance("PKCS12").apply { File(fixtures, "ota.p12").inputStream().use { load(it, "passw0rd".toCharArray()) } }
    val keys = listOf(ks.getCertificate("opencode-dev").publicKey)

    fun load(dir: String): Triple<OtaManifest, ByteArray, ByteArray> {
        val manifest = File(fixtures, "$dir/manifest.json").readBytes()

        return Triple(OtaManifest.parse(manifest), manifest, File(fixtures, "$dir/manifest.json.sig").readBytes())
    }

    // Server build and APK in one release.
    val (m, bytes, sig) = load("pkg")

    check("package signature verifies on the phone's side", OtaVerifier.verifySignature(bytes, sig, keys))
    eq("package id", m.id, "rt-2.0.40-1761000000")
    eq("package seq", m.seq, 1761000000L)
    eq("package upstream", m.upstreamVersion, "2.0.40")
    eq("package ref", m.upstreamRef, "0123456789abcdef0123456789abcdef01234567")
    eq("package channel", m.channel, "stable")
    eq("package abi", m.abi, "arm64-v8a")
    eq("package min app", m.minAppVersionCode, 1)
    eq("package app code", m.app?.versionCode, 12)
    eq("package app name", m.app?.name, "opencode-android-12.apk")
    eq("release tag matches the id", GithubReleases.TAG_PREFIX + m.id, "ota-rt-2.0.40-1761000000")

    val archive = File(fixtures, "pkg/${m.runtime!!.name}")

    eq("package runtime size", archive.length(), m.runtime!!.size)

    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }

    eq("package runtime sha", digest, m.runtime!!.sha256)
    eq("package apk size", File(fixtures, "pkg/${m.app!!.name}").length(), m.app!!.size)

    // The archive passes exactly the checks the phone applies before it runs anything from it.
    val target = Files.createTempDirectory("otapkg").toFile()

    try {
        val extractor = TarGzExtractor(target, NioFs)

        archive.inputStream().use { extractor.extract(it) }

        val entries = target.listFiles().orEmpty()

        eq("package has one entry", entries.map { it.name }, listOf("opencode"))
        eq("package skipped nothing", extractor.skipped, 0)
        check("package binary is large enough", File(target, "opencode").length() >= 1_000_000)
        check("package binary is executable", File(target, "opencode").canExecute())
    } finally {
        Fs.deleteTree(target)
    }

    // Tampering with any byte of the manifest breaks the signature.
    check("package manifest tamper detected", !OtaVerifier.verifySignature(bytes.copyOf().also { it[bytes.size / 2] = 'X'.code.toByte() }, sig, keys))

    // An app-only release and a server-only release, as the two workflows publish them.
    val (app, appBytes, appSig) = load("pkg-app")

    check("app-only signature", OtaVerifier.verifySignature(appBytes, appSig, keys))
    eq("app-only id", app.id, "app-13")
    check("app-only has no runtime", app.runtime == null && app.app?.versionCode == 13)
    eq("app-only records the bundled upstream version", app.upstreamVersion, "2.0.41")

    val (rt, rtBytes, rtSig) = load("pkg-rt")

    check("runtime-only signature", OtaVerifier.verifySignature(rtBytes, rtSig, keys))
    eq("runtime-only id", rt.id, "rt-2.0.41-1761000002")
    check("runtime-only has no app", rt.app == null && rt.runtime != null)

    // And the phone's selection logic prefers the right ones.
    fun offer(x: OtaManifest) = Offer(x, ReleaseInfo(GithubReleases.TAG_PREFIX + x.id, "", emptyMap()))

    val picks = OtaPolicy.pick(listOf(offer(rt), offer(app), offer(m)), installedSeq = 1760000000, appVersionCode = 10, blocked = emptySet())

    eq("selection runtime", picks.runtime?.manifest?.id, rt.id)
    eq("selection app", picks.app?.manifest?.id, app.id)
}

fun testGuestPaths() {
    val base = Files.createTempDirectory("guest").toFile()

    try {
        val rootfs = File(base, "rootfs").apply { mkdirs() }
        val home = File(base, "home").apply { mkdirs() }
        val shared = File(base, "shared").apply { mkdirs() }
        val secret = File(base, "secret.txt").apply { writeText("host only") }

        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/hostname").writeText("box")
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "tmp/out.txt").writeText("out")
        File(home, "proj").mkdirs()
        File(home, "proj/index.html").writeText("<html>")
        File(shared, "Download").mkdirs()
        File(shared, "Download/a.txt").writeText("a")
        File(home, "proj/dir").mkdirs()

        // Absolute links inside the guest point at the guest, never at the phone.
        Files.createSymbolicLink(File(rootfs, "tmp/abs").toPath(), java.nio.file.Paths.get("/etc/hostname"))
        Files.createSymbolicLink(File(rootfs, "tmp/rel").toPath(), java.nio.file.Paths.get("out.txt"))
        Files.createSymbolicLink(File(rootfs, "tmp/up").toPath(), java.nio.file.Paths.get("../etc/hostname"))
        Files.createSymbolicLink(File(rootfs, "tmp/host").toPath(), java.nio.file.Paths.get(secret.path))
        Files.createSymbolicLink(File(rootfs, "tmp/climb").toPath(), java.nio.file.Paths.get("../../../../../../../../../../${secret.path.removePrefix("/")}"))
        Files.createSymbolicLink(File(rootfs, "tmp/loop").toPath(), java.nio.file.Paths.get("loop"))
        Files.createSymbolicLink(File(home, "proj/toroot").toPath(), java.nio.file.Paths.get("/tmp/out.txt"))
        Files.createSymbolicLink(File(rootfs, "tmp/tohome").toPath(), java.nio.file.Paths.get("/root/proj/index.html"))
        Files.createSymbolicLink(File(rootfs, "tmp/dirlink").toPath(), java.nio.file.Paths.get("/root/proj"))

        val g = GuestPaths(rootfs, home, shared)

        eq("rootfs file", g.resolve("/etc/hostname")?.readText(), "box")
        eq("home mapped", g.resolve("/root/proj/index.html")?.readText(), "<html>")
        eq("sdcard mapped", g.resolve("/sdcard/Download/a.txt")?.readText(), "a")
        eq("storage path mapped", g.resolve("/storage/emulated/0/Download/a.txt")?.readText(), "a")
        eq("dot segments", g.resolve("/root/proj/./../proj/index.html")?.readText(), "<html>")
        eq("absolute link stays in guest", g.resolve("/tmp/abs")?.readText(), "box")
        eq("relative link", g.resolve("/tmp/rel")?.readText(), "out")
        eq("parent link", g.resolve("/tmp/up")?.readText(), "box")
        eq("link into home", g.resolve("/tmp/tohome")?.readText(), "<html>")
        eq("link from home to rootfs", g.resolve("/root/proj/toroot")?.readText(), "out")
        eq("link through a directory", g.resolve("/tmp/dirlink/index.html")?.readText(), "<html>")
        eq("host path link is a guest path", g.resolve("/tmp/host"), null)
        eq("climbing link is clamped to the guest root", g.resolve("/tmp/climb"), null)
        eq("direct climb", g.resolve("/../../../${secret.path.removePrefix("/")}"), null)
        eq("link loop", g.resolve("/tmp/loop"), null)
        eq("directory", g.resolve("/root/proj/dir"), null)
        eq("missing", g.resolve("/root/nothing"), null)
        eq("relative path", g.resolve("etc/hostname"), null)
        eq("nul byte", g.resolve("/etc/host\u0000name"), null)
        eq("host file is not reachable by name", g.resolve(secret.path), null)

        val closed = GuestPaths(rootfs, home, null)

        eq("shared storage not bound", closed.resolve("/sdcard/Download/a.txt"), null)
        eq("home still works", closed.resolve("/root/proj/index.html")?.readText(), "<html>")
    } finally {
        Fs.deleteTree(base)
    }
}

fun testMiniJsonWrite() {
    eq(
        "write mixed",
        MiniJson.write(linkedMapOf<String, Any?>("a" to 1L, "b" to "x\"y", "c" to listOf(true, null, 2.5), "d" to linkedMapOf<String, Any?>("e" to "текст"))),
        """{"a":1,"b":"x\"y","c":[true,null,2.5],"d":{"e":"текст"}}""",
    )
    eq("write int", MiniJson.write(42), "42")
    eq("write bool", MiniJson.write(false), "false")
    eq("write null", MiniJson.write(null), "null")
    eq("write double whole", MiniJson.write(3.0), "3")
    eq("write roundtrip", MiniJson.parse(MiniJson.write(MiniJson.parse("""{"x":[1,{"y":"z"}]}"""))), MiniJson.parse("""{"x":[1,{"y":"z"}]}"""))
}

fun testLoginFault() {
    check("transport error retries", LoginFault.isRetryable("Transport error (POST https://opencode.ai/console/auth/device/code)"))
    check("http 502 retries", LoginFault.isRetryable("HTTP 502"))
    check("dns retries", LoginFault.isRetryable("Unable to resolve host \"opencode.ai\""))
    check("timeout retries", LoginFault.isRetryable("connect timed out"))
    check("method mismatch does not retry", !LoginFault.isRetryable("The server has no account sign-in method"))
    check("http 404 does not retry", !LoginFault.isRetryable("HTTP 404"))
    check("empty does not retry", !LoginFault.isRetryable(""))
    check("null does not retry", !LoginFault.isRetryable(null))
    check("friendly transport", LoginFault.friendly("Transport error (POST https://opencode.ai/console/auth/device/code)").contains("opencode.ai"))
    eq("friendly passthrough", LoginFault.friendly("HTTP 401"), "HTTP 401")
}

fun testOpencodeStore() {
    val base = Files.createTempDirectory("store").toFile()

    try {
        val home = File(base, "home").apply { mkdirs() }

        // Plugin file names
        eq("name plain", OpencodeStore.pluginFileName("my.js"), "my.js")
        eq("name ts kept", OpencodeStore.pluginFileName("tool.ts"), "tool.ts")
        eq("name adds js", OpencodeStore.pluginFileName("plugin"), "plugin.js")
        eq("name strips path", OpencodeStore.pluginFileName("/etc/passwd"), "passwd.js")
        eq("name cleans", OpencodeStore.pluginFileName("my plugin!.js"), "my_plugin_.js")
        eq("name mjs", OpencodeStore.pluginFileName("a.mjs"), "a.mjs")

        // Install from bytes, list, delete
        val installed = OpencodeStore.installPluginBytes(home, "export const P = 1".toByteArray(), "nice plugin.js")

        eq("installed name", installed.name, "nice_plugin.js")
        check("installed exists", installed.exists())
        eq("listed", OpencodeStore.localPlugins(home).map { it.name }, listOf(installed.name))
        check("delete works", OpencodeStore.deletePlugin(home, installed))

        // A HTML page is refused as a plugin
        check("html is not a script", !OpencodeStore.looksLikeScript("x.js", "<!doctype html><html></html>".toByteArray()))
        check("script passes", OpencodeStore.looksLikeScript("x.js", "export const x = 1".toByteArray()))

        // Provider keys in auth.json
        OpencodeStore.setProviderKey(home, "anthropic", "sk-ant-0123456789abcdef")
        eq("key saved", OpencodeStore.connectedKeys(home)["anthropic"], "sk-ant...cdef")
        check("raw key on disk", OpencodeStore.authFile(home).readText().contains("sk-ant-0123456789abcdef"))
        eq("key masked", OpencodeStore.mask("sk-ant-0123456789abcdef"), "sk-ant...cdef")
        OpencodeStore.clearProviderKey(home, "anthropic")
        check("key cleared", OpencodeStore.connectedKeys(home).isEmpty())

        // Custom providers in the config
        OpencodeStore.addCustomProvider(
            home,
            CustomProvider("myapi", "My API", "https://api.example.com/v1", "sk-custom-1234567890", listOf("glm-4.6" to "GLM 4.6")),
        )

        val providers = OpencodeStore.customProviders(home)

        eq("provider listed", providers.map { it.id }, listOf("myapi"))
        eq("provider base", providers.first().baseUrl, "https://api.example.com/v1")
        eq("provider models", providers.first().models, listOf("glm-4.6" to "GLM 4.6"))
        eq("provider key also in auth", OpencodeStore.connectedKeys(home)["myapi"], "sk-cus...7890")
        eq("provider npm untouched", OpencodeStore.npmPlugins(home), emptyList())

        OpencodeStore.setDefaultModel(home, "myapi", "glm-4.6")
        eq("default model", OpencodeStore.defaultModel(home), "myapi/glm-4.6")

        OpencodeStore.removeCustomProvider(home, "myapi")
        check("provider removed", OpencodeStore.customProviders(home).isEmpty())
        eq("default model cleaned", OpencodeStore.defaultModel(home), null)
        check("auth entry removed", OpencodeStore.connectedKeys(home).isEmpty())

        // npm plugins
        OpencodeStore.addNpmPlugin(home, "opencode-example-plugin")
        OpencodeStore.addNpmPlugin(home, "opencode-example-plugin")
        eq("npm plugin once", OpencodeStore.npmPlugins(home), listOf("opencode-example-plugin"))
        OpencodeStore.removeNpmPlugin(home, "opencode-example-plugin")
        check("npm plugin removed", OpencodeStore.npmPlugins(home).isEmpty())

        // Rules: a global one and ones for specific models
        OpencodeStore.saveRule(home, null, "Global style", null, "Be brief.\n")
        OpencodeStore.saveRule(home, null, "Claude rule", "anthropic/claude-sonnet-4-5", "Use tools carefully.")
        OpencodeStore.saveRule(home, null, "Bare model", "glm-4.6", "Short answers.")

        val rules = OpencodeStore.rules(home)

        eq("three rules", rules.size, 3)
        eq("global rule file", rules.first { it.model == null }.fileName.endsWith(".md"), true)

        val instructions = (OpencodeStore.readMap(OpencodeStore.configFile(home))["instructions"] as? List<*>).orEmpty()

        eq("one global instruction", instructions.size, 1)
        check("instruction is a guest path", (instructions.first() as String).startsWith("/root/.config/opencode/rules/"))

        val plugin = File(OpencodeStore.pluginsDir(home), OpencodeStore.RULES_PLUGIN)

        check("rules plugin written", plugin.exists())
        check("plugin has model rule", plugin.readText().contains("anthropic/claude-sonnet-4-5"))
        check("plugin has bare model rule", plugin.readText().contains("glm-4.6"))
        check("plugin has hook", plugin.readText().contains("experimental.chat.system.transform"))

        // Editing and deleting
        val edited = OpencodeStore.saveRule(home, rules.first { it.name == "Global style" }, "Global style v2", null, "Be very brief.")

        eq("renamed", edited.name, "Global style v2")
        eq("content updated", OpencodeStore.ruleContent(home, edited), "Be very brief.")
        eq("still one global instruction", (OpencodeStore.readMap(OpencodeStore.configFile(home))["instructions"] as? List<*>).orEmpty().size, 1)

        for (rule in OpencodeStore.rules(home)) OpencodeStore.deleteRule(home, rule)

        check("no rules left", OpencodeStore.rules(home).isEmpty())
        eq("instructions removed", (OpencodeStore.readMap(OpencodeStore.configFile(home))["instructions"] as? List<*>).orEmpty().size, 0)
    } finally {
        Fs.deleteTree(base)
    }
}

fun main(args: Array<String>) {
    val fixtures = File(args[0])

    testJson()
    testSse()
    testUrls()
    testTar(fixtures)
    testFs()
    testProot()
    testPortPicker()
    testProfiles()
    testLogin()
    testLoginFault()
    testMiniJsonWrite()
    testHttpTransport()
    testOta(fixtures)
    testBridge()
    testOtaPackage(fixtures)
    testGuestPaths()
    testOpencodeStore()
    testWarp()
    testNetworkFeatures()

    println("passed: $passed, failed: ${failures.size}")
    failures.forEach { println("FAIL: $it") }

    if (failures.isNotEmpty()) System.exit(1)
}

// Cloudflare WARP: keys, registration, config generation, proxy environment.

fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

fun testWarp() {
    // RFC 7748 section 5.2 test vectors for X25519.
    eq(
        "x25519 vector 1",
        toHex(Warp.scalarMult(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))),
        "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
    )
    eq(
        "x25519 vector 2",
        toHex(Warp.scalarMult(hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"), hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"))),
        "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
    )

    // A key pair must agree as Diffie-Hellman: both sides derive the same shared secret.
    val (privA, pubA) = Warp.keygen()
    val (privB, pubB) = Warp.keygen()

    // The Cloudflare API and wireproxy both want STANDARD base64 (44 chars with padding), not base64url.
    eq("keygen b64 length", privA.length, 44)
    eq("keygen b64 padding", privA.last(), '=')
    eq("keygen raw size", Warp.b64Bytes(privA).size, 32)

    eq(
        "x25519 shared secret agrees",
        toHex(Warp.scalarMult(Warp.b64Bytes(privA), Warp.b64Bytes(pubB))),
        toHex(Warp.scalarMult(Warp.b64Bytes(privB), Warp.b64Bytes(pubA))),
    )
    eq("public key derives from private", toHex(Warp.publicKey(Warp.b64Bytes(privA))), toHex(Warp.b64Bytes(pubA)))

    // The 6.x reply shape: the interface lives in config.client.interface, the endpoint host carries the port.
    val reply = """
        {"id":"warp-id-1","account_type":"free","config":{"client_id":"aGVsbG8","peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":{"v4":"162.159.192.1:2408","v6":"[2606:4700:d0::a29f:c001]:2408","host":"162.159.192.1:2408"}}],"client":{"interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:8a5c:1a2b:3c4d:5e6f:7a8b/128"}}}},"key":"$pubA"}
    """.trimIndent()

    val account = Warp.parseRegistration(reply, privA)

    eq("account id", account.id, "warp-id-1")
    eq("account v4", account.addressV4, "172.16.0.2")
    eq("account v6", account.addressV6, "2606:4700:110:8a5c:1a2b:3c4d:5e6f:7a8b/128")
    eq("account peer", account.peerKey, "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
    eq("account endpoint ip", account.endpoint, "162.159.192.1:2408")
    eq("account key kept", account.privateKey, privA)

    // The 7.x reply shape (captured from the live API): the interface moved to config.interface and every endpoint
    // port is 0 - the real port comes from the host field and the ports list.
    val reply7 = """
        {"id":"warp-id-2","config":{"client_id":"EXIP","peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":{"v4":"162.159.192.7:0","v6":"[2606:4700:d0::a29f:c007]:0","host":"162.159.192.7:0","ports":[2408,500,1701,4500]}}],"interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:8142:cdc7:6b3c:fa61:d29c"}}}}
    """.trimIndent()

    val account7 = Warp.parseRegistration(reply7, privA)

    eq("7.x id", account7.id, "warp-id-2")
    eq("7.x v4", account7.addressV4, "172.16.0.2")
    eq("7.x v6", account7.addressV6, "2606:4700:110:8142:cdc7:6b3c:fa61:d29c")
    eq("7.x endpoint port from list", account7.endpoint, "162.159.192.7:2408")

    // An endpoint with no usable port anywhere still falls back to the default WARP port.
    val replyNoPort = """
        {"id":"warp-id-3","config":{"peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":{"v4":"162.159.192.9:0"}}],"interface":{"addresses":{"v4":"172.16.0.3"}}}}
    """.trimIndent()

    eq("endpoint default port", Warp.parseRegistration(replyNoPort, privA).endpoint, "162.159.192.9:2408")

    throws("broken reply refused") { Warp.parseRegistration("""{"config":{}}""", privA) }

    // register() walks the client-version list until one works and sends the public key.
    val seenBodies = ArrayList<String>()
    val seenVersions = ArrayList<String>()
    val seenAgents = ArrayList<String>()
    val transport = object : Warp.Transport {
        override fun post(url: String, body: String, headers: Map<String, String>): Warp.Reply {
            seenBodies += body
            seenVersions += headers.getValue("CF-Client-Version")
            seenAgents += headers.getValue("User-Agent")

            return if (headers.getValue("CF-Client-Version") == Warp.CLIENT_VERSIONS.first()) {
                Warp.Reply(403, """{"message":"blocked"}""")
            } else {
                Warp.Reply(200, reply)
            }
        }
    }

    val registered = Warp.register(transport, privA, pubA)

    eq("register walked to version 2", seenVersions, listOf(Warp.CLIENT_VERSIONS[0], Warp.CLIENT_VERSIONS[1]))
    check("register payload has the public key", seenBodies[0].contains("\"key\":\"$pubA\""))
    // Without the 1.1.1.1 app's User-Agent Cloudflare bans the request with 403 "error code: 1010".
    check("register sends the app user agent", seenAgents.all { it == Warp.USER_AGENT })
    eq("register result", registered.id, "warp-id-1")

    // The wireproxy config carries both proxies and the endpoint as an IP literal; bare addresses gain prefixes.
    val conf = Warp.conf(account)

    check("conf private key", conf.contains("PrivateKey = $privA"))
    check("conf addresses", conf.contains("Address = 172.16.0.2/32, 2606:4700:110:8a5c:1a2b:3c4d:5e6f:7a8b/128"))
    check("conf endpoint", conf.contains("Endpoint = 162.159.192.1:2408"))
    check("conf socks port", conf.contains("BindAddress = 127.0.0.1:${Warp.SOCKS_PORT}"))
    check("conf http port", conf.contains("BindAddress = 127.0.0.1:${Warp.HTTP_PORT}"))
    check("conf allowedips", conf.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
    check("conf bare v6 gains /128", Warp.conf(account7).contains("2606:4700:110:8142:cdc7:6b3c:fa61:d29c/128"))

    // The account file survives a round trip.
    val dir = Files.createTempDirectory("warp").toFile()

    try {
        val file = File(dir, "account.json")

        Warp.saveAccount(file, account)
        eq("account round trip", Warp.loadAccount(file), account)
        eq("missing account is null", Warp.loadAccount(File(dir, "nope.json")), null)
    } finally {
        dir.deleteRecursively()
    }

    // The server environment points at the local tunnel and never proxies loopback traffic.
    val proxied = ProotCommand.guestEnv(cfg(proxy = Warp.PROXY_URL))

    eq("proxy env http", proxied["HTTP_PROXY"], Warp.PROXY_URL)
    eq("proxy env https", proxied["HTTPS_PROXY"], Warp.PROXY_URL)
    check("proxy env no_proxy", proxied["NO_PROXY"]?.contains("127.0.0.1") == true)
    check("proxy env lowercase", proxied["http_proxy"] == Warp.PROXY_URL)

    // Node's built-in fetch only honours the proxy variables with this switch on.
    eq("node env proxy switch", proxied["NODE_USE_ENV_PROXY"], "1")

    // Cloudflare trace parsing: egress IP and whether WARP carried the request.
    eq("trace parsed", Warp.parseTrace("fl=1\nip=104.28.1.2\nwarp=on\nloc=NL\n"), Warp.Trace("104.28.1.2", true))
    eq("trace without warp", Warp.parseTrace("ip=1.2.3.4\nwarp=off\n"), Warp.Trace("1.2.3.4", false))
    eq("trace without ip", Warp.parseTrace("warp=on\n"), null)
    eq("trace warp=plus carries", Warp.parseTrace("ip=104.28.9.9\nwarp=plus\n"), Warp.Trace("104.28.9.9", true))

    // Only a trace Cloudflare itself confirms as WARP-carried proves the tunnel; anything else is a plain request.
    eq("confirmed WARP trace accepted", Warp.confirmedTrace(Warp.Trace("104.28.1.2", true)), Warp.Trace("104.28.1.2", true))
    eq("unconfirmed WARP trace rejected", Warp.confirmedTrace(Warp.Trace("1.2.3.4", false)), null)
    eq("missing trace rejected", Warp.confirmedTrace(null), null)

    // A route that is required must provide a proxy before the server may start; an optional one may be direct.
    check("required route fails closed without proxy", !Warp.proxySatisfiesRequirement(true, null))
    check("required route fails closed on empty proxy", !Warp.proxySatisfiesRequirement(true, ""))
    check("required route accepts a verified proxy", Warp.proxySatisfiesRequirement(true, Warp.PROXY_URL))
    check("optional route permits direct mode", Warp.proxySatisfiesRequirement(false, null))

    // Resolver failures cannot differ between client versions; everything else can (a new route, a new attempt).
    check(
        "dns failure recognized",
        Warp.isDnsFailure("Unable to resolve host \"api.cloudflareclient.com\": No address associated with hostname") &&
            Warp.isDnsFailure("unknownhost: api.cloudflareclient.com") &&
            Warp.isDnsFailure("java.net.UnknownHostException: eai_again"),
    )
    check("timeout is not a dns failure", !Warp.isDnsFailure("Read timed out"))
    check("http error is not a dns failure", !Warp.isDnsFailure("HTTP 403"))
    check("null message is not a dns failure", !Warp.isDnsFailure(null))

    // A transport error on one client version falls through to the next instead of escaping the loop.
    val retryCalls = ArrayList<String>()
    val retryTransport = object : Warp.Transport {
        override fun post(url: String, body: String, headers: Map<String, String>): Warp.Reply {
            retryCalls += headers.getValue("CF-Client-Version")

            if (retryCalls.size == 1) throw java.io.IOException("Read timed out")

            return Warp.Reply(200, reply)
        }
    }

    val retried = Warp.register(retryTransport, privA, pubA)

    eq("register survived a transport error", retried.id, "warp-id-1")
    eq("register retried on the next client version", retryCalls, listOf(Warp.CLIENT_VERSIONS[0], Warp.CLIENT_VERSIONS[1]))

    // A dead resolver fails at once: no point burning the remaining client versions on the same DNS.
    val dnsCalls = ArrayList<String>()
    val dnsTransport = object : Warp.Transport {
        override fun post(url: String, body: String, headers: Map<String, String>): Warp.Reply {
            dnsCalls += headers.getValue("CF-Client-Version")

            throw java.io.IOException("Unable to resolve host \"api.cloudflareclient.com\": No address associated with hostname")
        }
    }

    val dnsError = runCatching { Warp.register(dnsTransport, privA, pubA) }.exceptionOrNull()

    check("register fails fast on dns", dnsError is Warp.RegistrationException)
    eq("register did not retry dns", dnsCalls.size, 1)

    // The orphan sweep: a leftover wireproxy of an earlier app run runs from a different (deleted) install path,
    // so the match is on the file name, never on the full path - and never on this process itself.
    val procEntries = listOf(
        10 to "/data/app/~~x1/dev.opencode.mobile-A/lib/arm64/libwireproxy.so\u0000-c\u0000conf\u0000--silent",
        11 to "/data/app/~~x0/dev.opencode.mobile-OLD/lib/arm64/libwireproxy.so\u0000-c\u0000conf",
        12 to "/data/app/~~x1/dev.opencode.mobile-A/lib/arm64/libproot.so\u0000--kill-on-exit",
        13 to "libwireproxy.so\u0000-c\u0000conf",
        14 to "",
    )

    eq("sweep finds every wireproxy", Warp.wireproxyPids(procEntries.asSequence(), 1), listOf(10, 11, 13))
    eq("sweep never kills itself", Warp.wireproxyPids(procEntries.asSequence(), 10), listOf(11, 13))
    eq("sweep empty table", Warp.wireproxyPids(emptySequence<Pair<Int, String>>(), 1), emptyList<Int>())

    // The quoted tail of a tunnel log: the last lines within the cap, blank lines dropped.
    val longLog = (1..60).joinToString("\n") { "line $it" }

    check("log tail keeps the newest lines", Warp.logTail(longLog, 40).lines().first().let { it.startsWith("line 5") })
    check("log tail respects the cap", Warp.logTail(longLog, 40).length <= 40)
    check("log tail keeps whole lines", Warp.logTail("a\nb\nc", 40) == "a\nb\nc")
    eq("log tail empty log", Warp.logTail("", 40), "")
    eq("log tail blank log", Warp.logTail(" \n\n", 40), "")

    // Limit recognition on the event stream.
    check("free limit event", Limits.carriesLimit("session.step.failed", "{\"error\":{\"type\":\"provider.free-limit\",\"message\":\"Free usage limit reached\"}}"))
    check("rate limit event", Limits.carriesLimit("session.step.failed", "{\"error\":{\"type\":\"provider.rate-limit\",\"status\":429}}"))
    check("retry status with rate limit", Limits.carriesLimit("session.status", "{\"status\":{\"type\":\"retry\",\"message\":\"Rate limit exceeded\"}}"))
    check("unrelated event ignored", !Limits.carriesLimit("message.part.updated", "rate limit"))
    check("plain failure ignored", !Limits.carriesLimit("session.step.failed", "{\"error\":{\"message\":\"file not found\"}}"))

    val direct = ProotCommand.guestEnv(cfg())

    check("no node proxy switch without warp", direct["NODE_USE_ENV_PROXY"] == null)

    check("no proxy env without warp", direct.keys.none { it.equals("HTTP_PROXY", ignoreCase = true) })

    // Segfault classification: proot's signal 11 line and lowercase variants, but not unrelated text.
    check(
        "segfault detected",
        ProotCommand.segfaultIn("proot warning: signal 11 received from process -1") &&
            ProotCommand.segfaultIn("Segmentation fault (core dumped)") &&
            ProotCommand.segfaultIn("killed by SIGSEGV"),
    )
    check("no segfault in a clean log", !ProotCommand.segfaultIn("server listening on http://127.0.0.1:4099"))
}

// Network diagnostics and VLESS/Hysteria2 subscription parsing.

fun testNetworkFeatures() {
    val vless = VpnSubscriptionParser.parseUri(
        "vless://123e4567-e89b-12d3-a456-426614174000@de.example.net:443?encryption=none&security=reality&sni=example.net&fp=chrome&pbk=WwrPBX0txbX4KOtconVnvGwctm2v1jK7dGdOMOikvHo%3D&sid=abcd&type=ws&host=cdn.example.net&path=%2Fws#DE%20Frankfurt",
    )
    eq("VLESS protocol parsed", vless.protocol, "VLESS")
    eq("VLESS server host parsed", vless.host, "de.example.net")
    eq("VLESS port parsed", vless.port, 443)
    eq("VLESS uuid parsed", vless.user, "123e4567-e89b-12d3-a456-426614174000")
    eq("VLESS display label decoded", vless.name, "DE Frankfurt")

    // The Reality public key must survive the journey into the config in the exact form sing-box decodes:
    // unpadded URL-safe base64. A "PUBKEY" placeholder is not a key at all and must be refused.
    throws("reality key must be base64") { VpnSubscriptionParser.realityKey("PUBKEY") }
    throws("reality key must be 32 bytes") { VpnSubscriptionParser.realityKey("c2hvcnQ=") }

    // Standard base64 with "+" and "/" and padding is the common form in real links; sing-box wants URL-safe raw.
    val standardKey = "++++rd6rrbq+7/7t3q2+7/7t3q2+7/7t3q2+7/7t3q0="
    eq("reality key normalized to url-safe raw", VpnSubscriptionParser.realityKey(standardKey), "----rd6rrbq-7_7t3q2-7_7t3q2-7_7t3q2-7_7t3q0")
    eq("reality key accepted as url-safe raw", VpnSubscriptionParser.realityKey("WwrPBX0txbX4KOtconVnvGwctm2v1jK7dGdOMOikvHo="), "WwrPBX0txbX4KOtconVnvGwctm2v1jK7dGdOMOikvHo")
    eq("reality key accepted as unpadded standard", VpnSubscriptionParser.realityKey("WwrPBX0txbX4KOtconVnvGwctm2v1jK7dGdOMOikvHo"), "WwrPBX0txbX4KOtconVnvGwctm2v1jK7dGdOMOikvHo")

    val vlessConfig = VpnSubscriptionParser.singBoxConfig(vless, 25346)
    check("VLESS config has vless outbound", vlessConfig.contains("vless"))
    check("VLESS config carries Reality key", vlessConfig.contains("WwrPBX0txbX4KOtconVnvGwctm2v1jK7dGdOMOikvHo"))
    check("VLESS config has ws transport", vlessConfig.contains("ws"))
    check("local mixed proxy binds to loopback", vlessConfig.contains("127.0.0.1"))
    check("route final points at the proxy", vlessConfig.contains("proxy"))

    val hysteria = VpnSubscriptionParser.parseUri(
        "hysteria2://pass%2Bword@nl.example.net:443?sni=nl.example.net&obfs=salamander&obfs-password=secret#NL%20Amsterdam",
    )
    eq("Hysteria2 protocol parsed", hysteria.protocol, "Hysteria2")
    eq("Hysteria2 password decoded", hysteria.user, "pass+word")
    val hysteriaConfig = VpnSubscriptionParser.singBoxConfig(hysteria, 25346)
    check("Hysteria2 config has correct outbound", hysteriaConfig.contains("hysteria2"))
    check("Hysteria2 config includes obfuscation", hysteriaConfig.contains("salamander"))

    val extracted = VpnSubscriptionParser.parseSubscriptionText(
        "vless://123e4567-e89b-12d3-a456-426614174000@de.example.net:443?security=tls&sni=de.example.net#DE\n" +
            "hysteria2://pass@nl.example.net:443?sni=nl.example.net#NL",
    )
    eq("multi-node subscription parsed", extracted.size, 2)

    val plainVless = VpnSubscriptionParser.parseUri("vless://123e4567-e89b-12d3-a456-426614174000@de.example.net:443?security=tls&sni=de.example.net#DE")
    eq("plain TLS VLESS accepted", plainVless.parameters["security"], "tls")
    check("plain TLS config has no reality block", !VpnSubscriptionParser.singBoxConfig(plainVless, 25346).contains("reality"))

    val query = NetworkDiagnostics.makeDnsQuery("example.com", 0xBEEF)
    eq("DNS query id high byte", query[0].toInt() and 0xff, 0xBE)
    eq("DNS query id low byte", query[1].toInt() and 0xff, 0xEF)
    eq("DNS query has one question", (query[4].toInt() and 0xff) shl 8 or (query[5].toInt() and 0xff), 1)
    val response = ByteArray(12)
    response[0] = 0xBE.toByte(); response[1] = 0xEF.toByte()
    response[2] = 0x81.toByte(); response[3] = 0x80.toByte()
    response[6] = 0; response[7] = 1
    check("valid DNS response accepted", NetworkDiagnostics.validDnsResponse(response, 0xBEEF))
    response[3] = 0x83.toByte()
    check("DNS failure response rejected", !NetworkDiagnostics.validDnsResponse(response, 0xBEEF))
    eq("three DNS providers available", NetworkDiagnostics.resolvers.size, 3)
}
