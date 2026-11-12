package app.grab.pill

import android.app.Notification
import android.content.pm.PackageManager
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

        // 2) OTPs, rides, orders, tickets
        val card = Parser.parse(pkg, app, title, text)
        if (card != null) { put(card, pkg, n); return }

        // 3) messages the rules couldn't decide → optional AI (off unless you turn it on)
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

    private fun put(card: Card, pkg: String, n: Notification) {
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
