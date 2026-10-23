package dev.opencode.mobile

import android.webkit.JavascriptInterface

/**
 * The object the injected bridge.js talks to, exposed to the web UI as `OpenCodeAndroid`. It is only ever attached to the
 * app's own page, never to preview windows.
 */
class JsBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun saveBegin(name: String, mime: String): String = Downloads.begin(activity, name, mime) ?: ""

    @JavascriptInterface
    fun saveChunk(id: String, base64: String): Boolean = Downloads.chunk(id, base64)

    @JavascriptInterface
    fun saveEnd(id: String) {
        val where = Downloads.end(id)

        activity.runOnUiThread {
            Ui.toast(
                activity,
                if (where != null) tr("Сохранено: ", "Saved: ") + where else tr("Не удалось сохранить файл", "Could not save the file"),
            )
        }
    }

    @JavascriptInterface
    fun saveAbort(id: String) = Downloads.abort(id)

    @JavascriptInterface
    fun setBars(dark: Boolean, color: String) = activity.runOnUiThread { activity.applyBars(dark, color) }

    @JavascriptInterface
    fun terminalFocus(focused: Boolean) = activity.runOnUiThread { activity.keys.setTerminalFocus(focused) }

    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread { Browser.copy(activity, "OpenCode", text) }
    }

    @JavascriptInterface
    fun openPreview(url: String) {
        activity.runOnUiThread { activity.showPreview(url) }
    }

    /** The sign-in offer shown instead of a raw quota error opens the native sign-in screen. */
    @JavascriptInterface
    fun openLogin() {
        activity.runOnUiThread {
            try {
                activity.startActivity(android.content.Intent(activity, LoginActivity::class.java))
            } catch (e: Exception) {
                Log.w("bridge", "could not open the sign-in: ${e.message}")
            }
        }
    }

    /**
     * The page hit a limit error: reconnect the WARP tunnel so Cloudflare hands out a different egress IP, which
     * brings the shared anonymous free-model limits back. The result is reported as a toast.
     */
    @JavascriptInterface
    fun rotateWarp() {
        // The tunnel probe does a TCP connect, which must never run on the UI thread (NetworkOnMainThreadException
        // would be swallowed and the rotation would silently never happen). Probe and rotate on a worker thread.
        Thread {
            // Without a running tunnel (WARP off, or an account connected) there is nothing to refresh and the page
            // just shows the sign-in offer as before.
            val up = runCatching { activity.app.warp.listening() }.getOrDefault(false)

            if (!up) {
                activity.runOnUiThread { Ui.toast(activity, tr("WARP не запущен — подключите аккаунт или включите WARP в настройках", "WARP is not running — connect an account or enable WARP in settings")) }
                return@Thread
            }

            activity.app.warp.rotate("page limit error") { ok ->
                activity.runOnUiThread {
                    Ui.toast(
                        activity,
                        if (ok) {
                            tr("WARP: IP обновлён, отправьте запрос ещё раз", "WARP: IP refreshed, send the request again")
                        } else {
                            tr("WARP не смог обновить IP", "WARP could not refresh the IP")
                        },
                    )
                }
            }
        }.start()
    }

    /**
     * Whether a WARP tunnel makes sense here: enabled in settings and no personal account on the active profile.
     * The page uses it to word the quota offer honestly - no tunnel means no "new IP in a few seconds" promise.
     */
    @JavascriptInterface
    fun warpActive(): Boolean = runCatching {
        activity.app.prefs.warpEnabled && activity.app.profiles.active.account == null
    }.getOrDefault(false)

    @JavascriptInterface
    fun openExternal(url: String) {
        activity.runOnUiThread { activity.openExternal(url) }
    }

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME
}
