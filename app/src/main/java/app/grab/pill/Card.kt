package app.grab.pill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * One thing you might need to show or know: a ticket, an OTP, a live status.
 * Stored ON THE PHONE ONLY (app-private file). Expired cards are deleted automatically.
 */
data class Card(
    val id: String,
    val kind: String,          // OTP | RIDE | ORDER | METRO | MOVIE | TICKET | ASK
    val title: String,
    val sub: String,
    val value: String = "",    // OTP digits, seat, fare…
    val qr: String = "",       // raw QR payload if we decoded one
    val img: String = "",      // path to a saved ticket image inside app storage
    val whenMs: Long = 0,      // show time / journey time, 0 if unknown
    val src: String = "",      // app it came from
    val key: String = "",      // dedupe key: latest status per app replaces the previous one
    val ts: Long = System.currentTimeMillis(),
    val exp: Long = 0,         // auto-delete after this time
    val extra: String = ""     // small JSON for actions (ASK: app, sender, doc type, doc id)
) {
    fun x(): JSONObject = try { JSONObject(extra) } catch (e: Exception) { JSONObject() }

    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("title", title); put("sub", sub); put("value", value)
        put("qr", qr); put("img", img); put("when", whenMs); put("src", src); put("key", key); put("ts", ts); put("exp", exp); put("extra", extra)
    }
    companion object {
        fun from(o: JSONObject) = Card(
            o.optString("id"), o.optString("kind"), o.optString("title"), o.optString("sub"), o.optString("value"),
            o.optString("qr"), o.optString("img"), o.optLong("when"), o.optString("src"), o.optString("key"), o.optLong("ts"), o.optLong("exp"), o.optString("extra")
        )
        fun newId() = "c" + System.currentTimeMillis().toString(36) + (1000..9999).random()
    }
}

object CardStore {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private fun file(c: Context) = File(c.filesDir, "cards.json")

    @Synchronized
    fun all(c: Context): List<Card> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val list = (0 until arr.length()).map { Card.from(arr.getJSONObject(it)) }
        val alive = list.filter { it.exp == 0L || it.exp > now }
        if (alive.size != list.size) { // delete expired cards + their images
            list.filter { it !in alive }.forEach { if (it.img.isNotEmpty()) File(it.img).delete() }
            save(c, alive)
        }
        return alive.sortedByDescending { it.ts }
    }

    @Synchronized
    private fun save(c: Context, list: List<Card>) {
        val arr = JSONArray(); list.take(300).forEach { arr.put(it.json()) }
        file(c).writeText(arr.toString())
    }

    /** Add a card. Same key = replace (e.g. the newest Swiggy status replaces the older one). */
    @Synchronized
    fun put(c: Context, card: Card) {
        val list = all(c).toMutableList()
        if (card.key.isNotEmpty()) list.removeAll { it.key == card.key }
        // ignore exact duplicates arriving within 5 minutes
        if (list.any { it.kind == card.kind && it.value == card.value && it.title == card.title && card.ts - it.ts < 5 * MIN }) return
        val saved = card.copy(exp = if (card.exp != 0L) card.exp else defaultExpiry(card))
        list.add(0, saved)
        save(c, list)
        CardBus.emit()
        Assistant.onNewCard(c, saved) // decide whether this deserves a pop-up in the thumb zone
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        val list = all(c).toMutableList()
        list.firstOrNull { it.id == id }?.let { if (it.img.isNotEmpty()) File(it.img).delete() }
        list.removeAll { it.id == id }; save(c, list); CardBus.emit()
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    private fun defaultExpiry(card: Card): Long {
        val now = System.currentTimeMillis()
        return when (card.kind) {
            "OTP" -> now + 15 * MIN                                     // codes never linger
            "RIDE" -> now + 2 * HOUR
            "ORDER" -> now + 6 * HOUR
            "MOVIE" -> if (card.whenMs > 0) card.whenMs + 4 * HOUR else now + 3 * 24 * HOUR
            "METRO" -> endOfDay(now)
            "ASK" -> now + 24 * HOUR
            else -> now + 7 * 24 * HOUR
        }
    }

    private fun endOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 0)
    }.timeInMillis

    /**
     * What matters RIGHT NOW. Drives the pill and the top of the hub.
     * Higher = more urgent. Rules give the base; where you are (Ctx) and what you
     * usually do at this hour (Habits) push it up.
     */
    fun score(card: Card, now: Long = System.currentTimeMillis()): Int = base(card, now) + Habits.boost(card.kind, now)

    private fun base(card: Card, now: Long): Int {
        val ageMin = (now - card.ts) / MIN
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (card.kind) {
            "OTP" -> if (ageMin < 10) 100 else 30
            "RIDE" -> if (ageMin < 30) 92 else 20
            "ORDER" -> if (Regex("(?i)arriving|out for delivery|nearby|reached").containsMatchIn(card.sub + card.title)) 85 else 50
            "MOVIE" -> {
                if (card.whenMs == 0L) 40 else {
                    val minsToShow = (card.whenMs - now) / MIN
                    when { minsToShow in -30..90 -> 88; minsToShow in 91..360 -> 55; else -> 25 }
                }
            }
            "METRO" -> when { Ctx.atMetro() -> 97; h in 7..10 || h in 17..21 -> 70; else -> 42 }
            "ASK" -> if (ageMin < 60) 80 else 48
            else -> 10
        }
    }

    fun top(c: Context): Card? = all(c).maxByOrNull { score(it) }?.takeIf { score(it) >= 40 }
}

/** In-process "something changed" signal for the pill and the hub. */
object CardBus {
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun emit() { listeners.toList().forEach { it() } }
}
