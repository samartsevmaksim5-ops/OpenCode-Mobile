package dev.opencode.mobile

import android.content.Context
import java.io.File

/** Every location the app uses, in one place. All of it is inside the app's private storage. */
class Paths(context: Context) {
    val files: File = context.filesDir

    val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    val prootBin: String = "$nativeLibDir/libproot.so"

    val prootLoader: String = "$nativeLibDir/libproot-loader.so"

    val rootfs = File(files, "rootfs")

    val rootfsStamp = File(files, "rootfs.stamp")

    val fakeProc = File(files, "fakeproc")

    val prootTmp = File(files, "tmp")

    val profilesDir = File(files, "profiles")

    val profilesFile = File(files, "profiles.json")

    val logs = File(files, "logs")

    val serverLog = File(logs, "server.log")

    val ota = File(files, "ota")

    val otaVersions = File(ota, "versions")

    val otaStaging = File(ota, "staging")

    val otaBackups = File(ota, "backup")

    val otaState = File(ota, "state.json")

    /** App-private storage for the VPN subscription feature: imported servers, sing-box config and its log. */
    val networkDir = File(files, "network")

    val vpnNodesFile = File(networkDir, "vpn-servers.txt")

    val vpnConfigFile = File(networkDir, "sing-box.json")

    val vpnLogFile = File(networkDir, "sing-box.log")

    val downloadsCache = File(context.cacheDir, "downloads")

    fun otaVersion(id: String) = File(otaVersions, id)

    fun ensure() {
        for (dir in listOf(files, fakeProc, prootTmp, profilesDir, logs, ota, otaVersions, networkDir, downloadsCache)) dir.mkdirs()
    }
}
