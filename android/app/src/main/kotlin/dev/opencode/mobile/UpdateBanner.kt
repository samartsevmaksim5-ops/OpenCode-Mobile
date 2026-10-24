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
    private const val SEEN_KEY = "update_106"

    /** Guards against a second copy stacking over the first one when the page reports being ready again. */
    private var active = false

    fun shouldShow(activity: MainActivity): Boolean = !active && !activity.app.prefs.seen(SEEN_KEY)

    /** Builds the overlay over everything else; [close] runs when it goes away for any reason. */
    fun show(activity: MainActivity, close: () -> Unit) {
        if (active) return

        active = true

        val palette = activity.palette
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(palette.scrim)
            tag = SCRIM_TAG
        }
        val card = Ui.vertical(activity).apply {
            background = Ui.rounded(activity, palette.bg, 20f, palette.border)
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 18))
        }

        fun dismiss() {
            active = false
            activity.app.prefs.markSeen(SEEN_KEY)
            (scrim.parent as? FrameLayout)?.removeView(scrim)
            close()
        }

        // Header: the title on the left, the cross on the right.
        val header = Ui.horizontal(activity)
        header.addView(
            Ui.text(activity, tr("ОБНОВЛЕНИЕ 1.0.6", "UPDATE 1.0.6"), 16f, palette.text, bold = true),
            Ui.params(0, Ui.WRAP, weight = 1f),
        )
        header.addView(Ui.iconButton(activity, R.drawable.ic_close, tr("Закрыть", "Close"), palette, tint = palette.muted, sizeDp = 36) { dismiss() })
        card.addView(header, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(activity, 12)))

        // The announcement picture with corners rounded to match the card.
        val image = ImageView(activity).apply {
            setImageResource(R.drawable.update_106)
            adjustViewBounds = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(activity, 14).toFloat())
                }
            }
            contentDescription = tr("Обновление 1.0.6", "Update 1.0.6")
        }
        card.addView(image, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(activity, 12)))

        card.addView(Ui.text(activity, tr("Что изменено:", "What changed:"), 14f, palette.text, bold = true), Ui.params(bottom = Ui.dp(activity, 6)))

        for (line in listOf(
            tr("- добавлен Cloudflare WARP: запросы к моделям идут через чистый IP — общие лимиты бесплатных версий больше не помеха", "- Cloudflare WARP is here: model requests go through a clean IP - shared free-model limits no longer stop you"),
            tr("- при упоре в лимит туннель сам пересоздаётся с новым IP — без ваших действий и без нового аккаунта", "- when a limit is hit the tunnel rebuilds itself with a fresh IP - no action from you, no new account needed"),
            tr("- вход по Google больше не навязывается: экран входа никогда не открывается сам, только по вашему нажатию", "- Google sign-in is never pushed on you: the sign-in screen only opens when you tap it"),
            tr("- исправлены падения «сервер постоянно падает»: проблемные устройства сами переключаются в совместимый режим", "- fixed the keeps crashing loop: affected devices switch to compatibility mode on their own"),
            tr("- WARP теперь действительно проводит запросы сервера через туннель и проверяет, что трафик идёт; лимиты замечаются и в фоне", "- WARP now really carries the server's requests and checks that traffic flows; limits are noticed in the background too"),
            tr("- сервер не падает в цикле: счётчик перезапусков сбрасывается, порт профиля больше не «уплывает»", "- no more crash loops: the restart budget resets, and a profile's port no longer drifts"),
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

    /** A leftover banner from a rebuilt page never survives a new instance of the activity. */
    fun reset() {
        active = false
    }

    private const val SCRIM_TAG = "update_banner_scrim"
}
