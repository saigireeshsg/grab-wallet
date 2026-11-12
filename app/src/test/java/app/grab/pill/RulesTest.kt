package app.grab.pill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device rules that decide what becomes a pop-up. Add real messages from your phone here as you tune. */
class RulesTest {
    @Test fun whatsappPanRequest() {
        val a = Asks.detect("com.whatsapp", "Riya", "Hey can you send me your PAN card copy? Need it for the booking")
        assertNotNull(a); assertEquals("PAN", a!!.docType); assertEquals("Riya", a.sender)
    }
    @Test fun groupSenderName() {
        assertEquals("Riya", Asks.sender("com.whatsapp", "Flat 402: Riya"))
        assertEquals("Riya", Asks.sender("com.whatsapp", "Riya @ Flat 402"))
    }
    @Test fun resumeOverEmail() {
        val a = Asks.detect("com.google.android.gm", "Priya (HR)", "Next steps\nKindly share your updated resume and last 3 payslips")
        assertEquals("RESUME", a!!.docType); assertEquals("Next steps", a.subject)
    }
    @Test fun aadhaarSpellings() {
        assertEquals("AADHAAR", DocVault.guessType("pls send adhar"))
        assertEquals("AADHAAR", DocVault.guessType("Aadhar card front and back"))
        assertEquals("PHOTO", DocVault.guessType("one passport size photo"))
        assertEquals("PASSPORT", DocVault.guessType("scan of your passport"))
    }
    @Test fun genericDocument() {
        val a = Asks.detect("com.whatsapp", "Landlord", "Please send the signed documents today")
        assertNotNull(a); assertNull(a!!.docType); assertTrue(a.anyDoc)
    }
    @Test fun emailNeedsReply() {
        val a = Asks.detect("com.google.android.gm", "Arjun", "Q3 plan\nCan you confirm the numbers by EOD?")
        assertTrue(a!!.reply)
    }
    @Test fun chatterIsIgnored() {
        assertNull(Asks.detect("com.whatsapp", "Mom", "Reached home, call me later"))
        assertNull(Asks.detect("com.whatsapp", "Bank", "Your OTP is 482731. Please do not share"))
        assertNull(Asks.detect("com.rapido.passenger", "Captain arriving", "Please share OTP 4471"))
    }
    @Test fun spamNumbers() {
        assertEquals("1404567890", SpamRules.norm("+91 14045 67890"))
        assertEquals("9876543210", SpamRules.norm("09876543210"))
        assertEquals("98765 43210", SpamRules.pretty("+919876543210"))
        assertFalse(SpamRules.norm("+447700900123").startsWith("44")) // international stays with +
    }
    @Test fun otpStillParses() {
        val k = Parser.parse("com.snapwork.hdfc", "HDFC Bank", "OTP", "482731 is your OTP for login")
        assertEquals("OTP", k!!.kind); assertEquals("482731", k.value)
    }
}
