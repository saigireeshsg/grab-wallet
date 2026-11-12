package app.grab.pill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** After a reboot or an app update: re-arm the station geofences and bring the pill back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Places.register(c)
        if (Settings.canDrawOverlays(c)) try { c.startForegroundService(Intent(c, PillService::class.java)) } catch (e: Exception) { }
    }
}
