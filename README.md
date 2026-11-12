# Grab v0.3 (Android): a context assistant in your thumb zone

Grab watches what is happening on your phone (notifications, where you are, the time, incoming calls)
and puts the one thing you need **at the bottom of the screen**, so you stop jumping between apps and
reaching for the top of the screen.

| Moment | Before | With Grab |
|---|---|---|
| Metro gate | unlock → metro app → ticket → tap QR → brightness up (4–5 steps) | walk into the station → QR pops up at full brightness (0 taps) |
| Pay a shop | open UPI app → scan → type amount → PIN (4 screens) | hold the pill → camera + your usual amount on one screen → PIN in your UPI app |
| Rapido OTP | pull down notifications from the top → read → remember | pop-up at the bottom: "2 min away · 4 4 7 1" + Copy |
| Spam call | phone rings, you look, you decline | ringtone muted + pop-up: **Decline · Always block · It's OK** |
| "Send me your PAN" | open WhatsApp → attach → Files → find PAN → send | pop-up: **Send PAN card** → WhatsApp opens with it attached |
| Meeting in 2 min | open calendar → find event → find link | pop-up: **Join** |

Everything is processed **on the phone**. The only exception is the optional AI (off by default, see below).

## The pieces

**Pill** (bottom of every screen)
- tap: the most urgent card (OTP, ride, ticket, a request) · **hold: Scan & pay** · **swipe up: quick panel** · swipe down: hide that card · drag sideways: move

**Pop-ups** slide up above the pill when the assistant thinks something needs you, then go away by themselves (or swipe down).

**Home screen**: two tabs, **Now** (ranked for this moment) and **Wallet** (tickets, documents, codes), with a bottom bar: **Scan & pay · Metro · Docs**.

## How it understands context

| Signal | What Grab does with it |
|---|---|
| Notifications | OTPs, ride and order status, tickets (`Parser.kt`); "send me your PAN / resume…" and "can you confirm?" in WhatsApp, Gmail, Outlook, Telegram, Slack, SMS (`Asks` in `Docs.kt`) |
| Where you are | geofences around places it knows (`Places.kt`). **Metro stations are learned by themselves:** open your metro ticket at a station once, and next time the QR pops up as you walk in. Spots near Home/Work are never learned as stations |
| Habits | every time you open a ticket, pay or send a document it counts (weekday/weekend × hour, slowly fading). At that hour, those things rank higher in the pill, the panel and the hub (`Habits.kt`). The hub shows what it has learned ("Metro ticket: usually around 8 AM on weekdays") |
| Incoming calls | as the phone's Caller-ID & spam app, Grab sees calls from numbers **not in your contacts** (`SpamScreener.kt`): your blocked numbers are rejected; 140-series telemarketing, spoofed caller ID, hidden and unexpected international numbers are silenced with a Decline pop-up |
| Calendar | one-tap Join 3 minutes before a meeting with a Meet/Zoom/Teams link |
| Clock | movie ticket pops up 25 minutes before the show; the pill re-ranks every minute |

`Assistant.kt` is the one place that decides what deserves a pop-up; `CardStore.score` decides what the pill shows.

## Payments: what is and isn't possible
Grab puts the camera and the amount on **one screen** and hands the payment to your UPI app with payee and amount filled
(the original QR is passed through untouched, so merchant QRs keep working). The UPI app you pick the first time is remembered.
**Grab can't approve the payment itself:** under NPCI rules only your bank's UPI app can authorise it, with your UPI PIN.
To use your **fingerprint** instead of the PIN, turn on biometric UPI (or UPI Lite for small payments) in your UPI app, if it offers it.

## Documents
Wallet → Documents (or the **Docs** button): add your PAN, Aadhaar, resume, payslips, etc. once. They're copied into app-private
storage (not the gallery). When someone asks for one, the pop-up sends it straight into the app they asked in (WhatsApp, Gmail…):
you pick the chat and press send. If it isn't in the vault yet, the pop-up says **Find it**: pick the file once and Grab sends it
and keeps it for next time.

## Optional AI (off by default)
Setup → *Optional AI*. With your own Anthropic API key, messages the local rules can't decide ("can you mail me the signed copy?")
are sent to Claude, one message at a time (sender name, app, text, with runs of 4+ digits masked), only when they look like a question or request.
Claude answers whether it's a document request or needs a reply. OTPs, rides, tickets, calls and locations are never sent.
The key is stored encrypted on the phone. The default model is `claude-opus-5-5` and can be changed in the same dialog.
Note: the SDK for this adds ~55 MB to the APK.

## Setup (once)
1. Build: push to GitHub → Actions → "Build APK" → download `grab-pill-apk` (or open in Android Studio → ▶ Run).
2. **Android 13+:** Settings → Apps → Grab → ⋮ → *Allow restricted settings* (needed before notification access).
3. Open Grab → **Finish setup** → steps 1–7 → **Start the pill**.
   - Step 4 (location): choose **Allow all the time**, or the station pop-up only works with Grab open.
   - Step 5 (spam): pick Grab as *Caller ID & spam app*.
4. Optional: Setup → pick your **metro app** (opened at the station when no ticket is saved).

## Test in 5 minutes (Setup → Test)
1. **Fake Rapido arriving** → pop-up at the bottom with the OTP and Copy.
2. **Fake "send me your PAN"** → pop-up: *Find it* (first time) → pick a file → share sheet opens. Do it again: now it says *Send PAN card*.
3. **Fake "arrived at metro"** → metro QR pops up at full brightness.
4. **Fake spam call pop-up** → see the Decline / Always block / It's OK layout.
5. Hold the pill → Scan & pay opens with the camera; point at any UPI QR.

## Your tuning job (most important)
Real notification wording differs from app to app. For a week, collect real examples (Rapido, Swiggy, bank OTPs, WhatsApp requests)
and add them to `app/src/test/java/app/grab/pill/RulesTest.kt`; adjust the regexes in `Parser.kt` and `Docs.kt` until every one passes.
`gradle testDebugUnitTest` runs them (CI runs them on every push).

## Files
```
Assistant.kt     the decision maker: which moment deserves a pop-up, and its buttons (+ Live: exact "Open" targets)
PillService.kt   the pill (gestures) + pop-up overlay at the bottom, ticks every minute
Card.kt          Card model + CardStore (on-device file, expiry, context-aware ranking)
Habits.kt        Ctx (where you are now) + Habits (what you use when)
Places.kt        known places as geofences, self-learning metro stations, GeofenceReceiver
Docs.kt          DocVault (your documents) + Asks (detect "send me your X" and send it)
DocsActivity.kt  the vault screen; "Find it" flow
SpamScreener.kt  call screening + SpamRules
ScanPayActivity.kt  live camera + amount on one screen → your UPI app
Brain.kt         optional Claude classifier for unclear messages (off by default)
Parser.kt, Importer.kt, GrabNotificationListener.kt   notifications / screenshots / PDFs → cards
HubActivity.kt   Now · Wallet tabs + thumb bar      PillActivity.kt  quick panel
CardActivity.kt  show a card at full brightness     SetupActivity.kt permissions, places, assistant settings, tests
```

## Next (v0.4)
- Auto-import new screenshots that contain a ticket QR.
- "Leave by 6:38" for movies and meetings from travel time.
- Learn which chat a document usually goes to.
- Encrypted storage (Room + SQLCipher).
