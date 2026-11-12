package app.grab.pill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Quick panel (swipe the pill up, or tap it when idle): a sheet at the bottom, all within thumb reach.
 * Top things right now, then one-tap actions ordered by what you usually do at this hour.
 */
class PillActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Habits.init(this); Ctx.init(this)
        val metro = CardStore.all(this).filter { it.kind == "METRO" }.maxByOrNull { it.ts }
        val quick = listOf(
            PanelUi.Quick("PAY", "₹", "Pay") { startActivity(Intent(this, ScanPayActivity::class.java)) },
            PanelUi.Quick("METRO", "🚇", "Metro") {
                when {
                    metro != null -> startActivity(Intent(this, CardActivity::class.java).putExtra("id", metro.id))
                    Store.get(this, "metro_app") != null -> Assistant.launch(this, Store.get(this, "metro_app")!!)
                    else -> startActivity(Intent(this, HubActivity::class.java).putExtra("tab", "wallet"))
                }
            },
            PanelUi.Quick("ASK", "📄", "Docs") { startActivity(Intent(this, DocsActivity::class.java)) },
            PanelUi.Quick("HUB", "🗂", "All") { startActivity(Intent(this, HubActivity::class.java)) },
        ).sortedByDescending { if (it.kind == "HUB") -1 else Habits.boost(it.kind) }
        val ev = CalendarHelper.next(this)
        val v = PanelUi.build(this,
            cards = CardStore.all(this).sortedByDescending { CardStore.score(it) }.take(3),
            header = listOfNotNull("Grab", Ctx.place?.name, "${Store.savedSeconds(this) / 60} min saved").joinToString(" · "),
            meeting = ev?.let { "${it.title.take(26)} · ${it.timeLabel}" }, meetingLink = ev?.link != null,
            quick = quick,
            onCard = { k -> if (k.kind == "ASK") Asks.send(this, k) else startActivity(Intent(this, CardActivity::class.java).putExtra("id", k.id)); finish() },
            onMeeting = { ev?.link?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))); Store.addSaved(this, 45) }; finish() },
            onQuick = { it.go(); finish() },
            onClose = { finish() })
        v.setBackgroundColor(0x66000000)
        setContentView(v)
        (v as ViewGroup).getChildAt(0)?.let { s -> s.translationY = D.dpf(this, 300); s.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start() }
    }
}

object PanelUi {
    data class Quick(val kind: String, val emoji: String, val label: String, val go: () -> Unit)

    fun build(c: Context, cards: List<Card>, header: String, meeting: String?, meetingLink: Boolean, quick: List<Quick>,
              onCard: (Card) -> Unit, onMeeting: () -> Unit, onQuick: (Quick) -> Unit, onClose: () -> Unit): View {
        val p = D.p(c)
        val outer = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setOnClickListener { onClose() } }
        val sheet = D.col(c, 16, 10).apply {
            isClickable = true
            background = D.round(c, p.bg, 28); elevation = D.dpf(c, 20)
        }
        sheet.addView(View(c).apply { background = D.round(c, p.line, 99) }, LinearLayout.LayoutParams(D.dp(c, 40), D.dp(c, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = D.dp(c, 10) })
        sheet.addView(D.small(c, header, p.ink3).apply { setPadding(D.dp(c, 4), 0, 0, D.dp(c, 4)) })
        if (cards.isEmpty()) sheet.addView(D.listRow(c, "☀️", p.warn, "Nothing urgent right now", "Codes, riders and tickets show up here"), D.wide(c, top = 6))
        cards.forEach { sheet.addView(CardViews.card(c, it) { k -> onCard(k) }, D.wide(c, top = 8)) }
        if (meeting != null) sheet.addView(D.listRow(c, "📅", D.kind("MEET"), meeting, if (meetingLink) "Tap to join" else "No meeting link",
            if (meetingLink) D.pill(c, "Join", D.kind("MEET"), solid = true) else null) { onMeeting() }, D.wide(c, top = 8))
        val row = D.row(c).apply { setPadding(0, D.dp(c, 14), 0, D.dp(c, 6)) }
        quick.forEach { q ->
            val color = if (q.kind == "HUB") p.ink2 else D.kind(q.kind)
            row.addView(D.col(c).apply {
                gravity = Gravity.CENTER; setPadding(0, D.dp(c, 10), 0, D.dp(c, 10))
                D.tappable(c, this, D.alpha(color, if (p.dark) 0x33 else 0x1A), 20); setOnClickListener { onQuick(q) }
                addView(TextView(c).apply { text = q.emoji; textSize = 22f; gravity = Gravity.CENTER; setTextColor(color); typeface = android.graphics.Typeface.DEFAULT_BOLD })
                addView(TextView(c).apply { text = q.label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(p.ink); typeface = android.graphics.Typeface.DEFAULT_BOLD })
            }, D.weight(c, 1f, 76, 8))
        }
        sheet.addView(row, D.wide(c))
        outer.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { setMargins(D.dp(c, 8), 0, D.dp(c, 8), D.dp(c, 10)) })
        return outer
    }
}
