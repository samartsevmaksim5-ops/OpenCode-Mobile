package dev.opencode.mobile

import java.io.File

/** Everything that decides how the Linux environment is started. Pure data, so it can be tested off-device. */
data class ProotConfig(
    val prootBin: String,
    val loader: String,
    val nativeLibDir: String,
    val rootfs: String,
    val home: String,
    val fakeProc: String,
    val prootTmp: String,
    val port: Int,
    val password: String,
    val bridgeUrl: String,
    val bridgeToken: String,
    val compat: Boolean,
    val bindSharedStorage: Boolean,
    val timeZone: String,
    val locale: String = "C.UTF-8",
    /** Host directory holding an over-the-air runtime build (`opencode` inside); null runs the bundled one. */
    val otaBinDir: String? = null,
    /** Local HTTP proxy (the WARP tunnel) for the server's outbound traffic; null connects directly. */
    val proxy: String? = null,
)

data class Launch(val command: List<String>, val env: Map<String, String>)

object ProotCommand {
    const val GUEST_BINARY = "/usr/local/bin/opencode"
    const val GUEST_OTA_DIR = "/opt/opencode-ota"
    const val GUEST_INSTRUCTIONS = "/etc/opencode-android/AGENTS.md"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** The prefix every proot-wrapped command shares: root filesystem, binds and the fake-root identity. */
    fun base(c: ProotConfig): List<String> {
        val args = ArrayList<String>()

        args += c.prootBin
        args += "--kill-on-exit"
        args += "--link2symlink"
        args += "-0"
        args += "-r"
        args += c.rootfs
        args += "-w"
        args += "/root"

        fun bind(host: String, guest: String = host) {
            args += "-b"
            args += if (host == guest) host else "$host:$guest"
        }

        bind("/dev")
        bind("/dev/urandom", "/dev/random")
        bind("/proc")
        bind("/proc/self/fd", "/dev/fd")
        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")
        bind("/sys")

        // Android hides most of /proc from apps, and some tools abort when it is missing or empty.
        for ((name, guest) in FakeProc.targets) bind(File(c.fakeProc, name).path, guest)

        bind(c.home, "/root")

        if (c.otaBinDir != null) bind(c.otaBinDir, GUEST_OTA_DIR)

        if (c.bindSharedStorage) {
            bind("/storage/emulated/0", "/sdcard")
            bind("/storage/emulated/0/Download", "/storage/emulated/0/Download")
        }

        return args
    }

    fun guestBinary(c: ProotConfig): String = if (c.otaBinDir != null) "$GUEST_OTA_DIR/opencode" else GUEST_BINARY

    fun hostEnv(c: ProotConfig): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        env["PROOT_TMP_DIR"] = c.prootTmp
        env["PROOT_LOADER"] = c.loader
        env["LD_LIBRARY_PATH"] = c.nativeLibDir
        env["TMPDIR"] = c.prootTmp

        if (c.compat) env["PROOT_NO_SECCOMP"] = "1"

        return env
    }

    fun guestEnv(c: ProotConfig, extra: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
        val env = LinkedHashMap<String, String>()

        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["SHELL"] = "/bin/bash"
        env["PATH"] = GUEST_PATH
        env["LANG"] = c.locale
        env["LC_ALL"] = c.locale
        env["TERM"] = "xterm-256color"
        env["TZ"] = c.timeZone
        env["TMPDIR"] = "/tmp"
        env["OPENCODE_PASSWORD"] = c.password
        env["OPENCODE_CLIENT"] = "android"
        env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        env["OPENCODE_CONFIG_CONTENT"] = "{\"instructions\":[${MiniJson.quote(GUEST_INSTRUCTIONS)}]}"
        env["OPENCODE_ANDROID_BRIDGE"] = c.bridgeUrl
        env["OPENCODE_ANDROID_TOKEN"] = c.bridgeToken
        env["BROWSER"] = "/usr/local/bin/android-open"

        if (c.compat) {
            // Features that lean on native code or kernel interfaces Android may not allow.
            env["OPENCODE_DISABLE_FFF"] = "1"
            env["OPENCODE_FILEWATCHER_DISABLE"] = "1"
        }

        // The WARP tunnel: everything the server sends to model providers leaves through Cloudflare. Local loopback
        // traffic (the web UI to the server) must never touch the proxy, or the app would break without a tunnel.
        if (!c.proxy.isNullOrEmpty()) {
            for (key in listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) env[key] = c.proxy
            for (key in listOf("NO_PROXY", "no_proxy")) env[key] = Warp.NO_PROXY
        }

        env.putAll(extra)

        return env
    }

    /**
     * True when the log shows the traced process died from SIGSEGV. proot prints "proot warning: signal 11 received
     * from process -1" for it; on a number of devices the server cannot even start because of it, and the
     * compatibility loader (PROOT_NO_SECCOMP=1) makes it go away - the service retries with that mode automatically.
     */
    fun segfaultIn(log: String): Boolean {
        val lower = log.lowercase()

        return "signal 11" in lower || "segmentation fault" in lower || "sigsegv" in lower
    }

    fun server(c: ProotConfig): Launch {
        val command = ArrayList(base(c))

        command += "/usr/bin/env"
        command += "-i"
        for ((k, v) in guestEnv(c)) command += "$k=$v"
        command += guestBinary(c)
        command += "serve"
        command += "--hostname"
        command += "127.0.0.1"
        command += "--port"
        command += c.port.toString()

        return Launch(command, hostEnv(c))
    }

    /** Runs one program in the Linux environment with the standard guest environment. */
    fun exec(c: ProotConfig, program: String, vararg args: String): Launch {
        val command = ArrayList(base(c))

        command += "/usr/bin/env"
        command += "-i"
        for ((k, v) in guestEnv(c)) command += "$k=$v"
        command += program
        command += args

        return Launch(command, hostEnv(c))
    }

    /** A login shell in the same environment, for diagnostics. */
    fun shell(c: ProotConfig, vararg args: String): Launch {
        val command = ArrayList(base(c))

        command += "/usr/bin/env"
        command += "-i"
        for ((k, v) in guestEnv(c)) command += "$k=$v"
        command += "/bin/bash"
        command += args

        return Launch(command, hostEnv(c))
    }
}

/** Stand-ins for the /proc files Android denies to apps, bound over the real ones. */
object FakeProc {
    val targets: List<Pair<String, String>> = listOf(
        "loadavg" to "/proc/loadavg",
        "stat" to "/proc/stat",
        "uptime" to "/proc/uptime",
        "version" to "/proc/version",
        "vmstat" to "/proc/vmstat",
        "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
        "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
    )

    fun contents(cpus: Int = Runtime.getRuntime().availableProcessors()): Map<String, String> {
        val stat = StringBuilder("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")

        for (i in 0 until maxOf(1, cpus)) stat.append("cpu$i 244 0 359 11660 32 42 31 10 0 0\n")

        stat.append("intr 63361 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1700000000\nprocesses 26442\n")
        stat.append("procs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")

        return mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to stat.toString(),
            "uptime" to "5400.12 4800.00\n",
            "version" to "Linux version 6.2.1-PRoot-Android (proot@android) (gcc (GCC) 12.2.0) #1 SMP PREEMPT Fri Jan 1 00:00:00 UTC 2021\n",
            "vmstat" to "nr_free_pages 1000000\nnr_inactive_anon 100000\nnr_active_anon 100000\npgfault 1000000\npgmajfault 1000\n",
            "cap_last_cap" to "40\n",
            "max_user_watches" to "4096\n",
        )
    }
}
