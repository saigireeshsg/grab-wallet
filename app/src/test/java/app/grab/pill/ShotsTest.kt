package app.grab.pill

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

private val now = System.currentTimeMillis()
private val ride = Card("r1", "RIDE", "Rapido ride", "2 min away · TS09 EK 2231", value = "4471", src = "Rapido")
private val otp = Card("o1", "OTP", "HDFC Bank", "Login code", value = "482731", src = "HDFC Bank")
private val metro = Card("m1", "METRO", "Ameerpet → Hitec City", "Metro ticket · works offline", value = "₹35", qr = "METRO-TEST-0001", src = "Metro")
private val movie = Card("mv", "MOVIE", "Kaantha", "Today 7:15 PM · PVR Inorbit · Audi 3", value = "H7, H8", qr = "BMS-1", whenMs = now + 3_600_000, src = "BookMyShow")
private val ask = Card("a1", "ASK", "Riya wants your PAN card", "on WhatsApp · PAN card is ready · tap to send", value = "PAN card", src = "WhatsApp", extra = "{\"sender\":\"Riya\"}")
private val order = Card("s1", "ORDER", "Swiggy order", "Arriving soon · 6 min", src = "Swiggy")

private object NoHub : HubUi.Actions {
    override fun card(k: Card) {}; override fun action(k: Card) {}; override fun settings() {}; override fun setup() {}
    override fun pay() {}; override fun metro() {}; override fun docs() {}; override fun tab(id: String) {}
    override fun addScreenshot() {}; override fun addPdf() {}
}
private object NoSettings : SettingsUi.Actions {
    override fun back() {}; override fun perm(id: String) {}; override fun rerunSetup() {}; override fun pill(on: Boolean) {}
    override fun otpAutocopy(on: Boolean) {}; override fun block140(on: Boolean) {}; override fun learnPlace(type: String) {}; override fun place(p: Place) {}
    override fun metroApp() {}; override fun upiApp() {}; override fun ai() {}; override fun demo(what: String) {}; override fun deleteData() {}; override fun forget() {}
}
private object NoDocs : DocsUi.Actions { override fun back() {}; override fun add() {}; override fun send(d: Doc) {}; override fun options(d: Doc) {} }

/** Shared screen builders so the light and dark tests render the same things. */
abstract class Screens {
    abstract val paparazzi: Paparazzi
    protected val c get() = paparazzi.context
    protected fun shot(name: String, v: () -> View) = paparazzi.snapshot(v(), name)
    private val none = {}

    /** Home = scrolling content + fixed bottom bar, like the activity. */
    protected fun hub(content: View, tab: String): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(D.p(c).bg)
        addView(ScrollView(c).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(HubUi.bottom(c, tab, NoHub), LinearLayout.LayoutParams(-1, -2))
    }
    private fun page(v: View) = ScrollView(c).apply { setBackgroundColor(D.p(c).bg); addView(v) }
    private fun overlay(v: View) = FrameLayout(c).apply {
        setBackgroundColor(0xFF8A94A6.toInt())
        addView(v, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply { setMargins(D.dp(c, 10), 0, D.dp(c, 10), D.dp(c, 16)) })
    }

    protected fun nowModel(hero: Card?, more: List<Card>, nudge: String? = null) = HubUi.Now("Good morning", "Thursday, 12 November",
        listOf("📍 Ameerpet", "📅 Standup · 10:30", "🛡 2 spam stopped"), nudge, hero, more,
        listOf("🚇" to "Metro ticket: usually around 8 AM on weekdays", "📍" to "Knows 3 metro stations", "📄" to "4 documents ready to send"), 42)

    fun all() {
        shot("01_welcome") { OnboardingUi.welcome(c, none) }
        shot("02_step_notif") { OnboardingUi.step(c, Perms.get(Perms.NOTIF), 1, 8, OnboardingUi.State(false, null, false, null), none, none, none, none, none) }
        shot("03_step_notif_restricted") { OnboardingUi.step(c, Perms.get(Perms.NOTIF), 1, 8, OnboardingUi.State(false, null, true, null), none, none, none, none, none) }
        shot("04_step_location_partial") { OnboardingUi.step(c, Perms.get(Perms.LOCATION), 4, 8, OnboardingUi.State(false, "Almost there: choose \"Allow all the time\"", false, null), none, none, none, none, none) }
        shot("05_step_granted") { OnboardingUi.step(c, Perms.get(Perms.OVERLAY), 2, 8, OnboardingUi.State(true, null, false, null), none, none, none, none, none) }
        shot("06_step_battery") { OnboardingUi.step(c, Perms.get(Perms.BATTERY), 6, 8, OnboardingUi.State(false, null, false, "Xiaomi"), none, none, none, none, none) }
        shot("07_done") { OnboardingUi.done(c, Perms.ALL.mapIndexed { i, p -> p to (i != 3) }, {}, none, none) }
        shot("10_hub_now") { hub(HubUi.now(c, nowModel(ride, listOf(ask, metro, otp)), NoHub), "now") }
        shot("13_hub_update") { hub(HubUi.now(c, nowModel(otp, listOf(metro)).copy(update = "Grab 0.4.12 is ready · takes a few seconds"), NoHub), "now") }
        shot("08_step_photos") { OnboardingUi.step(c, Perms.get(Perms.PHOTOS), 7, 8, OnboardingUi.State(false, null, false, null), none, none, none, none, none) }
        shot("11_hub_now_empty") { hub(HubUi.now(c, nowModel(null, emptyList(), "Grab can't see OTPs and rides yet"), NoHub), "now") }
        shot("12_hub_wallet") { hub(HubUi.wallet(c, HubUi.Wallet(listOf(metro, movie), listOf(ask), listOf(order), listOf(otp),
            listOf(Doc("d1", "PAN card", "PAN", "", "application/pdf", 0), Doc("d2", "Resume", "RESUME", "", "application/pdf", 0))), NoHub), "wallet") }
        shot("20_settings") { page(SettingsUi.build(c, SettingsUi.Model(Perms.ALL.mapIndexed { i, p -> SettingsUi.PermRow(p, i < 3, if (i == 3) "Almost there: choose \"Allow all the time\"" else null) },
            listOf(Place("p1", "Ameerpet", Places.METRO, 0.0, 0.0), Place("p2", "Home", Places.HOME, 0.0, 0.0)), true, true, false, null, "PhonePe", false, true, "0.4.12", "Up to date · new versions install silently"), NoSettings)) }
        shot("30_docs") { page(DocsUi.build(c, listOf(Doc("d1", "PAN card", "PAN", "", "application/pdf", 0), Doc("d2", "Aadhaar", "AADHAAR", "", "image/jpeg", 0), Doc("d3", "Resume", "RESUME", "", "application/pdf", 0)), null, NoDocs)) }
        shot("31_docs_empty") { page(DocsUi.build(c, emptyList(), "Riya wants your PAN card", NoDocs)) }
        shot("40_card_otp") { CardUi.build(c, otp, none, none, none, none) }
        shot("41_card_metro") { CardUi.build(c, metro, none, none, none, none) }
        shot("50_panel") { PanelUi.build(c, listOf(ride, metro, ask), "Grab · Ameerpet · 42 min saved", "Standup · 10:30", true,
            listOf(PanelUi.Quick("METRO", "🚇", "Metro") {}, PanelUi.Quick("PAY", "₹", "Pay") {}, PanelUi.Quick("ASK", "📄", "Docs") {}, PanelUi.Quick("HUB", "🗂", "All") {}), {}, none, {}, none).apply { setBackgroundColor(0x99000000.toInt()) } }
        shot("60_popup_ride") { overlay(PopupUi.sheet(c, Moment("k", "RIDE", "Rapido ride", "2 min away · TS09 EK 2231", big = "4471", acts = listOf(Act("Copy OTP", true) {}, Act("Open Rapido") {}))) {}) }
        shot("61_popup_metro") { overlay(PopupUi.sheet(c, Moment("k", "METRO", "Ameerpet · your ticket", "Ameerpet → Hitec City", qr = "METRO-TEST-0001", acts = listOf(Act("Full screen", true) {}, Act("Metro app") {}))) {}) }
        shot("62_popup_spam") { overlay(PopupUi.sheet(c, Moment("k", "CALL", "Likely spam · 14045 67890", "Telemarketing (140-series) · ringtone muted", acts = listOf(Act("Decline", true) {}, Act("Block") {}, Act("It's OK") {}))) {}) }
        shot("63_popup_ask") { overlay(PopupUi.sheet(c, Moment("k", "ASK", "Riya wants your PAN card", "on WhatsApp · PAN card is ready", acts = listOf(Act("Send PAN card", true) {}, Act("Open chat") {}))) {}) }
        shot("64_pills") { LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF8A94A6.toInt()); setPadding(40, 40, 40, 40)
            listOf(PopupUi.pill(c, "G", "hold to pay · swipe up", "", null), PopupUi.pill(c, "🛵", "Rapido ride · 2 min away", "4471", D.kind("RIDE")),
                PopupUi.pill(c, "🚇", "Ameerpet → Hitec · tap to show", "", D.kind("METRO")), PopupUi.pill(c, "📄", "Riya · tap to send", "", D.kind("ASK")))
                .forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 30 }) }
        } }
    }
}

class ShotsTest : Screens() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false), theme = "Theme.Grab", maxPercentDifference = 1.0)
    @Test fun light() = all()
}

class ShotsDarkTest : Screens() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false, nightMode = NightMode.NIGHT), theme = "Theme.Grab", maxPercentDifference = 1.0)
    @Test fun dark() = all()
}
