package dev.opencode.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.PublicKey
import java.security.cert.CertificateFactory

/** How many of the newest update releases are examined. Each check costs two small downloads per release. */
private const val MAX_RELEASES = 30

sealed interface CheckResult {
    data object NotConfigured : CheckResult

    data object UpToDate : CheckResult

    /** [runtime] is a new server build for this app version; [app] is a newer APK of the app itself. */
    data class Available(val runtime: Offer?, val app: Offer?) : CheckResult

    data class Failed(val message: String) : CheckResult
}

sealed interface DownloadResult {
    data class Staged(val version: String) : DownloadResult

    data object NeedsUnmeteredNetwork : DownloadResult

    data class Failed(val message: String) : DownloadResult
}

/**
 * Over-the-air updates of the OpenCode server build.
 *
 * What makes an update acceptable: it comes from a published (non-draft, non-pre-release) `ota-*` release of the
 * configured repository, its manifest is signed with the same key that signed this app, the manifest names this very
 * release, it is newer than what is installed, the downloaded file matches the announced size and SHA-256, it passes
 * a run of `--version` inside the Linux environment, and it is only switched to at the next server start with the
 * profile's database saved first. A build that then fails to start is rolled back and never offered again.
 */
class OtaManager(private val context: Context, private val paths: Paths, private val prefs: Prefs) {
    private val state = OtaState(paths.otaState)

    val current: String get() = synchronized(this) { state.currentVersion ?: BuildConfig.UPSTREAM_VERSION }

    val stagedVersion: String? get() = synchronized(this) { state.stagedVersion }

    val lastCheck: Long get() = synchronized(this) { state.lastCheck }

    val trial: Boolean get() = synchronized(this) { state.trial != null }

    // Checking
    fun check(): CheckResult {
        val repo = prefs.otaRepo

        if (!GithubReleases.valid(repo)) return CheckResult.NotConfigured

        val releases = try {
            GithubReleases.parse(getBytes("https://api.github.com/repos/$repo/releases?per_page=30", 4 * 1024 * 1024).toString(Charsets.UTF_8)).take(MAX_RELEASES)
        } catch (e: Exception) {
            return CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }

        val keys = signingKeys()

        if (keys.isEmpty()) return CheckResult.Failed("The app's signing certificate is unavailable")

        val installedSeq = synchronized(this) { state.effectiveSeq(BuildConfig.RUNTIME_SEQ) }
        val blocked = synchronized(this) { state.blocked }
        val verified = ArrayList<Offer>()
        var transportFails = 0

        for (release in releases) {
            val manifestUrl = release.assets["manifest.json"] ?: continue
            val signatureUrl = release.assets["manifest.json.sig"] ?: continue

            val manifestBytes: ByteArray
            val signature: ByteArray

            try {
                manifestBytes = getBytes(manifestUrl, 64 * 1024)
                signature = getBytes(signatureUrl, 8 * 1024)
            } catch (e: Exception) {
                Log.w("ota", "${release.tag}: ${e.message}")

                // The network just went away: hammering the remaining 29 releases each with two GETs only spams
                // "Software caused connection abort" and slows the next check; fail fast after a couple of tries.
                if (++transportFails >= 3) return CheckResult.Failed(e.message ?: e.javaClass.simpleName)

                continue
            }

            if (!OtaVerifier.verifySignature(manifestBytes, signature, keys)) {
                Log.w("ota", "${release.tag}: signature does not match, ignoring this release")
                continue
            }

            val manifest = try {
                OtaManifest.parse(manifestBytes)
            } catch (e: OtaException) {
                Log.w("ota", "${release.tag}: ${e.message}")
                continue
            }

            // A valid manifest must not be replayable under another release.
            if (release.tag != GithubReleases.TAG_PREFIX + manifest.id) {
                Log.w("ota", "${release.tag}: manifest id ${manifest.id} does not match the tag")
                continue
            }

            verified += Offer(manifest, release)
        }

        synchronized(this) { state.checked(System.currentTimeMillis()) }

        if (verified.isEmpty()) {
            return if (releases.isEmpty()) CheckResult.UpToDate else CheckResult.Failed(tr("Нет ни одного проверенного обновления", "No release could be verified"))
        }

        val picks = OtaPolicy.pick(verified, installedSeq, BuildConfig.VERSION_CODE, blocked)

        return if (picks.runtime != null || picks.app != null) CheckResult.Available(picks.runtime, picks.app) else CheckResult.UpToDate
    }

    // Downloading
    /** Downloads the runtime build, checks it, runs [selfTest] on it and stages it for the next server start. */
    fun download(
        update: Offer,
        force: Boolean,
        selfTest: (dir: File, manifest: OtaManifest) -> Boolean,
        progress: (Int) -> Unit,
    ): DownloadResult {
        val manifest = update.manifest
        val asset = manifest.runtime ?: return DownloadResult.Failed("This release has no server build")

        if (!force && prefs.otaWifiOnly && !unmetered()) return DownloadResult.NeedsUnmeteredNetwork

        val url = update.release.assets[asset.name] ?: return DownloadResult.Failed("Release has no file ${asset.name}")
        val archive = File(paths.otaStaging, "${manifest.id}.tar.gz")
        val extracted = File(paths.otaVersions, "${manifest.id}.tmp")
        val final = paths.otaVersion(manifest.id)

        try {
            paths.otaStaging.mkdirs()
            Fs.deleteTree(extracted)

            val free = paths.ota.usableSpace

            if (free < asset.size * 4) {
                return DownloadResult.Failed(tr("Недостаточно места для обновления", "Not enough free space for the update"))
            }

            fetch(url, asset.size, asset.sha256, archive) { progress((it * 80 / 100).coerceIn(0, 80)) }

            val extractor = TarGzExtractor(extracted, AndroidFs)

            FileInputStream(archive).use { extractor.extract(it) }

            val entries = extracted.listFiles().orEmpty()
            val binary = File(extracted, asset.entry)

            if (extractor.skipped > 0 || !binary.isFile || binary.length() < 1_000_000 || entries.size > 8 || entries.any { !it.isFile }) {
                throw OtaException("The archive does not look like a server build")
            }

            AndroidFs.chmod(binary, 493)
            progress(90)

            if (!selfTest(extracted, manifest)) {
                synchronized(this) { state.block(manifest.id) }

                throw OtaException(tr("Новая сборка не запустилась на этом устройстве и отклонена", "The new build did not run on this device and was rejected"))
            }

            Fs.deleteTree(final)

            if (!extracted.renameTo(final)) throw IOException("Could not move the build into place")

            synchronized(this) {
                state.stage(manifest.id, manifest.seq, manifest.upstreamVersion)
                prune()
            }

            progress(100)

            return DownloadResult.Staged(manifest.upstreamVersion)
        } catch (e: Exception) {
            Fs.deleteTree(extracted)
            Log.w("ota", "download failed: ${e.message}")

            return DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            archive.delete()
        }
    }

    /** Downloads [url] to [dest] and fails unless it has exactly the announced size and SHA-256. Used for the APK too. */
    fun fetch(url: String, size: Long, sha256: String, dest: File, progress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        dest.delete()

        val http = open(url)

        try {
            if (http.responseCode != 200) throw IOException("HTTP ${http.responseCode}")

            val (hash, length) = FileOutputStream(dest).use { out ->
                OtaVerifier.copyHashed(http.inputStream, out, size) { done -> progress((done * 100 / size).toInt().coerceIn(0, 100)) }
            }

            if (length != size) throw OtaException(tr("Размер файла не совпадает с объявленным", "File size differs from the announced size"))
            if (hash != sha256) throw OtaException(tr("Контрольная сумма не совпала", "Checksum mismatch"))
        } catch (e: Exception) {
            dest.delete()

            throw e
        } finally {
            http.disconnect()
        }
    }

    // Applying
    /**
     * Called before every local server start. Switches to a staged build (after saving the profile's data) and returns
     * the directory of the build to run, or null for the one bundled in the app.
     */
    @Synchronized
    fun prepareStart(profile: Profile, apply: Boolean, auto: Boolean = true): File? {
        if (state.supersededBy(BuildConfig.RUNTIME_SEQ)) {
            Log.i("ota", "the bundled build is newer than the downloaded one; dropping the download")
            dropAll()
        }

        if (state.staged != null && state.trial == null && (apply || (auto && prefs.otaAuto))) {
            if (backup(profile)) {
                state.promote()
                Log.i("ota", "switching to ${state.current}")
            } else {
                Log.w("ota", "could not save the profile data first; the update waits")
            }
        }

        val id = state.current ?: return null
        val dir = paths.otaVersion(id)

        if (!File(dir, "opencode").isFile) {
            Log.w("ota", "build $id is missing on disk")
            state.forgetCurrent()

            return null
        }

        return dir
    }

    /** The new build answered and stayed up: keep it and forget the saved data. */
    @Synchronized
    fun confirm() {
        if (state.trial == null) return

        state.confirm()
        Fs.deleteTree(paths.otaBackups)
        prune()
    }

    /** The new build failed to start. Goes back to the previous one and restores the saved data. Returns the rejected id. */
    @Synchronized
    fun failTrial(profile: Profile): String? {
        val bad = state.rollback() ?: return null

        restore(profile)
        Fs.deleteTree(paths.otaVersion(bad))
        prune()

        return bad
    }

    @Synchronized
    fun discardStaged() {
        val id = state.staged ?: return

        state.clearStaged()
        Fs.deleteTree(paths.otaVersion(id))
    }

    @Synchronized
    private fun dropAll() {
        for (id in state.keep()) Fs.deleteTree(paths.otaVersion(id))

        state.forgetCurrent()
        state.clearStaged()
    }

    private fun prune() {
        val keep = state.keep()

        for (dir in paths.otaVersions.listFiles().orEmpty()) {
            if (dir.name !in keep) Fs.deleteTree(dir)
        }
    }

    // Saving the profile's data
    private fun dataDir(profile: Profile) = File(Profiles.homeDir(paths.profilesDir, profile), ".local/share/opencode")

    private val dataFile = { name: String ->
        name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm") || name.endsWith(".sqlite") || name.endsWith(".json")
    }

    private fun backup(profile: Profile): Boolean {
        return try {
            val source = dataDir(profile)
            val size = Fs.sizeFlat(source, dataFile)

            if (paths.ota.usableSpace < size * 2 + 200L * 1024 * 1024) return false

            val target = File(paths.otaBackups, profile.id)

            Fs.deleteTree(target)
            Fs.copyFlat(source, target, dataFile)
            File(target, ".complete").writeText("1")

            true
        } catch (e: Exception) {
            Log.w("ota", "backup failed: ${e.message}")

            false
        }
    }

    private fun restore(profile: Profile) {
        try {
            val from = File(paths.otaBackups, profile.id)

            if (!File(from, ".complete").exists()) return

            val into = dataDir(profile)

            for (file in into.listFiles().orEmpty()) if (file.isFile && dataFile(file.name)) file.delete()

            Fs.copyFlat(from, into) { it != ".complete" }
            Fs.deleteTree(paths.otaBackups)
        } catch (e: Exception) {
            Log.e("ota", "restoring the saved data failed", e)
        }
    }

    // Plumbing
    private fun unmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun unmeteredNow(): Boolean = unmetered()

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Only https addresses are allowed" }

        val http = URL(url).openConnection() as HttpURLConnection

        http.connectTimeout = 15_000
        http.readTimeout = 30_000
        http.instanceFollowRedirects = true
        http.setRequestProperty("User-Agent", "OpenCode-Android/${BuildConfig.VERSION_NAME}")
        http.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")

        return http
    }

    private fun getBytes(url: String, limit: Int): ByteArray {
        val http = open(url)

        try {
            if (http.responseCode != 200) throw IOException("HTTP ${http.responseCode}")

            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)

            http.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)

                    if (n < 0) break

                    out.write(buffer, 0, n)

                    if (out.size() > limit) throw IOException("Reply is too large")
                }
            }

            return out.toByteArray()
        } finally {
            http.disconnect()
        }
    }

    /** The certificates that signed this app. Updates must be signed with the same key. */
    fun signingKeys(): List<PublicKey> {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo

            if (info == null) emptyList() else if (info.hasMultipleSigners()) info.apkContentsSigners.toList() else info.signingCertificateHistory.toList()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }

        val factory = CertificateFactory.getInstance("X.509")

        return signatures.mapNotNull { sig ->
            runCatching { factory.generateCertificate(sig.toByteArray().inputStream()).publicKey }.getOrNull()
        }
    }
}
