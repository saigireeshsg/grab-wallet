package app.grab.pill

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Show a card to the gate / usher / rider: full brightness, screen stays on, big code. */
class CardActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val k = CardStore.get(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = { v: Int -> CardViews.dp(this, v) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(40), dp(20), dp(30)) }
        col.addView(TextView(this).apply { text = "${CardViews.ICON[k.kind] ?: ""} ${k.title}"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = k.sub; textSize = 15f; gravity = Gravity.CENTER; setTextColor(0x99000000.toInt()); setPadding(0, dp(4), 0, dp(14)) })

        // Prefer a crisp re-drawn QR (from the decoded payload); fall back to the saved screenshot
        val qrBmp = if (k.qr.isNotEmpty()) Qr.encode(k.qr, 900) else null
        when {
            qrBmp != null -> col.addView(ImageView(this).apply { setImageBitmap(qrBmp); adjustViewBounds = true }, LinearLayout.LayoutParams(dp(280), dp(280)))
            k.img.isNotEmpty() -> col.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(k.img)); adjustViewBounds = true }, LinearLayout.LayoutParams(-1, -2))
        }
        if (k.value.isNotEmpty()) col.addView(TextView(this).apply {
            text = if (k.kind == "OTP" || k.kind == "RIDE") k.value.chunked(1).joinToString(" ") else k.value
            textSize = 40f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
        })
        if (k.kind == "OTP" || k.kind == "RIDE") col.addView(Button(this).apply {
            text = "Copy ${k.value}"; isAllCaps = false
            setOnClickListener { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", k.value)); Toast.makeText(this@CardActivity, "Copied", Toast.LENGTH_SHORT).show() }
        })
        col.addView(TextView(this).apply {
            text = "from ${k.src.ifBlank { "you" }} · tap anywhere outside to close"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(0x66000000.toInt()); setPadding(0, dp(20), 0, dp(8))
        })
        col.addView(Button(this).apply {
            text = "Delete"; isAllCaps = false; setTextColor(Color.RED); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { CardStore.delete(this@CardActivity, k.id); finish() }
        })
        Store.addSaved(this, if (k.kind == "METRO" || k.kind == "MOVIE") 30 else 15)
        Habits.used(this, k.kind)
        // Learn metro stations by themselves: opening a metro ticket here marks this spot as a station
        if (k.kind == "METRO" && Places.hasPermission(this)) Places.learnHere(applicationContext, Places.METRO)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(col); setOnClickListener { finish() } })
    }
}

/** Draws a crisp QR from the decoded payload (gates scan this better than a blurry screenshot). */
object Qr {
    fun encode(payload: String, size: Int): Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) { null }
}
