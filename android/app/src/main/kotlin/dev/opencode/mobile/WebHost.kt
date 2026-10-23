package dev.opencode.mobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import java.net.HttpURLConnection
import java.net.URL

/** Settings every web view in the app shares. */
object WebConfig {
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(settings: WebSettings, userAgentSuffix: String) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " " + userAgentSuffix
    }
}

/** Dialogs and the file picker, shared by the main page and the preview windows. */
open class AppChromeClient(protected val activity: MainActivity) : WebChromeClient() {
    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()

        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> result.confirm() }
            .setNegativeButton(tr("Отмена", "Cancel")) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()

        return true
    }

    override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
        val field = EditText(activity).apply { setText(defaultValue.orEmpty()) }

        AlertDialog.Builder(activity)
            .setMessage(message)
            .setView(field)
            .setPositiveButton("OK") { _, _ -> result.confirm(field.text.toString()) }
            .setNegativeButton(tr("Отмена", "Cancel")) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()

        return true
    }

    override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        activity.pickFiles(params, callback)

        return true
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (BuildConfig.WEBVIEW_DEBUG) Log.i("web", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")

        return true
    }

    // Camera, location and the like are never granted to a page.
    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
}

/** The web view that shows the OpenCode interface itself. */
class WebHost(private val activity: MainActivity) {
    var view: WebView = create()
        private set

    private val script: String by lazy {
        activity.assets.open("bridge.js").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_DEBUG)

        val web = WebView(activity)

        WebConfig.apply(web.settings, "OpenCodeAndroid/${BuildConfig.VERSION_NAME}")
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        web.setBackgroundColor(activity.palette.bg)
        web.addJavascriptInterface(JsBridge(activity), "OpenCodeAndroid")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }

        return web
    }

    /** Pairs with the server (a one-time code that becomes a session cookie) and loads the interface. */
    fun open(base: String, password: String) {
        Thread({
            val target = pairedUrl(base, password)

            activity.runOnUiThread { view.loadUrl(target) }
        }, "pairing").apply { isDaemon = true; start() }
    }

    private fun pairedUrl(base: String, password: String): String {
        val root = Urls.withoutTrailingSlash(base)

        if (password.isEmpty()) return "$root/"

        return try {
            val http = URL("$root/api/pair").openConnection() as HttpURLConnection

            try {
                http.requestMethod = "POST"
                http.connectTimeout = 5_000
                http.readTimeout = 10_000
                http.doOutput = true
                http.setRequestProperty("Authorization", "Basic " + Urls.basicCredential(password))
                http.setRequestProperty("Content-Type", "application/json")
                http.setRequestProperty("Accept", "application/json")
                http.outputStream.use { it.write("{}".toByteArray()) }

                if (http.responseCode !in 200..299) throw java.io.IOException("HTTP ${http.responseCode}")

                val body = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val json = MiniJson.obj(body)
                val code = (json?.get("code") ?: (json?.get("data") as? Map<*, *>)?.get("code")) as? String
                    ?: throw java.io.IOException("No pairing code in the reply")

                "$root/auth/connect/${Uri.encode(code)}"
            } finally {
                http.disconnect()
            }
        } catch (e: Exception) {
            // Without a cookie the page still loads; the web view then answers the server's password prompt itself.
            Log.w("web", "pairing failed (${e.message}); falling back to the password prompt")
            "$root/"
        }
    }

    fun recreate(): WebView {
        runCatching { view.destroy() }
        view = create()

        return view
    }

    fun destroy() {
        runCatching { view.destroy() }
    }

    private fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        when {
            url.startsWith("blob:") -> view.evaluateJavascript(
                "window.__ocSaveUrl && window.__ocSaveUrl(${MiniJson.quote(url)}, ${MiniJson.quote(Downloads.clean(android.webkit.URLUtil.guessFileName(url, disposition, mime)))})",
                null,
            )
            url.startsWith("http://") || url.startsWith("https://") -> Downloads.fromUrl(activity, url, userAgent, disposition, mime) { result ->
                activity.runOnUiThread {
                    Ui.toast(
                        activity,
                        result.fold(
                            { tr("Сохранено: ", "Saved: ") + it },
                            { tr("Не удалось скачать: ", "Download failed: ") + (it.message ?: "") },
                        ),
                    )
                }
            }
            else -> Log.w("web", "download of an unsupported link was ignored")
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = activity.route(request.url.toString())

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (activity.isInternal(url)) view.evaluateJavascript(script, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (activity.isInternal(url)) {
                view.evaluateJavascript(script, null)
                activity.onPageReady()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Aborted/cancelled loads (redirects, window.stop(), a superseded navigation) arrive with a non-positive
            // code and are not failures: the full-screen error view over a live page reads as "the app froze".
            if (request.isForMainFrame && error.errorCode > 0) activity.onPageError(error.description?.toString().orEmpty())
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String, realm: String?) {
            val password = activity.currentPassword()

            if (password.isNotEmpty() && activity.isInternalHost(host)) handler.proceed("opencode", password) else handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w("web", "the web view's renderer went away (crashed=${detail.didCrash()})")
            activity.runOnUiThread { activity.rebuildWebView() }

            return true
        }
    }

    private inner class Chrome : AppChromeClient(activity) {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // The page asked for a new window. Take the address it would load and send it where it belongs.
            val probe = WebView(activity)

            probe.webViewClient = object : WebViewClient() {
                private var handled = false

                private fun take(url: String?) {
                    if (handled || url.isNullOrEmpty() || url == "about:blank") return

                    handled = true

                    // A link to the app itself belongs in the main page; everything else goes where route sends it.
                    if (!activity.route(url)) this@WebHost.view.loadUrl(url)

                    probe.post { probe.destroy() }
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    take(request.url.toString())

                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) = take(url)
            }

            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()

            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            activity.onWebPermission(request)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            activity.onPageProgress(newProgress)
        }
    }
}
