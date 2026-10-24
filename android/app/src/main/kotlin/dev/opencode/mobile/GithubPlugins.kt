package dev.opencode.mobile

/**
 * Plugins straight from a GitHub repository. Pure parsing and filtering so it runs on the JVM tests; the network and
 * the dialogs live in PluginsActivity.
 *
 * Accepted input: `owner/repo`, `owner/repo@ref`, `github:owner/repo#ref`, a repository link, a `tree/<ref>/<dir>` link
 * (that directory is searched), a `blob/<ref>/<file>` link or a raw.githubusercontent.com link (that single file).
 */
object GithubPlugins {
    /** [path] is a file when [isFile], otherwise the directory to search (null: the usual places). */
    data class Source(val owner: String, val repo: String, val ref: String?, val path: String?, val isFile: Boolean)

    data class Candidate(val name: String, val path: String, val downloadUrl: String, val size: Long)

    /** Where a repository usually keeps its plugin files, in the order they are tried. */
    val SEARCH_DIRS = listOf("", ".opencode/plugins", ".opencode/plugin", "plugins", "plugin", "src")

    private val PLUGIN_EXTENSIONS = setOf("js", "ts", "mjs")

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

    private val RAW = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
    private val BLOB = Regex("^https://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$")
    private val TREE = Regex("^https://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/tree/([^/]+)(?:/(.+))?$")
    private val REPO = Regex("^https://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?$")
    private val SHORT = Regex("^(?:github:)?([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?(?:[@#]([A-Za-z0-9._/-]+))?$")

    fun parse(input: String): Source? {
        val trimmed = input.trim()
        // `github:owner/repo#ref` keeps its fragment; a pasted URL loses its query and anchor (they mean nothing here).
        val text = (if (trimmed.startsWith("github:")) trimmed else trimmed.substringBefore('?').substringBefore('#')).trimEnd('/')

        if (text.isEmpty()) return null

        RAW.matchEntire(text)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4], true) }
        BLOB.matchEntire(text)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4], true) }
        TREE.matchEntire(text)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].ifEmpty { null }, false) }
        REPO.matchEntire(text)?.let { return build(it.groupValues[1], it.groupValues[2], null, null, false) }

        if (text.startsWith("http://") || text.startsWith("https://")) return null

        SHORT.matchEntire(text)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifEmpty { null }, null, false) }

        return null
    }

    private fun build(owner: String, repo: String, ref: String?, path: String?, isFile: Boolean): Source? {
        if (!NAME.matches(owner) || !NAME.matches(repo) || owner == "." || owner == ".." || repo == "." || repo == "..") return null
        if (ref != null && (ref.isBlank() || ref.contains(".."))) return null
        if (path != null && path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        if (isFile && (path == null || !isPluginName(path.substringAfterLast('/')))) return null

        return Source(owner, repo, ref, path, isFile)
    }

    /** A plugin script: .js/.ts/.mjs, but not typings, tests or tool configuration files. */
    fun isPluginName(name: String): Boolean {
        val lower = name.lowercase()

        if (lower.substringAfterLast('.', "") !in PLUGIN_EXTENSIONS) return false
        if (lower.endsWith(".d.ts") || lower.contains(".test.") || lower.contains(".spec.") || lower.contains(".config.")) return false

        return true
    }

    /** The GitHub API address that lists [dir] (empty: the repository root) of the repository. */
    fun contentsUrl(source: Source, dir: String): String {
        val base = "https://api.github.com/repos/${source.owner}/${source.repo}/contents" + if (dir.isEmpty()) "" else "/$dir"

        return if (source.ref == null) base else "$base?ref=${java.net.URLEncoder.encode(source.ref, "UTF-8")}"
    }

    /** The direct download address of one file. A branch is resolved by the server, so the default branch is `HEAD`. */
    fun rawUrl(source: Source, path: String): String =
        "https://raw.githubusercontent.com/${source.owner}/${source.repo}/${source.ref ?: "HEAD"}/$path"

    /** Plugin files in a directory listing of the contents API. Anything else (folders, a single-file reply) is ignored. */
    fun candidates(json: String): List<Candidate> {
        val list = MiniJson.parseOrNull(json) as? List<*> ?: return emptyList()
        val out = ArrayList<Candidate>()

        for (item in list) {
            val entry = item as? Map<*, *> ?: continue

            if (entry["type"] != "file") continue

            val name = entry["name"] as? String ?: continue
            val url = entry["download_url"] as? String ?: continue
            val size = (entry["size"] as? Number)?.toLong() ?: 0L

            if (!isPluginName(name) || !trusted(url) || size > MAX_FILE_BYTES) continue

            out += Candidate(name, entry["path"] as? String ?: name, url, size)
        }

        return out.sortedBy { it.name.lowercase() }
    }

    /** Files are taken only from GitHub's own raw host, over https. */
    fun trusted(url: String): Boolean = url.startsWith("https://raw.githubusercontent.com/")
}
