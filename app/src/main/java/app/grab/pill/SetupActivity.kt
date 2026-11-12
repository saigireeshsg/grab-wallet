package app.grab.pill

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** One-time setup, places, assistant settings and privacy controls. */
class SetupActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var placesBox: LinearLayout
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh(); Places.register(this) }
    private val askRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    private fun dp(v: Int) = CardViews.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Habits.init(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(36), dp(18), dp(30)) }
        fun add(t: String, f: () -> Unit) = col.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } })
        fun head(t: String) = col.addView(TextView(this).apply { text = "\n$t"; textSize = 18f })
        col.addView(TextView(this).apply { text = "Setup"; textSize = 30f })
        col.addView(TextView(this).apply { text = "Everything Grab reads is processed on this phone. Nothing is uploaded (unless you turn on the optional AI below)."; textSize = 14f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(16), 0, dp(16)) }
        col.addView(status)

        add("1 · Notification access (OTPs, rides, orders, requests)") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        add("2 · Show the pill and pop-ups over other apps") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        add("3 · Calendar, camera, Grab notifications") {
            val p = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            askPerms.launch(p.toTypedArray())
        }
        add("4 · Location: metro QR pops up at the station") {
            if (!Places.hasPermission(this)) askPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else if (!Places.hasBackground(this)) {
                Toast.makeText(this, "Choose \"Allow all the time\" so Grab notices the station while the phone is in your pocket", Toast.LENGTH_LONG).show()
                askPerms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            } else Toast.makeText(this, "Location is on", Toast.LENGTH_SHORT).show()
        }
        add("5 · Spam calls: let Grab screen unknown numbers") {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING))
                askRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            else Toast.makeText(this, if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) "Already on" else "Not available on this phone", Toast.LENGTH_SHORT).show()
        }
        add("6 · Let pop-ups decline spam calls") { askPerms.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)) }
        add("7 · Battery: let Grab run (Xiaomi/Vivo/Oppo/Samsung)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Battery → No restrictions · Autostart ON", Toast.LENGTH_LONG).show()
        }
        add("▶ Start the pill") {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Do step 2 first", Toast.LENGTH_SHORT).show(); return@add }
            startForegroundService(Intent(this, PillService::class.java)); finish()
        }
        add("■ Stop the pill") { stopService(Intent(this, PillService::class.java)) }

        head("Places")
        col.addView(TextView(this).apply { text = "Metro stations are learned by themselves: open your metro ticket at a station once, and next time the QR pops up there. Or save a spot now:"; textSize = 13f })
        add("📍 I'm at a metro station: save this spot") { learn(Places.METRO) }
        add("🏠 Save this spot as Home") { learn(Places.HOME, "Home") }
        add("🏢 Save this spot as Work") { learn(Places.WORK, "Work") }
        placesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(placesBox)

        head("Assistant")
        col.addView(CheckBox(this).apply {
            text = "Copy OTPs to the clipboard as soon as they arrive"; isChecked = Store.get(this@SetupActivity, "otp_autocopy") == "1"
            setOnCheckedChangeListener { _, on -> Store.put(this@SetupActivity, "otp_autocopy", if (on) "1" else "0") }
        })
        col.addView(CheckBox(this).apply {
            text = "Block telemarketing (140-series) calls without asking"; isChecked = Store.get(this@SetupActivity, "spam_auto140") == "1"
            setOnCheckedChangeListener { _, on -> Store.put(this@SetupActivity, "spam_auto140", if (on) "1" else "0") }
        })
        add("🚇 Your metro app (opened when there's no saved ticket)") { pickApp("metro_app") }
        add("₹ Default UPI app for Scan & pay") { pickUpi() }
        add("✨ Optional AI: let Claude read unclear requests") { aiDialog() }

        head("Test")
        add("Fake OTP") { CardStore.put(this, Card(Card.newId(), "OTP", "HDFC Bank", "login code", value = "482731", src = "Test")) }
        add("Fake Rapido arriving") { CardStore.put(this, Card(Card.newId(), "RIDE", "Rapido ride", "2 min away · TS09 EK 2231", value = "4471", src = "Rapido", key = "ride:test")) }
        add("Fake movie (in 20 min)") {
            CardStore.put(this, Card(Card.newId(), "MOVIE", "Test movie", "Today · PVR · Audi 3", value = "H7, H8", qr = "BMS-TEST-1234567", whenMs = System.currentTimeMillis() + 20 * 60_000, src = "Test"))
            Assistant.tick(this)
        }
        add("Fake \"send me your PAN\" on WhatsApp") {
            Asks.detect("com.whatsapp", "Riya", "Hey can you send me your PAN card copy? Need it for the booking")?.let { CardStore.put(this, Asks.toCard(this, it).let { k -> k.copy(extra = k.x().put("pkg", "com.whatsapp").toString()) }) }
        }
        add("Fake spam call pop-up") {
            Assistant.pop(Moment("call:test", "CALL", "Likely spam · 14045 67890", "Telemarketing (140-series) · ringtone muted",
                acts = listOf(Act("Decline", true) {}, Act("Always block") {}, Act("It's OK") {})), force = true)
        }
        add("Fake \"arrived at metro\"") {
            val p = Places.all(this).firstOrNull { it.type == Places.METRO } ?: Place("test", "Test station", Places.METRO, 0.0, 0.0)
            if (CardStore.all(this).none { it.kind == "METRO" }) CardStore.put(this, Card(Card.newId(), "METRO", "Ameerpet → Hitec City", "Metro ticket · works offline", value = "₹35", qr = "METRO-TEST-0001", src = "Test"))
            Ctx.enter(this, p); Assistant.onPlace(this, p)
        }

        head("Privacy")
        add("Delete all cards, tickets and documents now") {
            File(filesDir, "cards.json").delete(); File(filesDir, "tickets").deleteRecursively(); File(filesDir, "docs").deleteRecursively(); CardBus.emit()
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
        }
        add("Forget learned habits, places and blocked numbers") {
            listOf("habits", "places", "spam_block", "spam_allow", "spam_log", "ctx_place").forEach { Store.put(this, it, "") }
            Places.register(this); refreshPlaces()
            Toast.makeText(this, "Forgotten (restart Grab to clear habits from memory)", Toast.LENGTH_SHORT).show()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh(); refreshPlaces() }

    private fun learn(type: String, name: String? = null) {
        if (!Places.hasPermission(this)) { Toast.makeText(this, "Do step 4 first", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Finding where you are…", Toast.LENGTH_SHORT).show()
        Places.learnHere(applicationContext, type, name) { p ->
            runOnUiThread {
                Toast.makeText(this, p?.let { "Saved ${it.name}" } ?: "Couldn't get an accurate location. Try near a window or outside.", Toast.LENGTH_SHORT).show()
                refreshPlaces()
            }
        }
    }

    private fun refreshPlaces() {
        if (!::placesBox.isInitialized) return
        placesBox.removeAllViews()
        Places.all(this).forEach { p ->
            placesBox.addView(Button(this).apply {
                text = "${if (p.type == Places.METRO) "🚇" else if (p.type == Places.HOME) "🏠" else "🏢"}  ${p.name}  ·  hold to rename / delete"; isAllCaps = false; textSize = 13f
                setOnLongClickListener {
                    AlertDialog.Builder(this@SetupActivity).setTitle(p.name).setItems(arrayOf("Rename", "Delete")) { _, i ->
                        if (i == 1) { Places.delete(this@SetupActivity, p.id); refreshPlaces() }
                        else {
                            val e = EditText(this@SetupActivity).apply { setText(p.name) }
                            AlertDialog.Builder(this@SetupActivity).setTitle("Name").setView(e)
                                .setPositiveButton("Save") { _, _ -> Places.add(this@SetupActivity, p.copy(name = e.text.toString().ifBlank { p.name })); refreshPlaces() }.show()
                        }
                    }.show(); true
                }
            })
        }
    }

    private fun pickApp(key: String) {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this).setTitle("Pick an app").setItems(apps.map { it.second }.toTypedArray()) { _, i ->
            Store.put(this, key, apps[i].first); Toast.makeText(this, "Saved ${apps[i].second}", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun pickUpi() {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }.distinctBy { it.first }
        if (apps.isEmpty()) { Toast.makeText(this, "No UPI app found", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Pay with").setItems(apps.map { it.second }.toTypedArray()) { _, i ->
            Store.put(this, "upi_app", apps[i].first)
        }.show()
    }

    private fun aiDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            textSize = 13f
            text = "When a WhatsApp or email message looks like a request but Grab's rules can't tell, Grab sends that one message (sender name, app and text, with numbers masked) to Claude to ask whether you're being asked for a document or a reply. Nothing else is sent. Uses your own Anthropic API key (billed to you)."
        })
        val on = CheckBox(this).apply { text = "Turn on"; isChecked = Store.get(this@SetupActivity, "ai_on") == "1" }
        val key = EditText(this).apply { hint = if (Brain.key(this@SetupActivity).isNullOrBlank()) "Anthropic API key (sk-ant-…)" else "Key saved · type to replace"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = EditText(this).apply { setText(Brain.model(this@SetupActivity)); hint = "Model" }
        box.addView(on); box.addView(key); box.addView(model)
        AlertDialog.Builder(this).setTitle("Optional AI").setView(box)
            .setPositiveButton("Save") { _, _ ->
                Brain.configure(this, on.isChecked, key.text.toString().takeIf { it.isNotBlank() }, model.text.toString())
                Toast.makeText(this, if (Brain.enabled(this)) "AI on" else "AI off", Toast.LENGTH_SHORT).show(); refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val ov = Settings.canDrawOverlays(this)
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val rm = getSystemService(RoleManager::class.java)
        val screen = rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        fun m(b: Boolean) = if (b) "✅" else "⬜"
        status.text = listOf(
            "${m(nl)} Notification access", "${m(ov)} Pill + pop-ups over apps",
            "${m(has(Manifest.permission.READ_CALENDAR))} Calendar   ${m(has(Manifest.permission.CAMERA))} Camera",
            "${m(Places.hasPermission(this) && Places.hasBackground(this))} Location (stations)",
            "${m(screen)} Spam screening   ${m(has(Manifest.permission.ANSWER_PHONE_CALLS))} Decline from pop-up",
            "${m(Brain.enabled(this))} Optional AI"
        ).joinToString("\n")
    }
}
