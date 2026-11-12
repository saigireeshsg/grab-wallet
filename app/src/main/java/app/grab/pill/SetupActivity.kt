package app.grab.pill

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** One-time setup + privacy controls. */
class SetupActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = { v: Int -> CardViews.dp(this, v) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(36), dp(18), dp(30)) }
        fun add(t: String, f: () -> Unit) = col.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } })
        col.addView(TextView(this).apply { text = "Setup"; textSize = 30f })
        col.addView(TextView(this).apply { text = "Everything Grab reads is processed on this phone. Nothing is uploaded."; textSize = 14f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(16), 0, dp(16)) }
        col.addView(status)
        add("1 · Notification access (OTPs, rides, orders, tickets)") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        add("2 · Show the pill over other apps") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        add("3 · Calendar + Grab notifications") {
            val p = mutableListOf(Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            askPerms.launch(p.toTypedArray())
        }
        add("4 · Battery: let Grab run (Xiaomi/Vivo/Oppo)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Battery → No restrictions · Autostart ON", Toast.LENGTH_LONG).show()
        }
        add("▶ Start the pill") {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Do step 2 first", Toast.LENGTH_SHORT).show(); return@add }
            startForegroundService(Intent(this, PillService::class.java)); finish()
        }
        add("■ Stop the pill") { stopService(Intent(this, PillService::class.java)) }
        col.addView(TextView(this).apply { text = "\nTest"; textSize = 18f })
        add("Add a fake OTP") { CardStore.put(this, Card(Card.newId(), "OTP", "HDFC Bank", "login code", value = "482731", src = "Test")) }
        add("Add a fake ride") { CardStore.put(this, Card(Card.newId(), "RIDE", "Rapido ride", "3 min away · TS09 EK 2231", value = "4471", src = "Test", key = "ride:test")) }
        add("Add a fake movie (in 1 hour)") {
            CardStore.put(this, Card(Card.newId(), "MOVIE", "Kaantha", "Today · PVR Inorbit · Audi 3", value = "H7, H8", qr = "BMS-TEST-1234567", whenMs = System.currentTimeMillis() + 3_600_000, src = "Test"))
        }
        col.addView(TextView(this).apply { text = "\nPrivacy"; textSize = 18f })
        add("Delete all cards and tickets now") {
            File(filesDir, "cards.json").delete(); File(filesDir, "tickets").deleteRecursively(); CardBus.emit()
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val ov = Settings.canDrawOverlays(this)
        val cal = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        fun m(b: Boolean) = if (b) "✅" else "⬜"
        status.text = "${m(nl)} Notification access\n${m(ov)} Pill over apps\n${m(cal)} Calendar"
    }
}
