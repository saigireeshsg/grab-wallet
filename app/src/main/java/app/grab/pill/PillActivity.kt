package app.grab.pill

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Opens when the pill is idle and tapped: top 3 cards + quick actions. Tap outside to close. */
class PillActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = { v: Int -> CardViews.dp(this, v) }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setBackgroundColor(0x55000000); setOnClickListener { finish() } }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFFF2F2F7.toInt()) }
        }
        sheet.addView(TextView(this).apply { text = "Grab · ${Store.savedSeconds(this@PillActivity) / 60} min saved"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0x99000000.toInt()) })
        val cards = CardStore.all(this).sortedByDescending { CardStore.score(it) }.take(3)
        if (cards.isEmpty()) sheet.addView(TextView(this).apply { text = "Nothing urgent right now."; textSize = 15f; setPadding(0, dp(10), 0, dp(6)) })
        cards.forEach { k ->
            sheet.addView(CardViews.gap(this))
            sheet.addView(CardViews.card(this, k) { startActivity(Intent(this, CardActivity::class.java).putExtra("id", it.id)); finish() })
        }
        sheet.addView(CardViews.gap(this))
        fun act(t: String, f: () -> Unit) = sheet.addView(Button(this).apply { text = t; isAllCaps = false; textSize = 15f; setOnClickListener { f() } })
        act("📷  Scan & pay a QR") { startActivity(Intent(this, ScanPayActivity::class.java)); finish() }
        CalendarHelper.next(this)?.let { ev ->
            act("📅  ${ev.title.take(22)} · ${ev.timeLabel}" + if (ev.link != null) " · Join" else "") {
                if (ev.link != null) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.link))); Store.addSaved(this, 45) }
                else Toast.makeText(this, "No meeting link in this event", Toast.LENGTH_SHORT).show()
            }
        }
        act("🗂  All tickets & codes") { startActivity(Intent(this, HubActivity::class.java)); finish() }
        outer.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        setContentView(outer)
    }
}
