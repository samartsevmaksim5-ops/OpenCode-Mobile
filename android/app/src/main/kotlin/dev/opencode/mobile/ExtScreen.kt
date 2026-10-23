package dev.opencode.mobile

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Common scaffolding for the screens that manage connectors, plugins and rules: one scrolling column of cards over the
 * active profile's OpenCode home. Changes land on disk at once and the server is offered a restart afterwards.
 */
abstract class ExtScreen : Activity() {
    protected val app: App get() = application as App

    protected lateinit var palette: Palette
    protected lateinit var column: LinearLayout

    /** The active profile's OpenCode home; everything this screen writes lives under it. */
    protected val home: File get() = Profiles.homeDir(app.paths.profilesDir, app.profiles.active)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        palette = Palette(this)
        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        val top = Ui.horizontal(this).apply {
            setPadding(Ui.dp(this@ExtScreen, 4), Ui.dp(this@ExtScreen, 12), Ui.dp(this@ExtScreen, 16), Ui.dp(this@ExtScreen, 4))
        }

        top.addView(Ui.iconButton(this, R.drawable.ic_back, tr("Назад", "Back"), palette, tint = palette.text) { finish() })
        top.addView(Ui.text(this, screenTitle(), 20f, palette.text, bold = true), Ui.params(Ui.WRAP, Ui.WRAP, left = Ui.dp(this, 8)))

        column = Ui.vertical(this).apply {
            setPadding(Ui.dp(this@ExtScreen, 16), Ui.dp(this@ExtScreen, 8), Ui.dp(this@ExtScreen, 16), Ui.dp(this@ExtScreen, 32))
        }

        val page = Ui.vertical(this)

        page.addView(top, Ui.params())
        page.addView(column, Ui.params())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    protected abstract fun screenTitle(): String

    protected abstract fun render()

    // Building blocks, shaped like the ones SettingsActivity uses
    protected fun section(title: String): LinearLayout {
        column.addView(
            Ui.text(this, title.uppercase(), 12f, palette.faint, bold = true).apply { letterSpacing = 0.08f },
            Ui.params(top = Ui.dp(this, 22), bottom = Ui.dp(this, 8), left = Ui.dp(this, 4)),
        )

        val card = Ui.card(this, palette)

        column.addView(card, Ui.params())

        return card
    }

    protected fun LinearLayout.line(title: String, subtitle: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null) {
        val row = Ui.horizontal(this@ExtScreen).apply {
            minimumHeight = Ui.dp(this@ExtScreen, 48)

            if (onClick != null) {
                background = Ui.ripple(this@ExtScreen, Ui.rounded(this@ExtScreen, android.graphics.Color.TRANSPARENT, 8f), palette)
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        val texts = Ui.vertical(this@ExtScreen)

        texts.addView(Ui.text(this@ExtScreen, title, 15f, palette.text))

        if (!subtitle.isNullOrEmpty()) texts.addView(
            Ui.text(this@ExtScreen, subtitle, 12f, palette.muted).apply { setPadding(0, Ui.dp(this@ExtScreen, 2), 0, 0) },
        )

        row.addView(texts, Ui.params(0, Ui.WRAP, weight = 1f))

        if (trailing != null) row.addView(trailing)

        addView(row, Ui.params(bottom = Ui.dp(this@ExtScreen, 2)))
    }

    protected fun LinearLayout.action(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
        addView(Ui.button(this@ExtScreen, label, palette, primary, danger, onClick), Ui.params(top = Ui.dp(this@ExtScreen, 8)))
    }

    protected fun LinearLayout.divide() = addView(Ui.divider(this@ExtScreen, palette))

    protected fun note(text: String) {
        column.addView(Ui.text(this, text, 12f, palette.muted), Ui.params(top = Ui.dp(this, 10), left = Ui.dp(this, 4)))
    }

    protected fun state(text: String, good: Boolean) = Ui.text(this, text, 12f, if (good) palette.ok else palette.faint, bold = true)

    /** Offers a server restart, since config, keys and plugins are read when the server starts. */
    protected fun askRestart() {
        Ui.confirm(
            this,
            tr("Применить изменения", "Apply the changes"),
            tr("Перезапустить сервер, чтобы изменения вошли в силу?", "Restart the server so the changes take effect?"),
            tr("Перезапустить", "Restart"),
        ) { ServerService.send(this, ServerService.ACTION_RESTART) }
    }
}
