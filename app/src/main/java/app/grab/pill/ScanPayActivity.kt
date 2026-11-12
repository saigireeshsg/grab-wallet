package app.grab.pill

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Scan & pay on ONE screen: the camera is live at the top, the amount sits right below it in the thumb zone.
 * Point at any UPI QR → payee appears, your usual amount is pre-filled → one tap → your UPI app opens
 * with payee + amount filled, and you only confirm with PIN (or fingerprint, if your UPI app has
 * biometric UPI turned on). Grab never moves money and never sees your PIN: under NPCI rules only
 * your bank's UPI app can authorise a payment.
 * The UPI app you pick the first time is remembered, so next time there's no chooser.
 */
class ScanPayActivity : AppCompatActivity() {
    private var raw = ""; private var pa = ""; private var pn = ""; private var amt = 0; private var qrHasAmount = false
    private lateinit var preview: PreviewView
    private lateinit var who: TextView
    private lateinit var hint: TextView
    private lateinit var input: EditText
    private lateinit var quick: LinearLayout
    private lateinit var payBtn: Button
    private var camera: ProcessCameraProvider? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var found = false

    private val upiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Many UPI apps return "Status=SUCCESS" in "response". Not all do, so an empty answer counts too.
        val resp = r.data?.getStringExtra("response") ?: ""
        if (resp.contains("SUCCESS", true) || resp.isEmpty()) {
            Store.rememberPayment(this, pa, pn, amt)
            Store.addSaved(this, 18); Habits.used(this, "PAY")
            Toast.makeText(this, "₹$amt to $pn · remembered for next time", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else fallbackScanner()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() { camera?.unbindAll(); worker.shutdown(); super.onDestroy() }

    private fun buildUi() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        outer.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(22))
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f); setColor(0xFFFFFDF8.toInt()) }
        }
        who = TextView(this).apply { text = "Point at a UPI QR"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF000000.toInt()) }
        hint = TextView(this).apply { text = "Amount and pay button stay right here"; textSize = 13f; setTextColor(0x99000000.toInt()) }
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 36f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            hint = "₹ 0"; isEnabled = false
        }
        quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        payBtn = Button(this).apply {
            text = "Pay"; isAllCaps = false; textSize = 17f; isEnabled = false; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF1C8C4E.toInt()) }
            stateListAnimator = null
            setOnClickListener {
                amt = input.text.toString().toIntOrNull() ?: 0
                if (amt <= 0) { input.error = "Enter amount"; return@setOnClickListener }
                pay()
            }
        }
        val other = TextView(this).apply {
            text = "Pay with a different UPI app"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF007AFF.toInt()); setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                amt = input.text.toString().toIntOrNull() ?: 0
                if (pa.isNotEmpty() && amt > 0) pay(forceChooser = true)
            }
        }
        sheet.addView(who); sheet.addView(hint); sheet.addView(input)
        sheet.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) })
        sheet.addView(payBtn, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        sheet.addView(other)
        outer.addView(sheet, LinearLayout.LayoutParams(-1, -2))
        setContentView(outer)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try { future.get() } catch (e: Exception) { fallbackScanner(); return@addListener }
            camera = provider
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { proxy ->
                val media = proxy.image
                if (media == null || found) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val v = codes.firstOrNull { it.rawValue?.startsWith("upi://", true) == true }?.rawValue
                        if (v != null && !found) { found = true; runOnUiThread { onScanned(v) } }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
            } catch (e: Exception) { fallbackScanner() }
        }, ContextCompat.getMainExecutor(this))
    }

    /** No camera permission → Google's scanner (needs no permission), then the same amount sheet. */
    private fun fallbackScanner() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { code -> onScanned(code.rawValue ?: "") }
            .addOnCanceledListener { finish() }
            .addOnFailureListener { Toast.makeText(this, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun onScanned(v: String) {
        if (!v.startsWith("upi://", true)) { Toast.makeText(this, "Not a UPI QR", Toast.LENGTH_SHORT).show(); found = false; return }
        try { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) } catch (e: Exception) { }
        camera?.unbindAll()
        raw = v
        val uri = Uri.parse(v)
        pa = uri.getQueryParameter("pa") ?: ""
        pn = uri.getQueryParameter("pn")?.takeIf { it.isNotBlank() } ?: pa
        val qrAmount = uri.getQueryParameter("am")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        qrHasAmount = qrAmount != null
        val guess = qrAmount ?: Store.guessAmount(this, pa)
        who.text = pn
        hint.text = when {
            qrHasAmount -> "Amount is in the QR"
            guess != null -> "Your usual here: ₹$guess · ${Store.vendor(this, pa)?.optInt("count") ?: 0} payments"
            else -> "New shop · type the amount"
        }
        input.isEnabled = !qrHasAmount
        input.setText(guess?.toString() ?: "")
        input.setSelection(input.text.length)
        quick.removeAllViews()
        val base = guess ?: 0
        if (!qrHasAmount) listOf(base, base + 10, 20, 50, 100, 200).filter { it > 0 }.distinct().take(6).forEach { a ->
            quick.addView(Button(this).apply {
                text = "₹$a"; isAllCaps = false; textSize = 15f
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFF2F2F7.toInt()) }
                stateListAnimator = null
                setOnClickListener { input.setText(a.toString()); input.setSelection(input.text.length) }
            }, LinearLayout.LayoutParams(dp(72), dp(44)).apply { marginEnd = dp(8) })
        }
        payBtn.isEnabled = true
        payBtn.text = "Pay" + (preferredLabel()?.let { " with $it" } ?: "")
        if (guess == null) { input.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }
    }

    private fun preferredLabel(): String? {
        val pkg = Store.get(this, "upi_app") ?: return null
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { null }
    }

    /** Keep every parameter of the scanned QR (merchant code, signature…) and only add the amount. */
    private fun payUri(): Uri {
        val src = Uri.parse(raw)
        val b = Uri.Builder().scheme("upi").authority("pay")
        src.queryParameterNames.filter { it != "am" && it != "cu" }.forEach { k -> src.getQueryParameter(k)?.let { b.appendQueryParameter(k, it) } }
        b.appendQueryParameter("am", if (qrHasAmount) src.getQueryParameter("am") else amt.toString())
        b.appendQueryParameter("cu", "INR")
        if (src.getQueryParameter("tn") == null && src.getQueryParameter("sign") == null) b.appendQueryParameter("tn", "via Grab")
        return b.build()
    }

    private fun pay(forceChooser: Boolean = false) {
        if (qrHasAmount) amt = Uri.parse(raw).getQueryParameter("am")?.toDoubleOrNull()?.toInt() ?: amt
        val view = Intent(Intent.ACTION_VIEW, payUri())
        val pref = Store.get(this, "upi_app")
        val canDirect = !forceChooser && pref != null && packageManager.queryIntentActivities(Intent(view).setPackage(pref), 0).isNotEmpty()
        val i = if (canDirect) view.setPackage(pref) else {
            // remember whichever app you pick, so next time it opens straight away
            val pi = PendingIntent.getBroadcast(this, 3, Intent(this, UpiChosenReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            Intent.createChooser(view, "Pay ₹$amt with", pi.intentSender)
        }
        try { upiLauncher.launch(i) } catch (e: ActivityNotFoundException) { Toast.makeText(this, "No UPI app found", Toast.LENGTH_LONG).show() }
    }
}

/** Called by the system chooser with the UPI app you picked → becomes your default for Scan & pay. */
class UpiChosenReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val cn: ComponentName? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        cn?.packageName?.let { Store.put(c, "upi_app", it) }
    }
}
