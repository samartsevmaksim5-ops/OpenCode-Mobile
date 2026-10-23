package dev.opencode.mobile

/**
 * Connectors, the way the regular OpenCode lists them: catalog providers that take a plain API key, plus any number of
 * custom OpenAI-compatible endpoints. Keys go to the profile's auth.json, custom providers to the config, so the server
 * and the web UI pick them up like anything added with the CLI.
 */
class ConnectorsActivity : ExtScreen() {
    override fun screenTitle() = tr("Коннекторы", "Connectors")

    override fun render() {
        column.removeAllViews()

        val profile = runCatching { app.profiles.active }.getOrNull()

        if (profile != null) {
            note(tr("Профиль: ", "Profile: ") + profile.title)
        }

        note(
            tr(
                "Ключи хранятся в профиле (auth.json) и читаются сервером как ключи, добавленные через CLI.",
                "Keys are stored in the profile (auth.json) and read by the server like keys added with the CLI.",
            ),
        )

        keysSection()
        customSection()
    }

    // Catalog providers
    private fun keysSection() {
        val card = section(tr("Ключи провайдеров", "Provider keys"))
        val connected = OpencodeStore.connectedKeys(home)

        for (connector in OpencodeStore.CONNECTORS) {
            val key = connected[connector.id]

            card.line(
                connector.name,
                if (key != null) tr("Ключ: ", "Key: ") + key else tr("Не подключён", "Not connected"),
                if (key != null) state(tr("Вкл", "On"), true) else null,
            ) { connectorMenu(connector, key != null) }
        }

        card.divide()
        card.action(tr("Другой провайдер (свой ключ)", "Another provider (own key)")) { otherKey() }
    }

    private fun connectorMenu(connector: Connector, connected: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()

        items += tr("Вставить ключ", "Paste a key") to { askKey(connector.id, connector.name) }
        items += tr("Где взять ключ", "Where to get a key") to { openExternal(connector.keyUrl) }

        if (connected) {
            items += tr("Отключить", "Disconnect") to {
                Ui.confirm(
                    this,
                    connector.name,
                    tr("Ключ будет удалён из профиля.", "The key will be removed from the profile."),
                    tr("Удалить", "Remove"),
                ) {
                    OpencodeStore.clearProviderKey(home, connector.id)
                    render()
                    askRestart()
                }
            }
        }

        Ui.choose(this, connector.name, items.map { it.first }) { items[it].second() }
    }

    private fun otherKey() {
        Ui.input(this, tr("Идентификатор провайдера", "Provider id"), "myprovider", "") { id ->
            val clean = id.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')

            if (clean.isEmpty()) {
                Ui.toast(this, tr("Нужен идентификатор из латиницы и цифр", "Use an id of letters and digits"))

                return@input
            }

            askKey(clean, clean)
        }
    }

    private fun askKey(providerId: String, title: String) {
        Ui.input(this, tr("Ключ API: ", "API key: ") + title, "sk-...", "", password = true) { key ->
            if (key.isBlank()) return@input

            OpencodeStore.setProviderKey(home, providerId, key)
            render()
            askRestart()
        }
    }

    // Custom OpenAI-compatible providers
    private fun customSection() {
        val card = section(tr("Своё API (OpenAI-совместимое)", "Custom API (OpenAI-compatible)"))
        val providers = OpencodeStore.customProviders(home)
        val model = OpencodeStore.defaultModel(home)

        if (model != null) {
            card.line(tr("Модель по умолчанию", "Default model"), model, null, null)
            card.divide()
        }

        if (providers.isEmpty()) {
            card.addView(
                Ui.text(
                    this,
                    tr(
                        "Пока нет своих API. Добавьте любой OpenAI-совместимый эндпоинт со своими моделями.",
                        "No custom APIs yet. Add any OpenAI-compatible endpoint with your own models.",
                    ),
                    12f,
                    palette.muted,
                ),
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        for (provider in providers) {
            card.line(
                provider.name,
                provider.baseUrl + "  ·  " + tr("моделей: ", "models: ") + provider.models.size,
            ) { providerMenu(provider) }
        }

        card.divide()
        card.action(tr("Добавить провайдера", "Add a provider"), primary = true) { editProvider(null) }
        note(
            tr(
                "Адрес обычно кончается на /v1. Ключ сохраняется и в config, и в auth.json.",
                "The URL usually ends with /v1. The key is saved to the config and to auth.json.",
            ),
        )
    }

    private fun providerMenu(provider: CustomProvider) {
        val items = ArrayList<Pair<String, () -> Unit>>()

        if (provider.models.isNotEmpty()) {
            items += tr("Модель по умолчанию", "Default model") to {
                val labels = provider.models.map { "${it.second} (${it.first})" }

                Ui.choose(this, provider.name, labels) { index ->
                    OpencodeStore.setDefaultModel(home, provider.id, provider.models[index].first)
                    render()
                    askRestart()
                }
            }
        } else {
            items += tr("Вписать модель по умолчанию", "Type a default model") to {
                Ui.input(this, tr("Идентификатор модели", "Model id"), "my-model", "") { mid ->
                    if (mid.isNotBlank()) {
                        OpencodeStore.setDefaultModel(home, provider.id, mid)
                        render()
                        askRestart()
                    }
                }
            }
        }

        items += tr("Изменить ключ", "Change the key") to {
            Ui.input(this, tr("Ключ API", "API key"), "sk-...", "", password = true) { key ->
                if (key.isBlank()) return@input

                OpencodeStore.addCustomProvider(home, provider.copy(apiKey = key))
                render()
                askRestart()
            }
        }
        items += tr("Изменить", "Edit") to { editProvider(provider) }
        items += tr("Удалить", "Delete") to {
            Ui.confirm(
                this,
                provider.name,
                tr("Провайдер и его модели исчезнут из списка. Ключ будет удалён.", "The provider and its models disappear from the list. The key is removed too."),
                tr("Удалить", "Delete"),
            ) {
                OpencodeStore.removeCustomProvider(home, provider.id)
                render()
                askRestart()
            }
        }

        Ui.choose(this, provider.name, items.map { it.first }) { items[it].second() }
    }

    /** The add/edit flow: id, name, base URL, key, then the model list, one per line as `id = Name`. */
    private fun editProvider(existing: CustomProvider?) {
        Ui.input(this, tr("Идентификатор (латиница)", "Id (latin letters)"), "myapi", existing?.id ?: "") { id ->
            val pid = id.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')

            if (pid.isEmpty()) {
                Ui.toast(this, tr("Нужен идентификатор из латиницы и цифр", "Use an id of letters and digits"))

                return@input
            }

            Ui.input(this, tr("Название", "Name"), tr("Мой провайдер", "My provider"), existing?.name ?: "") { name ->
                Ui.input(this, tr("Базовый адрес", "Base URL"), "https://api.example.com/v1", existing?.baseUrl ?: "") { url ->
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        Ui.toast(this, tr("Адрес должен начинаться с http:// или https://", "The URL must start with http:// or https://"))

                        return@input
                    }

                    Ui.input(this, tr("Ключ API (необязательно)", "API key (optional)"), "sk-...", "", password = true) { key ->
                        Ui.multilineInput(
                            this,
                            tr("Модели, по одной на строку: id = Название", "Models, one per line: id = Name"),
                            "glm-4.6 = GLM 4.6",
                            existing?.models?.joinToString("\n") { (mid, mname) -> if (mid == mname) mid else "$mid = $mname" } ?: "",
                        ) { modelsText ->
                            val models = modelsText.lines()
                                .map { line ->
                                    val eq = line.indexOf('=')

                                    if (eq > 0) {
                                        line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                                    } else {
                                        line.trim() to line.trim()
                                    }
                                }
                                .filter { it.first.isNotBlank() }

                            if (models.isEmpty()) {
                                Ui.toast(this, tr("Нужна хотя бы одна модель", "At least one model is required"))

                                return@multilineInput
                            }

                            try {
                                OpencodeStore.addCustomProvider(
                                    home,
                                    CustomProvider(pid, name.trim().ifBlank { pid }, url.trim(), key.trim().takeIf { it.isNotBlank() }, models),
                                )
                                render()
                                askRestart()
                            } catch (e: Exception) {
                                Ui.toast(this, e.message ?: tr("Не удалось сохранить", "Could not save"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openExternal(url: String) = Browser.open(this, url)
}
