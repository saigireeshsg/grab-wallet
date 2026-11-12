package app.grab.pill

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps Grab up to date without the Play Store.
 * Every push to main publishes a GitHub release ("build-<versionCode>" with grab.apk). Grab checks a few
 * times a day; when a newer build exists it downloads it and installs it with Android's PackageInstaller.
 *  - First update: Android shows one "Update" confirmation (Grab wasn't the app that installed itself).
 *  - After that (Android 12+): Grab is the installer of record, so updates install silently in the background.
 */
object Updater {
    data class Update(val code: Int, val name: String, val url: String, val notes: String, val size: Long)

    private const val CHECK_EVERY_MS = 4 * 3_600_000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile var progress: Int = -1   // 0..100 while downloading, -1 otherwise
        private set

    fun current(c: Context): Int = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    fun currentName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** A newer build we already know about (from the last check), or null. */
    fun available(c: Context): Update? {
        val o = Store.get(c, "update_json")?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: return null
        val u = Update(o.optInt("code"), o.optString("name"), o.optString("url"), o.optString("notes"), o.optLong("size"))
        return u.takeIf { it.code > current(c) && it.url.isNotEmpty() }
    }

    fun lastError(c: Context): String? = Store.get(c, "update_error")?.takeIf { it.isNotBlank() }

    /** Ask GitHub for the latest release (throttled unless `force`). `done` runs on the main thread. */
    fun check(c: Context, force: Boolean = false, done: (Update?) -> Unit = {}) {
        val app = c.applicationContext
        val last = Store.get(app, "update_checked")?.toLongOrNull() ?: 0
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) { done(available(app)); return }
        Thread {
            val u = try {
                val con = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
                con.setRequestProperty("Accept", "application/vnd.github+json")
                con.setRequestProperty("User-Agent", "Grab-Android")
                con.connectTimeout = 10_000; con.readTimeout = 10_000
                if (con.responseCode != 200) null else {
                    val j = JSONObject(con.inputStream.bufferedReader().readText())
                    val code = j.optString("tag_name").substringAfter("build-").toIntOrNull() ?: 0
                    val assets = j.optJSONArray("assets")
                    val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                    if (apk == null) null else Update(code, j.optString("name"), apk.optString("browser_download_url"), j.optString("body").take(300), apk.optLong("size"))
                }
            } catch (e: Exception) { null }
            Store.put(app, "update_checked", System.currentTimeMillis().toString())
            if (u != null) Store.put(app, "update_json", JSONObject().put("code", u.code).put("name", u.name).put("url", u.url).put("notes", u.notes).put("size", u.size).toString())
            main.post { done(available(app)) }
        }.start()
    }

    /** Can Grab install APKs? (Android's "Install unknown apps" switch for Grab.) */
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()
    fun allowInstallsIntent(c: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    /** Will Android let us update without asking? (Android 12+, and Grab installed the current version itself.) */
    fun silentPossible(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !canInstall(c)) return false
        return try { c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName == c.packageName } catch (e: Exception) { false }
    }

    /** Download and hand to PackageInstaller. `onProgress` gets 0..100; runs callbacks on the main thread. */
    fun install(c: Context, u: Update, onProgress: (Int) -> Unit = {}, onError: (String) -> Unit = {}) {
        val app = c.applicationContext
        if (progress >= 0) return
        progress = 0
        Store.put(app, "update_error", "")
        Thread {
            try {
                val f = File(app.cacheDir, "update.apk")
                val con = URL(u.url).openConnection() as HttpURLConnection
                con.instanceFollowRedirects = true; con.setRequestProperty("User-Agent", "Grab-Android")
                val total = con.contentLengthLong.takeIf { it > 0 } ?: u.size
                con.inputStream.use { inp -> f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var n: Int; var got = 0L; var lastPct = -1
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n); got += n
                        val pct = if (total > 0) (got * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; progress = pct; main.post { onProgress(pct) } }
                    }
                } }
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("grab.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val status = PendingIntent.getBroadcast(app, 11, Intent(app, UpdateReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(status.intentSender)
                }
            } catch (e: Exception) {
                val msg = "Couldn't download the update. Check your connection and try again."
                Store.put(app, "update_error", msg); main.post { onError(msg) }
            } finally { progress = -1 }
        }.start()
    }

    /** Background check + install: silent when Android allows it, otherwise a pop-up offering the update. */
    fun auto(c: Context) {
        check(c) { u ->
            if (u == null) return@check
            if (silentPossible(c)) install(c, u)
            else Assistant.pop(Moment("update:${u.code}", "UPDATE", "Grab update ready", "${u.name} · tap Update to install",
                acts = listOf(Act("Update", true) { ctx -> Assistant.open(ctx, Intent(ctx, HubActivity::class.java).putExtra("update", true)) }, Act("Later") {})),
                quietMin = 12 * 60)
        }
    }
}

/** PackageInstaller reports back here: ask the user to confirm (first time), or show what went wrong. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { try { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
            }
            PackageInstaller.STATUS_SUCCESS -> Store.put(c, "update_error", "")
            PackageInstaller.STATUS_FAILURE_ABORTED -> Store.put(c, "update_error", "")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val msg = "This update is signed differently from the Grab on your phone. Uninstall Grab once and install the latest APK from GitHub; after that, updates are automatic."
                Store.put(c, "update_error", msg); Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            }
            else -> {
                val msg = "Update failed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}"
                Store.put(c, "update_error", msg); Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
