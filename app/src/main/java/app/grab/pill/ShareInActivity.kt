package app.grab.pill

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** "Share → Save to Grab" from any app: ticket screenshots, PDFs, or text (SMS / email snippets). */
class ShareInActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent
        val type = i.type ?: ""
        if (type.startsWith("image/") || type == "application/pdf") {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri == null) { finish(); return }
            Toast.makeText(this, "Reading ticket…", Toast.LENGTH_SHORT).show()
            Importer.importUri(applicationContext, uri, type) { card ->
                runOnUiThread {
                    Toast.makeText(this, card?.let { "Saved: ${it.title}" } ?: "Couldn't read it", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val card = Parser.parse("shared", "Shared", i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "", text)
            if (card != null) CardStore.put(this, card)
            Toast.makeText(this, card?.let { "Saved: ${it.title}" } ?: "Nothing to save in that text", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
