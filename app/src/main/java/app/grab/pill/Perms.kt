package app.grab.pill

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat

/** One thing Grab needs, explained the way a person would explain it. */
data class Perm(
    val id: String,
    val emoji: String,
    val color: Int,
    val title: String,          // what it unlocks, not the Android name
    val why: String,            // one sentence: the benefit
    val bullets: List<Pair<String, String>>,
    val promise: String,        // what Grab never does with it
    val button: String,
    val required: Boolean,
)

/**
 * Every permission Grab uses, how to check it, and the shortest path to granting it.
 * The setup flow and the Settings screen both use this, so status is always consistent.
 */
object Perms {
    const val NOTIF = "notif"; const val OVERLAY = "overlay"; const val ESSENTIALS = "essentials"
    const val LOCATION = "location"; const val CALLS = "calls"; const val BATTERY = "battery"
    const val UPDATES = "updates"; const val PHOTOS = "photos"

    val ALL = listOf(
        Perm(NOTIF, "🔔", 0xFF6366F1.toInt(), "OTPs, rides & requests",
            "Grab reads notifications as they arrive, so the code or the rider shows up at the bottom of your screen.",
            listOf("🔢" to "OTPs from banks, logins and deliveries", "🛵" to "Rapido / Uber / Ola status and ride OTP", "📄" to "\"Send me your PAN\" on WhatsApp or email"),
            "Read on the phone, never uploaded. Message text is not stored.", "Turn on notification access", true),
        Perm(OVERLAY, "🫧", 0xFF0EA5E9.toInt(), "Show the pill and pop-ups",
            "The pill and pop-ups float at the bottom, in your thumb zone, over any app.",
            listOf("👍" to "One tap instead of opening apps", "↕️" to "Swipe down to dismiss anything", "🤚" to "Hold the pill to scan & pay"),
            "Grab only draws its own pill. It can't see or tap what's on your screen.", "Allow display over apps", true),
        Perm(ESSENTIALS, "✨", 0xFFA855F7.toInt(), "Camera, calendar, alerts",
            "Three quick taps: scan QR codes, join meetings in one tap, and let Grab nudge you.",
            listOf("📷" to "Camera: scan & pay on one screen", "📅" to "Calendar: one-tap Join before meetings", "🔔" to "Notifications: keeps Grab running reliably"),
            "Calendar is read on the phone only. The camera is used only while Scan & pay is open.", "Allow all three", false),
        Perm(LOCATION, "📍", 0xFF22C55E.toInt(), "Metro QR at the station",
            "Walk into a station and your ticket pops up at full brightness. No unlocking, no app hopping.",
            listOf("🚇" to "Learns your stations by itself", "🔋" to "Low-power geofences, not constant GPS", "🏠" to "Never learns home or work as a station"),
            "Your location never leaves the phone.", "Allow location", false),
        Perm(CALLS, "🛡", 0xFFE11D48.toInt(), "Stop spam calls",
            "Unknown numbers that look like spam are silenced, with a Decline button at the bottom.",
            listOf("📵" to "Telemarketing (140-series) and spoofed numbers", "✋" to "One tap: Decline · Block · It's OK", "👥" to "Your contacts always ring normally"),
            "Grab sees only the incoming number. It can't listen to or record calls.", "Set Grab as spam filter", false),
        Perm(BATTERY, "🔋", 0xFFF59E0B.toInt(), "Keep Grab awake",
            "Some phones put apps to sleep. One tap keeps the pill and station alerts working all day.",
            listOf("⚡" to "Uses almost no battery when idle", "⏰" to "Pop-ups arrive on time, not late"),
            "Grab doesn't run anything heavy in the background.", "Allow background use", false),
        Perm(PHOTOS, "🖼", 0xFF8B5CF6.toInt(), "Save ticket screenshots by itself",
            "Screenshot a ticket QR in any app and Grab saves it on its own: no sharing, no adding.",
            listOf("📸" to "Only new screenshots are looked at", "🎟" to "Saved only if it has a QR and looks like a ticket", "🚇" to "Metro, movie, train, bus, event tickets"),
            "Photos are read on the phone. Nothing is uploaded, and other photos are ignored.", "Allow screenshots", false),
        Perm(UPDATES, "⬆️", 0xFF4F46E5.toInt(), "Updates install themselves",
            "New versions of Grab arrive automatically from GitHub. No re-downloading APKs.",
            listOf("🔁" to "Checks a few times a day", "🤫" to "On Android 12+, later updates install silently", "✅" to "Only updates signed with Grab's own key are accepted"),
            "Grab only ever installs new versions of itself.", "Allow updates", false),
    )

    fun get(id: String) = ALL.first { it.id == id }

    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun essentials(): Array<String> = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun granted(c: Context, id: String): Boolean = when (id) {
        NOTIF -> NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
        OVERLAY -> Settings.canDrawOverlays(c)
        ESSENTIALS -> essentials().all { has(c, it) }
        LOCATION -> has(c, Manifest.permission.ACCESS_FINE_LOCATION) && has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        CALLS -> roleHeld(c) && has(c, Manifest.permission.ANSWER_PHONE_CALLS)
        BATTERY -> c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        PHOTOS -> has(c, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        UPDATES -> Updater.canInstall(c)
        else -> false
    }

    /** Half-way states get their own wording, so the next tap is obvious. */
    fun progress(c: Context, id: String): String? = when {
        granted(c, id) -> null
        id == LOCATION && has(c, Manifest.permission.ACCESS_FINE_LOCATION) -> "Almost there: choose \"Allow all the time\""
        id == CALLS && roleHeld(c) -> "Almost there: allow Grab to decline calls"
        id == ESSENTIALS && essentials().any { has(c, it) } -> "${essentials().count { has(c, it) }} of ${essentials().size} allowed"
        else -> null
    }

    fun roleHeld(c: Context): Boolean {
        val rm = c.getSystemService(RoleManager::class.java)
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun requiredDone(c: Context) = ALL.filter { it.required }.all { granted(c, it.id) }
    fun count(c: Context) = ALL.count { granted(c, it.id) }

    fun appInfo(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    /** Phone makers that kill background apps, and the screen where you can stop it. */
    fun autostartIntent(c: Context): Intent? {
        val m = Build.MANUFACTURER.lowercase()
        val candidates = when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "oppo" in m || "realme" in m || "oneplus" in m -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oplus.battery" to "com.oplus.startupapp.view.StartupAppListActivity")
            "vivo" in m || "iqoo" in m -> listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            "huawei" in m || "honor" in m -> listOf("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            else -> emptyList()
        }
        return candidates.map { Intent().setComponent(ComponentName(it.first, it.second)) }
            .firstOrNull { c.packageManager.resolveActivity(it, 0) != null }
    }

    fun oemName(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
}

/**
 * Runs the shortest grant path for each permission. Must be created while the activity is being
 * constructed (it registers result launchers). `onChange` fires whenever something may have changed.
 */
class PermRequester(private val a: ComponentActivity, private val onChange: () -> Unit) {
    private var after: (() -> Unit)? = null

    private val multi = a.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        // Asked before and said "don't ask again"? The dialog no longer appears → send them to the app page.
        val blocked = res.filterValues { !it }.keys.any { !a.shouldShowRequestPermissionRationale(it) }
        val next = after; after = null
        if (res.values.all { it }) next?.invoke() else if (blocked && res.isNotEmpty()) a.startActivity(Perms.appInfo(a))
        onChange()
    }
    private var lastSingle = ""
    private val single = a.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // only when Android has stopped showing the dialog ("don't ask again") do we open the app page
        if (!ok && after == null && !a.shouldShowRequestPermissionRationale(lastSingle)) a.startActivity(Perms.appInfo(a))
        after = null; onChange()
    }
    private fun ask(p: String) { lastSingle = p; single.launch(p) }
    private val forResult = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val next = after; after = null
        next?.invoke(); onChange()
    }

    @SuppressLint("BatteryLife") // Grab is a user-facing assistant whose core job is timely pop-ups
    fun request(id: String) {
        when (id) {
            Perms.NOTIF -> {
                // Straight to Grab's own switch (Android 11+), not the list of every app.
                val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(a, GrabNotificationListener::class.java).flattenToString())
                launch(direct, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Perms.OVERLAY -> launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")), Perms.appInfo(a))
            Perms.ESSENTIALS -> multi.launch(Perms.essentials())
            Perms.LOCATION -> {
                if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    after = { a.window.decorView.postDelayed({ ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, 400) }
                    multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    after = {} // don't bounce to app info if they back out of the settings page
                    ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            Perms.CALLS -> {
                val rm = a.getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    after = { if (Perms.roleHeld(a)) ask(Manifest.permission.ANSWER_PHONE_CALLS) }
                    forResult.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                } else ask(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            Perms.PHOTOS -> ask(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
            Perms.UPDATES -> launch(Updater.allowInstallsIntent(a), Perms.appInfo(a))
            Perms.BATTERY -> launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")), Perms.appInfo(a))
        }
    }

    private fun launch(i: Intent, fallback: Intent) {
        try { forResult.launch(i) } catch (e: Exception) { try { forResult.launch(fallback) } catch (_: Exception) { } }
    }
}
