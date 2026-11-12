package app.grab.pill

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

/**
 * Two tabs, everything reachable with your thumb:
 *   NOW    – what matters at this moment, ranked by the assistant (where you are, the time, your habits)
 *   WALLET – tickets, documents, codes, places
 * The bottom bar (Pay · Metro · Docs) is always one tap away.
 */
class HubActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout
    private lateinit var tabNow: TextView
    private lateinit var tabWallet: TextView
    private var tab = "now"
    private val listener: () -> Unit = { runOnUiThread { build() } }

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { u -> Importer.importUri(this, u, contentResolver.getType(u)) { card ->
            runOnUiThread { Toast.makeText(this, card?.let { "Added: ${it.title}" } ?: "Couldn't read that image", Toast.LENGTH_SHORT).show() }
        } }
    }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) Importer.importUri(this, u, "application/pdf") { card ->
            runOnUiThread { Toast.makeText(this, card?.let { "Added: ${it.title}" } ?: "Couldn't read that PDF", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Habits.init(this); Ctx.init(this)
        tab = intent.getStringExtra("tab") ?: "now"
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(24)) }
        outer.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(bottomBar(), LinearLayout.LayoutParams(-1, -2))
        setContentView(outer)
        CardBus.listen(listener)
        if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, PillService::class.java))
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); i.getStringExtra("tab")?.let { tab = it; build() } }
    override fun onDestroy() { CardBus.unlisten(listener); super.onDestroy() }
    override fun onResume() { super.onResume(); build() }
    private fun dp(v: Int) = CardViews.dp(this, v)

    // ------------------------------------------------------------------ bottom (thumb zone)

    private fun bottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(14))
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()) }; elevation = dp(12).toFloat()
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(t: String, color: Int, f: () -> Unit) = actions.addView(Button(this).apply {
            text = t; isAllCaps = false; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); stateListAnimator = null
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color) }
            setOnClickListener { f() }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        act("₹ Scan & pay", 0xFF1C8C4E.toInt()) { startActivity(Intent(this, ScanPayActivity::class.java)) }
        act("🚇 Metro", 0xFF007AFF.toInt()) { metro() }
        act("📄 Docs", 0xFF30B0C7.toInt()) { startActivity(Intent(this, DocsActivity::class.java)) }
        bar.addView(actions)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun tabView(t: String, id: String) = TextView(this).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, dp(6))
            setOnClickListener { tab = id; build() }
        }
        tabNow = tabView("Now", "now"); tabWallet = tabView("Wallet", "wallet")
        tabs.addView(tabNow, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(tabWallet, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tabs)
        return bar
    }

    /** Metro button: today's ticket full-screen, else the metro app you picked, else add one. */
    private fun metro() {
        val t = CardStore.all(this).filter { it.kind == "METRO" }.maxByOrNull { it.ts }
        val app = Store.get(this, "metro_app")
        when {
            t != null -> open(t)
            app != null -> Assistant.launch(this, app)
            else -> { tab = "wallet"; build(); Toast.makeText(this, "Add a metro ticket screenshot, or pick your metro app in Setup", Toast.LENGTH_LONG).show() }
        }
    }

    // ------------------------------------------------------------------ content

    private fun build() {
        col.removeAllViews()
        if (::tabNow.isInitialized) {
            tabNow.setTextColor(if (tab == "now") 0xFF000000.toInt() else 0x66000000)
            tabWallet.setTextColor(if (tab == "wallet") 0xFF000000.toInt() else 0x66000000)
        }
        if (tab == "wallet") buildWallet() else buildNow()
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    private fun buildNow() {
        col.addView(Ui.h1(this, greeting()))
        val ctxLine = listOfNotNull(
            Ctx.place?.let { "📍 at ${it.name}" },
            CalendarHelper.next(this)?.let { "📅 ${it.title.take(24)} at ${it.timeLabel}" },
        ).joinToString("\n").ifEmpty { "Nothing needs you right now." }
        col.addView(Ui.p(this, ctxLine))

        if (!setupDone()) {
            col.addView(CardViews.gap(this))
            col.addView(Ui.button(this, "Finish setup (3 min) →", 0xFF007AFF.toInt()) { startActivity(Intent(this, SetupActivity::class.java)) })
        }

        val ranked = CardStore.all(this).sortedByDescending { CardStore.score(it) }
        val urgent = ranked.filter { CardStore.score(it) >= 40 }.take(6)
        if (urgent.isNotEmpty()) {
            col.addView(CardViews.section(this, "Right now"))
            urgent.forEach { k -> col.addView(CardViews.card(this, k) { open(it) }); col.addView(CardViews.gap(this)) }
        }

        // What the assistant has learned / done for you
        val learned = mutableListOf<String>()
        Habits.usualHour("METRO")?.let { learned += "🚇 Metro ticket: $it" }
        Habits.usualHour("PAY")?.let { learned += "₹ Scan & pay: $it" }
        val stations = Places.all(this).count { it.type == Places.METRO }
        if (stations > 0) learned += "📍 Knows $stations metro station${if (stations > 1) "s" else ""}: your QR pops up when you arrive"
        val spam = SpamRules.todayCount(this)
        if (spam > 0) learned += "🛡 $spam spam call${if (spam > 1) "s" else ""} stopped today"
        val docs = DocVault.all(this).size
        if (docs > 0) learned += "📄 $docs document${if (docs > 1) "s" else ""} ready to send on request"
        learned += "⏱ About ${Store.savedSeconds(this) / 60} min saved so far"
        col.addView(CardViews.section(this, "Your assistant"))
        col.addView(TextView(this).apply {
            text = learned.joinToString("\n"); textSize = 14f; setTextColor(0xCC000000.toInt()); setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFFFFFFFF.toInt()) }
        })
        col.addView(Ui.p(this, "\nTip: hold the pill to scan & pay · swipe it up for the quick panel."))
    }

    private fun buildWallet() {
        col.addView(Ui.h1(this, "Wallet"))
        col.addView(Ui.p(this, "Tickets, codes and documents · stays on this phone"))
        val cards = CardStore.all(this)
        fun group(title: String, kinds: Set<String>) {
            val list = cards.filter { it.kind in kinds }
                .sortedWith(compareBy<Card> { if (it.whenMs > 0) it.whenMs else Long.MAX_VALUE }.thenByDescending { it.ts })
            if (list.isEmpty()) return
            col.addView(CardViews.section(this, title))
            list.forEach { col.addView(CardViews.card(this, it) { k -> open(k) }); col.addView(CardViews.gap(this)) }
        }
        group("Tickets", setOf("METRO", "MOVIE", "TICKET"))
        group("Waiting on you", setOf("ASK"))
        group("Live status", setOf("RIDE", "ORDER"))
        group("Codes · auto-delete in 15 min", setOf("OTP"))

        val docs = DocVault.all(this)
        col.addView(CardViews.section(this, "Documents"))
        col.addView(Ui.row(this, "📄", if (docs.isEmpty()) "Add your PAN, Aadhaar, resume…" else "${docs.size} ready to send",
            docs.take(4).joinToString(" · ") { it.name }.ifEmpty { "Sent in one tap when someone asks" }, 0xFF30B0C7.toInt(),
            onClick = { startActivity(Intent(this, DocsActivity::class.java)) }))

        col.addView(CardViews.section(this, "Add a ticket"))
        col.addView(Ui.button(this, "📷  From screenshots", 0xFF000000.toInt()) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        col.addView(CardViews.gap(this))
        col.addView(Ui.button(this, "📄  From a PDF (movie / train)", 0xFF000000.toInt()) { pickPdf.launch(arrayOf("application/pdf")) })
        col.addView(CardViews.gap(this))
        col.addView(Ui.button(this, "⚙︎  Setup, places & privacy", 0xFF8E8E93.toInt()) { startActivity(Intent(this, SetupActivity::class.java)) })
    }

    private fun open(k: Card) {
        if (k.kind == "ASK") { Asks.send(this, k); return }
        startActivity(Intent(this, CardActivity::class.java).putExtra("id", k.id))
    }

    private fun setupDone(): Boolean {
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        return nl && Settings.canDrawOverlays(this)
    }
}
