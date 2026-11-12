package app.grab.pill

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Where you are right now (set by geofences). Survives the process being killed
 * for 45 minutes, so a geofence that fired while Grab was asleep still counts.
 */
object Ctx {
    private const val VALID_MS = 45 * 60_000L
    @Volatile var place: Place? = null
        private set
    @Volatile private var since = 0L

    fun init(c: Context) {
        if (place != null) return
        val o = Store.get(c, "ctx_place")?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: return
        if (System.currentTimeMillis() - o.optLong("since") < VALID_MS) {
            place = Places.all(c).firstOrNull { it.id == o.optString("id") }; since = o.optLong("since")
        }
    }

    fun enter(c: Context, p: Place) {
        place = p; since = System.currentTimeMillis()
        Store.put(c, "ctx_place", JSONObject().put("id", p.id).put("since", since).toString())
    }

    fun exit(c: Context, id: String) {
        if (place?.id == id) { place = null; Store.put(c, "ctx_place", "{}") }
    }

    fun atMetro(): Boolean {
        val p = place ?: return false
        if (System.currentTimeMillis() - since > VALID_MS) { place = null; return false }
        return p.type == Places.METRO
    }
}

/**
 * Learns what you reach for, and when. Every time you open a metro ticket, pay, or send a document
 * Grab counts it against (weekday/weekend, hour). Next time, at that hour, the same thing ranks higher
 * in the pill and the hub. Counts fade slowly (x0.97 per new event) so old routines wash out.
 * On the phone only.
 */
object Habits {
    private var cache: JSONObject? = null
    private lateinit var app: Context

    fun init(c: Context) {
        app = c.applicationContext
        if (cache == null) cache = try { JSONObject(Store.get(app, "habits") ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    private fun slot(now: Long, hourShift: Int = 0): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.HOUR_OF_DAY, hourShift) }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val wk = if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) "E" else "W"
        return "$wk${cal.get(Calendar.HOUR_OF_DAY)}"
    }

    @Synchronized
    fun used(c: Context, kind: String, now: Long = System.currentTimeMillis()) {
        init(c)
        val o = cache ?: return
        // slow fade of everything for this kind, then +1 for this slot
        val keys = o.keys().asSequence().filter { it.startsWith("$kind@") }.toList()
        keys.forEach { o.put(it, o.optDouble(it) * 0.97) }
        val k = "$kind@${slot(now)}"
        o.put(k, o.optDouble(k, 0.0) + 1.0)
        Store.put(app, "habits", o.toString())
    }

    /** 0..15 extra points when this kind is something you usually use around this hour. */
    fun boost(kind: String, now: Long = System.currentTimeMillis()): Int {
        val o = cache ?: return 0
        fun v(shift: Int) = o.optDouble("$kind@${slot(now, shift)}", 0.0)
        val n = v(0) + 0.5 * (v(-1) + v(1))
        return (n * 3).toInt().coerceIn(0, 15)
    }

    /** "You usually do this around 8 AM on weekdays" – shown in the hub so the learning is visible. */
    fun usualHour(kind: String): String? {
        val o = cache ?: return null
        val best = o.keys().asSequence().filter { it.startsWith("$kind@W") }.maxByOrNull { o.optDouble(it) } ?: return null
        if (o.optDouble(best) < 2.5) return null
        val h = best.substringAfter("@W").toIntOrNull() ?: return null
        val label = when { h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM" }
        return "usually around $label on weekdays"
    }
}
