package app.grab.pill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The always-on pill, bottom-centre in the thumb zone, and the pop-ups that slide up above it.
 *
 * Pill:  tap = show the most urgent card (or the quick panel when idle)
 *        long-press = Scan & pay      swipe up = quick panel
 *        swipe down = hide this card  drag sideways = move
 * Pop-up: what the assistant thinks you need right now (OTP, rider arriving, metro QR, spam call,
 *         a document someone asked for). Swipe down or wait and it goes away.
 */
class PillService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var icon: TextView
    private lateinit var title: TextView
    private lateinit var value: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { main.post { render() } }
    private val popListener: (Moment) -> Unit = { m -> main.post { showPopup(m) } }
    private var shown: Card? = null
    private val hidden = mutableSetOf<String>()
    private var popup: View? = null
    private var popupKey: String? = null
    private val closePopup = Runnable { hidePopup() }
    private val ticker = object : Runnable {
        override fun run() { render(); try { Assistant.tick(this@PillService) } catch (e: Exception) { }; main.postDelayed(this, 60_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotice()
        Habits.init(this); Ctx.init(this)
        wm = getSystemService(WindowManager::class.java)
        buildPill()
        CardBus.listen(listener)
        Assistant.listen(popListener)
        main.post(ticker)
        Places.register(this)
    }

    override fun onDestroy() {
        CardBus.unlisten(listener); Assistant.unlisten(popListener); main.removeCallbacks(ticker)
        hidePopup()
        try { wm.removeView(root) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundWithNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pill", "Grab pill", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HubActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "pill").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Grab is ready").setContentText("Codes, tickets and rides in the pill").setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buzz() = try {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    } catch (e: Exception) { }

    // ------------------------------------------------------------------ pill

    private fun buildPill() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xE61C1C1E.toInt()) }
            elevation = dp(10).toFloat()
        }
        icon = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(8), 0) }
        title = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; maxWidth = dp(170) }
        value = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 17f; typeface = Typeface.MONOSPACE; setPadding(dp(10), 0, 0, 0) }
        root.addView(icon); root.addView(title); root.addView(value)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(72); x = (Store.get(this@PillService, "pill_x")?.toIntOrNull() ?: 0) }

        var x0 = 0f; var y0 = 0f; var sx = 0; var dragging = false; var longFired = false
        val longPress = Runnable { longFired = true; buzz(); open(Intent(this, ScanPayActivity::class.java)) }
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; sx = lp.x; dragging = false; longFired = false; main.postDelayed(longPress, 550); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) main.removeCallbacks(longPress)
                    if (abs(dx) > dp(16) && abs(dx) > abs(dy)) { dragging = true; lp.x = sx + dx.toInt(); wm.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    when {
                        longFired -> {}
                        dragging -> Store.put(this, "pill_x", lp.x.toString())
                        dy < -dp(40) && abs(dx) < dp(60) -> open(Intent(this, PillActivity::class.java))          // swipe up → panel
                        dy > dp(40) && abs(dx) < dp(60) -> { shown?.let { hidden.add(it.id) }; render() }       // swipe down → hide card
                        abs(dx) < dp(10) && abs(dy) < dp(10) -> onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); true }
                else -> false
            }
        }
        wm.addView(root, lp)
        render()
    }

    private fun open(i: Intent) = startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun onTap() {
        val k = shown
        if (k?.kind == "ASK") { Asks.send(this, k); return }
        open(if (k != null) Intent(this, CardActivity::class.java).putExtra("id", k.id) else Intent(this, PillActivity::class.java))
    }

    private fun render() {
        val k = CardStore.all(this).filter { it.id !in hidden }.maxByOrNull { CardStore.score(it) }?.takeIf { CardStore.score(it) >= 40 }
        shown = k
        val bg = root.background as GradientDrawable
        if (k == null) {
            icon.text = "G"
            title.text = Ctx.place?.let { "at ${it.name}" } ?: CalendarHelper.next(this)?.let { "next: ${it.title.take(16)} ${it.timeLabel}" } ?: "hold to pay · swipe up"
            value.text = ""
            bg.setColor(0xE61C1C1E.toInt()); return
        }
        icon.text = CardViews.ICON[k.kind] ?: "•"
        title.text = when (k.kind) {
            "MOVIE", "METRO" -> "${k.title} · tap to show"
            "ASK" -> "${k.x().optString("sender")} · tap to send"
            else -> "${k.title} · ${k.sub}"
        }
        value.text = when {
            k.kind == "OTP" || (k.kind == "RIDE" && k.value.all { it.isDigit() }) -> k.value.chunked(1).joinToString(" ")
            k.kind == "MOVIE" -> Parser.timeLabel(k.whenMs).substringAfter(" ")
            k.kind == "ASK" -> ""
            else -> k.value
        }
        bg.setColor((CardViews.COLOR[k.kind] ?: 0xFF1C1C1E.toInt()) and 0x00FFFFFF or (0xF2 shl 24))
    }

    // ------------------------------------------------------------------ pop-up

    private fun showPopup(m: Moment) {
        hidePopup()
        val color = CardViews.COLOR[m.kind] ?: 0xFF1C1C1E.toInt()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dp(2), color) }
            elevation = dp(16).toFloat()
        }
        // grab handle
        sheet.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0x33000000) } },
            LinearLayout.LayoutParams(dp(36), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = CardViews.ICON[m.kind] ?: "•"; textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        words.addView(TextView(this).apply { text = m.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF000000.toInt()); maxLines = 2 })
        if (m.sub.isNotBlank()) words.addView(TextView(this).apply { text = m.sub; textSize = 13f; setTextColor(0x99000000.toInt()); maxLines = 2 })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        sheet.addView(head)

        val qr = if (m.qr.isNotEmpty()) Qr.encode(m.qr, 720) else null
        when {
            qr != null -> sheet.addView(ImageView(this).apply { setImageBitmap(qr); adjustViewBounds = true },
                LinearLayout.LayoutParams(dp(230), dp(230)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
            m.img.isNotEmpty() -> BitmapFactory.decodeFile(m.img)?.let { b ->
                sheet.addView(ImageView(this).apply { setImageBitmap(b); adjustViewBounds = true }, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(10) })
            }
        }
        if (m.big.isNotEmpty()) sheet.addView(TextView(this).apply {
            text = if (m.big.all { it.isDigit() }) m.big.chunked(1).joinToString(" ") else m.big
            textSize = 34f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setTextColor(0xFF000000.toInt()); setPadding(0, dp(8), 0, dp(4))
        })
        if (m.acts.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            m.acts.take(3).forEachIndexed { i, a ->
                row.addView(Button(this).apply {
                    text = a.label; isAllCaps = false; textSize = 15f; maxLines = 1
                    setTextColor(if (a.primary) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (a.primary) color else 0xFFF2F2F7.toInt()) }
                    stateListAnimator = null
                    setOnClickListener { hidePopup(); try { a.run(this@PillService) } catch (e: Exception) { } }
                }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            sheet.addView(row)
        }
        // tap the body = open the card in full; swipe down = dismiss
        var y0 = 0f
        sheet.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { v.translationY = (e.rawY - y0).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - y0
                    if (dy > dp(50)) hidePopup()
                    else {
                        v.animate().translationY(0f).setDuration(120).start()
                        if (abs(dy) < dp(8) && m.cardId.isNotEmpty()) { hidePopup(); open(Intent(this, CardActivity::class.java).putExtra("id", m.cardId)) }
                    }
                    true
                }
                else -> false
            }
        }

        val plp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (m.bright) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM; y = dp(16)
            if (m.bright) screenBrightness = 1f  // gate scanners read a bright QR first time
            windowAnimations = android.R.style.Animation_InputMethod // slides up from the bottom
        }
        val holder = LinearLayout(this).apply { setPadding(dp(10), 0, dp(10), 0); addView(sheet, LinearLayout.LayoutParams(-1, -2)) }
        try { wm.addView(holder, plp) } catch (e: Exception) { return }
        popup = holder; popupKey = m.key
        root.visibility = View.GONE
        buzz()
        main.postDelayed(closePopup, m.ttlMs)
    }

    private fun hidePopup() {
        main.removeCallbacks(closePopup)
        popup?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popup = null; popupKey = null
        if (::root.isInitialized) root.visibility = View.VISIBLE
    }
}
