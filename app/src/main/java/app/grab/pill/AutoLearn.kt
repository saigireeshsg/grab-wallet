package app.grab.pill

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * What each app usually sends you, learned from its notifications.
 * Once an app has produced ride (or delivery, or ticket) cards twice, its weaker notifications are read
 * the same way: a new ride app is understood after a couple of trips, with no list to maintain.
 * It also fills in settings you'd otherwise pick by hand, like your metro app.
 */
object AppMemory {
    private var cache: JSONObject? = null

    private fun obj(c: Context): JSONObject = cache ?: (try { JSONObject(Store.get(c, "app_memory") ?: "{}") } catch (e: Exception) { JSONObject() }).also { cache = it }

    @Synchronized
    fun saw(c: Context, pkg: String, kind: String) {
        if (pkg.isBlank() || pkg == "shared" || kind == "OTP" || kind == "ASK") return
        val o = obj(c)
        val m = o.optJSONObject(pkg) ?: JSONObject().also { o.put(pkg, it) }
        m.put(kind, m.optInt(kind) + 1)
        Store.put(c, "app_memory", o.toString())
        // learned settings
        if (kind == "METRO" && Store.get(c, "metro_app") == null && c.packageManager.getLaunchIntentForPackage(pkg) != null) Store.put(c, "metro_app", pkg)
    }

    /** The kind this app usually sends (after ≥ 2 sightings), or null. */
    fun hint(c: Context, pkg: String): String? {
        val m = obj(c).optJSONObject(pkg) ?: return null
        val best = m.keys().asSequence().maxByOrNull { m.optInt(it) } ?: return null
        return best.takeIf { m.optInt(it) >= 2 }
    }

    fun learnedApps(c: Context): Int = obj(c).length()
}

/**
 * QR codes inside notifications. Many apps attach the ticket itself as the notification's big picture
 * (metro, events, some airlines). Grab reads it on the phone and stores a crisp copy on the card.
 */
object NotifPicture {
    fun scan(c: Context, bmp: Bitmap, done: (qr: String?, path: String?) -> Unit) {
        BarcodeScanning.getClient().process(InputImage.fromBitmap(bmp, 0)).addOnCompleteListener { t ->
            val qr = if (t.isSuccessful) t.result?.firstOrNull { (it.rawValue?.length ?: 0) >= 6 }?.rawValue else null
            val path = if (qr != null) try {
                val dir = File(c.filesDir, "tickets").apply { mkdirs() }
                File(dir, "n_${System.currentTimeMillis()}.jpg").also { f -> FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }.absolutePath
            } catch (e: Exception) { null } else null
            done(qr, path)
        }
    }
}

/**
 * New screenshots that contain a ticket QR are saved by themselves (opt-in: "Save ticket screenshots").
 * Only screenshots taken while Grab is running are looked at; other photos are ignored.
 */
class ScreenshotWatcher(private val c: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var lastId = Store.get(c, "shot_last_id")?.toLongOrNull() ?: -1L
    private var registered = false

    fun start() {
        if (registered || !Perms.granted(c, Perms.PHOTOS)) return
        c.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        registered = true
        if (lastId < 0) newest()?.let { lastId = it.first; Store.put(c, "shot_last_id", lastId.toString()) } // don't import old ones
    }

    fun stop() { if (registered) c.contentResolver.unregisterContentObserver(this); registered = false }

    /** (id, path-ish) of the most recent image. */
    private fun newest(): Pair<Long, String>? {
        val cols = if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME)
                   else arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DISPLAY_NAME)
        return try {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
                if (cur.moveToFirst()) cur.getLong(0) to ((cur.getString(1) ?: "") + "/" + (cur.getString(2) ?: "")) else null
            }
        } catch (e: Exception) { null }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val (id, path) = newest() ?: return
        if (id <= lastId) return
        lastId = id; Store.put(c, "shot_last_id", id.toString())
        if (!path.contains("screenshot", ignoreCase = true)) return
        val u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        // screenshots are written a moment after they're announced
        Handler(Looper.getMainLooper()).postDelayed({
            Importer.importUri(c, u, "image/png", auto = true) { card ->
                if (card != null) Assistant.pop(Moment("shot:${card.id}", card.kind, "Saved: ${card.title}", "From your screenshot · it'll pop up when you need it",
                    qr = card.qr, cardId = card.id, ttlMs = 12_000))
            }
        }, 1200)
    }
}
