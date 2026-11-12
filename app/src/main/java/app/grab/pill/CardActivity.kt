package app.grab.pill

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Show a card to the gate / usher / rider: white background, full brightness, screen stays on, big code. */
class CardActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val k = CardStore.get(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        D.window(this, D.kind(k.kind))
        setContentView(CardUi.build(this, k,
            onCopy = { Assistant.copy(this, k.value) },
            onOpen = { Live.open(this, k) },
            onDelete = {
                MaterialAlertDialogBuilder(this).setTitle("Delete this card?").setPositiveButton("Delete") { _, _ -> CardStore.delete(this, k.id); finish() }
                    .setNegativeButton("Cancel", null).show()
            },
            onClose = { finish() }))
        Store.addSaved(this, if (k.kind == "METRO" || k.kind == "MOVIE") 30 else 15)
        Habits.used(this, k.kind)
        // Learn metro stations by themselves: opening a metro ticket here marks this spot as a station
        if (k.kind == "METRO" && Places.hasPermission(this)) Places.learnHere(applicationContext, Places.METRO)
    }
}

object CardUi {
    fun build(a: android.content.Context, k: Card, onCopy: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit): View {
        val color = D.kind(k.kind)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color) }
        // coloured header
        val head = D.col(a, 22, 12)
        head.addView(D.row(a).apply {
            addView(TextView(a).apply { text = "${CardViews.ICON[k.kind] ?: ""}  ${k.src.ifBlank { "Grab" }}"; textSize = 14f; setTextColor(0xE6FFFFFF.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(a).apply {
                text = "✕"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = D.circle(0x33FFFFFF); setOnClickListener { onClose() }; contentDescription = "Close"
            }, LinearLayout.LayoutParams(D.dp(a, 40), D.dp(a, 40)))
        })
        head.addView(TextView(a).apply { text = k.title; textSize = 26f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, D.dp(a, 8), 0, 0) })
        if (k.sub.isNotBlank()) head.addView(TextView(a).apply { text = k.sub; textSize = 15f; setTextColor(0xE6FFFFFF.toInt()); setPadding(0, D.dp(a, 4), 0, D.dp(a, 8)) })
        root.addView(head)

        // white sheet: the code itself (scanners read black on white best)
        val sheet = D.col(a, 24, 24).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply { val r = D.dpf(a, 30); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(Color.WHITE) }
        }
        val qrBmp: Bitmap? = if (k.qr.isNotEmpty()) Qr.encode(k.qr, 900) else null
        when {
            qrBmp != null -> sheet.addView(ImageView(a).apply { setImageBitmap(qrBmp); adjustViewBounds = true }, LinearLayout.LayoutParams(D.dp(a, 290), D.dp(a, 290)).apply { topMargin = D.dp(a, 8) })
            k.img.isNotEmpty() -> BitmapFactory.decodeFile(k.img)?.let { sheet.addView(ImageView(a).apply { setImageBitmap(it); adjustViewBounds = true }, D.wide(a)) }
        }
        if (k.value.isNotEmpty() && k.kind != "ASK") sheet.addView(TextView(a).apply {
            text = CardViews.valueText(k).ifEmpty { k.value }; textSize = if (k.kind == "OTP" || k.kind == "RIDE") 52f else 30f
            setTextColor(0xFF0B0B0F.toInt()); gravity = Gravity.CENTER; letterSpacing = if (k.value.all { it.isDigit() }) 0.25f else 0.04f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setPadding(0, D.dp(a, 16), 0, D.dp(a, 8))
        })
        if (qrBmp != null) sheet.addView(TextView(a).apply { text = "Brightness is at full · hold steady for the scanner"; textSize = 13f; setTextColor(0xFF6B6B76.toInt()); gravity = Gravity.CENTER; setPadding(0, D.dp(a, 10), 0, 0) })
        sheet.addView(D.space(a, 20))
        val digits = k.value.isNotEmpty() && k.value.all { it.isDigit() }
        if (k.kind == "OTP" || (k.kind == "RIDE" && digits)) sheet.addView(D.primary(a, "Copy ${k.value}", color) { onCopy() }.apply { setTextColor(Color.WHITE) })
        if (k.kind == "RIDE" || k.kind == "ORDER") sheet.addView(D.outline(a, "Open ${k.src}") { onOpen() }.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); setTextColor(0xFF0B0B0F.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFE4E4EA.toInt())
        }, D.wide(a, 52, 10))
        sheet.addView(D.ghost(a, "Delete card", 0xFFE11D48.toInt()) { onDelete() }, D.wide(a, 48, 10))
        root.addView(ScrollView(a).apply { isFillViewport = true; addView(sheet) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
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
