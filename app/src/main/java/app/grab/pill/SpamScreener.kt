package app.grab.pill

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.TelecomManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Android asks Grab about every incoming call from a number NOT in your contacts (role: Caller ID & spam).
 *  - numbers you blocked → rejected silently
 *  - likely spam (telemarketing 140-series, spoofed caller ID, unexpected international, hidden) →
 *    ringtone silenced + a pop-up at the bottom: Decline · It's OK · Always block
 *  - everything else rings normally
 * Decisions are on the phone, from the number only. Nothing is uploaded.
 */
class SpamScreener : CallScreeningService() {
    override fun onScreenCall(d: Call.Details) {
        val r = CallResponse.Builder()
        if (d.callDirection != Call.Details.DIRECTION_INCOMING) { respondToCall(d, r.build()); return }
        val num = d.handle?.schemeSpecificPart ?: ""
        val verified = if (Build.VERSION.SDK_INT >= 30) d.callerNumberVerificationStatus else Connection.VERIFICATION_STATUS_NOT_VERIFIED
        val v = SpamRules.verdict(this, num, verified)
        when (v.level) {
            SpamRules.BLOCK -> {
                r.setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
                SpamRules.log(this, num, v.reason, blocked = true)
            }
            SpamRules.SUSPECT -> {
                r.setSilenceCall(true)
                SpamRules.log(this, num, v.reason, blocked = false)
                Habits.init(this)
                Assistant.pop(Moment("call:$num:${System.currentTimeMillis() / 60_000}", "CALL", "Likely spam · ${SpamRules.pretty(num)}", v.reason + " · ringtone muted",
                    ttlMs = 30_000, acts = listOf(
                        Act("Decline", true) { SpamRules.endCall(it) },
                        Act("Always block") { SpamRules.block(it, num); SpamRules.endCall(it) },
                        Act("It's OK") { SpamRules.allow(it, num) }
                    )), force = true)
            }
        }
        respondToCall(d, r.build())
    }
}

object SpamRules {
    const val OK = 0; const val SUSPECT = 1; const val BLOCK = 2
    data class Verdict(val level: Int, val reason: String)

    /** Digits only, Indian numbers without +91 / leading 0. */
    fun norm(n: String): String {
        var d = n.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+91")) d = d.drop(3) else if (d.startsWith("0091")) d = d.drop(4)
        if (!d.startsWith("+") && d.length == 11 && d.startsWith("0")) d = d.drop(1)
        return d
    }

    fun pretty(n: String) = norm(n).let { if (it.length == 10 && !it.startsWith("+")) "${it.take(5)} ${it.drop(5)}" else it.ifEmpty { "Hidden number" } }

    private fun set(c: Context, k: String) = (Store.get(c, k) ?: "").split(',').filter { it.isNotBlank() }.toSet()
    fun block(c: Context, n: String) { Store.put(c, "spam_block", (set(c, "spam_block") + norm(n)).joinToString(",")); Toast.makeText(c, "Blocked ${pretty(n)}", Toast.LENGTH_SHORT).show() }
    fun allow(c: Context, n: String) { Store.put(c, "spam_allow", (set(c, "spam_allow") + norm(n)).joinToString(",")) }

    fun verdict(c: Context, raw: String, verification: Int): Verdict {
        val n = norm(raw)
        if (n in set(c, "spam_allow")) return Verdict(OK, "")
        if (n.isNotEmpty() && n in set(c, "spam_block")) return Verdict(BLOCK, "You blocked this number")
        if (n.isEmpty()) return Verdict(SUSPECT, "Hidden number")
        if (verification == Connection.VERIFICATION_STATUS_FAILED) return Verdict(SUSPECT, "Caller ID looks spoofed")
        // TRAI: 140-series is reserved for promotional telemarketing (banks/services use 160-series)
        if (!n.startsWith("+") && n.startsWith("140")) {
            return if (Store.get(c, "spam_auto140") == "1") Verdict(BLOCK, "Telemarketing (140-series)") else Verdict(SUSPECT, "Telemarketing (140-series)")
        }
        if (n.startsWith("+")) return Verdict(SUSPECT, "International call from ${n.take(4)}")
        return Verdict(OK, "")
    }

    fun log(c: Context, n: String, reason: String, blocked: Boolean) {
        val a = try { JSONArray(Store.get(c, "spam_log") ?: "[]") } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("n", norm(n)).put("why", reason).put("blocked", blocked).put("ts", System.currentTimeMillis()))
        while (a.length() > 100) a.remove(0)
        Store.put(c, "spam_log", a.toString())
        if (blocked) Store.addSaved(c, 20)
    }

    fun recent(c: Context): List<JSONObject> {
        val a = try { JSONArray(Store.get(c, "spam_log") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.reversed()
    }

    fun todayCount(c: Context): Int {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        return recent(c).count { it.optLong("ts") >= start }
    }

    /** Hang up the ringing call. Needs the "Phone: answer calls" permission (setup step). */
    @Suppress("DEPRECATION")
    fun endCall(c: Context) {
        if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(c, "Allow Grab to end calls in Setup (step 6)", Toast.LENGTH_LONG).show(); return
        }
        try { c.getSystemService(TelecomManager::class.java).endCall(); Store.addSaved(c, 15) } catch (e: Exception) { }
    }
}
