package dev.opencode.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The colours of the desktop app's grey scale, resolved for the current light or dark mode. */
class Palette(context: Context) {
    val bg = context.getColor(R.color.oc_bg)
    val layer = context.getColor(R.color.oc_layer)
    val layerStrong = context.getColor(R.color.oc_layer_strong)
    val border = context.getColor(R.color.oc_border)
    val text = context.getColor(R.color.oc_text)
    val muted = context.getColor(R.color.oc_muted)
    val faint = context.getColor(R.color.oc_faint)
    val accentBg = context.getColor(R.color.oc_accent_bg)
    val accentText = context.getColor(R.color.oc_accent_text)
    val danger = context.getColor(R.color.oc_danger)
    val ok = context.getColor(R.color.oc_ok)
    val scrim = context.getColor(R.color.oc_scrim)
}

object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    fun isNight(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun rounded(context: Context, fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()

            if (stroke != null) setStroke(dp(context, 1), stroke)
        }

    fun ripple(context: Context, content: android.graphics.drawable.Drawable, palette: Palette): android.graphics.drawable.Drawable =
        RippleDrawable(ColorStateList.valueOf(palette.layerStrong), content, null)

    fun text(context: Context, value: CharSequence, sp: Float = 14f, color: Int, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
        }

    fun vertical(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun params(width: Int = MATCH, height: Int = WRAP, weight: Float = 0f, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height, weight).apply { setMargins(left, top, right, bottom) }

    fun icon(context: Context, res: Int, tint: Int, sizeDp: Int = 24): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    fun iconButton(context: Context, res: Int, description: String, palette: Palette, tint: Int = palette.muted, sizeDp: Int = 44, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            setColorFilter(tint)
            contentDescription = description
            val pad = dp(context, 10)

            setPadding(pad, pad, pad, pad)
            background = ripple(context, rounded(context, Color.TRANSPARENT, 22f), palette)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            setOnClickListener { onClick() }
        }

    fun button(context: Context, label: String, palette: Palette, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        text(
            context,
            label,
            14f,
            if (primary) palette.accentText else if (danger) palette.danger else palette.text,
            bold = true,
        ).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 44)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            background = ripple(
                context,
                rounded(context, if (primary) palette.accentBg else Color.TRANSPARENT, 10f, if (primary) null else palette.border),
                palette,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun card(context: Context, palette: Palette): LinearLayout = vertical(context).apply {
        background = rounded(context, palette.layer, 12f, palette.border)
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
    }

    fun divider(context: Context, palette: Palette): View = View(context).apply {
        setBackgroundColor(palette.border)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(context, 1)).apply { setMargins(0, dp(context, 10), 0, dp(context, 10)) }
    }

    fun spacer(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(context, heightDp))
    }

    fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private fun builder(activity: Activity): AlertDialog.Builder = AlertDialog.Builder(
        activity,
        if (isNight(activity)) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
    )

    fun confirm(activity: Activity, title: String, message: String, yes: String, onYes: () -> Unit) {
        builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    fun input(activity: Activity, title: String, hint: String, initial: String = "", password: Boolean = false, onOk: (String) -> Unit) {
        val field = EditText(activity).apply {
            setText(initial)
            this.hint = hint
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
            addView(field, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    /** A multi-line text editor for rule (README) content. Hands back the text as typed, without trimming. */
    fun multilineInput(activity: Activity, title: String, hint: String, initial: String = "", onOk: (String) -> Unit) {
        val field = EditText(activity).apply {
            setText(initial)
            this.hint = hint
            minLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
            addView(field, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(field.text.toString()) }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    fun choose(activity: Activity, title: String, items: List<String>, onPick: (Int) -> Unit) {
        builder(activity)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton(tr("Закрыть", "Close"), null)
            .show()
    }
}
