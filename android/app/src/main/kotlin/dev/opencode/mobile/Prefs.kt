package dev.opencode.mobile

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("opencode", Context.MODE_PRIVATE)

    /** Local server or a server running elsewhere. */
    var remote: Boolean
        get() = sp.getBoolean("remote", false)
        set(value) = sp.edit().putBoolean("remote", value).apply()

    var remoteUrl: String
        get() = sp.getString("remote_url", "") ?: ""
        set(value) = sp.edit().putString("remote_url", value).apply()

    var remotePassword: String
        get() = sp.getString("remote_password", "") ?: ""
        set(value) = sp.edit().putString("remote_password", value).apply()

    /** Slower, more cautious process tracing for devices where the default makes the server crash. */
    var compat: Boolean
        get() = sp.getBoolean("compat", false)
        set(value) = sp.edit().putBoolean("compat", value).apply()

    var bindStorage: Boolean
        get() = sp.getBoolean("bind_storage", true)
        set(value) = sp.edit().putBoolean("bind_storage", value).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(value) = sp.edit().putBoolean("keep_awake", value).apply()

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", true)
        set(value) = sp.edit().putBoolean("notify", value).apply()

    var otaAuto: Boolean
        get() = sp.getBoolean("ota_auto", true)
        set(value) = sp.edit().putBoolean("ota_auto", value).apply()

    var otaWifiOnly: Boolean
        get() = sp.getBoolean("ota_wifi_only", true)
        set(value) = sp.edit().putBoolean("ota_wifi_only", value).apply()

    /** Empty means the repository this app was built from. */
    var otaRepoOverride: String
        get() = sp.getString("ota_repo", "") ?: ""
        set(value) = sp.edit().putString("ota_repo", value.trim()).apply()

    val otaRepo: String get() = otaRepoOverride.ifEmpty { BuildConfig.OTA_REPO }

    var floatingMenu: Boolean
        get() = sp.getBoolean("floating_menu", true)
        set(value) = sp.edit().putBoolean("floating_menu", value).apply()

    /**
     * Route the server's outbound traffic through the Cloudflare WARP tunnel. Off by default: anonymous
     * free-model quota is counted per IP address, and Cloudflare WARP egress addresses are shared by the whole
     * WARP user base, so a fresh install that tunnels from the first request lands on an IP whose shared
     * anonymous quota is already drained - the "quota exhausted on the very first message" reports. Direct
     * connection uses the phone's own IP and its own quota; WARP stays an explicit opt-in for people whose
     * own address is blocked or drained.
     */
    var warpEnabled: Boolean
        get() = sp.getBoolean("warp_enabled", false)
        set(value) = sp.edit().putBoolean("warp_enabled", value).apply()

    /** DNS resolver tests in settings are manual diagnostics; off by default and never changes Android's system DNS. */
    var dnsDiagnosticsEnabled: Boolean
        get() = sp.getBoolean("dns_diagnostics_enabled", false)
        set(value) = sp.edit().putBoolean("dns_diagnostics_enabled", value).apply()

    /** Route the local server through a user-supplied VLESS/Hysteria2 subscription (sing-box). Off by default. */
    var vpnSubscriptionEnabled: Boolean
        get() = sp.getBoolean("vpn_subscription_enabled", false)
        set(value) = sp.edit().putBoolean("vpn_subscription_enabled", value).apply()

    var vpnSubscriptionUrl: String
        get() = sp.getString("vpn_subscription_url", "") ?: ""
        set(value) = sp.edit().putString("vpn_subscription_url", value.trim()).apply()

    /** Key of the chosen subscription server; blank means the first one. */
    var vpnSelectedNode: String
        get() = sp.getString("vpn_selected_node", "") ?: ""
        set(value) = sp.edit().putString("vpn_selected_node", value).apply()

    /** One-time prompts: true once [key] has been shown. */
    fun seen(key: String) = sp.getBoolean("seen_$key", false)

    /** True once the person has signed in with a Google account on the first launch. */
    var signedIn: Boolean
        get() = sp.getBoolean("signed_in", false)
        set(value) = sp.edit().putBoolean("signed_in", value).apply()

    /** True when the first-launch sign-in was passed over; the offer does not come back until the app is reinstalled. */
    var loginSkipped: Boolean
        get() = sp.getBoolean("login_skipped", false)
        set(value) = sp.edit().putBoolean("login_skipped", value).apply()

    fun markSeen(key: String) = sp.edit().putBoolean("seen_$key", true).apply()

    fun forget(key: String) = sp.edit().remove("seen_$key").apply()

    fun menuPosition(): Pair<Float, Float> = sp.getFloat("menu_x", 1f) to sp.getFloat("menu_y", 0.45f)

    fun setMenuPosition(x: Float, y: Float) = sp.edit().putFloat("menu_x", x).putFloat("menu_y", y).apply()

    /** The password the local server and its pairing use. Random, created once, never shown. */
    val password: String get() = secret("password", 24)

    /** The token commands in the Linux environment present to the app's loopback bridge. */
    val bridgeToken: String get() = secret("bridge_token", 16)

    // The worker thread (server spawn) and the UI thread can both ask for a secret on a fresh install; without the
    // lock each of them could generate a different value and the last write would win, leaving the server with a
    // password that is nowhere in the prefs.
    @Synchronized
    private fun secret(key: String, bytes: Int): String {
        sp.getString(key, null)?.let { return it }

        val raw = ByteArray(bytes)

        SecureRandom().nextBytes(raw)

        val value = raw.joinToString("") { "%02x".format(it) }

        sp.edit().putString(key, value).apply()

        return value
    }

    /** Drops every preference - part of "erase all app data", which promises a full wipe. */
    fun clearAll() {
        sp.edit().clear().commit()
    }
}
