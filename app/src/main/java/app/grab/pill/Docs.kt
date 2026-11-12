package app.grab.pill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Doc(val id: String, val name: String, val type: String, val path: String, val mime: String, val ts: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("path", path).put("mime", mime).put("ts", ts)
    companion object {
        fun from(o: JSONObject) = Doc(o.optString("id"), o.optString("name"), o.optString("type"), o.optString("path"), o.optString("mime"), o.optLong("ts"))
    }
}

/**
 * Your documents, ready to send: PAN, Aadhaar, resume, payslips... Copies live in app-private
 * storage (not the gallery), so Grab can attach one the moment someone asks for it.
 */
object DocVault {
    /** type → (label, how people ask for it) */
    val TYPES: LinkedHashMap<String, Pair<String, Regex>> = linkedMapOf(
        "PAN" to ("PAN card" to Regex("(?i)\\bpan\\s?(card|no|number|copy)?\\b")),
        "AADHAAR" to ("Aadhaar" to Regex("(?i)\\baadh?aa?r|\\buidai\\b|\\badhar\\b")),
        "PASSPORT" to ("Passport" to Regex("(?i)\\bpassport\\b(?!\\s*(size|photo))")),
        "DL" to ("Driving licence" to Regex("(?i)driving\\s?licen[cs]e|\\bDL\\b")),
        "VOTER" to ("Voter ID" to Regex("(?i)voter\\s?id|epic card")),
        "RESUME" to ("Resume" to Regex("(?i)\\b(resume|résumé|cv|biodata|bio-data)\\b")),
        "PAYSLIP" to ("Payslip" to Regex("(?i)pay\\s?slips?|salary\\s?slips?|salary\\s?statement")),
        "BANK" to ("Bank statement" to Regex("(?i)bank\\s?statements?|account\\s?statement")),
        "CHEQUE" to ("Cancelled cheque" to Regex("(?i)cancell?ed\\s?cheque|\\bcheque\\b")),
        "ITR" to ("ITR / Form 16" to Regex("(?i)\\bitr\\b|form\\s?16|tax\\s?return")),
        "OFFER" to ("Offer letter" to Regex("(?i)offer\\s?letter|appointment\\s?letter")),
        "RELIEVING" to ("Relieving letter" to Regex("(?i)relieving\\s?letter|experience\\s?letter|service\\s?certificate")),
        "EDU" to ("Degree / marks" to Regex("(?i)marks?\\s?(card|sheet|memo)|degree\\s?certificate|transcripts?|provisional\\s?certificate")),
        "RENT" to ("Rent agreement" to Regex("(?i)rent(al)?\\s?agreement|lease\\s?(deed|agreement)")),
        "PHOTO" to ("Passport photo" to Regex("(?i)passport\\s?size|\\bphoto(graph)?\\b")),
        "INSURANCE" to ("Insurance" to Regex("(?i)insurance|policy\\s?(copy|document)")),
        "VEHICLE" to ("RC / vehicle" to Regex("(?i)\\brc\\b(\\s?book)?|registration\\s?certificate")),
    )

    fun label(type: String) = TYPES[type]?.first ?: "Document"

    /** First document type mentioned in this text (null if none). */
    fun guessType(text: String): String? = TYPES.entries.firstOrNull { it.value.second.containsMatchIn(text) }?.key

    private fun indexFile(c: Context) = File(c.filesDir, "docs/index.json")

    @Synchronized
    fun all(c: Context): List<Doc> {
        val f = indexFile(c)
        if (!f.exists()) return emptyList()
        val a = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { Doc.from(a.getJSONObject(it)) }.filter { File(it.path).exists() }.sortedByDescending { it.ts }
    }

    @Synchronized
    private fun save(c: Context, list: List<Doc>) {
        indexFile(c).parentFile?.mkdirs()
        val a = JSONArray(); list.forEach { a.put(it.json()) }
        indexFile(c).writeText(a.toString())
    }

    fun get(c: Context, id: String): Doc? = if (id.isEmpty()) null else all(c).firstOrNull { it.id == id }
    fun byType(c: Context, type: String?): Doc? = if (type.isNullOrEmpty()) null else all(c).firstOrNull { it.type == type }

    /** Copy a picked file into the vault. Type is guessed from the file name if not given. */
    fun add(c: Context, uri: Uri, type: String? = null): Doc? = try {
        var display = "document"
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) display = it.getString(0) ?: display }
        val mime = c.contentResolver.getType(uri) ?: "application/octet-stream"
        val dir = File(c.filesDir, "docs").apply { mkdirs() }
        val safe = display.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60)
        val f = File(dir, "${System.currentTimeMillis()}_$safe")
        c.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: throw IllegalStateException()
        val t = type ?: guessType(display) ?: "OTHER"
        val name = if (t == "OTHER") display.substringBeforeLast('.').take(30) else label(t)
        val d = Doc("d" + System.currentTimeMillis().toString(36), name, t, f.absolutePath, mime, System.currentTimeMillis())
        save(c, listOf(d) + all(c))
        d
    } catch (e: Exception) { null }

    fun retag(c: Context, id: String, type: String) =
        save(c, all(c).map { if (it.id == id) it.copy(type = type, name = if (type == "OTHER") it.name else label(type)) else it })

    fun delete(c: Context, id: String) {
        val list = all(c)
        list.firstOrNull { it.id == id }?.let { File(it.path).delete() }
        save(c, list.filter { it.id != id })
    }

    /** Share a document straight into the app the request came from (WhatsApp, Gmail…). */
    fun share(c: Context, d: Doc, pkg: String? = null, subject: String? = null, to: String? = null) {
        val uri = FileProvider.getUriForFile(c, "app.grab.pill.files", File(d.path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = d.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (to != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val direct = pkg?.takeIf { p -> c.packageManager.queryIntentActivities(Intent(send).setPackage(p), 0).isNotEmpty() }
        val i = if (direct != null) send.setPackage(direct) else Intent.createChooser(send, "Send ${d.name}")
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { Toast.makeText(c, "Couldn't open the share screen", Toast.LENGTH_SHORT).show() }
    }
}

/**
 * Spots "can you send me your PAN?" / "please share your resume" / "kindly revert" in WhatsApp,
 * Gmail, Outlook, Telegram, Slack and SMS, and turns it into an ASK card with the right document ready.
 */
object Asks {
    val MSG_APPS = mapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook",
        "org.telegram.messenger" to "Telegram", "com.Slack" to "Slack",
        "com.google.android.apps.messaging" to "Messages", "com.samsung.android.messaging" to "Messages",
        "com.microsoft.teams" to "Teams", "com.linkedin.android" to "LinkedIn"
    )
    val EMAIL_APPS = setOf("com.google.android.gm", "com.microsoft.office.outlook")

    private val ASKING = Regex("(?i)\\b(send|share|forward|mail|e-?mail|attach|upload|submit|need|needed|require[ds]?|want|pls|plz|please|kindly|can you|could you|can u)\\b")
    private val ANY_DOC = Regex("(?i)\\b(documents?|docs?|files?|pdf|copy|copies|scan(ned)?|proofs?|id proof|address proof|kyc)\\b")
    private val REPLY = Regex("(?i)(please (confirm|revert|reply|respond|let me know)|kindly (confirm|revert|reply)|let me know|get back to me|awaiting your (reply|response)|can you confirm|\\?\\s*$)")

    data class Ask(val sender: String, val app: String, val pkg: String, val docType: String?, val anyDoc: Boolean, val reply: Boolean, val subject: String)

    fun detect(pkg: String, title: String, text: String): Ask? {
        val app = MSG_APPS[pkg] ?: return null
        val s = "$title $text"
        if (s.isBlank() || Regex("(?i)\\b(otp|one time password|verification code)\\b").containsMatchIn(s)) return null
        val sender = sender(pkg, title)
        val subject = if (pkg in EMAIL_APPS) text.lineSequence().firstOrNull()?.take(80) ?: "" else ""
        val asking = ASKING.containsMatchIn(text)
        val type = DocVault.guessType(text)
        if (type != null && asking) return Ask(sender, app, pkg, type, false, false, subject)
        if (asking && ANY_DOC.containsMatchIn(text)) return Ask(sender, app, pkg, null, true, false, subject)
        if (pkg in EMAIL_APPS && REPLY.containsMatchIn(text)) return Ask(sender, app, pkg, null, false, true, subject)
        return null
    }

    /** WhatsApp titles look like "Riya", "Family: Riya" or "Riya @ Family". */
    fun sender(pkg: String, title: String): String {
        val t = title.substringBefore(" @ ").let { if (it.contains(": ")) it.substringAfterLast(": ") else it }
        return t.replace(Regex("\\s*\\(\\d+ messages?\\)"), "").trim().take(28).ifBlank { MSG_APPS[pkg] ?: "Someone" }
    }

    fun toCard(c: Context, a: Ask): Card {
        val doc = DocVault.byType(c, a.docType)
        val what = when {
            a.reply -> "is waiting for your reply"
            a.docType != null -> "wants your ${DocVault.label(a.docType)}"
            else -> "asked for a document"
        }
        val sub = when {
            doc != null -> "on ${a.app} · ${doc.name} is ready · tap to send"
            a.docType != null -> "on ${a.app} · not in your vault yet · tap to find it"
            a.reply -> "on ${a.app}" + if (a.subject.isNotBlank()) " · ${a.subject}" else ""
            else -> "on ${a.app} · tap to pick a file"
        }
        val extra = JSONObject().put("pkg", a.pkg).put("sender", a.sender).put("docType", a.docType ?: "")
            .put("docId", doc?.id ?: "").put("anyDoc", a.anyDoc).put("reply", a.reply).put("subject", a.subject)
        return Card(Card.newId(), "ASK", "${a.sender} $what", sub, value = doc?.name ?: "", src = a.app,
            key = "ask:${a.pkg}:${a.sender}:${a.docType ?: if (a.reply) "reply" else "doc"}", extra = extra.toString())
    }

    /** One tap: attach the document and open the app it was asked in. */
    fun send(c: Context, k: Card) {
        val x = k.x()
        if (x.optBoolean("reply")) { Live.open(c, k); CardStore.delete(c, k.id); return } // a question, not a file: go to the thread
        val doc = DocVault.get(c, x.optString("docId")) ?: DocVault.byType(c, x.optString("docType"))
        if (doc == null) { find(c, k); return }
        val subj = x.optString("subject").takeIf { it.isNotBlank() && x.optString("pkg") in EMAIL_APPS }?.let { if (it.startsWith("Re:", true)) it else "Re: $it" }
        DocVault.share(c, doc, x.optString("pkg"), subj)
        Habits.used(c, "ASK"); Store.addSaved(c, 60)
        CardStore.delete(c, k.id)
    }

    /** Not in the vault yet: pick it once, Grab remembers it for next time, and sends it now. */
    fun find(c: Context, k: Card) = c.startActivity(
        Intent(c, DocsActivity::class.java).putExtra("ask", k.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
