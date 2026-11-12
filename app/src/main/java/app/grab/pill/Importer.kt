package app.grab.pill

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a ticket screenshot or PDF into a Card, entirely on the phone:
 * 1. copy the image into app-private storage
 * 2. ML Kit reads the QR code + the text (movie name, show time, seat, stations)
 * 3. Parser classifies it as METRO / MOVIE / TICKET
 */
object Importer {
    /** `auto` = a screenshot Grab noticed by itself: only kept if it really is a ticket (has a QR + ticket-like text). */
    fun importUri(c: Context, uri: Uri, mime: String?, auto: Boolean = false, done: (Card?) -> Unit) {
        val bmp: Bitmap? = try {
            if (mime == "application/pdf") renderPdf(c, uri)
            else c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
        if (bmp == null) { done(null); return }
        val path = saveBitmap(c, bmp)
        val img = InputImage.fromBitmap(bmp, 0)
        BarcodeScanning.getClient().process(img)
            .addOnCompleteListener { bt ->
                val qr = if (bt.isSuccessful) bt.result?.firstOrNull()?.rawValue ?: "" else ""
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                    .addOnCompleteListener { tt ->
                        val text = if (tt.isSuccessful) tt.result?.text ?: "" else ""
                        val all = "$text\n$qr"
                        val looksLikeTicket = qr.isNotEmpty() && (Parser.isMetro(all) || Parser.isMovie(all) ||
                            Regex("(?i)ticket|booking|pnr|seat|journey|valid|passenger|entry|admit|boarding").containsMatchIn(all))
                        if (auto && !looksLikeTicket) { java.io.File(path).delete(); done(null); return@addOnCompleteListener }
                        val card = when {
                            Parser.isMetro(all) -> Parser.metroFrom(text, "Metro", qr, path)
                            Parser.isMovie(all) -> Parser.movieFrom(text, "Movie", qr, path)
                            Parser.travelFrom(all, "Ticket") != null -> Parser.travelFrom(all, "Ticket")!!.copy(qr = qr, img = path)
                            else -> Card(Card.newId(), "TICKET", text.lineSequence().firstOrNull { it.length in 3..40 } ?: "Ticket",
                                Parser.timeLabel(Parser.parseWhen(text)).ifEmpty { "Saved ticket" }, qr = qr, img = path,
                                whenMs = Parser.parseWhen(text), src = "Imported")
                        }
                        CardStore.put(c, card)
                        done(card)
                    }
            }
    }

    private fun renderPdf(c: Context, uri: Uri): Bitmap? {
        val pfd: ParcelFileDescriptor = c.contentResolver.openFileDescriptor(uri, "r") ?: return null
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) return null
            r.openPage(0).use { p ->
                val w = 1200; val h = (w.toFloat() / p.width * p.height).toInt()
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return b
            }
        }
    }

    private fun saveBitmap(c: Context, b: Bitmap): String {
        val dir = File(c.filesDir, "tickets").apply { mkdirs() }
        val f = File(dir, "t_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f.absolutePath
    }
}
