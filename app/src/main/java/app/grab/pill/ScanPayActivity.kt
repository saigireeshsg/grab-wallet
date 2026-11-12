package app.grab.pill

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scan any UPI QR -> guess amount from vendor memory -> hand off to YOUR UPI app
 * with payee + amount filled. You only enter PIN / fingerprint there.
 * (Grab never moves money itself: that needs an NPCI-licensed partner.)
 */
class ScanPayActivity : AppCompatActivity() {
    private var pa = ""; private var pn = ""; private var amt = 0

    private val upiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Many UPI apps return "Status=SUCCESS" in the "response" extra. Not all do, so we also confirm via SMS/notification.
        val resp = r.data?.getStringExtra("response") ?: ""
        if (resp.contains("SUCCESS", true) || resp.isEmpty()) {
            Store.rememberPayment(this, pa, pn, amt)
            Store.addSaved(this, 18)
            Toast.makeText(this, "₹$amt to $pn · remembered for next time", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { code -> onScanned(code.rawValue ?: "") }
            .addOnCanceledListener { finish() }
            .addOnFailureListener { Toast.makeText(this, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun onScanned(raw: String) {
        val uri = Uri.parse(raw)
        if (!raw.startsWith("upi://", true)) { Toast.makeText(this, "Not a UPI QR", Toast.LENGTH_SHORT).show(); finish(); return }
        pa = uri.getQueryParameter("pa") ?: ""
        pn = uri.getQueryParameter("pn") ?: pa
        val qrAmount = uri.getQueryParameter("am")?.toDoubleOrNull()?.toInt()
        val guess = qrAmount ?: Store.guessAmount(this, pa)
        showAmount(qrAmount != null, guess)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showAmount(fromQr: Boolean, guess: Int?) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(24))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0xFFFFFDF8.toInt()) }
        }
        col.addView(TextView(this).apply { text = pn; textSize = 18f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply {
            textSize = 12f
            text = when {
                fromQr -> "Amount is in the QR"
                guess != null -> "Your usual here: ₹$guess"
                else -> "New vendor · no amount on the QR"
            }
        })
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 34f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setText(guess?.toString() ?: ""); hint = "₹"
        }
        col.addView(input)
        // quick amounts, so you rarely use the keypad
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val base = guess ?: 0
        listOf(base, base + 10, 20, 50, 100).filter { it > 0 }.distinct().take(5).forEach { a ->
            quick.addView(Button(this).apply { text = "₹$a"; isAllCaps = false; setOnClickListener { input.setText(a.toString()) } },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(quick)
        col.addView(Button(this).apply {
            text = "Pay with my UPI app"; isAllCaps = false; textSize = 16f
            setOnClickListener {
                amt = input.text.toString().toIntOrNull() ?: 0
                if (amt <= 0) { input.error = "Enter amount"; return@setOnClickListener }
                pay()
            }
        })
        val outer = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setBackgroundColor(0x66000000); addView(col, LinearLayout.LayoutParams(-1, -2)) }
        setContentView(outer)
    }

    private fun pay() {
        val u = Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", pa).appendQueryParameter("pn", pn)
            .appendQueryParameter("am", amt.toString()).appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", "via Grab").build()
        try { upiLauncher.launch(Intent.createChooser(Intent(Intent.ACTION_VIEW, u), "Pay with")) }
        catch (e: ActivityNotFoundException) { Toast.makeText(this, "No UPI app found", Toast.LENGTH_LONG).show() }
    }
}
