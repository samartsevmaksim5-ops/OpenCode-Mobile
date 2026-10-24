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

    /**
     * True when this profile already has a Console account (or a sign-in completed on this install), so the web UI
     * must not offer the sign-in again under a limit error. Read on the WebView's thread: both sources are cheap.
     */
    @JavascriptInterface
    fun hasAccount(): Boolean {
        val app = activity.applicationContext as App

        return app.prefs.signedIn || app.profiles.active.account != null
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

    @JavascriptInterface
    fun openExternal(url: String) {
        activity.runOnUiThread { activity.openExternal(url) }
    }

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME
}
