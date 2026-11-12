# Grab Wallet v0.2 (Android): tickets, OTPs and live status in one place

**What it does**
- **Hub** (the app's home screen): **Now** (the single most urgent thing) · **Tickets** (metro, movie, other) · **Live status** (rides, orders) · **Codes** (OTPs, deleted after 15 min).
- **Pill** (floats at the bottom of every screen): shows the one card that matters right now. Tap = show it at full brightness. Swipe down = hide that card from the pill. Drag = move the pill.
- Everything is read and stored **on the phone**. Nothing is uploaded.

## Where each card comes from

| Card | How it gets in | Expires |
|---|---|---|
| OTP | notification from any app (bank, login, delivery) | 15 min |
| Ride (Rapido, Uber, Ola, Namma Yatri) | that app's notifications · newest status replaces older | 2 h |
| Order (Swiggy, Zomato, Zepto, Blinkit, Amazon, Flipkart, BigBasket) | that app's notifications · one live card per app | 6 h |
| Movie ticket | BookMyShow notification, **or** screenshot/PDF shared into Grab | 4 h after showtime |
| Metro ticket | screenshot of the metro QR shared into Grab, or "Add a ticket" | end of day |

For screenshots and PDFs, ML Kit runs **on the phone**: it reads the QR (Grab re-draws a sharp copy) and the text (movie name, show time, seat, audi, stations).

## How the pill decides what to show (`CardStore.score`)
OTP under 10 min (100) > live ride (92) > movie starting within 90 min (88) > order arriving (85) > metro during commute hours (75) > everything else. It re-ranks every minute, so a movie ticket rises by itself as the show gets close.

## Build
1. Unzip → Android Studio → Open `grab-wallet` → wait for sync (first time 10–15 min).
2. Plug in the phone (USB debugging on) → ▶ Run.
   No laptop? Push the folder to GitHub → Actions → "Build APK" → download the artifact.
3. **Android 13+:** Settings → Apps → Grab → ⋮ → *Allow restricted settings* before notification access.
4. Open Grab → **Finish setup** → steps 1–4 → **Start the pill**.

## Test in 5 minutes
1. Setup → **Add a fake movie (in 1 hour)**: the pill turns purple ("Kaantha · tap to show"); tap it for a crisp QR at full brightness.
2. Setup → **Add a fake OTP**: the pill shows 4 8 2 7 3 1; it disappears by itself after 15 min.
3. Book a real Rapido → the pill turns orange with the OTP → it updates as the status changes.
4. In your metro app, screenshot the QR → Share → **Save to Grab** → it appears under Tickets and in the pill during commute hours.
5. Share a BookMyShow ticket PDF to Grab → the movie name, time and seats are read off the ticket.

## Your tuning job (most important)
Real notification wording differs from app to app. For one week:
1. In Android Studio's **Logcat**, add a temporary `Log.d("GRAB", "$pkg | $title | $text")` in `GrabNotificationListener`.
2. Collect 20–30 real examples (Rapido, Swiggy, bank OTPs, BookMyShow, metro).
3. Adjust the regexes and keyword lists in `Parser.kt` until every one becomes the right card.

## Files
```
Card.kt          Card model + CardStore (on-device file, expiry, ranking) + CardBus
Parser.kt        notification/ticket text → Card (OTP, ride, order, movie, metro)
Importer.kt      screenshot/PDF → ML Kit QR + text → Card
GrabNotificationListener.kt   notifications → Parser → CardStore
HubActivity.kt   the one place (Now · Tickets · Live status · Codes) + add from screenshots/PDF
CardActivity.kt  show a card: full brightness, screen stays on, crisp QR, copy code, delete
PillService.kt   floating pill (most urgent card, re-ranks every minute)
PillActivity.kt  idle pill panel: top 3 cards + scan & pay + next meeting
SetupActivity.kt permissions, battery tip, test cards, delete everything
ScanPayActivity.kt, CalendarHelper.kt, Store.kt   from v0.1 (scan & pay, calendar, vendor memory)
```

## Next (v0.3)
- Auto-import new screenshots that contain a ticket QR (opt-in, using the photo picker or the screenshot observer).
- Movie: "leave by 6:38" from show time + travel time.
- Metro: show the ticket when you reach the station (geofence).
- Encrypted storage (Room + SQLCipher).
