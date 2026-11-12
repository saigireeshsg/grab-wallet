package app.grab.pill

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Home screen content as plain views (the activity feeds it data; tests render it to PNG). */
object HubUi {
    data class Now(
        val greeting: String, val date: String,
        val chips: List<String>,
        val nudge: String?,              // missing required setup, in words
        val hero: Card?,
        val more: List<Card>,
        val insights: List<Pair<String, String>>,
        val savedMin: Int,
        val update: String? = null,      // "0.4.12 is ready" or "Downloading… 40%"
    )

    data class Wallet(val tickets: List<Card>, val asks: List<Card>, val live: List<Card>, val codes: List<Card>, val docs: List<Doc>)

    interface Actions {
        fun card(k: Card); fun action(k: Card); fun settings(); fun setup()
        fun pay(); fun metro(); fun docs(); fun tab(id: String)
        fun addScreenshot(); fun addPdf()
        fun update() {}
    }

    // ------------------------------------------------------------------ header

    private fun header(c: Context, title: String, sub: String, a: Actions): View {
        val p = D.p(c)
        val r = D.row(c).apply { gravity = Gravity.TOP }
        val words = D.col(c)
        words.addView(D.small(c, sub, p.ink2))
        words.addView(D.display(c, title))
        r.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(c).apply {
            text = "⚙️"; textSize = 18f; gravity = Gravity.CENTER
            D.tappable(c, this, p.surface, 99, if (p.dark) null else p.line); setOnClickListener { a.settings() }
            contentDescription = "Settings"
        }, LinearLayout.LayoutParams(D.dp(c, 46), D.dp(c, 46)).apply { topMargin = D.dp(c, 4) })
        return r
    }

    // ------------------------------------------------------------------ Now

    fun now(c: Context, m: Now, a: Actions): View {
        val p = D.p(c)
        val col = D.col(c, 20, 8)
        col.addView(D.space(c, 12))
        col.addView(header(c, m.greeting, m.date, a))

        if (m.chips.isNotEmpty()) {
            val chips = D.row(c).apply { setPadding(0, D.dp(c, 14), 0, 0) }
            m.chips.forEach { chips.addView(D.chip(c, it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = D.dp(c, 8) }) }
            col.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }

        if (m.nudge != null) {
            col.addView(D.space(c, 16))
            val n = D.row(c).apply { setPadding(D.dp(c, 16), D.dp(c, 14), D.dp(c, 12), D.dp(c, 14)) }
            D.tappable(c, n, D.alpha(p.brand, if (p.dark) 0x33 else 0x14), 20); n.setOnClickListener { a.setup() }
            n.addView(TextView(c).apply { text = "⚡"; textSize = 20f; setPadding(0, 0, D.dp(c, 12), 0) })
            n.addView(D.col(c).apply {
                addView(D.title(c, "Finish setup"))
                addView(D.small(c, m.nudge))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            n.addView(D.pill(c, "Fix", p.brand, solid = true))
            col.addView(n, D.wide(c))
        }

        if (m.update != null) {
            col.addView(D.space(c, 12))
            val u = D.row(c).apply { setPadding(D.dp(c, 16), D.dp(c, 12), D.dp(c, 12), D.dp(c, 12)) }
            D.tappable(c, u, p.surface, 20, if (p.dark) null else p.line); u.setOnClickListener { a.update() }
            u.addView(D.bubble(c, "⬆️", D.kind("UPDATE"), 40))
            u.addView(D.col(c).apply {
                setPadding(D.dp(c, 12), 0, D.dp(c, 8), 0)
                addView(D.title(c, "New version of Grab")); addView(D.small(c, m.update))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!m.update.startsWith("Downloading")) u.addView(D.pill(c, "Update", D.kind("UPDATE"), solid = true))
            col.addView(u, D.wide(c))
        }

        col.addView(D.space(c, 18))
        if (m.hero != null) col.addView(CardViews.hero(c, m.hero, { a.action(it) }, { a.card(it) }), D.wide(c))
        else col.addView(empty(c, a), D.wide(c))

        if (m.more.isNotEmpty()) {
            col.addView(D.label(c, "Also on your plate"))
            m.more.forEach { col.addView(CardViews.card(c, it) { k -> a.card(k) }, D.wide(c)); col.addView(D.space(c, 10)) }
        }

        col.addView(D.label(c, "Your assistant"))
        val stats = D.row(c)
        stats.addView(stat(c, "${m.savedMin}", "min saved"), D.weight(c, 1f, gap = 0))
        stats.addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 10), 1))
        stats.addView(stat(c, "${m.insights.size}", "things learned"), D.weight(c, 1f, gap = 0))
        col.addView(stats, D.wide(c))
        if (m.insights.isNotEmpty()) {
            col.addView(D.space(c, 10))
            val box = D.card(c, 14)
            m.insights.forEach { (e, t) -> box.addView(D.bullet(c, e, t)) }
            col.addView(box, D.wide(c))
        }
        col.addView(D.small(c, "Tip: hold the pill to scan & pay · swipe it up for the quick panel", p.ink3).apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 18), 0, D.dp(c, 18)) }, D.wide(c))
        return col
    }

    private fun stat(c: Context, big: String, label: String) = D.card(c, 16).apply {
        addView(D.h1(c, big))
        addView(D.small(c, label))
    }

    private fun empty(c: Context, a: Actions): View {
        val p = D.p(c)
        val box = D.card(c, 22).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(D.bubble(c, "☀️", p.warn, 64, square = false))
        box.addView(D.space(c, 12))
        box.addView(D.h2(c, "All clear").apply { gravity = Gravity.CENTER })
        box.addView(D.body(c, "OTPs, riders and tickets appear here the moment they arrive.").apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 4), 0, D.dp(c, 14)) })
        val r = D.row(c).apply { gravity = Gravity.CENTER }
        r.addView(D.chip(c, "🚇  Add metro ticket") { a.addScreenshot() })
        r.addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 8), 1))
        r.addView(D.chip(c, "📄  Add documents") { a.docs() })
        box.addView(r)
        return box
    }

    // ------------------------------------------------------------------ Wallet

    fun wallet(c: Context, m: Wallet, a: Actions): View {
        val p = D.p(c)
        val col = D.col(c, 20, 8)
        col.addView(D.space(c, 12))
        col.addView(header(c, "Wallet", "Tickets, codes & documents", a))
        fun group(title: String, list: List<Card>) {
            if (list.isEmpty()) return
            col.addView(D.label(c, title))
            list.forEach { col.addView(CardViews.card(c, it) { k -> a.card(k) }, D.wide(c)); col.addView(D.space(c, 10)) }
        }
        group("Tickets", m.tickets)
        group("Waiting on you", m.asks)
        group("Live status", m.live)
        group("Codes · auto-delete in 15 min", m.codes)

        col.addView(D.label(c, "Documents"))
        col.addView(D.listRow(c, "📄", D.kind("ASK"),
            if (m.docs.isEmpty()) "Add your PAN, Aadhaar, resume…" else "${m.docs.size} document${if (m.docs.size > 1) "s" else ""} ready",
            if (m.docs.isEmpty()) "Sent in one tap when someone asks for them" else m.docs.take(4).joinToString(" · ") { it.name },
            D.chevron(c)) { a.docs() }, D.wide(c))

        col.addView(D.label(c, "Add a ticket"))
        val add = D.card(c, 16)
        add.addView(D.body(c, "Screenshot the QR in your metro, movie or train app, then add it here. Or use Share → Save to Grab from any app.", p.ink2))
        add.addView(D.space(c, 12))
        val r = D.row(c)
        r.addView(D.secondary(c, "📷  Screenshot") { a.addScreenshot() }, D.weight(c, 1f, 52, 0))
        r.addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 10), 1))
        r.addView(D.secondary(c, "📄  PDF") { a.addPdf() }, D.weight(c, 1f, 52, 0))
        add.addView(r, D.wide(c))
        col.addView(add, D.wide(c))
        if (m.tickets.isEmpty() && m.asks.isEmpty() && m.live.isEmpty() && m.codes.isEmpty())
            col.addView(D.small(c, "Codes and live rides show up here by themselves.", p.ink3).apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 18), 0, 0) }, D.wide(c))
        col.addView(D.space(c, 24))
        return col
    }

    // ------------------------------------------------------------------ bottom: quick actions + tabs (thumb zone)

    fun bottom(c: Context, tab: String, a: Actions): View {
        val p = D.p(c)
        val bar = D.bottomBar(c)
        val acts = D.row(c)
        fun tile(emoji: String, label: String, color: Int, f: () -> Unit) = D.col(c).apply {
            gravity = Gravity.CENTER; setPadding(0, D.dp(c, 10), 0, D.dp(c, 10))
            D.tappable(c, this, D.alpha(color, if (p.dark) 0x33 else 0x18), 18); setOnClickListener { f() }
            addView(TextView(c).apply { text = emoji; textSize = 20f; gravity = Gravity.CENTER; setTextColor(color); typeface = android.graphics.Typeface.DEFAULT_BOLD })
            addView(TextView(c).apply { text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(p.ink); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, D.dp(c, 2), 0, 0) })
        }
        acts.addView(tile("₹", "Scan & pay", D.kind("PAY")) { a.pay() }, D.weight(c, 1f, 68, 8))
        acts.addView(tile("🚇", "Metro", D.kind("METRO")) { a.metro() }, D.weight(c, 1f, 68, 8))
        acts.addView(tile("📄", "Docs", D.kind("ASK")) { a.docs() }, D.weight(c, 1f, 68, 8))
        bar.addView(acts, D.wide(c))

        val seg = D.row(c).apply { setPadding(D.dp(c, 4), D.dp(c, 4), D.dp(c, 4), D.dp(c, 4)); background = D.round(c, p.surface2, 16) }
        fun segBtn(t: String, id: String) = TextView(c).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (tab == id) p.ink else p.ink2)
            if (tab == id) { background = D.round(c, p.surface, 12); elevation = D.dpf(c, 1) }
            else { D.tappable(c, this, android.graphics.Color.TRANSPARENT, 12) }
            setOnClickListener { a.tab(id) }
        }
        seg.addView(segBtn("Now", "now"), D.weight(c, 1f, 40))
        seg.addView(segBtn("Wallet", "wallet"), D.weight(c, 1f, 40))
        bar.addView(seg, D.wide(c, top = 12))
        return bar
    }
}
