package app.grab.pill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything lives ON THE PHONE (app-private SharedPreferences).
 * Nothing here is uploaded. OTPs expire after 15 minutes.
 * v2: move to Room + SQLCipher (encrypted) once the schema settles.
 */
object Store {
    private const val P = "grab"
    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    // ---------- vendor memory (for QR payments) ----------
    // key = UPI id (pa). value = {name, amounts:[...], count}
    fun vendor(c: Context, pa: String): JSONObject? =
        prefs(c).getString("v_$pa", null)?.let { JSONObject(it) }

    fun rememberPayment(c: Context, pa: String, name: String, amount: Int) {
        val v = vendor(c, pa) ?: JSONObject().put("name", name).put("amounts", JSONArray()).put("count", 0)
        val arr = v.getJSONArray("amounts"); arr.put(amount)
        while (arr.length() > 20) arr.remove(0)
        v.put("count", v.optInt("count") + 1).put("name", name)
        prefs(c).edit().putString("v_$pa", v.toString()).apply()
        addLedger(c, name, amount)
    }

    /** Most frequent past amount at this vendor = the guess. */
    fun guessAmount(c: Context, pa: String): Int? {
        val arr = vendor(c, pa)?.optJSONArray("amounts") ?: return null
        val counts = HashMap<Int, Int>()
        for (i in 0 until arr.length()) { val a = arr.optInt(i); counts[a] = (counts[a] ?: 0) + 1 }
        return counts.maxByOrNull { it.value }?.key
    }

    // ---------- ledger ----------
    fun addLedger(c: Context, who: String, amount: Int) {
        val a = JSONArray(prefs(c).getString("ledger", "[]"))
        a.put(JSONObject().put("who", who).put("amt", amount).put("ts", System.currentTimeMillis()))
        while (a.length() > 300) a.remove(0)
        prefs(c).edit().putString("ledger", a.toString()).apply()
    }

    fun ledger(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("ledger", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.reversed()
    }

    // ---------- seconds saved (estimated per action) ----------
    fun addSaved(c: Context, seconds: Int) {
        prefs(c).edit().putInt("saved", savedSeconds(c) + seconds).apply()
    }
    fun savedSeconds(c: Context) = prefs(c).getInt("saved", 0)

    // ---------- simple settings ----------
    fun put(c: Context, k: String, v: String) = prefs(c).edit().putString(k, v).apply()
    fun get(c: Context, k: String): String? = prefs(c).getString(k, null)
}

