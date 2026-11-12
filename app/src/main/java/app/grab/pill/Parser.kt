package app.grab.pill

import java.util.Calendar
import java.util.Locale

/**
 * Turns ANY app's notification (or text read from a ticket image) into a Card, on the phone.
 *
 * It doesn't need a list of apps: every notification is scored on what it says (ride words, delivery
 * words, ticket words, an OTP…), and the best match wins. Known apps and what this app sent before
 * (AppMemory) only nudge the score, so a new ride or delivery app works on day one and gets better
 * the more it's seen. Promotions ("50% off your next ride") are ignored.
 */
object Parser {
    private val OTP = Regex("""(?i)\b(otp|code|pin|passcode|verification)\b[^0-9]{0,30}?(\d{4,8})\b""")
    private val OTP2 = Regex("""\b(\d{4,8})\b[^0-9]{0,20}(?i:is your|is the|is ur)""")
    private val AMT = Regex("""(?i)(?:₹|rs\.?|inr)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val PLATE = Regex("""\b([A-Z]{2}\s?\d{1,2}\s?[A-Z]{1,3}\s?\d{3,4})\b""")
    private val ETA = Regex("""(?i)(\d{1,2})\s?(min|mins|minutes)\b""")
    private val TIME = Regex("""(?i)\b(\d{1,2})[:.](\d{2})\s?(am|pm|hrs)?\b""")
    private val DATE = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?[\s-]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*,?[\s-]?(\d{4})?\b""")
    private val SEAT = Regex("""(?i)\b(?:seats?|seat no\.?)\s*[:\-]?\s*([A-Z]{1,2}\s?\d{1,3}(?:\s?,\s?[A-Z]{0,2}\s?\d{1,3})*)""")
    private val SCREEN = Regex("""(?i)\b(audi(?:torium)?|screen)\s*[:\-]?\s*(\d{1,2})""")
    private val PNR = Regex("""(?i)\bpnr\s*(?:no\.?|number|:)?\s*[:\-]?\s*([A-Z0-9]{6,10})\b""")
    private val FLIGHT_NO = Regex("""\b(6E|AI|UK|SG|QP|IX|G8|I5|EK|QR|EY|SQ|LH|BA)\s?-?(\d{2,4})\b""")
    private val TRAIN_NO = Regex("""(?i)\btrain\s*(?:no\.?)?\s*[:\-]?\s*(\d{5})\b""")
    private val COACH = Regex("""(?i)\b(?:coach|berth)\s*[:\-]?\s*([A-Z]{0,2}\d{1,3}(?:\s*[/,]\s*\d{1,3})?)""")
    private val GATE = Regex("""(?i)\bgate\s*[:\-]?\s*([A-Z]?\d{1,3}[A-Z]?)\b""")

    // ---- what each kind of notification tends to say
    private val RIDE_WORDS = Regex("""(?i)\b(captain|driver|rider|cab|auto|bike taxi|pick-?up|arriving|arrived|has arrived|is here|on (the|his|her) way to you|your ride|your trip|trip (started|ended|completed)|ride (otp|pin|started|completed|ended)|start (the )?ride|reached your (pickup|location)|vehicle)\b""")
    private val ORDER_WORDS = Regex("""(?i)\b(order|delivery|delivered|out for delivery|delivery (partner|executive|agent|boy)|shipped|dispatched|parcel|package|shipment|courier|picked up|being prepared|preparing|packed|on its way|arriving (today|soon|in))\b""")
    private val TICKET_WORDS = Regex("""(?i)\b(booking (confirmed|id|successful)|booked|e-?ticket|ticket|pnr|seats?|coach|berth|boarding|gate|flight|departs?|departure|show ?time|audi|screen \d|journey|check-?in|platform|bus no|boarding point)\b""")
    private val PROMO = Regex("""(?i)(\d{1,2}\s?% off|\bflat ₹?\d+ off|\bcashback\b|\bcoupon\b|\bpromo\b|\bdiscount\b|\boffer\b|\bsale\b|\bdeal(s)?\b|\bwin\b|\bhurry\b|\blimited time\b|\bget ₹?\d+ off|\bfree delivery on\b|\border now\b|\bbook now\b|\btry (our|the) new\b|\bmissing you\b|\bcome back\b)""")
    private val STATUS = Regex("""(?i)(arriv|reached|on the way|out for delivery|delivered|picked up|confirmed|booked|started|completed|ended|your otp|is here|departs|boarding)""")

    private val RIDE_APPS = mapOf(
        "com.rapido.passenger" to "Rapido", "com.ubercab" to "Uber", "com.olacabs.customer" to "Ola", "in.juspay.nammayatri" to "Namma Yatri",
        "com.blusmart.rider" to "BluSmart", "com.ubercab.uberlite" to "Uber", "com.indriver" to "inDrive", "sinet.startup.inDriver" to "inDrive"
    )
    private val ORDER_APPS = mapOf(
        "in.swiggy.android" to "Swiggy", "com.application.zomato" to "Zomato", "com.zeptoconsumerapp" to "Zepto", "com.grofers.customerapp" to "Blinkit",
        "in.amazon.mShop.android.shopping" to "Amazon", "com.flipkart.android" to "Flipkart", "com.bigbasket.mobileapp" to "BigBasket",
        "com.myntra.android" to "Myntra", "com.dunzo.user" to "Dunzo", "com.meesho.supply" to "Meesho", "com.delhivery.consumer" to "Delhivery",
        "com.jio.mart" to "JioMart", "com.nykaa.app" to "Nykaa"
    )
    private val TICKET_APPS = mapOf(
        "com.bt.bms" to "BookMyShow", "com.application.zomato.district" to "District", "cris.org.in.prs.ima" to "IRCTC",
        "in.redbus.android" to "redBus", "com.makemytrip" to "MakeMyTrip", "com.ixigo" to "ixigo", "com.goibibo" to "Goibibo",
        "in.goindigo.android" to "IndiGo", "com.cleartrip.android" to "Cleartrip", "com.abhibus.mobile" to "AbhiBus"
    )

    data class Scores(val ride: Int, val order: Int, val ticket: Int)

    /** How ride-like / delivery-like / ticket-like is this text? (Exposed for AppMemory and tests.) */
    fun scores(pkg: String, s: String, hint: String? = null): Scores {
        fun n(r: Regex) = r.findAll(s).count().coerceAtMost(3)
        var ride = n(RIDE_WORDS) + (if (PLATE.containsMatchIn(s)) 2 else 0) + (if (Regex("(?i)\\b(ride|trip)\\b").containsMatchIn(s) && OTP.containsMatchIn(s)) 2 else 0)
        var order = n(ORDER_WORDS)
        var ticket = n(TICKET_WORDS) + (if (PNR.containsMatchIn(s)) 2 else 0) + (if (FLIGHT_NO.containsMatchIn(s) && Regex("(?i)flight|pnr|boarding").containsMatchIn(s)) 2 else 0)
        if (isMetro(s) || isMovie(s)) ticket += 1
        if (pkg in RIDE_APPS) ride += 3
        if (pkg in ORDER_APPS) order += 3
        if (pkg in TICKET_APPS) ticket += 3
        when (hint) { "RIDE" -> ride += 2; "ORDER" -> order += 2; "TICKET", "TRAVEL", "MOVIE", "METRO" -> ticket += 2 }
        return Scores(ride, order, ticket)
    }

    fun parse(pkg: String, appLabel: String, title: String, text: String, hint: String? = null): Card? {
        val s = "$title $text".replace(Regex("\\s+"), " ").trim()
        if (s.isBlank()) return null
        val otp = OTP.find(s)?.groupValues?.get(2) ?: OTP2.find(s)?.groupValues?.get(1)
        // marketing pushes from the same apps ("Flat ₹100 off your next ride") are not events
        if (PROMO.containsMatchIn(s) && otp == null && !STATUS.containsMatchIn(s.replace(PROMO, ""))) return null

        val sc = scores(pkg, s, hint)
        val app = RIDE_APPS[pkg] ?: ORDER_APPS[pkg] ?: TICKET_APPS[pkg] ?: appLabel.ifBlank { "App" }
        val best = listOf("RIDE" to sc.ride, "ORDER" to sc.order, "TICKET" to sc.ticket).maxByOrNull { it.second }!!

        if (best.second >= 2) when (best.first) {
            "RIDE" -> return ride(pkg, app, title, s, otp)
            "ORDER" -> statusLine(s)?.let { return Card(Card.newId(), "ORDER", "$app order", it, value = otp ?: "", src = app, key = "order:$pkg") }
            "TICKET" -> {
                if (isMovie(s) || pkg == "com.bt.bms") return movieFrom(s, app)
                if (isMetro(s)) return metroFrom(s, app)
                travelFrom(s, app)?.let { return it }
                if (Regex("(?i)booked|confirmed|booking id|e-?ticket").containsMatchIn(s))
                    return Card(Card.newId(), "TICKET", title.take(40).ifBlank { "$app booking" }, timeLabel(parseWhen(s)).ifEmpty { "$app · booking confirmed" },
                        whenMs = parseWhen(s), src = app, key = "ticket:$pkg:${title.take(20)}")
            }
        }
        // Plain OTPs (bank, login, delivery) from any app
        if (otp != null) return Card(Card.newId(), "OTP", appLabel.ifBlank { "Code" }, title.take(60), value = otp, src = appLabel)
        return null
    }

    private fun ride(pkg: String, app: String, title: String, s: String, otp: String?): Card {
        val plate = PLATE.find(s)?.groupValues?.get(1) ?: ""
        val eta = ETA.find(s)?.groupValues?.get(1)
        val fare = AMT.find(s)?.groupValues?.get(1)
        val ended = Regex("(?i)complete|ended|reached your destination|pay |rate your").containsMatchIn(s)
        val arrived = Regex("(?i)arrived|is here|has reached|waiting for you|reached your pickup").containsMatchIn(s)
        val sub = when {
            ended && fare != null -> "Trip ended · fare ₹$fare"
            ended -> "Trip ended"
            arrived -> "Arrived · waiting for you" + if (plate.isNotEmpty()) " · $plate" else ""
            eta != null -> "$eta min away" + if (plate.isNotEmpty()) " · $plate" else ""
            plate.isNotEmpty() -> "On the way · $plate"
            else -> title.take(50)
        }
        return Card(Card.newId(), "RIDE", "$app ride", sub, value = otp ?: (if (ended && fare != null) "₹$fare" else ""), src = app, key = "ride:$pkg")
    }

    /** Flights, trains and buses: PNR, number, seat/coach, gate, time. */
    fun travelFrom(s: String, src: String): Card? {
        val pnr = PNR.find(s)?.groupValues?.get(1)
        val flight = FLIGHT_NO.find(s)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        val train = TRAIN_NO.find(s)?.groupValues?.get(1)
        val mode = when {
            flight != null && Regex("(?i)flight|boarding|gate|pnr").containsMatchIn(s) -> "Flight"
            train != null || Regex("(?i)\\b(irctc|train|coach|berth)\\b").containsMatchIn(s) -> "Train"
            Regex("(?i)\\b(bus|redbus|abhibus|boarding point)\\b").containsMatchIn(s) -> "Bus"
            else -> return null
        }
        if (pnr == null && flight == null && train == null) return null
        val whenMs = parseWhen(s)
        val parts = listOfNotNull(
            flight?.let { "$mode $it" } ?: train?.let { "Train $it" } ?: mode,
            timeLabel(whenMs).ifEmpty { null },
            GATE.find(s)?.let { "Gate ${it.groupValues[1]}" },
            COACH.find(s)?.let { "Coach/berth ${it.groupValues[1]}" },
            SEAT.find(s)?.let { "Seat ${it.groupValues[1].replace(" ", "")}" },
        )
        val route = Regex("""(?i)\b([A-Z]{3})\s*(?:-|→|to)\s*([A-Z]{3})\b""").find(s)?.let { "${it.groupValues[1].uppercase()} → ${it.groupValues[2].uppercase()}" }
            ?: Regex("""(?i)\bfrom\s+([A-Za-z .]{3,24}?)\s+to\s+([A-Za-z .]{3,24}?)(?:[,.]|\s+on\b|\s+at\b|$)""").find(s)?.let { "${it.groupValues[1].trim()} → ${it.groupValues[2].trim()}" }
        return Card(Card.newId(), "TRAVEL", route ?: "$mode · $src", parts.joinToString(" · "), value = pnr?.let { "PNR $it" } ?: "",
            whenMs = whenMs, src = src, key = "travel:${pnr ?: flight ?: train}")
    }

    fun isMovie(s: String) = Regex("(?i)\\b(audi|screen \\d|showtime|show time|pvr|inox|cinepolis|amb cinemas|prasads|bookmyshow|movie)\\b").containsMatchIn(s)
    fun isMetro(s: String) = Regex("(?i)\\b(metro|hmrl|l&t metro|dmrc|bmrcl|namma metro|mmrc|cmrl|kmrl)\\b").containsMatchIn(s)

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
        val stations = Regex("(?i)\\bfrom\\s*[:\\-]?\\s*([A-Za-z ]{3,30}?)\\s+to\\s*[:\\-]?\\s*([A-Za-z ]{3,30}?)(?=\\s+(?:is|was|for|on|at|fare|valid|via)\\b|[,.\\n|]|\\s*$)").find(s)
        val route = stations?.let { "${it.groupValues[1].trim()} → ${it.groupValues[2].trim()}" } ?: "Metro journey"
        val fare = AMT.find(s)?.groupValues?.get(1)?.let { "₹$it" } ?: ""
        return Card(Card.newId(), "METRO", route.take(40), "Metro ticket · works offline", value = fare, qr = qr, img = img, src = src.ifBlank { "Metro" })
    }

    private fun statusLine(s: String): String? {
        val pairs = listOf(
            "(?i)delivered" to "Delivered ✓",
            "(?i)out for delivery|on the way|on its way|arriving|nearby|reached" to "Arriving soon",
            "(?i)picked up" to "Picked up · on the way",
            "(?i)shipped|dispatched|in transit" to "Shipped",
            "(?i)being prepared|preparing|cooking|packed|packing" to "Being prepared",
            "(?i)confirmed|placed|accepted" to "Order confirmed"
        )
        for ((rx, label) in pairs) if (Regex(rx).containsMatchIn(s)) {
            val eta = ETA.find(s)?.groupValues?.get(1)
            return label + if (eta != null && label != "Delivered ✓") " · $eta min" else ""
        }
        return null
    }

    /** "7:15 PM", "15 Nov 7:15 PM", "19:40 hrs" → epoch ms (today if no date). 0 if no time found. */
    fun parseWhen(s: String): Long {
        val t = TIME.findAll(s).firstOrNull {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            m <= 59 && ((it.groupValues[3].isNotEmpty() && h in 1..12) || h in 13..23 || (it.groupValues[3].equals("hrs", true) && h <= 23))
        } ?: return 0
        var hh = t.groupValues[1].toInt()
        val ampm = t.groupValues[3].lowercase(Locale.ROOT)
        if (ampm == "am" || ampm == "pm") { hh %= 12; if (ampm == "pm") hh += 12 }
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
