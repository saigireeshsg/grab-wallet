package app.grab.pill

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Your document vault. Opened with extra "ask" = an ASK card id when someone is waiting for a file:
 * pick it once → Grab sends it right away and remembers it for the next request.
 */
class DocsActivity : AppCompatActivity(), DocsUi.Actions {
    private lateinit var host: FrameLayout
    private var askId: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u == null) return@registerForActivityResult
        val ask = askId?.let { CardStore.get(this, it) }
        val wanted = ask?.x()?.optString("docType")?.takeIf { it.isNotEmpty() }
        if (wanted != null) addAndMaybeSend(u, wanted, ask) else chooseType { t -> addAndMaybeSend(u, t, ask) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        D.window(this)
        askId = intent.getStringExtra("ask")
        host = FrameLayout(this)
        setContentView(ScrollView(this).apply { setBackgroundColor(D.p(this@DocsActivity).bg); isVerticalScrollBarEnabled = false; addView(host) })
        build()
        if (askId != null && b == null) add() // someone is waiting: go straight to the picker
    }

    private fun build() {
        host.removeAllViews()
        host.addView(DocsUi.build(this, DocVault.all(this), askId?.let { CardStore.get(this, it) }?.title, this))
    }

    override fun back() = finish()
    override fun add() = pick.launch(arrayOf("application/pdf", "image/*", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*"))
    override fun send(d: Doc) { DocVault.share(this, d); Habits.used(this, "ASK") }
    override fun options(d: Doc) {
        MaterialAlertDialogBuilder(this).setTitle(d.name).setItems(arrayOf("Send", "Change type", "Delete")) { _, i ->
            when (i) {
                0 -> send(d)
                1 -> chooseType { t -> DocVault.retag(this, d.id, t); build() }
                else -> MaterialAlertDialogBuilder(this).setTitle("Delete ${d.name}?").setPositiveButton("Delete") { _, _ -> DocVault.delete(this, d.id); build() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun chooseType(done: (String) -> Unit) {
        val keys = DocVault.TYPES.keys.toList() + "OTHER"
        val labels = keys.map { if (it == "OTHER") "Something else" else DocVault.label(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("What is this document?").setItems(labels) { _, i -> done(keys[i]) }.show()
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

object DocsUi {
    interface Actions { fun back(); fun add(); fun send(d: Doc); fun options(d: Doc) }

    private val EMOJI = mapOf("PAN" to "🪪", "AADHAAR" to "🪪", "PASSPORT" to "🛂", "DL" to "🚗", "VOTER" to "🗳", "RESUME" to "📝", "PAYSLIP" to "💰",
        "BANK" to "🏦", "CHEQUE" to "🧾", "ITR" to "🧾", "OFFER" to "✉️", "RELIEVING" to "✉️", "EDU" to "🎓", "RENT" to "🏠", "PHOTO" to "🖼",
        "INSURANCE" to "☂️", "VEHICLE" to "🚗")

    fun build(c: Context, docs: List<Doc>, askTitle: String?, a: Actions): View {
        val p = D.p(c)
        val col = D.col(c, 20, 8)
        col.addView(D.space(c, 8))
        col.addView(TextView(c).apply {
            text = "‹"; textSize = 30f; setTextColor(p.ink); gravity = Gravity.CENTER
            D.tappable(c, this, android.graphics.Color.TRANSPARENT, 99); setOnClickListener { a.back() }
        }, LinearLayout.LayoutParams(D.dp(c, 44), D.dp(c, 44)))
        col.addView(D.display(c, "Documents"))
        col.addView(D.body(c, if (askTitle != null) "$askTitle. Pick the file once; next time it goes in one tap."
            else "Ready to send the moment someone asks on WhatsApp or email. Kept on this phone only.").apply { setPadding(0, D.dp(c, 4), 0, 0) })
        col.addView(D.space(c, 18))
        col.addView(D.primary(c, "+  Add a document") { a.add() })

        if (docs.isEmpty()) {
            col.addView(D.space(c, 18))
            val e = D.card(c, 20).apply { gravity = Gravity.CENTER_HORIZONTAL }
            e.addView(D.bubble(c, "🗂", D.kind("ASK"), 60, square = false))
            e.addView(D.space(c, 10))
            e.addView(D.h2(c, "Start with the usual suspects").apply { gravity = Gravity.CENTER })
            e.addView(D.body(c, "PAN, Aadhaar, resume and payslips are what people ask for most.").apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 4), 0, 0) })
            col.addView(e, D.wide(c))
        } else {
            col.addView(D.label(c, "Your documents · tap to send"))
            docs.forEach { d ->
                val row = D.listRow(c, EMOJI[d.type] ?: if (d.mime.startsWith("image")) "🖼" else "📄", D.kind("ASK"), d.name,
                    (if (d.type == "OTHER") "Other" else DocVault.label(d.type)) + " · " + (if (d.mime.contains("pdf")) "PDF" else if (d.mime.startsWith("image")) "Image" else "File"),
                    D.pill(c, "Send", D.kind("ASK"))) { a.send(d) }
                row.setOnLongClickListener { a.options(d); true }
                col.addView(row, D.wide(c)); col.addView(D.space(c, 10))
            }
            col.addView(D.small(c, "Hold a document to rename its type or delete it.", p.ink3).apply { setPadding(D.dp(c, 4), D.dp(c, 2), 0, 0) })
        }
        col.addView(D.label(c, "Grab spots requests for"))
        col.addView(D.small(c, DocVault.TYPES.values.joinToString(" · ") { it.first }, p.ink2).apply { setPadding(D.dp(c, 4), 0, D.dp(c, 4), D.dp(c, 30)) })
        return col
    }
}
