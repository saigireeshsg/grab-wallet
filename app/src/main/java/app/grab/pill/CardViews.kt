package app.grab.pill

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** How a Card looks everywhere: list rows, the big "Right now" card, and section headers. */
object CardViews {
    val COLOR get() = D.KIND
    val ICON = mapOf(
        "OTP" to "🔢", "RIDE" to "🛵", "ORDER" to "🛍", "METRO" to "🚇", "MOVIE" to "🎬", "TICKET" to "🎟",
        "ASK" to "📄", "CALL" to "🛡", "MEET" to "📅", "PAY" to "₹"
    )

    fun dp(c: Context, v: Int) = D.dp(c, v)

    /** Big value shown on the right / in the hero: OTP digits spaced out, otherwise as-is. */
    fun valueText(k: Card): String = when {
        k.kind == "MOVIE" && k.whenMs > 0 -> Parser.timeLabel(k.whenMs).substringAfter(" ")
        k.kind == "ASK" -> ""
        else -> k.value
    }

    /** What the main button on a card does, in words. */
    fun actionLabel(k: Card): String = when (k.kind) {
        "OTP" -> "Copy code"
        "RIDE" -> if (k.value.isNotEmpty() && k.value.all { it.isDigit() }) "Copy OTP" else "Open ${k.src}"
        "ORDER" -> "Open ${k.src}"
        "METRO", "MOVIE", "TICKET" -> "Show ticket"
        "ASK" -> when {
            k.x().optBoolean("reply") -> "Reply"
            k.value.isNotEmpty() -> "Send ${k.value}"
            else -> "Find the document"
        }
        else -> "Open"
    }

    /** List row for a card. */
    fun card(c: Context, k: Card, onClick: (Card) -> Unit): View {
        val trailing: View? = when {
            k.img.isNotEmpty() && k.qr.isEmpty() -> ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb(k.img)); clipToOutline = true
                background = D.round(c, D.p(c).surface2, 10)
                layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 44))
            }
            valueText(k).isNotEmpty() -> D.mono(c, valueText(k), 17f).apply { maxLines = 1 }
            else -> D.chevron(c)
        }
        return D.listRow(c, ICON[k.kind] ?: "•", D.kind(k.kind), k.title, k.sub, trailing) { onClick(k) }
    }

    /** The big coloured "Right now" card at the top of the home screen. */
    fun hero(c: Context, k: Card, onAction: (Card) -> Unit, onOpen: (Card) -> Unit): View {
        val color = D.kind(k.kind)
        val box = D.col(c, 20, 18).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, darker(color))).apply { cornerRadius = D.dpf(c, 26) }
            elevation = D.dpf(c, 2)
            isClickable = true; setOnClickListener { onOpen(k) }
        }
        val top = D.row(c)
        top.addView(TextView(c).apply { text = "${ICON[k.kind] ?: "•"}  RIGHT NOW"; textSize = 12f; letterSpacing = 0.1f; setTextColor(0xCCFFFFFF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (k.src.isNotBlank()) top.addView(TextView(c).apply { text = k.src; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()) })
        box.addView(top)
        box.addView(D.space(c, 10))
        box.addView(TextView(c).apply { text = k.title; textSize = 22f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (k.sub.isNotBlank()) box.addView(TextView(c).apply { text = k.sub; textSize = 14f; setTextColor(0xE6FFFFFF.toInt()); maxLines = 2; setPadding(0, dp(c, 2), 0, 0) })
        val v = valueText(k)
        if (v.isNotEmpty()) box.addView(TextView(c).apply {
            text = v; textSize = 38f; setTextColor(Color.WHITE); letterSpacing = if (v.all { it.isDigit() }) 0.22f else 0.04f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setPadding(0, dp(c, 10), 0, 0)
        })
        box.addView(D.space(c, 16))
        box.addView(MaterialButton(c).apply {
            text = actionLabel(k); isAllCaps = false; textSize = 16f; setTextColor(darker(color))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            cornerRadius = dp(c, 16); insetTop = 0; insetBottom = 0; elevation = 0f; stateListAnimator = null
            setOnClickListener { onAction(k) }
        }, D.wide(c, 52))
        return box
    }

    fun darker(color: Int): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(color, hsv); hsv[2] *= 0.72f; hsv[1] = (hsv[1] * 1.05f).coerceAtMost(1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun thumb(path: String) = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 })
    } catch (e: Exception) { null }

    fun section(c: Context, title: String) = D.label(c, title)
    fun gap(c: Context) = D.space(c, 10)
}
