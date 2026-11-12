package app.grab.pill

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Look of the pop-up that slides up in the thumb zone (used by the pill service; rendered in tests). */
object PopupUi {
    const val PILL_IDLE = 0xF21C1C22.toInt()

    fun sheet(c: Context, m: Moment, onAct: (Act) -> Unit): LinearLayout {
        val p = D.p(c)
        val color = D.kind(m.kind)
        val sheet = D.col(c, 16, 10).apply {
            background = D.round(c, p.surface, 28, D.alpha(color, 0x66), 1)
            elevation = D.dpf(c, 18)
        }
        sheet.addView(View(c).apply { background = D.round(c, p.line, 99) },
            LinearLayout.LayoutParams(D.dp(c, 36), D.dp(c, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = D.dp(c, 10) })

        val head = D.row(c)
        head.addView(D.bubble(c, CardViews.ICON[m.kind] ?: "•", color, 44))
        val words = D.col(c).apply { setPadding(D.dp(c, 12), 0, D.dp(c, 4), 0) }
        words.addView(D.title(c, m.title))
        if (m.sub.isNotBlank()) words.addView(D.small(c, m.sub).apply { maxLines = 2 })
        head.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (m.big.isNotEmpty() && m.qr.isEmpty()) head.addView(TextView(c).apply {
            text = m.big
            textSize = if (m.big.length <= 6) 28f else 20f; setTextColor(p.ink); letterSpacing = if (m.big.all { it.isDigit() }) 0.18f else 0.02f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        sheet.addView(head)

        val qr = if (m.qr.isNotEmpty()) Qr.encode(m.qr, 720) else null
        when {
            // the code always sits on white, even in dark mode, so scanners read it first time
            qr != null -> sheet.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER; setPadding(D.dp(c, 14), D.dp(c, 14), D.dp(c, 14), D.dp(c, 14)); background = D.round(c, Color.WHITE, 20)
                addView(ImageView(c).apply { setImageBitmap(qr); adjustViewBounds = true }, LinearLayout.LayoutParams(D.dp(c, 220), D.dp(c, 220)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = D.dp(c, 12) })
            m.img.isNotEmpty() -> BitmapFactory.decodeFile(m.img)?.let { b ->
                sheet.addView(ImageView(c).apply { setImageBitmap(b); adjustViewBounds = true; clipToOutline = true; background = D.round(c, Color.WHITE, 16) },
                    LinearLayout.LayoutParams(-1, D.dp(c, 260)).apply { topMargin = D.dp(c, 12) })
            }
        }

        if (m.acts.isNotEmpty()) {
            val row = D.row(c).apply { setPadding(0, D.dp(c, 14), 0, D.dp(c, 2)) }
            m.acts.take(3).forEachIndexed { i, a ->
                row.addView(MaterialButton(c).apply {
                    text = a.label; isAllCaps = false; textSize = 15f; maxLines = 1
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    setTextColor(if (a.primary) D.on(color) else p.ink)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(if (a.primary) color else p.surface2)
                    cornerRadius = D.dp(c, 16); insetTop = 0; insetBottom = 0; elevation = 0f; stateListAnimator = null
                    setPadding(D.dp(c, 6), 0, D.dp(c, 6), 0)
                    setOnClickListener { onAct(a) }
                }, LinearLayout.LayoutParams(0, D.dp(c, 52), if (a.primary) 1.3f else 1f).apply { if (i > 0) marginStart = D.dp(c, 8) })
            }
            sheet.addView(row)
        }
        return sheet
    }

    /** The pill as drawn by the service (for previews). */
    fun pill(c: Context, emoji: String, title: String, value: String, color: Int?): View = D.row(c).apply {
        setPadding(D.dp(c, 8), D.dp(c, 8), D.dp(c, 16), D.dp(c, 8))
        background = D.round(c, if (color == null) PILL_IDLE else D.alpha(CardViews.darker(color), 0xF2), 26, 0x22FFFFFF)
        elevation = D.dpf(c, 12)
        addView(TextView(c).apply { text = emoji; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = D.circle(0x26FFFFFF) }, LinearLayout.LayoutParams(D.dp(c, 30), D.dp(c, 30)))
        addView(TextView(c).apply { text = title; textSize = 13f; setTextColor(Color.WHITE); setPadding(D.dp(c, 10), 0, 0, 0); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD) })
        if (value.isNotEmpty()) addView(TextView(c).apply { text = value; textSize = 17f; setTextColor(Color.WHITE); setPadding(D.dp(c, 10), 0, 0, 0); typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD) })
    }
}
