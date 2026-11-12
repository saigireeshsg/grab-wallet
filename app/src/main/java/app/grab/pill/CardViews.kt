package app.grab.pill

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One look for every card, used by the hub and the pill panel. */
object CardViews {
    val COLOR = mapOf(
        "OTP" to 0xFF5856D6.toInt(), "RIDE" to 0xFFFF9500.toInt(), "ORDER" to 0xFFFF3B30.toInt(),
        "METRO" to 0xFF007AFF.toInt(), "MOVIE" to 0xFFAF52DE.toInt(), "TICKET" to 0xFF34C759.toInt()
    )
    val ICON = mapOf("OTP" to "🔢", "RIDE" to "🛵", "ORDER" to "🛍", "METRO" to "🚇", "MOVIE" to "🎬", "TICKET" to "🎟")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun card(c: Context, k: Card, onClick: (Card) -> Unit): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
            background = GradientDrawable().apply { cornerRadius = dp(c, 14).toFloat(); setColor(0xFFFFFFFF.toInt()) }
            setOnClickListener { onClick(k) }
        }
        val icon = TextView(c).apply {
            text = ICON[k.kind] ?: "•"; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(c, 10).toFloat(); setColor(COLOR[k.kind] ?: 0xFF8E8E93.toInt()) }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)))
        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 12), 0, dp(c, 8), 0) }
        mid.addView(TextView(c).apply { text = k.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF000000.toInt()); maxLines = 1 })
        mid.addView(TextView(c).apply { text = k.sub; textSize = 13f; setTextColor(0x99000000.toInt()); maxLines = 2 })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        if (k.img.isNotEmpty()) {
            val th = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb(k.img)) }
            row.addView(th, LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)))
        } else if (k.value.isNotEmpty()) {
            row.addView(TextView(c).apply {
                text = if (k.kind == "OTP" || (k.kind == "RIDE" && k.value.all { it.isDigit() })) k.value.chunked(1).joinToString(" ") else k.value
                textSize = 18f; typeface = Typeface.MONOSPACE; setTextColor(0xFF000000.toInt())
            })
        }
        return row
    }

    private fun thumb(path: String) = try {
        val o = BitmapFactory.Options().apply { inSampleSize = 8 }
        BitmapFactory.decodeFile(path, o)
    } catch (e: Exception) { null }

    fun section(c: Context, title: String) = TextView(c).apply {
        text = title.uppercase(); textSize = 12f; setTextColor(0x99000000.toInt()); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(c, 6), dp(c, 18), 0, dp(c, 6))
    }

    fun gap(c: Context) = View(c).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(c, 8)) }
}
