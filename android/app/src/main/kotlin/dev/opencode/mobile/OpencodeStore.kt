package dev.opencode.mobile

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Everything the app manages inside the active profile's OpenCode home: provider keys (auth.json), custom providers and
 * plugins in the config, and README-style rule files. The layout follows the upstream OpenCode conventions, so files
 * written here are the same ones the server and the CLI read.
 */
object OpencodeStore {
    const val OPENAI_COMPATIBLE_PACKAGE = "@opencode/ai/providers/openai-compatible"
    const val RULES_PLUGIN = "android-rules.js"
    const val GUEST_RULES_DIR = "/root/.config/opencode/rules"
    const val GUEST_PLUGIN = "/root/.config/opencode/plugins/$RULES_PLUGIN"

    /** Connectors with a plain API key, in the order the console sign-in lists them. */
    val CONNECTORS = listOf(
        Connector("opencode", "OpenCode Zen", "https://opencode.ai/auth"),
        Connector("anthropic", "Anthropic (Claude)", "https://console.anthropic.com/settings/keys"),
        Connector("openai", "OpenAI (GPT)", "https://platform.openai.com/api-keys"),
        Connector("google", "Google (Gemini)", "https://aistudio.google.com/app/apikey"),
        Connector("xai", "xAI (Grok)", "https://console.x.ai"),
        Connector("openrouter", "OpenRouter", "https://openrouter.ai/keys"),
        Connector("groq", "Groq", "https://console.groq.com/keys"),
        Connector("deepseek", "DeepSeek", "https://platform.deepseek.com/api_keys"),
        Connector("mistral", "Mistral", "https://console.mistral.ai/api-keys"),
        Connector("moonshotai", "Moonshot (Kimi)", "https://platform.moonshot.ai/console/api-keys"),
        Connector("zai", "Z.ai (GLM)", "https://z.ai/manage-api_key/apikey-list"),
        Connector("minimax", "MiniMax", "https://platform.minimax.io"),
        Connector("together", "Together AI", "https://api.together.xyz/settings/api-keys"),
        Connector("fireworks", "Fireworks AI", "https://fireworks.ai/account/api-keys"),
        Connector("cerebras", "Cerebras", "https://cloud.cerebras.ai"),
        Connector("perplexity", "Perplexity", "https://www.perplexity.ai/settings/api"),
    )

    // Paths (all inside the profile home, which the Linux environment sees as /root)
    fun configDir(home: File) = File(home, ".config/opencode")

    fun configFile(home: File) = File(configDir(home), "opencode.json")

    fun pluginsDir(home: File) = File(configDir(home), "plugins")

    fun rulesDir(home: File) = File(configDir(home), "rules")

    fun rulesManifest(home: File) = File(rulesDir(home), "rules.json")

    fun authFile(home: File) = File(File(home, ".local/share/opencode"), "auth.json")

    // Reading and writing
    fun readMap(file: File): MutableMap<String, Any?> {
        if (!file.exists()) return LinkedHashMap()

        val parsed = MiniJson.obj(file.readText()) ?: return LinkedHashMap()

        return (parsed as? MutableMap<String, Any?>) ?: LinkedHashMap(parsed)
    }

    fun writeJson(file: File, value: Map<*, *>) {
        writeAtomic(file, MiniJson.write(value))
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()

        val temp = File(file.parentFile, file.name + ".tmp")

        temp.writeText(text)

        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    // Connectors and their keys
    /** provider id to a masked key, for every connector that has a key stored. */
    fun connectedKeys(home: File): Map<String, String> {
        val root = readMap(authFile(home))
        val out = LinkedHashMap<String, String>()

        for ((id, value) in root) {
            val entry = value as? Map<*, *> ?: continue
            val key = entry["key"] as? String ?: continue

            out[id] = mask(key)
        }

        return out
    }

    fun setProviderKey(home: File, providerId: String, key: String) {
        val root = readMap(authFile(home))

        root[providerId] = linkedMapOf("type" to "api", "key" to key.trim())
        writeJson(authFile(home), root)
    }

    fun clearProviderKey(home: File, providerId: String) {
        val root = readMap(authFile(home))

        if (root.remove(providerId) != null) writeJson(authFile(home), root)
    }

    /**
     * Signs the profile out of the Console: every credential that is not a plain API key (the OAuth session the
     * device sign-in stored, in whatever shape the server version keeps it) is removed, while pasted connector keys
     * survive. Without this the server keeps using the old account's quota even after the person creates a new one.
     * Returns true when there was something to remove.
     */
    fun signOut(home: File): Boolean {
        val root = readMap(authFile(home))
        val drop = root.entries.filter { entry -> (entry.value as? Map<*, *>)?.get("type") != "api" }.map { it.key }

        if (drop.isEmpty()) return false

        for (id in drop) root.remove(id)

        if (root.isEmpty()) authFile(home).delete() else writeJson(authFile(home), root)

        return true
    }

    fun mask(key: String): String {
        val trimmed = key.trim()

        if (trimmed.length <= 10) return "..."

        return trimmed.take(6) + "..." + trimmed.takeLast(4)
    }

    // Custom providers (OpenAI-compatible endpoints) in the config
    fun customProviders(home: File): List<CustomProvider> {
        val root = readMap(configFile(home))
        val out = LinkedHashMap<String, CustomProvider>()

        collectProviders(root["providers"], out)
        collectProviders(root["provider"], out)

        return out.values.toList()
    }

    private fun collectProviders(source: Any?, out: MutableMap<String, CustomProvider>) {
        val providers = source as? Map<*, *> ?: return

        for ((id, value) in providers) {
            val entry = value as? Map<*, *> ?: continue
            val pid = id as? String ?: continue

            val settings = entry["options"] as? Map<*, *> ?: entry["settings"] as? Map<*, *>
            val baseUrl = (settings?.get("baseURL") as? String).orEmpty()
            val apiKey = settings?.get("apiKey") as? String
            val models = LinkedHashMap<String, String>()

            for ((mid, mvalue) in (entry["models"] as? Map<*, *>).orEmpty()) {
                val name = when (mvalue) {
                    is Map<*, *> -> (mvalue["name"] as? String) ?: (mvalue["modelID"] as? String)
                    else -> null
                }

                models[mid as? String ?: continue] = name ?: mid
            }

            out[pid] = CustomProvider(
                id = pid,
                name = (entry["name"] as? String)?.takeIf { it.isNotBlank() } ?: pid,
                baseUrl = baseUrl,
                apiKey = apiKey,
                models = models.map { it.key to it.value },
            )
        }
    }

    fun addCustomProvider(home: File, provider: CustomProvider) {
        require(Regex("^[a-z0-9][a-z0-9-]{0,40}$").matches(provider.id)) { "bad provider id" }

        val root = readMap(configFile(home))
        val providers = (root["providers"] as? MutableMap<String, Any?>)
            ?: LinkedHashMap<String, Any?>().also { root["providers"] = it }

        val entry = LinkedHashMap<String, Any?>()

        entry["name"] = provider.name
        entry["package"] = OPENAI_COMPATIBLE_PACKAGE

        val settings = LinkedHashMap<String, Any?>()

        settings["baseURL"] = provider.baseUrl.trim()

        if (!provider.apiKey.isNullOrBlank()) settings["apiKey"] = provider.apiKey.trim()

        entry["settings"] = settings

        val models = LinkedHashMap<String, Any?>()

        for ((mid, mname) in provider.models) {
            if (mid.isBlank()) continue

            models[mid.trim()] = linkedMapOf("name" to (mname.ifBlank { mid.trim() }))
        }

        entry["models"] = models
        providers[provider.id] = entry
        writeJson(configFile(home), root)

        // The key also goes to auth.json, the credential place the server reads first.
        if (!provider.apiKey.isNullOrBlank()) setProviderKey(home, provider.id, provider.apiKey)
    }

    fun removeCustomProvider(home: File, id: String) {
        val root = readMap(configFile(home))
        var changed = false

        for (key in listOf("providers", "provider")) {
            val providers = root[key] as? MutableMap<*, *> ?: continue

            if (providers.keys.removeIf { it == id }) changed = true
        }

        if (changed) writeJson(configFile(home), root)

        // A default model pointing at the removed provider would break every new chat.
        val model = root["model"] as? String

        if (model != null && model.startsWith("$id/")) {
            root.remove("model")
            writeJson(configFile(home), root)
        }

        clearProviderKey(home, id)
    }

    fun setDefaultModel(home: File, providerId: String, modelId: String) {
        val root = readMap(configFile(home))

        root["model"] = "$providerId/$modelId"
        writeJson(configFile(home), root)
    }

    fun defaultModel(home: File): String? = readMap(configFile(home))["model"] as? String

    // Plugins
    /** Local plugin files kept in the global plugins directory, sorted by name. */
    fun localPlugins(home: File): List<File> =
        pluginsDir(home).listFiles { f -> f.isFile && f.extension.lowercase() in PLUGIN_EXTENSIONS }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Packages from the config's plugins list, skipping local path entries. */
    fun npmPlugins(home: File): List<String> {
        val list = readMap(configFile(home))["plugins"] as? List<*> ?: return emptyList()

        return list.mapNotNull { it as? String }.filter { it.isNotBlank() && !it.startsWith(".") && !it.contains('/') }
    }

    fun installPlugin(home: File, source: File, wantedName: String): File {
        val name = pluginFileName(wantedName.ifBlank { source.name })
        val target = File(pluginsDir(home), name)
        val temp = File(pluginsDir(home), "$name.part")

        pluginsDir(home).mkdirs()
        source.copyTo(temp, overwrite = true)

        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        return target
    }

    fun installPluginBytes(home: File, bytes: ByteArray, wantedName: String): File {
        pluginsDir(home).mkdirs()

        val temp = File.createTempFile("plugin", ".tmp", pluginsDir(home))

        try {
            temp.writeBytes(bytes)

            return installPlugin(home, temp, wantedName)
        } finally {
            temp.delete()
        }
    }

    fun deletePlugin(home: File, file: File): Boolean = file.delete()

    fun removeNpmPlugin(home: File, id: String) {
        val root = readMap(configFile(home))
        val list = (root["plugins"] as? MutableList<Any?>) ?: return

        if (list.removeIf { it == id }) writeJson(configFile(home), root)
    }

    fun addNpmPlugin(home: File, id: String) {
        val packageId = id.trim()

        require(Regex("^[A-Za-z0-9@/._-]{1,120}$").matches(packageId)) { "bad package name" }

        val root = readMap(configFile(home))
        val list = (root["plugins"] as? MutableList<Any?>) ?: ArrayList<Any?>().also { root["plugins"] = it }

        if (list.none { it == packageId }) {
            list.add(packageId)
            writeJson(configFile(home), root)
        }
    }

    /** A safe file name for a plugin: no paths, no odd characters, and a script extension. */
    fun pluginFileName(name: String): String {
        var base = name.trim().substringAfterLast('/').substringAfterLast('\\')

        base = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifBlank { "plugin.js" }

        if (base.substringAfterLast('.', "").lowercase() !in PLUGIN_EXTENSIONS) base += ".js"

        return base.take(80)
    }

    private val PLUGIN_EXTENSIONS = setOf("js", "ts", "mjs")

    // Rules (README-style instructions for all models or one model)
    fun rules(home: File): List<Rule> {
        val file = rulesManifest(home)

        if (!file.exists()) return emptyList()

        val root = MiniJson.obj(file.readText()) ?: return emptyList()
        val list = root["rules"] as? List<*> ?: return emptyList()

        return list.mapNotNull { value ->
            val entry = value as? Map<*, *> ?: return@mapNotNull null
            val id = entry["id"] as? String ?: return@mapNotNull null
            val file = (entry["file"] as? String)?.takeIf { !it.contains('/') && !it.contains("..") } ?: return@mapNotNull null

            Rule(
                id = id,
                name = (entry["name"] as? String)?.takeIf { it.isNotBlank() } ?: id,
                model = entry["model"] as? String,
                fileName = file,
            )
        }
    }

    fun ruleContent(home: File, rule: Rule): String {
        val file = File(rulesDir(home), rule.fileName)

        return if (file.exists()) file.readText() else ""
    }

    fun saveRule(home: File, existing: Rule?, name: String, model: String?, content: String): Rule {
        val rule = if (existing != null) {
            existing.copy(name = name.trim().ifBlank { existing.name }.take(80), model = normalizeModel(model))
        } else {
            Rule(newId(), name.trim().ifBlank { tr("Правило", "Rule") }.take(80), normalizeModel(model), newId() + ".md")
        }

        writeAtomic(File(rulesDir(home), rule.fileName), content)
        writeManifest(home, rules(home).filter { it.id != rule.id } + rule)
        sync(home)

        return rule
    }

    fun deleteRule(home: File, rule: Rule) {
        File(rulesDir(home), rule.fileName).delete()
        writeManifest(home, rules(home).filter { it.id != rule.id })
        sync(home)
    }

    private fun writeManifest(home: File, list: List<Rule>) {
        val entries = list.map { rule ->
            LinkedHashMap<String, Any?>().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("model", rule.model)
                put("file", rule.fileName)
            }
        }

        writeJson(rulesManifest(home), linkedMapOf("rules" to entries))
    }

    private fun normalizeModel(model: String?): String? {
        val text = model?.trim()?.trimStart('@')?.takeIf { it.isNotBlank() } ?: return null

        return if (Regex("^[A-Za-z0-9._/@-]{1,120}$").matches(text)) text else null
    }

    /**
     * Applies the rules to what the server reads: global rule files go into the config's instructions (the native
     * instruction mechanism), model-specific ones ride in the generated plugin that appends them to the system prompt.
     */
    fun sync(home: File) {
        val all = rules(home)
        val root = readMap(configFile(home))
        val current = (root["instructions"] as? List<*>) ?: emptyList<Any?>()
        val mine = current.filterIsInstance<String>().filter { it.startsWith("$GUEST_RULES_DIR/") }
        val kept = current.filterNot { it is String && it.startsWith("$GUEST_RULES_DIR/") }
        val globals = all.filter { it.model == null }.map { "$GUEST_RULES_DIR/${it.fileName}" }

        val merged = ArrayList<Any?>(kept)

        for (path in globals) if (merged.none { it == path }) merged.add(path)

        if (merged != current) {
            if (merged.isNotEmpty()) root["instructions"] = merged else root.remove("instructions")
            writeJson(configFile(home), root)
        }

        writeRulesPlugin(home, all.filter { it.model != null })
    }

    private fun writeRulesPlugin(home: File, modelRules: List<Rule>) {
        val payload = modelRules.map { rule ->
            val text = ruleContent(home, rule)

            linkedMapOf<String, Any?>("model" to rule.model, "name" to rule.name, "text" to text)
        }
        val js = buildString {
            append("// Generated by OpenCode for Android: rules for specific models. The app rewrites this file.\n")
            append("const RULES = ")
            append(MiniJson.write(payload))
            append("\n\n")
            append("export const AndroidRules = async () => ({\n")
            append("  \"experimental.chat.system.transform\": async (input, output) => {\n")
            append("    try {\n")
            append("      const model = (input && input.model) || {}\n")
            append("      const providerID = String(model.providerID || \"\").toLowerCase()\n")
            append("      const modelID = String(model.modelID || \"\").toLowerCase()\n")
            append("      for (const rule of RULES) {\n")
            append("        const wanted = String(rule.model || \"\").toLowerCase()\n")
            append("        if (!wanted) continue\n")
            append("        const slash = wanted.indexOf(\"/\")\n")
            append("        const wantProvider = slash > 0 ? wanted.slice(0, slash) : null\n")
            append("        const wantModel = slash > 0 ? wanted.slice(slash + 1) : wanted\n")
            append("        if (wantProvider && wantProvider !== \"*\" && wantProvider !== providerID) continue\n")
            append("        if (wantModel !== \"*\" && modelID !== wantModel && !modelID.endsWith(\"/\" + wantModel)) continue\n")
            append("        if (rule.text) output.system.push(rule.text)\n")
            append("      }\n")
            append("    } catch (e) {}\n")
            append("  },\n")
            append("})\n")
        }

        writeAtomic(File(pluginsDir(home), RULES_PLUGIN), js)
    }

    private fun newId(): String {
        val bytes = ByteArray(5)

        SecureRandom().nextBytes(bytes)

        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Reads a downloaded plugin and refuses content that cannot be a script, so a HTML error page never installs. */
    fun looksLikeScript(name: String, bytes: ByteArray): Boolean {
        val head = bytes.take(512).toByteArray().toString(Charsets.ISO_8859_1).lowercase()

        if (head.startsWith("<!doctype") || head.startsWith("<html")) return false

        val ext = name.substringAfterLast('.', "").lowercase()

        return ext in PLUGIN_EXTENSIONS || ext == "" || ext == "txt"
    }
}

/** One connector the Connectors screen offers: a catalog provider that takes a plain API key. */
data class Connector(val id: String, val name: String, val keyUrl: String)

/** A custom OpenAI-compatible provider the person added, stored in the config like upstream expects. */
data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String?,
    val models: List<Pair<String, String>>,
) {
    val summary: String get() = models.joinToString(", ") { it.first }
}

/** One README-style rule, applied to every model or to one specific model (provider/model or model). */
data class Rule(val id: String, val name: String, val model: String?, val fileName: String) {
    val scope: String get() = model ?: ""
}
