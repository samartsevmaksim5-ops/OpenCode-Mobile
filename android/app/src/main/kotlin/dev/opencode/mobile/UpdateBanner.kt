package dev.opencode.mobile

import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one-time card that greets a fresh install after the interface appears: what this emergency update changes. The
 * person closes it with the corner cross, a tap on the dark backdrop, or Back; it never comes back afterwards.
 */
object UpdateBanner {
    private const val SEEN_KEY = "update_105"

    fun shouldShow(activity: MainActivity): Boolean = !activity.app.prefs.seen(SEEN_KEY)

    /** Builds the overlay over everything else; [close] runs when it goes away for any reason. */
    fun show(activity: MainActivity, close: () -> Unit) {
        val palette = activity.palette
        val scrim = FrameLayout(activity).apply { setBackgroundColor(palette.scrim) }
        val card = Ui.vertical(activity).apply {
            background = Ui.rounded(activity, palette.bg, 20f, palette.border)
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 18))
        }
        val box = FrameLayout(activity)

        fun dismiss() {
            activity.app.prefs.markSeen(SEEN_KEY)
            (scrim.parent as? FrameLayout)?.removeView(scrim)
            close()
        }

        // Header: the title on the left, the cross on the right.
        val header = Ui.horizontal(activity)
        header.addView(
            Ui.text(activity, tr("ЭКСТРЕННОЕ ОБНОВЛЕНИЕ 1.0.5", "EMERGENCY UPDATE 1.0.5"), 16f, palette.text, bold = true),
            Ui.params(0, Ui.WRAP, weight = 1f),
        )
        header.addView(Ui.iconButton(activity, R.drawable.ic_close, tr("Закрыть", "Close"), palette, tint = palette.muted, sizeDp = 36) { dismiss() })
        card.addView(header, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(activity, 12)))

        // The announcement picture with corners rounded to match the card.
        val image = ImageView(activity).apply {
            setImageResource(R.drawable.update_105)
            adjustViewBounds = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(activity, 14).toFloat())
                }
            }
            contentDescription = tr("Экстренное обновление 1.0.5", "Emergency update 1.0.5")
        }
        card.addView(image, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(activity, 12)))

        card.addView(Ui.text(activity, tr("Что изменено:", "What changed:"), 14f, palette.text, bold = true), Ui.params(bottom = Ui.dp(activity, 6)))

        for (line in listOf(
            tr("- фикс критических ошибок", "- fixes for critical bugs"),
            tr("- упрощение пользования", "- simpler to use"),
            tr("- маленькие доработки", "- small improvements"),
        )) {
            card.addView(Ui.text(activity, line, 14f, palette.muted), Ui.params(bottom = Ui.dp(activity, 3)))
        }

        card.addView(
            Ui.text(activity, tr("Нажмите на крестик, чтобы продолжить.", "Tap the cross to continue."), 12f, palette.faint),
            Ui.params(top = Ui.dp(activity, 8)),
        )

        scrim.addView(card, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            gravity = Gravity.CENTER
            setMargins(Ui.dp(activity, 20), Ui.dp(activity, 24), Ui.dp(activity, 20), Ui.dp(activity, 24))
        })

        // A tap on the dark backdrop also closes; touches on the card itself do not.
        scrim.setOnClickListener { dismiss() }
        card.isClickable = true
        card.setOnClickListener { }

        activity.overlaySurface().addView(
            scrim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }
}
