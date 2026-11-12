package app.grab.pill

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/** A button on a pop-up. `run` gets a context that can start activities (the pill service). */
data class Act(val label: String, val primary: Boolean = false, val run: (Context) -> Unit)

/**
 * One pop-up in the thumb zone. `big` is the thing you need (OTP digits, fare);
 * `qr` is drawn as a crisp code at full brightness. Closes by itself after `ttlMs`.
 */
data class Moment(
    val key: String,
    val kind: String,
    val title: String,
    val sub: String,
    val big: String = "",
    val qr: String = "",
    val img: String = "",
    val cardId: String = "",
    val acts: List<Act> = emptyList(),
    val ttlMs: Long = 25_000,
    val bright: Boolean = false
)

/**
 * The assistant: watches what arrives (cards, places, calls, the clock) and decides
 * whether it is worth interrupting you with a pop-up at the bottom of the screen.
 * Rules first; Habits and Ctx tune the ranking; the optional Brain helps read messages.
 */
object Assistant {
    private val listeners = mutableListOf<(Moment) -> Unit>()
    private val lastPop = HashMap<String, Long>()
    private const val MIN = 60_000L

    fun listen(l: (Moment) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: (Moment) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    /** Show a pop-up (once per key every `quietMin` minutes). Returns false when the pill isn't running. */
    fun pop(m: Moment, quietMin: Int = 3, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lastPop) {
            if (!force && (lastPop[m.key] ?: 0) > now - quietMin * MIN) return true
            lastPop[m.key] = now
        }
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { it(m) }
        return ls.isNotEmpty()
    }

    // ------------------------------------------------------------------ cards

    fun onNewCard(c: Context, k: Card) {
        val app = c.applicationContext
        when (k.kind) {
            "OTP" -> {
                if (Store.get(app, "otp_autocopy") == "1") copy(app, k.value, toast = false)
                pop(Moment("otp:${k.value}", "OTP", k.title, if (Store.get(app, "otp_autocopy") == "1") "Copied · paste it" else k.sub.ifBlank { "One-time code" },
                    big = k.value, cardId = k.id, ttlMs = 40_000,
                    acts = listOf(Act("Copy", true) { copy(it, k.value); Store.addSaved(it, 6) })))
            }
            "RIDE" -> {
                val s = (k.sub + " " + k.title).lowercase()
                val eta = Regex("(\\d{1,2}) min").find(s)?.groupValues?.get(1)?.toIntOrNull()
                val worth = k.value.isNotEmpty() || (eta != null && eta <= 5) || Regex("arriv|reached|here|waiting|ended").containsMatchIn(s)
                if (!worth) return
                val acts = mutableListOf<Act>()
                if (k.value.all { it.isDigit() } && k.value.isNotEmpty()) acts += Act("Copy OTP", true) { copy(it, k.value) }
                acts += Act("Open ${k.src}") { Live.open(it, k) }
                pop(Moment("ride:${k.key}:${k.sub}", "RIDE", k.title, k.sub, big = k.value, cardId = k.id, ttlMs = 45_000, acts = acts), quietMin = 1)
            }
            "ORDER" -> {
                if (CardStore.score(k) < 85) return
                pop(Moment("order:${k.key}:${k.sub}", "ORDER", k.title, k.sub, big = k.value, cardId = k.id,
                    acts = listOf(Act("Open ${k.src}", true) { Live.open(it, k) })), quietMin = 5)
            }
            "ASK" -> popAsk(app, k)
        }
    }

    fun popAsk(c: Context, k: Card) {
        val x = k.x()
        val doc = DocVault.get(c, x.optString("docId"))
        val acts = mutableListOf<Act>()
        if (doc != null) acts += Act("Send ${doc.name}", true) { Asks.send(it, k) }
        else if (x.optString("docType").isNotEmpty() || x.optBoolean("anyDoc")) acts += Act("Find it", true) { Asks.find(it, k) }
        acts += Act("Open chat") { Live.open(it, k) }
        pop(Moment(k.key.ifEmpty { k.id }, "ASK", k.title, k.sub, cardId = k.id, ttlMs = 60_000, acts = acts), quietMin = 30)
    }

    // ------------------------------------------------------------------ places

    fun onPlace(c: Context, p: Place) {
        if (p.type != Places.METRO) return
        val ticket = CardStore.all(c).filter { it.kind == "METRO" }.maxByOrNull { it.ts }
        val metroApp = Store.get(c, "metro_app")
        val acts = mutableListOf<Act>()
        if (ticket != null) {
            acts += Act("Full screen", true) { ctx -> open(ctx, Intent(ctx, CardActivity::class.java).putExtra("id", ticket.id)) }
            if (metroApp != null) acts += Act("Metro app") { ctx -> launch(ctx, metroApp) }
            pop(Moment("metro:${p.id}", "METRO", "${p.name} · your ticket", ticket.title, qr = ticket.qr, img = if (ticket.qr.isEmpty()) ticket.img else "",
                cardId = ticket.id, ttlMs = 120_000, bright = true, acts = acts), quietMin = 20)
        } else {
            if (metroApp != null) acts += Act("Open metro app", true) { ctx -> launch(ctx, metroApp); Store.addSaved(ctx, 8) }
            acts += Act("Scan a ticket") { ctx -> open(ctx, Intent(ctx, HubActivity::class.java).putExtra("tab", "wallet")) }
            pop(Moment("metro:${p.id}", "METRO", "At ${p.name}", "No ticket saved for today", ttlMs = 30_000, acts = acts), quietMin = 20)
        }
    }

    // ------------------------------------------------------------------ the clock (every minute, from the pill)

    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        // Movie about to start → ticket ready
        CardStore.all(c).filter { it.kind == "MOVIE" && it.whenMs > 0 && (it.whenMs - now) in 0..25 * MIN }.forEach { k ->
            pop(Moment("movie:${k.id}", "MOVIE", k.title, "Starts ${Parser.timeLabel(k.whenMs)} · ${k.value}".trimEnd(' ', '·'),
                qr = k.qr, img = if (k.qr.isEmpty()) k.img else "", cardId = k.id, ttlMs = 60_000, bright = true,
                acts = listOf(Act("Full screen", true) { open(it, Intent(it, CardActivity::class.java).putExtra("id", k.id)) })), quietMin = 60)
        }
        // Meeting in ≤ 3 min with a link → one-tap join
        CalendarHelper.next(c)?.let { ev ->
            val mins = (ev.start - now) / MIN
            if (ev.link != null && mins in 0..3) pop(Moment("meet:${ev.title}:${ev.start}", "MEET", ev.title, "Starts at ${ev.timeLabel}",
                acts = listOf(Act("Join", true) { open(it, Intent(Intent.ACTION_VIEW, Uri.parse(ev.link))); Store.addSaved(it, 40) })), quietMin = 30)
        }
    }

    // ------------------------------------------------------------------ helpers

    fun copy(c: Context, text: String, toast: Boolean = true) {
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", text))
        if (toast && Build.VERSION.SDK_INT < 33) Toast.makeText(c, "Copied $text", Toast.LENGTH_SHORT).show() // 13+ shows its own
    }

    fun open(c: Context, i: Intent) = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        Toast.makeText(c, "Couldn't open that", Toast.LENGTH_SHORT).show()
    }

    fun launch(c: Context, pkg: String) {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return
        open(c, i)
    }
}

/**
 * The tap targets of the notifications Grab read (e.g. "open this Rapido trip", "open this WhatsApp chat").
 * Kept in memory only, so "Open" goes to the exact screen, not just the app's home.
 */
object Live {
    private val intents = HashMap<String, PendingIntent>()

    fun remember(key: String, pi: PendingIntent?) { if (pi != null) synchronized(intents) { intents[key] = pi; if (intents.size > 60) intents.remove(intents.keys.first()) } }

    fun open(c: Context, k: Card) {
        val pi = synchronized(intents) { intents[k.id] ?: intents[k.key] }
        if (pi != null) try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(c, 0, null, null, null, null, o.toBundle())
            } else pi.send()
            return
        } catch (e: Exception) { }
        val pkg = k.x().optString("pkg").ifEmpty { k.src }
        Assistant.launch(c, pkg)
    }
}
