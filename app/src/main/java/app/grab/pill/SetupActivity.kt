package app.grab.pill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/** Settings: permissions (same engine as setup), places, assistant options, try-it buttons, privacy. */
class SetupActivity : AppCompatActivity(), SettingsUi.Actions {
    private val req = PermRequester(this) { build() }
    private lateinit var host: FrameLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        D.window(this)
        Habits.init(this)
        host = FrameLayout(this)
        setContentView(ScrollView(this).apply { setBackgroundColor(D.p(this@SetupActivity).bg); isVerticalScrollBarEnabled = false; addView(host) })
    }

    override fun onResume() { super.onResume(); build() }

    private fun appName(pkg: String?): String? = pkg?.let { try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { null } }

    private fun build() {
        val m = SettingsUi.Model(
            perms = Perms.ALL.map { SettingsUi.PermRow(it, Perms.granted(this, it.id), Perms.progress(this, it.id)) },
            places = Places.all(this), locationOn = Places.hasPermission(this),
            otpAutocopy = Store.get(this, "otp_autocopy") == "1", block140 = Store.get(this, "spam_auto140") == "1",
            metroApp = appName(Store.get(this, "metro_app")), upiApp = appName(Store.get(this, "upi_app")),
            aiOn = Brain.enabled(this), pillOn = Perms.granted(this, Perms.OVERLAY),
            version = Updater.currentName(this),
            updateLine = Updater.lastError(this) ?: Updater.available(this)?.let { "${it.name} is ready · tap to install" }
                ?: if (Updater.silentPossible(this)) "Up to date · new versions install silently" else "Up to date · checks a few times a day",
        )
        host.removeAllViews(); host.addView(SettingsUi.build(this, m, this))
    }

    // ------------------------------------------------------------------ actions

    override fun back() = finish()
    override fun perm(id: String) { if (Perms.granted(this, id)) Toast.makeText(this, "Already on ✓", Toast.LENGTH_SHORT).show() else req.request(id) }
    override fun rerunSetup() { startActivity(Intent(this, OnboardingActivity::class.java).putExtra("start", 1)) }
    override fun pill(on: Boolean) {
        if (on) { if (!Perms.granted(this, Perms.OVERLAY)) { req.request(Perms.OVERLAY); return }; startForegroundService(Intent(this, PillService::class.java)) }
        else stopService(Intent(this, PillService::class.java))
    }
    override fun otpAutocopy(on: Boolean) = Store.put(this, "otp_autocopy", if (on) "1" else "0")
    override fun block140(on: Boolean) = Store.put(this, "spam_auto140", if (on) "1" else "0")

    override fun learnPlace(type: String) {
        if (!Places.hasPermission(this)) { req.request(Perms.LOCATION); return }
        Toast.makeText(this, "Finding where you are…", Toast.LENGTH_SHORT).show()
        Places.learnHere(applicationContext, type, if (type == Places.METRO) null else Places.LABEL[type]) { p ->
            runOnUiThread {
                if (p == null) Toast.makeText(this, "Couldn't get an accurate location. Try near a window or outside.", Toast.LENGTH_LONG).show()
                build()
            }
        }
    }

    override fun place(p: Place) {
        MaterialAlertDialogBuilder(this).setTitle(p.name).setItems(arrayOf("Rename", "Delete")) { _, i ->
            if (i == 1) { Places.delete(this, p.id); build() }
            else {
                val e = EditText(this).apply { setText(p.name); setSelection(text.length) }
                MaterialAlertDialogBuilder(this).setTitle("Rename").setView(FrameLayout(this).apply { setPadding(D.dp(this@SetupActivity, 22), D.dp(this@SetupActivity, 8), D.dp(this@SetupActivity, 22), 0); addView(e) })
                    .setPositiveButton("Save") { _, _ -> Places.add(this, p.copy(name = e.text.toString().ifBlank { p.name })); build() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    override fun metroApp() {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        // metro-looking apps first
        val sorted = apps.sortedByDescending { Regex("(?i)metro|hmrl|dmrc|bmrc|namma|mmrc|cmrl|transit|ticket").containsMatchIn(it.second + it.first) }
        MaterialAlertDialogBuilder(this).setTitle("Your metro app").setItems(sorted.map { it.second }.toTypedArray()) { _, i ->
            Store.put(this, "metro_app", sorted[i].first); build()
        }.show()
    }

    override fun upiApp() {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }.distinctBy { it.first }
        if (apps.isEmpty()) { Toast.makeText(this, "No UPI app found", Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(this).setTitle("Pay with").setItems(apps.map { it.second }.toTypedArray()) { _, i ->
            Store.put(this, "upi_app", apps[i].first); build()
        }.show()
    }

    override fun ai() {
        val c: Context = this
        val box = D.col(c, 22, 4)
        box.addView(D.body(c, "When a WhatsApp or email message looks like a request but Grab's rules can't tell, Grab sends that one message (sender, app and text, with numbers masked) to Claude. Nothing else is sent. Uses your own Anthropic API key, billed to you."))
        val sw = MaterialSwitch(c).apply { text = "Turn on"; isChecked = Store.get(c, "ai_on") == "1"; textSize = 16f; setPadding(0, D.dp(c, 12), 0, D.dp(c, 4)) }
        val key = EditText(c).apply { hint = if (Brain.key(c).isNullOrBlank()) "Anthropic API key (sk-ant-…)" else "Key saved · type to replace"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = EditText(c).apply { setText(Brain.model(c)); hint = "Model" }
        box.addView(sw); box.addView(key); box.addView(model)
        MaterialAlertDialogBuilder(this).setTitle("Optional AI").setView(box)
            .setPositiveButton("Save") { _, _ -> Brain.configure(this, sw.isChecked, key.text.toString().takeIf { it.isNotBlank() }, model.text.toString()); build() }
            .setNegativeButton("Cancel", null).show()
    }

    override fun demo(what: String) {
        when (what) {
            "otp" -> CardStore.put(this, Card(Card.newId(), "OTP", "HDFC Bank", "Login code", value = "482731", src = "Demo"))
            "ride" -> CardStore.put(this, Card(Card.newId(), "RIDE", "Rapido ride", "2 min away · TS09 EK 2231", value = "4471", src = "Rapido", key = "ride:demo"))
            "movie" -> { CardStore.put(this, Card(Card.newId(), "MOVIE", "Demo movie", "Today · PVR · Audi 3", value = "H7, H8", qr = "BMS-TEST-1234567", whenMs = System.currentTimeMillis() + 20 * 60_000, src = "Demo")); Assistant.tick(this) }
            "pan" -> Asks.detect("com.whatsapp", "Riya", "Hey can you send me your PAN card copy? Need it for the booking")?.let { a ->
                val k = Asks.toCard(this, a); CardStore.put(this, k.copy(extra = k.x().put("pkg", "com.whatsapp").toString()))
            }
            "spam" -> Assistant.pop(Moment("call:demo", "CALL", "Likely spam · 14045 67890", "Telemarketing (140-series) · ringtone muted",
                acts = listOf(Act("Decline", true) {}, Act("Block") {}, Act("It's OK") {})), force = true)
            "metro" -> {
                val p = Places.all(this).firstOrNull { it.type == Places.METRO } ?: Place("demo", "Ameerpet", Places.METRO, 0.0, 0.0)
                if (CardStore.all(this).none { it.kind == "METRO" }) CardStore.put(this, Card(Card.newId(), "METRO", "Ameerpet → Hitec City", "Metro ticket · works offline", value = "₹35", qr = "METRO-TEST-0001", src = "Demo"))
                Ctx.enter(this, p); Assistant.onPlace(this, p)
            }
        }
        if (!Perms.granted(this, Perms.OVERLAY)) Toast.makeText(this, "Pop-ups need \"Show the pill and pop-ups\" turned on", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Look at the bottom of the screen ↓", Toast.LENGTH_SHORT).show()
    }

    override fun checkUpdate() {
        if (Updater.available(this) != null) { startActivity(Intent(this, HubActivity::class.java).putExtra("update", true)); return }
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Updater.check(this, force = true) { u ->
            if (u != null) startActivity(Intent(this, HubActivity::class.java).putExtra("update", true))
            else { Toast.makeText(this, "You're on the latest version", Toast.LENGTH_SHORT).show(); build() }
        }
    }

    override fun deleteData() {
        MaterialAlertDialogBuilder(this).setTitle("Delete all cards, tickets and documents?").setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                File(filesDir, "cards.json").delete(); File(filesDir, "tickets").deleteRecursively(); File(filesDir, "docs").deleteRecursively(); CardBus.emit()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show(); build()
            }.setNegativeButton("Cancel", null).show()
    }

    override fun forget() {
        MaterialAlertDialogBuilder(this).setTitle("Forget what Grab learned?").setMessage("Habits, places and blocked numbers will be cleared.")
            .setPositiveButton("Forget") { _, _ ->
                listOf("habits", "places", "spam_block", "spam_allow", "spam_log", "ctx_place").forEach { Store.put(this, it, "") }
                Places.register(this); build()
            }.setNegativeButton("Cancel", null).show()
    }
}

object SettingsUi {
    data class PermRow(val perm: Perm, val on: Boolean, val progress: String?)
    data class Model(
        val perms: List<PermRow>, val places: List<Place>, val locationOn: Boolean,
        val otpAutocopy: Boolean, val block140: Boolean, val metroApp: String?, val upiApp: String?,
        val aiOn: Boolean, val pillOn: Boolean, val version: String,
        val updateLine: String = "",
    )
    interface Actions {
        fun back(); fun perm(id: String); fun rerunSetup(); fun pill(on: Boolean)
        fun otpAutocopy(on: Boolean); fun block140(on: Boolean)
        fun learnPlace(type: String); fun place(p: Place)
        fun metroApp(); fun upiApp(); fun ai(); fun demo(what: String)
        fun deleteData(); fun forget()
        fun checkUpdate() {}
    }

    fun build(c: Context, m: Model, a: Actions): View {
        val p = D.p(c)
        val col = D.col(c, 20, 8)
        col.addView(D.space(c, 8))
        col.addView(D.row(c).apply {
            addView(TextView(c).apply {
                text = "‹"; textSize = 30f; setTextColor(p.ink); gravity = Gravity.CENTER
                D.tappable(c, this, android.graphics.Color.TRANSPARENT, 99); setOnClickListener { a.back() }
            }, LinearLayout.LayoutParams(D.dp(c, 44), D.dp(c, 44)))
        })
        col.addView(D.display(c, "Settings"))

        // --- permissions summary + rows
        val on = m.perms.count { it.on }
        col.addView(D.space(c, 14))
        val sum = D.card(c, 18)
        val sr = D.row(c)
        sr.addView(D.col(c).apply {
            addView(D.h2(c, if (on == m.perms.size) "Everything is on" else "$on of ${m.perms.size} features on"))
            addView(D.small(c, if (on == m.perms.size) "Grab can help with everything." else "Turn on the rest for more help."))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sr.addView(D.bubble(c, if (on == m.perms.size) "✓" else "$on/${m.perms.size}", if (on == m.perms.size) p.good else p.brand, 52, square = false))
        sum.addView(sr)
        sum.addView(D.space(c, 12))
        sum.addView(D.steps(c, on, m.perms.size))
        if (on < m.perms.size) sum.addView(D.secondary(c, "Guided setup") { a.rerunSetup() }, D.wide(c, 48, 14))
        col.addView(sum, D.wide(c))

        col.addView(D.label(c, "Features"))
        m.perms.forEach { r ->
            val status = when { r.on -> D.pill(c, "On", p.good); r.progress != null -> D.pill(c, "Almost", p.warn); else -> D.pill(c, "Turn on", p.brand, solid = true) }
            col.addView(D.listRow(c, r.perm.emoji, r.perm.color, r.perm.title, r.progress ?: if (r.on) r.perm.why.substringBefore(',').substringBefore('.') else r.perm.why, status) { a.perm(r.perm.id) }, D.wide(c))
            col.addView(D.space(c, 10))
        }
        col.addView(D.switchRow(c, "🫧", D.kind("METRO"), "Floating pill", if (m.pillOn) "On every screen, in your thumb zone" else "Off", m.pillOn) { a.pill(it) }, D.wide(c))

        // --- places
        col.addView(D.label(c, "Places"))
        col.addView(D.small(c, "Metro stations are learned automatically: open your ticket at a station once and it pops up there next time.", p.ink2).apply { setPadding(D.dp(c, 4), 0, D.dp(c, 4), D.dp(c, 10)) })
        m.places.forEach { pl ->
            val e = when (pl.type) { Places.METRO -> "🚇"; Places.HOME -> "🏠"; else -> "🏢" }
            col.addView(D.listRow(c, e, if (pl.type == Places.METRO) D.kind("METRO") else D.kind("PAY"), pl.name, Places.LABEL[pl.type], D.chevron(c)) { a.place(pl) }, D.wide(c))
            col.addView(D.space(c, 10))
        }
        val chips = D.row(c)
        chips.addView(D.chip(c, "🚇  I'm at a station") { a.learnPlace(Places.METRO) }); chips.addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 8), 1))
        chips.addView(D.chip(c, "🏠  Save Home") { a.learnPlace(Places.HOME) }); chips.addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 8), 1))
        chips.addView(D.chip(c, "🏢  Save Work") { a.learnPlace(Places.WORK) })
        col.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // --- assistant
        col.addView(D.label(c, "Assistant"))
        col.addView(D.switchRow(c, "🔢", D.kind("OTP"), "Copy OTPs automatically", "Paste straight into the app asking for it", m.otpAutocopy) { a.otpAutocopy(it) }, D.wide(c))
        col.addView(D.space(c, 10))
        col.addView(D.switchRow(c, "📵", D.kind("CALL"), "Block telemarketing calls", "140-series numbers are rejected without ringing", m.block140) { a.block140(it) }, D.wide(c))
        col.addView(D.space(c, 10))
        col.addView(D.listRow(c, "🚇", D.kind("METRO"), "Metro app", m.metroApp ?: "Opened at the station when no ticket is saved", if (m.metroApp != null) D.chevron(c) else D.pill(c, "Choose", p.brand)) { a.metroApp() }, D.wide(c))
        col.addView(D.space(c, 10))
        col.addView(D.listRow(c, "₹", D.kind("PAY"), "UPI app for Scan & pay", m.upiApp ?: "Remembered after your first payment", if (m.upiApp != null) D.chevron(c) else D.pill(c, "Choose", p.brand)) { a.upiApp() }, D.wide(c))
        col.addView(D.space(c, 10))
        col.addView(D.listRow(c, "✨", D.kind("MOVIE"), "Optional AI", if (m.aiOn) "On · Claude reads unclear requests" else "Off · everything stays on the phone", if (m.aiOn) D.pill(c, "On", p.good) else D.chevron(c)) { a.ai() }, D.wide(c))

        col.addView(D.space(c, 10))
        col.addView(D.listRow(c, "⬆️", D.kind("UPDATE"), "App updates · ${m.version}", m.updateLine, D.pill(c, "Check", p.brand)) { a.checkUpdate() }, D.wide(c))

        // --- try it
        col.addView(D.label(c, "Try it"))
        val t1 = D.row(c); val t2 = D.row(c).apply { setPadding(0, D.dp(c, 8), 0, 0) }
        fun demo(row: LinearLayout, t: String, id: String) { row.addView(D.chip(c, t) { a.demo(id) }); row.addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 8), 1)) }
        demo(t1, "🛵 Rider", "ride"); demo(t1, "🔢 OTP", "otp"); demo(t1, "🚇 At metro", "metro")
        demo(t2, "📄 PAN request", "pan"); demo(t2, "🛡 Spam call", "spam"); demo(t2, "🎬 Movie", "movie")
        col.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(t1) })
        col.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(t2) })

        // --- privacy
        col.addView(D.label(c, "Privacy"))
        val priv = D.card(c, 16)
        priv.addView(D.bullet(c, "📱", "Notifications, places, habits and documents are processed and stored on this phone only."))
        priv.addView(D.bullet(c, "⏱", "OTPs delete themselves after 15 minutes; tickets after the trip or show."))
        priv.addView(D.bullet(c, "✨", "The optional AI sends only the single message it's asked about, with numbers masked."))
        col.addView(priv, D.wide(c))
        col.addView(D.space(c, 10))
        col.addView(D.outline(c, "Forget learned habits & places") { a.forget() }, D.wide(c, 50))
        col.addView(D.space(c, 8))
        col.addView(D.ghost(c, "Delete all cards, tickets and documents", p.bad) { a.deleteData() }, D.wide(c, 48))
        col.addView(D.small(c, "Grab ${m.version}", p.ink3).apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 12), 0, D.dp(c, 28)) }, D.wide(c))
        return col
    }
}
