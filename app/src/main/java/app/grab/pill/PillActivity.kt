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

/**
 * Quick panel (swipe the pill up, or tap it when idle). Sits at the bottom, all within thumb reach:
 * top 3 things right now, then a row of one-tap actions ordered by what you usually do at this hour.
 */
class PillActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Habits.init(this); Ctx.init(this)
        val dp = { v: Int -> CardViews.dp(this, v) }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setBackgroundColor(0x55000000); setOnClickListener { finish() } }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFFF2F2F7.toInt()) }
        }
        val where = Ctx.place?.let { " · at ${it.name}" } ?: ""
        sheet.addView(TextView(this).apply { text = "Grab$where · ${Store.savedSeconds(this@PillActivity) / 60} min saved"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0x99000000.toInt()) })
        val cards = CardStore.all(this).sortedByDescending { CardStore.score(it) }.take(3)
        if (cards.isEmpty()) sheet.addView(TextView(this).apply { text = "Nothing urgent right now."; textSize = 15f; setPadding(0, dp(10), 0, dp(6)) })
        cards.forEach { k ->
            sheet.addView(CardViews.gap(this))
            sheet.addView(CardViews.card(this, k) {
                if (it.kind == "ASK") Asks.send(this, it) else startActivity(Intent(this, CardActivity::class.java).putExtra("id", it.id))
                finish()
            })
        }
        CalendarHelper.next(this)?.let { ev ->
            sheet.addView(CardViews.gap(this))
            sheet.addView(Button(this).apply {
                text = "📅  ${ev.title.take(24)} · ${ev.timeLabel}" + if (ev.link != null) " · Join" else ""; isAllCaps = false; textSize = 15f
                setOnClickListener {
                    if (ev.link != null) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.link))); Store.addSaved(this@PillActivity, 45); finish() }
                    else Toast.makeText(this@PillActivity, "No meeting link in this event", Toast.LENGTH_SHORT).show()
                }
            })
        }

        // one-tap actions, the ones you use at this hour first
        data class Q(val kind: String, val label: String, val color: Int, val go: () -> Unit)
        val metroTicket = CardStore.all(this).filter { it.kind == "METRO" }.maxByOrNull { it.ts }
        val qs = listOf(
            Q("PAY", "₹\nPay", 0xFF1C8C4E.toInt()) { startActivity(Intent(this, ScanPayActivity::class.java)) },
            Q("METRO", "🚇\nMetro", 0xFF007AFF.toInt()) {
                when {
                    metroTicket != null -> startActivity(Intent(this, CardActivity::class.java).putExtra("id", metroTicket.id))
                    Store.get(this, "metro_app") != null -> Assistant.launch(this, Store.get(this, "metro_app")!!)
                    else -> startActivity(Intent(this, HubActivity::class.java).putExtra("tab", "wallet"))
                }
            },
            Q("ASK", "📄\nDocs", 0xFF30B0C7.toInt()) { startActivity(Intent(this, DocsActivity::class.java)) },
            Q("HUB", "🗂\nAll", 0xFF1C1C1E.toInt()) { startActivity(Intent(this, HubActivity::class.java)) },
        ).sortedByDescending { if (it.kind == "HUB") -1 else Habits.boost(it.kind) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        qs.forEach { q ->
            row.addView(Button(this).apply {
                text = q.label; isAllCaps = false; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); stateListAnimator = null
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(q.color) }
                setOnClickListener { q.go(); finish() }
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        sheet.addView(row)
        outer.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        setContentView(outer)
    }
}
