package dev.opencode.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plugins, the way OpenCode keeps them: script files in the profile's plugins directory and npm packages in the config.
 * New plugins can come from a file on the phone or from a link; the server loads them at the next start.
 */
class PluginsActivity : ExtScreen() {
    private var pickForInstall = false

    override fun screenTitle() = tr("Плагины и правила", "Plugins and rules")

    override fun render() {
        column.removeAllViews()

        val profile = runCatching { app.profiles.active }.getOrNull()

        if (profile != null) note(tr("Профиль: ", "Profile: ") + profile.title)

        pluginsSection()
        npmSection()

        column.addView(
            Ui.button(this, tr("Правила (README.md)", "Rules (README.md)"), palette, primary = true) {
                startActivity(Intent(this, RulesActivity::class.java))
            },
            Ui.params(top = Ui.dp(this, 24)),
        )
        note(
            tr(
                "Файлы инструкций для всех моделей или для одной конкретной.",
                "Instruction files for every model, or for one specific model.",
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // Local plugin files
    private fun pluginsSection() {
        val card = section(tr("Файлы плагинов", "Plugin files"))
        val plugins = OpencodeStore.localPlugins(home)

        if (plugins.isEmpty()) {
            card.addView(
                Ui.text(
                    this,
                    tr(
                        "Пока нет локальных плагинов. Это файлы .js/.ts, которые сервер загружает при старте.",
                        "No local plugins yet. These are .js/.ts files the server loads at start.",
                    ),
                    12f,
                    palette.muted,
                ),
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        for (plugin in plugins) {
            card.line(
                plugin.name,
                humanSize(plugin.length()),
                state(tr("Вкл", "On"), true),
            ) { fileMenu(plugin) }
        }

        card.divide()
        card.action(tr("Установить из файла", "Install from a file"), primary = true) { pickForInstall = true; pickFile() }
        card.action(tr("Установить по ссылке", "Install from a link")) { askUrl() }
        note(
            tr(
                "Подойдут .js и .ts файлы плагинов OpenCode. Телефон спросит, какой файл взять.",
                "OpenCode plugin .js and .ts files work. The phone will ask which file to take.",
            ),
        )
    }

    private fun fileMenu(plugin: File) {
        val items = ArrayList<Pair<String, () -> Unit>>()

        items += tr("Показать первые строки", "Show the first lines") to { preview(plugin) }
        items += tr("Удалить", "Delete") to {
            Ui.confirm(this, plugin.name, tr("Файл плагина будет удалён из профиля.", "The plugin file will be removed from the profile."), tr("Удалить", "Delete")) {
                OpencodeStore.deletePlugin(home, plugin)
                render()
                askRestart()
            }
        }

        Ui.choose(this, plugin.name, items.map { it.first }) { items[it].second() }
    }

    private fun preview(plugin: File) {
        val text = runCatching { plugin.readText().take(600) }.getOrDefault("")

        android.app.AlertDialog.Builder(this)
            .setTitle(plugin.name)
            .setMessage(if (text.isBlank()) tr("Пустой файл", "An empty file") else text)
            .setPositiveButton("OK", null)
            .show()
    }

    // npm plugins from the config
    private fun npmSection() {
        val card = section(tr("Плагины из пакетов", "Package plugins"))
        val packages = OpencodeStore.npmPlugins(home)

        if (packages.isEmpty()) {
            card.line(tr("Нет пакетных плагинов", "No package plugins"), null, null, null)
        }

        for (id in packages) {
            card.line(id, null, state(tr("Вкл", "On"), true)) {
                Ui.confirm(this, id, tr("Убрать плагин из конфигурации?", "Remove the plugin from the config?"), tr("Убрать", "Remove")) {
                    OpencodeStore.removeNpmPlugin(home, id)
                    render()
                    askRestart()
                }
            }
        }

        card.divide()
        card.action(tr("Добавить npm-плагин", "Add an npm plugin")) {
            Ui.input(this, tr("Имя пакета", "Package name"), "opencode-example-plugin", "") { id ->
                if (id.isBlank()) return@input

                try {
                    OpencodeStore.addNpmPlugin(home, id)
                    render()
                    askRestart()
                } catch (e: Exception) {
                    Ui.toast(this, e.message ?: tr("Не удалось добавить", "Could not add"))
                }
            }
        }
    }

    // File picker
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/javascript", "application/javascript", "text/plain", "application/octet-stream"))
        }

        try {
            startActivityForResult(Intent.createChooser(intent, tr("Выберите файл плагина", "Choose a plugin file")), REQ_PICK)
        } catch (e: Exception) {
            Log.w("plugins", "no file picker: ${e.message}")
            Ui.toast(this, tr("Не удалось открыть выбор файла", "Could not open the file picker"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data?.data == null) return

        if (!pickForInstall) return

        pickForInstall = false
        installFromUri(data.data!!)
    }

    private fun installFromUri(uri: Uri) {
        val name = queryName(uri)

        Thread({
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException(tr("Файл не читается", "The file cannot be read"))

                if (!OpencodeStore.looksLikeScript(name, bytes)) {
                    runOnUiThread {
                        Ui.toast(this, tr("Это не похоже на файл плагина (нужен .js или .ts)", "This does not look like a plugin file (.js or .ts needed)"))
                    }

                    return@Thread
                }

                val target = OpencodeStore.installPluginBytes(home, bytes, name)

                runOnUiThread {
                    render()
                    Ui.toast(this, tr("Установлен: ", "Installed: ") + target.name)
                    askRestart()
                }
            } catch (e: Exception) {
                Log.w("plugins", "install failed: ${e.message}")
                runOnUiThread { Ui.toast(this, tr("Не удалось установить: ", "Could not install: ") + (e.message ?: "")) }
            }
        }, "plugin-file").apply { isDaemon = true; start() }
    }

    private fun queryName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)

            (if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null)
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    // Download by link
    private fun askUrl() {
        Ui.input(this, tr("Ссылка на файл плагина", "Link to a plugin file"), "https://example.com/plugin.js", "") { link ->
            val url = link.trim()

            if (url.isEmpty()) return@input

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Ui.toast(this, tr("Ссылка должна начинаться с http:// или https://", "The link must start with http:// or https://"))

                return@input
            }

            download(url)
        }
    }

    private fun download(url: String) {
        val toast = tr("Скачиваю...", "Downloading...")

        Ui.toast(this, toast)

        Thread({
            try {
                val connection = URL(url).openConnection() as HttpURLConnection

                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("Accept", "*/*")

                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${connection.responseCode}")
                    }

                    val bytes = connection.inputStream.use { it.readBytes() }

                    if (bytes.size > 4 * 1024 * 1024) {
                        throw IllegalStateException(tr("Файл больше 4 МБ", "The file is over 4 MB"))
                    }

                    val name = OpencodeStore.pluginFileName(url.substringBefore('?').substringAfterLast('/'))

                    if (!OpencodeStore.looksLikeScript(name, bytes)) {
                        throw IllegalStateException(tr("По ссылке не файл плагина (нужен .js или .ts)", "The link is not a plugin file (.js or .ts needed)"))
                    }

                    val target = OpencodeStore.installPluginBytes(home, bytes, name)

                    runOnUiThread {
                        render()
                        Ui.toast(this, tr("Установлен: ", "Installed: ") + target.name)
                        askRestart()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w("plugins", "download failed: ${e.message}")
                runOnUiThread { Ui.toast(this, tr("Не удалось скачать: ", "Could not download: ") + (e.message ?: "")) }
            }
        }, "plugin-download").apply { isDaemon = true; start() }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val REQ_PICK = 11
    }
}
