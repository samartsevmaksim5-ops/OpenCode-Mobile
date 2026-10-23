package dev.opencode.mobile

/**
 * README-style rules: instruction files the server loads for every model, or only when a specific model answers. Global
 * rules become entries in the config's instructions (native OpenCode mechanism); model-specific ones are appended to the
 * system prompt by a small generated plugin.
 */
class RulesActivity : ExtScreen() {
    override fun screenTitle() = tr("Правила (README.md)", "Rules (README.md)")

    override fun render() {
        column.removeAllViews()

        val profile = runCatching { app.profiles.active }.getOrNull()

        if (profile != null) note(tr("Профиль: ", "Profile: ") + profile.title)

        val card = section(tr("Файлы правил", "Rule files"))
        val rules = runCatching { OpencodeStore.rules(home) }.getOrDefault(emptyList())

        if (rules.isEmpty()) {
            card.addView(
                Ui.text(
                    this,
                    tr(
                        "Пока нет правил. Правило — это текст (README.md), который агент читает вместе с системными инструкциями.",
                        "No rules yet. A rule is text (README.md) the agent reads along with its system instructions.",
                    ),
                    12f,
                    palette.muted,
                ),
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        for (rule in rules) {
            card.line(
                rule.name,
                if (rule.model == null) tr("Все модели", "All models") else tr("Модель: ", "Model: ") + rule.model,
                null,
            ) { ruleMenu(rule) }
        }

        card.divide()
        card.action(tr("Добавить правило", "Add a rule"), primary = true) { editRule(null) }
        note(
            tr(
                "Для всех моделей правила подключаются через instructions; для конкретной модели их добавляет плагин android-rules.",
                "Rules for all models go through instructions; rules for one model are added by the android-rules plugin.",
            ),
        )
    }

    private fun ruleMenu(rule: Rule) {
        val items = ArrayList<Pair<String, () -> Unit>>()

        items += tr("Открыть", "Open") to { showRule(rule) }
        items += tr("Изменить", "Edit") to { editRule(rule) }
        items += tr("Удалить", "Delete") to {
            Ui.confirm(this, rule.name, tr("Файл правила будет удалён.", "The rule file will be deleted."), tr("Удалить", "Delete")) {
                runCatching { OpencodeStore.deleteRule(home, rule) }
                render()
                askRestart()
            }
        }

        Ui.choose(this, rule.name, items.map { it.first }) { items[it].second() }
    }

    private fun showRule(rule: Rule) {
        val text = runCatching { OpencodeStore.ruleContent(home, rule) }.getOrDefault("")

        android.app.AlertDialog.Builder(this)
            .setTitle(rule.name)
            .setMessage(text.ifBlank { tr("Пусто", "Empty") })
            .setPositiveButton("OK", null)
            .show()
    }

    /** The add/edit flow: name, then the model it applies to (empty for all), then the text itself. */
    private fun editRule(existing: Rule?) {
        Ui.input(this, tr("Название", "Name"), tr("Например, Стиль ответов", "For example, Answer style"), existing?.name ?: "") { name ->
            if (name.isBlank()) return@input

            Ui.input(
                this,
                tr("Модель (пусто = все модели)", "Model (empty = all models)"),
                "anthropic/claude-sonnet-4-5",
                existing?.model ?: "",
            ) { model ->
                val content = runCatching {
                    if (existing != null) OpencodeStore.ruleContent(home, existing) else ""
                }.getOrDefault("")

                Ui.multilineInput(
                    this,
                    tr("Текст правила (Markdown)", "Rule text (Markdown)"),
                    tr("Пиши кратко и по делу...", "Be brief and to the point..."),
                    content,
                ) { text ->
                    try {
                        OpencodeStore.saveRule(home, existing, name, model, text)
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
