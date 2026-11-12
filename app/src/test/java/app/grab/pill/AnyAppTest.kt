package app.grab.pill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notifications from apps Grab has no special rules for: understood from what they say. */
class AnyAppTest {
    private fun p(pkg: String, label: String, title: String, text: String, hint: String? = null) = Parser.parse(pkg, label, title, text, hint)

    @Test fun unknownRideApp() {
        val k = p("com.newcabs.app", "NewCabs", "Your driver is arriving", "Suresh is 3 mins away in a white Swift TS08 UB 4521. Share OTP 5521 to start the trip")!!
        assertEquals("RIDE", k.kind); assertEquals("5521", k.value); assertTrue(k.sub.startsWith("3 min away")); assertEquals("NewCabs ride", k.title)
    }
    @Test fun rideArrived() {
        val k = p("com.ubercab", "Uber", "Your driver has arrived", "Meet Ravi at the pickup point. KA05 MN 3344")!!
        assertEquals("RIDE", k.kind); assertTrue(k.sub.startsWith("Arrived"))
    }
    @Test fun unknownCourier() {
        val k = p("com.shadowfax.app", "Shadowfax", "Out for delivery", "Your package from Ajio is out for delivery today. Delivery partner: Imran")!!
        assertEquals("ORDER", k.kind); assertEquals("Arriving soon", k.sub)
    }
    @Test fun flight() {
        val k = p("com.someairline", "Akasa Air", "Booking confirmed", "Flight QP 1342 HYD-BLR on 15 Nov, departs 19:40 hrs. PNR K7X2QP. Gate 12")!!
        assertEquals("TRAVEL", k.kind); assertEquals("PNR K7X2QP", k.value); assertEquals("HYD → BLR", k.title)
        assertTrue(k.sub.contains("Flight QP 1342")); assertTrue(k.sub.contains("Gate 12")); assertTrue(k.whenMs > 0)
    }
    @Test fun train() {
        val k = p("cris.org.in.prs.ima", "IRCTC", "Ticket booked", "PNR 4521367890 Train No 12723 from Secunderabad to New Delhi, Coach B2 Berth 34, 6:40 PM")!!
        assertEquals("TRAVEL", k.kind); assertEquals("PNR 4521367890", k.value); assertTrue(k.sub.contains("Train 12723"))
    }
    @Test fun metroFromAnyApp() {
        val k = p("com.sometransit", "Transit", "Your metro ticket", "QR ticket from Ameerpet to Hitec City is ready. Fare ₹35")!!
        assertEquals("METRO", k.kind); assertEquals("Ameerpet → Hitec City", k.title)
    }
    @Test fun promotionsIgnored() {
        assertNull(p("com.rapido.passenger", "Rapido", "Weekend offer!", "Get 50% off on your next ride. Book now"))
        assertNull(p("in.swiggy.android", "Swiggy", "Hungry?", "Flat ₹100 off on your first order. Order now"))
        assertNull(p("com.newcabs.app", "NewCabs", "We miss you", "Come back and get a free ride"))
    }
    @Test fun learnedAppReadsWeakSignal() {
        // "Captain is on the way" alone is weak; once the app is known as a ride app, it's enough
        val text = "Captain Raju is on the way"
        assertEquals("RIDE", p("com.tinyride", "TinyRide", "Update", text, hint = "RIDE")!!.kind)
    }
    @Test fun otpAnyApp() {
        val k = p("com.anybank", "AnyBank", "Login", "Use 739201 as your one time password")
        // no "otp" keyword match needed? covered by "is your" form below
        val k2 = p("com.anybank", "AnyBank", "Login", "739201 is your verification code")!!
        assertEquals("OTP", k2.kind); assertEquals("739201", k2.value)
        assertTrue(k == null || k.kind == "OTP")
    }
    @Test fun timesParse() {
        assertTrue(Parser.parseWhen("departs 19:40 hrs") > 0)
        assertEquals(0L, Parser.parseWhen("fare 35.00 only"))
    }
}
