package app.grab.pill

import java.util.Calendar
import java.util.Locale

/**
 * Turns notification text (or text read from a ticket image) into a Card.
 * Pure rules, on device. Tune these with the real notifications on your phone.
 */
object Parser {
    private val OTP = Regex("""(?i)\b(otp|code|pin|passcode)\b[^0-9]{0,30}?(\d{4,8})\b""")
    private val OTP2 = Regex("""\b(\d{4,8})\b[^0-9]{0,20}(?i:is your|is the)""")
    private val AMT = Regex("""(?i)(?:₹|rs\.?|inr)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val PLATE = Regex("""\b([A-Z]{2}\s?\d{1,2}\s?[A-Z]{1,3}\s?\d{3,4})\b""")
    private val ETA = Regex("""(?i)(\d{1,2})\s?(min|mins|minutes)\b""")
    private val TIME = Regex("""(?i)\b(\d{1,2})[:.](\d{2})\s?(am|pm)\b""")
    private val DATE = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*,?\s?(\d{4})?\b""")
    private val SEAT = Regex("""(?i)\b(?:seats?|seat no\.?)\s*[:\-]?\s*([A-Z]{1,2}\s?\d{1,3}(?:\s?,\s?[A-Z]{0,2}\s?\d{1,3})*)""")
    private val SCREEN = Regex("""(?i)\b(audi(?:torium)?|screen)\s*[:\-]?\s*(\d{1,2})""")

    private val RIDE_APPS = mapOf(
        "com.rapido.passenger" to "Rapido", "com.ubercab" to "Uber",
        "com.olacabs.customer" to "Ola", "in.juspay.nammayatri" to "Namma Yatri"
    )
    private val ORDER_APPS = mapOf(
        "in.swiggy.android" to "Swiggy", "com.application.zomato" to "Zomato",
        "com.zeptoconsumerapp" to "Zepto", "com.grofers.customerapp" to "Blinkit",
        "in.amazon.mShop.android.shopping" to "Amazon", "com.flipkart.android" to "Flipkart",
        "com.bigbasket.mobileapp" to "BigBasket"
    )
    private val MOVIE_APPS = mapOf("com.bt.bms" to "BookMyShow")

    fun parse(pkg: String, appLabel: String, title: String, text: String): Card? {
        val s = "$title $text".replace(Regex("\\s+"), " ").trim()
        if (s.isBlank()) return null
        val otp = OTP.find(s)?.groupValues?.get(2) ?: OTP2.find(s)?.groupValues?.get(1)

        // 1) Rides: latest status per app replaces the previous one
        RIDE_APPS[pkg]?.let { app ->
            val plate = PLATE.find(s)?.groupValues?.get(1) ?: ""
            val eta = ETA.find(s)?.groupValues?.get(1)
            val fare = AMT.find(s)?.groupValues?.get(1)
            val ended = Regex("(?i)complete|ended|reached your destination|pay ").containsMatchIn(s)
            val sub = when {
                ended && fare != null -> "Trip ended · fare ₹$fare"
                eta != null -> "$eta min away" + if (plate.isNotEmpty()) " · $plate" else ""
                else -> title.take(50)
            }
            return Card(Card.newId(), "RIDE", "$app ride", sub, value = otp ?: (if (ended) "₹${fare ?: ""}" else ""), src = app, key = "ride:$pkg")
        }

        // 2) Orders / deliveries: one live card per app, always the newest status
        val orderApp = ORDER_APPS[pkg]
        if (orderApp != null || Regex("(?i)out for delivery|order (is )?(on the way|picked up|delivered|confirmed|being prepared)|arriving").containsMatchIn(s)) {
            val app = orderApp ?: appLabel
            val status = statusLine(s)
            if (status != null) return Card(Card.newId(), "ORDER", "$app order", status, value = otp ?: "", src = app, key = "order:${pkg}")
        }

        // 3) Movie tickets
        if (MOVIE_APPS.containsKey(pkg) || isMovie(s)) {
            if (Regex("(?i)booked|confirmed|booking id|ticket").containsMatchIn(s)) return movieFrom(s, MOVIE_APPS[pkg] ?: appLabel)
        }

        // 4) Metro tickets
        if (isMetro(s) && Regex("(?i)ticket|qr|journey|booked|confirmed").containsMatchIn(s)) return metroFrom(s, appLabel)

        // 5) Plain OTPs (bank, login, delivery)
        if (otp != null) return Card(Card.newId(), "OTP", appLabel.ifBlank { "Code" }, title.take(60), value = otp, src = appLabel)

        return null
    }

    fun isMovie(s: String) = Regex("(?i)\\b(audi|screen \\d|seats?|showtime|show time|pvr|inox|cinepolis|amb cinemas|prasads|bookmyshow|district)\\b").containsMatchIn(s)
    fun isMetro(s: String) = Regex("(?i)\\b(metro|hmrl|l&t metro|dmrc|bmrcl|namma metro|mmrc|cmrl)\\b").containsMatchIn(s)

    fun movieFrom(s: String, src: String, qr: String = "", img: String = ""): Card {
        val whenMs = parseWhen(s)
        val seat = SEAT.find(s)?.groupValues?.get(1)?.replace(" ", "") ?: ""
        val screen = SCREEN.find(s)?.let { "Audi ${it.groupValues[2]}" } ?: ""
        val venue = Regex("(?i)\\b(pvr|inox|cinepolis|amb cinemas|prasads|asian)[^,\\n]{0,24}").find(s)?.value?.trim() ?: ""
        val name = s.split(Regex("[|\\n]")).firstOrNull { it.length in 3..40 && !Regex("(?i)booking|ticket|confirmed|seat|audi|screen|₹").containsMatchIn(it) }?.trim() ?: "Movie"
        val sub = listOf(timeLabel(whenMs), venue, screen).filter { it.isNotEmpty() }.joinToString(" · ")
        return Card(Card.newId(), "MOVIE", name.take(36), sub.ifEmpty { "Movie ticket" }, value = seat, qr = qr, img = img, whenMs = whenMs, src = src)
    }

    fun metroFrom(s: String, src: String, qr: String = "", img: String = ""): Card {
        val stations = Regex("(?i)\\bfrom\\s*[:\\-]?\\s*([A-Za-z .]{3,30}?)\\s+to\\s*[:\\-]?\\s*([A-Za-z .]{3,30})").find(s)
        val route = stations?.let { "${it.groupValues[1].trim()} → ${it.groupValues[2].trim()}" } ?: "Metro journey"
        val fare = AMT.find(s)?.groupValues?.get(1)?.let { "₹$it" } ?: ""
        return Card(Card.newId(), "METRO", route.take(40), "Metro ticket · works offline", value = fare, qr = qr, img = img, src = src.ifBlank { "Metro" })
    }

    private fun statusLine(s: String): String? {
        val pairs = listOf(
            "(?i)delivered" to "Delivered ✓",
            "(?i)out for delivery|on the way|arriving|nearby|reached" to "Arriving soon",
            "(?i)picked up" to "Picked up · on the way",
            "(?i)being prepared|preparing|cooking|packed|packing" to "Being prepared",
            "(?i)confirmed|placed|accepted" to "Order confirmed"
        )
        for ((rx, label) in pairs) if (Regex(rx).containsMatchIn(s)) {
            val eta = ETA.find(s)?.groupValues?.get(1)
            return label + if (eta != null && label != "Delivered ✓") " · $eta min" else ""
        }
        return null
    }

    /** "7:15 PM", "15 Nov 7:15 PM", etc. → epoch ms (today if no date). 0 if no time found. */
    fun parseWhen(s: String): Long {
        val t = TIME.find(s) ?: return 0
        var hh = t.groupValues[1].toInt() % 12
        if (t.groupValues[3].equals("pm", true)) hh += 12
        val cal = Calendar.getInstance()
        DATE.find(s)?.let { d ->
            val months = listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
            cal.set(Calendar.DAY_OF_MONTH, d.groupValues[1].toInt())
            cal.set(Calendar.MONTH, months.indexOf(d.groupValues[2].lowercase(Locale.ROOT).take(3)))
            d.groupValues[3].toIntOrNull()?.let { cal.set(Calendar.YEAR, it) }
        }
        cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, t.groupValues[2].toInt()); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun timeLabel(ms: Long): String {
        if (ms == 0L) return ""
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        val h = c.get(Calendar.HOUR); val m = c.get(Calendar.MINUTE)
        val hm = "${if (h == 0) 12 else h}:${m.toString().padStart(2, '0')} ${if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"}"
        return if (c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "Today $hm"
        else "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1} $hm"
    }
}
