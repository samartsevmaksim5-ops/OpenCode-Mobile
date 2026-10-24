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
    private const val SEEN_KEY = "update_107"

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
            Ui.text(activity, tr("ОБНОВЛЕНИЕ 1.0.7", "UPDATE 1.0.7"), 16f, palette.text, bold = true),
            Ui.params(0, Ui.WRAP, weight = 1f),
        )
        header.addView(Ui.iconButton(activity, R.drawable.ic_close, tr("Закрыть", "Close"), palette, tint = palette.muted, sizeDp = 36) { dismiss() })
        card.addView(header, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(activity, 12)))

        // The announcement picture with corners rounded to match the card.
        val image = ImageView(activity).apply {
            setImageResource(R.drawable.update_107)
            adjustViewBounds = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(activity, 14).toFloat())
                }
            }
            contentDescription = tr("Обновление 1.0.7", "Update 1.0.7")
        }
        card.addView(image, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(activity, 12)))

        card.addView(Ui.text(activity, tr("Что изменено:", "What changed:"), 14f, palette.text, bold = true), Ui.params(bottom = Ui.dp(activity, 6)))

        for (line in listOf(
            tr("- исправлено «квота исчерпана» на первом же сообщении: WARP больше не включён по умолчанию, сервер выходит в сеть с вашего собственного IP", "- fixed quota-exhausted on the very first message: WARP is no longer on by default, the server now goes online from your own IP"),
            tr("- ошибки квоты теперь говорят правду: дождитесь сброса или войдите в аккаунт — без обещаний про «новый IP через пару секунд»", "- quota errors tell the truth now: wait for the reset or sign in - no more promises about a new IP in a few seconds"),
            tr("- убрана автоперерегистрация WARP при ошибках квоты: это не сетевая проблема, туннель больше не пересоздаётся зря", "- removed the automatic WARP re-registration on quota errors: that is not a network failure, the tunnel is no longer rebuilt in vain"),
            tr("- добавлен раздел «Сеть, IP и VPN»: проверка публичного IP с геолокацией и ручные DNS-тесты трёх DoH-резолверов", "- new Network, IP and VPN section: public IP check with geolocation and manual DNS tests of three DoH resolvers"),
            tr("- поддержка VPN-подписок VLESS и Hysteria2 через sing-box — только для локального сервера, по вашему выбору", "- VLESS and Hysteria2 subscription support via sing-box - for the local server only, strictly opt-in"),
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
