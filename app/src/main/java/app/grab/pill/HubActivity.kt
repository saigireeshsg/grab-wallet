package app.grab.pill

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The one place: Now (most urgent) · Tickets (metro, movie) · Live status (rides, orders) · Codes (OTPs).
 * Launcher screen. Rebuilds whenever a card arrives.
 */
class HubActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout
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
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFFF2F2F7.toInt()); addView(col) })
        CardBus.listen(listener)
        // Start the pill automatically once the overlay permission exists
        if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, PillService::class.java))
    }

    override fun onDestroy() { CardBus.unlisten(listener); super.onDestroy() }
    override fun onResume() { super.onResume(); build() }
    private fun dp(v: Int) = CardViews.dp(this, v)

    private fun build() {
        col.removeAllViews()
        col.addView(TextView(this).apply { text = "Grab"; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF000000.toInt()) })
        col.addView(TextView(this).apply { text = "Tickets, codes and live status in one place · stays on this phone"; textSize = 14f; setTextColor(0x99000000.toInt()) })

        if (!setupDone()) {
            col.addView(CardViews.gap(this))
            col.addView(pill("Finish setup (2 min) →", 0xFF007AFF.toInt()) { startActivity(Intent(this, SetupActivity::class.java)) })
        }

        val cards = CardStore.all(this)
        val now = CardStore.top(this)
        if (now != null) {
            col.addView(CardViews.section(this, "Now"))
            col.addView(CardViews.card(this, now) { open(it) })
        }
        fun group(title: String, kinds: Set<String>) {
            val list = cards.filter { it.kind in kinds && it.id != now?.id }
                .sortedWith(compareBy<Card> { if (it.whenMs > 0) it.whenMs else Long.MAX_VALUE }.thenByDescending { it.ts })
            if (list.isEmpty()) return
            col.addView(CardViews.section(this, title))
            list.forEach { col.addView(CardViews.card(this, it) { k -> open(k) }); col.addView(CardViews.gap(this)) }
        }
        group("Tickets", setOf("METRO", "MOVIE", "TICKET"))
        group("Live status", setOf("RIDE", "ORDER"))
        group("Codes · auto-delete in 15 min", setOf("OTP"))

        if (cards.isEmpty()) {
            col.addView(TextView(this).apply {
                textSize = 15f; setTextColor(0x99000000.toInt()); setPadding(dp(4), dp(24), dp(4), dp(8))
                text = "Nothing yet. OTPs, ride and delivery status appear here by themselves.\n\nFor tickets: take a screenshot of the QR in your metro or movie app, then Share → Save to Grab, or add it below."
            })
        }

        col.addView(CardViews.section(this, "Add a ticket"))
        col.addView(pill("📷  From screenshots", 0xFF000000.toInt()) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        col.addView(CardViews.gap(this))
        col.addView(pill("📄  From a PDF (movie / train)", 0xFF000000.toInt()) { pickPdf.launch(arrayOf("application/pdf")) })
        col.addView(CardViews.gap(this))
        col.addView(pill("⚙︎  Setup & privacy", 0xFF8E8E93.toInt()) { startActivity(Intent(this, SetupActivity::class.java)) })
    }

    private fun pill(t: String, color: Int, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        stateListAnimator = null; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52))
    }

    private fun open(k: Card) = startActivity(Intent(this, CardActivity::class.java).putExtra("id", k.id))

    private fun setupDone(): Boolean {
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        return nl && Settings.canDrawOverlays(this)
    }
}
