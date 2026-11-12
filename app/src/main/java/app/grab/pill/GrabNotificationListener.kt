package app.grab.pill

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notifications ON DEVICE → a Card (OTP, ride, order, ticket, or someone asking you for something).
 * The raw text is never stored. The notification's own tap target is kept in memory so "Open" on a
 * pop-up lands on the exact trip / chat.
 */
class GrabNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { Habits.init(this); Ctx.init(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // "3 new messages" summaries repeat the children
        val e = n.extras ?: return
        val pkg = sbn.packageName
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val app = label(pkg)

        // 1) someone asking you for a document / a reply (WhatsApp, Gmail, …)
        val ask = Asks.detect(pkg, title, text)
        if (ask != null) { put(Asks.toCard(this, ask), pkg, n); return }

        // 2) OTPs, rides, orders, tickets: from any app, read by what the notification says
        val card = Parser.parse(pkg, app, title, text, AppMemory.hint(this, pkg))
        val pic = picture(e)
        if (card != null) {
            // the ticket QR is often attached as the notification picture: read it and keep a crisp copy
            if (pic != null && card.kind in TICKETS && card.qr.isEmpty()) NotifPicture.scan(this, pic) { qr, path ->
                put(card.copy(qr = qr ?: "", img = path ?: card.img), pkg, n)
            } else put(card, pkg, n)
            return
        }
        if (pic != null && Regex("(?i)ticket|booking|pass|entry|qr|boarding").containsMatchIn("$title $text")) {
            NotifPicture.scan(this, pic) { qr, path ->
                if (qr != null) put(Card(Card.newId(), if (Parser.isMetro("$title $text")) "METRO" else "TICKET", title.take(40).ifBlank { "$app ticket" },
                    text.take(60).ifBlank { "From $app" }, qr = qr, img = path ?: "", src = app, key = "ticket:$pkg:${title.take(20)}"), pkg, n)
            }
            return
        }

        // 3) other apps: anything that looks like an event (times, codes, bookings) the rules couldn't read → optional AI
        if (pkg !in Asks.MSG_APPS && Brain.enabled(this) && EVENTISH.containsMatchIn("$title $text")) {
            Brain.extract(applicationContext, app, title, text) { k -> if (k != null) put(k.copy(src = app, key = "ai:$pkg:${k.kind}"), pkg, n) }
            return
        }

        // 4) messages the rules couldn't decide → optional AI (off unless you turn it on)
        if (pkg in Asks.MSG_APPS && text.length > 12 && MAYBE_ASK.containsMatchIn(text) && Brain.enabled(this)) {
            val sender = Asks.sender(pkg, title)
            Brain.classify(applicationContext, Asks.MSG_APPS[pkg] ?: app, sender, text) { r ->
                if (r == null || r.intent == "none") return@classify
                val a = Asks.Ask(sender, Asks.MSG_APPS[pkg] ?: app, pkg, r.docType, r.intent == "document_request" && r.docType == null,
                    r.intent == "reply_needed", if (pkg in Asks.EMAIL_APPS) text.lineSequence().firstOrNull()?.take(80) ?: "" else r.summary)
                put(Asks.toCard(applicationContext, a), pkg, n)
            }
        }
    }

    // only messages that look like a question or a request are worth asking the AI about
    private val MAYBE_ASK = Regex("(?i)\\?|\\b(send|share|need|please|pls|kindly|can you|could you|mail|attach|forward)\\b")

    private val TICKETS = setOf("METRO", "MOVIE", "TICKET", "TRAVEL")
    private val EVENTISH = Regex("(?i)(\\b\\d{1,2}[:.]\\d{2}\\b|\\b(arriv|deliver|booking|booked|confirmed|order|trip|ride|ticket|pnr|appointment|scheduled|reminder|due)\\w*)")

    @Suppress("DEPRECATION")
    private fun picture(e: Bundle): Bitmap? = try {
        (e.get(Notification.EXTRA_PICTURE) as? Bitmap)
            ?: if (Build.VERSION.SDK_INT >= 31) (e.get(Notification.EXTRA_PICTURE_ICON) as? Icon)?.loadDrawable(this)?.toBitmap() else null
    } catch (_: Exception) { null }

    private fun put(card: Card, pkg: String, n: Notification) {
        AppMemory.saw(applicationContext, pkg, card.kind)
        val withPkg = card.copy(extra = card.x().put("pkg", pkg).toString())
        Live.remember(withPkg.id, n.contentIntent)
        if (withPkg.key.isNotEmpty()) Live.remember(withPkg.key, n.contentIntent)
        CardStore.put(applicationContext, withPkg)
    }

    private fun label(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg.substringAfterLast('.') }
}
