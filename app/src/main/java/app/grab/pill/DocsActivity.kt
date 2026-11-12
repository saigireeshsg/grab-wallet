package app.grab.pill

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Your document vault. Opened with extra "ask" = an ASK card id when someone is waiting for a file:
 * pick it once → Grab sends it right away and remembers it for the next request.
 */
class DocsActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout
    private var askId: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u == null) return@registerForActivityResult
        val ask = askId?.let { CardStore.get(this, it) }
        val wanted = ask?.x()?.optString("docType")?.takeIf { it.isNotEmpty() }
        if (wanted != null) addAndMaybeSend(u, wanted, ask) else chooseType { t -> addAndMaybeSend(u, t, ask) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        askId = intent.getStringExtra("ask")
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
        build()
        if (askId != null && b == null) launchPick() // someone is waiting: go straight to the picker
    }

    private fun dp(v: Int) = CardViews.dp(this, v)
    private fun launchPick() = pick.launch(arrayOf("application/pdf", "image/*", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*"))

    private fun build() {
        col.removeAllViews()
        col.addView(Ui.h1(this, "Documents"))
        val ask = askId?.let { CardStore.get(this, it) }
        col.addView(Ui.p(this, if (ask != null) "${ask.title}. Pick the file once; next time it goes in one tap."
            else "Kept on this phone. When someone asks for one on WhatsApp or email, it's ready to send."))

        val docs = DocVault.all(this)
        if (docs.isEmpty()) col.addView(Ui.p(this, "\nNo documents yet. Add your PAN, Aadhaar, resume, payslips: the things people keep asking for."))
        docs.forEach { d ->
            col.addView(CardViews.gap(this))
            col.addView(Ui.row(this, if (d.mime.startsWith("image")) "🖼" else "📄", d.name,
                "${DocVault.label(d.type).takeIf { d.type != "OTHER" } ?: "Other"} · tap to send · hold for options", 0xFF34C759.toInt(),
                onClick = { DocVault.share(this, d); Habits.used(this, "ASK") },
                onLong = { options(d) }))
        }
        col.addView(CardViews.gap(this)); col.addView(CardViews.gap(this))
        col.addView(Ui.button(this, "+  Add a document", 0xFF000000.toInt()) { launchPick() })
        col.addView(Ui.p(this, "\nGrab recognises requests for: " + DocVault.TYPES.values.joinToString(", ") { it.first } + "."))
    }

    private fun chooseType(done: (String) -> Unit) {
        val keys = DocVault.TYPES.keys.toList() + "OTHER"
        val labels = keys.map { if (it == "OTHER") "Something else" else DocVault.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("What is this?").setItems(labels) { _, i -> done(keys[i]) }.show()
    }

    private fun options(d: Doc) {
        AlertDialog.Builder(this).setTitle(d.name).setItems(arrayOf("Change type", "Delete")) { _, i ->
            if (i == 0) chooseType { t -> DocVault.retag(this, d.id, t); build() }
            else { DocVault.delete(this, d.id); build() }
        }.show()
    }

    private fun addAndMaybeSend(u: Uri, type: String, ask: Card?) {
        val d = DocVault.add(this, u, type) ?: run { Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Saved ${d.name}", Toast.LENGTH_SHORT).show()
        if (ask != null) {
            Asks.send(this, ask.copy(extra = ask.x().put("docId", d.id).toString()))
            finish()
        } else build()
    }
}

/** Small shared look for the screens (light, iOS-like, thumb-sized buttons). */
object Ui {
    const val BG = 0xFFF2F2F7.toInt()

    fun h1(c: android.content.Context, t: String) = TextView(c).apply { text = t; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF000000.toInt()) }
    fun p(c: android.content.Context, t: String) = TextView(c).apply { text = t; textSize = 14f; setTextColor(0x99000000.toInt()) }

    fun button(c: android.content.Context, t: String, color: Int, f: () -> Unit) = android.widget.Button(c).apply {
        text = t; isAllCaps = false; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = CardViews.dp(c, 14).toFloat(); setColor(color) }
        stateListAnimator = null; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-1, CardViews.dp(c, 52))
    }

    fun row(c: android.content.Context, icon: String, title: String, sub: String, color: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null) =
        CardViews.card(c, Card("", "X", title, sub), { onClick() }).apply {
            ((this as LinearLayout).getChildAt(0) as TextView).apply {
                text = icon
                (background as android.graphics.drawable.GradientDrawable).setColor(color)
            }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
}
