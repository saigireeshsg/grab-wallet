package app.grab.pill

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home. Two tabs, everything in thumb reach:
 *   Now    – the one thing that matters (big card with its action), then the rest, then what Grab learned
 *   Wallet – tickets, requests, live status, codes, documents
 * Bottom bar: Scan & pay · Metro · Docs, and the tab switch.
 * First launch goes to the guided setup.
 */
class HubActivity : AppCompatActivity(), HubUi.Actions {
    private lateinit var content: FrameLayout
    private lateinit var bottomHost: FrameLayout
    private lateinit var scroll: ScrollView
    private var tabId = "now"
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
        if (Store.get(this, "onboarded") != "1") {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        D.window(this)
        Habits.init(this); Ctx.init(this)
        tabId = intent.getStringExtra("tab") ?: "now"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(D.p(this@HubActivity).bg) }
        content = FrameLayout(this)
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        bottomHost = FrameLayout(this)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        CardBus.listen(listener)
        if (Perms.granted(this, Perms.OVERLAY)) startForegroundService(Intent(this, PillService::class.java))
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); i.getStringExtra("tab")?.let { tab(it) } }
    override fun onDestroy() { CardBus.unlisten(listener); super.onDestroy() }
    override fun onResume() { super.onResume(); if (::content.isInitialized) build() }

    private fun build() {
        content.removeAllViews()
        content.addView(if (tabId == "wallet") HubUi.wallet(this, walletModel(), this) else HubUi.now(this, nowModel(), this))
        bottomHost.removeAllViews()
        bottomHost.addView(HubUi.bottom(this, tabId, this))
    }

    private fun nowModel(): HubUi.Now {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (h) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        val chips = listOfNotNull(
            Ctx.place?.let { "📍 ${it.name}" },
            CalendarHelper.next(this)?.let { "📅 ${it.title.take(22)} · ${it.timeLabel}" },
            SpamRules.todayCount(this).takeIf { it > 0 }?.let { "🛡 $it spam stopped" },
        )
        val missing = Perms.ALL.filter { it.required && !Perms.granted(this, it.id) }
        val nudge = if (missing.isEmpty()) null else "Grab can't " + missing.joinToString(" or ") {
            if (it.id == Perms.NOTIF) "see OTPs and rides yet" else "show the pill yet"
        }
        val ranked = CardStore.all(this).sortedByDescending { CardStore.score(it) }.filter { CardStore.score(it) >= 40 }
        val insights = mutableListOf<Pair<String, String>>()
        Habits.usualHour("METRO")?.let { insights += "🚇" to "Metro ticket: $it" }
        Habits.usualHour("PAY")?.let { insights += "₹" to "Scan & pay: $it" }
        val stations = Places.all(this).count { it.type == Places.METRO }
        if (stations > 0) insights += "📍" to "Knows $stations metro station${if (stations > 1) "s" else ""}"
        val docs = DocVault.all(this).size
        if (docs > 0) insights += "📄" to "$docs document${if (docs > 1) "s" else ""} ready to send"
        val spam = SpamRules.recent(this).size
        if (spam > 0) insights += "🛡" to "$spam suspicious call${if (spam > 1) "s" else ""} handled"
        return HubUi.Now(greeting, SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), chips, nudge,
            ranked.firstOrNull(), ranked.drop(1).take(5), insights, Store.savedSeconds(this) / 60)
    }

    private fun walletModel(): HubUi.Wallet {
        val cards = CardStore.all(this)
        fun of(vararg k: String) = cards.filter { it.kind in k }.sortedWith(compareBy<Card> { if (it.whenMs > 0) it.whenMs else Long.MAX_VALUE }.thenByDescending { it.ts })
        return HubUi.Wallet(of("METRO", "MOVIE", "TICKET"), of("ASK"), of("RIDE", "ORDER"), of("OTP"), DocVault.all(this))
    }

    // ------------------------------------------------------------------ actions

    override fun card(k: Card) {
        if (k.kind == "ASK") { Asks.send(this, k); return }
        startActivity(Intent(this, CardActivity::class.java).putExtra("id", k.id))
    }

    override fun action(k: Card) {
        when (k.kind) {
            "OTP" -> { Assistant.copy(this, k.value); Store.addSaved(this, 6) }
            "RIDE" -> if (k.value.isNotEmpty() && k.value.all { it.isDigit() }) Assistant.copy(this, k.value) else Live.open(this, k)
            "ORDER" -> Live.open(this, k)
            "ASK" -> Asks.send(this, k)
            else -> card(k)
        }
    }

    override fun settings() { startActivity(Intent(this, SetupActivity::class.java)) }
    override fun setup() {
        val first = Perms.ALL.indexOfFirst { it.required && !Perms.granted(this, it.id) }
        startActivity(Intent(this, OnboardingActivity::class.java).putExtra("start", if (first >= 0) first + 1 else 1))
    }
    override fun pay() { startActivity(Intent(this, ScanPayActivity::class.java)) }
    override fun docs() { startActivity(Intent(this, DocsActivity::class.java)) }
    override fun tab(id: String) { tabId = id; build(); scroll.scrollTo(0, 0) }
    override fun addScreenshot() { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    override fun addPdf() { pickPdf.launch(arrayOf("application/pdf")) }

    /** Metro: today's ticket full-screen, else the metro app you picked, else add one. */
    override fun metro() {
        val t = CardStore.all(this).filter { it.kind == "METRO" }.maxByOrNull { it.ts }
        val app = Store.get(this, "metro_app")
        when {
            t != null -> card(t)
            app != null -> Assistant.launch(this, app)
            else -> { tab("wallet"); Toast.makeText(this, "Add a screenshot of your metro QR, or pick your metro app in Settings", Toast.LENGTH_LONG).show() }
        }
    }
}
