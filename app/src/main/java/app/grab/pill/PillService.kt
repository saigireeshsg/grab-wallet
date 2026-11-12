package app.grab.pill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The always-on pill, bottom-centre in the thumb zone.
 * Shows the single most urgent card (OTP, ride, order, ticket near show time).
 * Tap = open that card (or the panel if idle). Swipe down = hide this card from the pill. Drag = move.
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
    private var shown: Card? = null
    private val hidden = mutableSetOf<String>()
    private val ticker = object : Runnable { override fun run() { render(); main.postDelayed(this, 60_000) } } // re-rank every minute

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotice()
        wm = getSystemService(WindowManager::class.java)
        buildPill()
        CardBus.listen(listener)
        main.post(ticker)
    }

    override fun onDestroy() {
        CardBus.unlisten(listener); main.removeCallbacks(ticker)
        try { wm.removeView(root) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundWithNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pill", "Grab pill", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HubActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "pill").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Grab is ready").setContentText("Tickets, codes and status in the pill").setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(72) }

        var dx0 = 0f; var dy0 = 0f; var sx = 0; var sy = 0; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; sx = lp.x; sy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dx0; val dy = e.rawY - dy0
                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) { moved = true; lp.x = sx + dx.toInt(); lp.y = sy - dy.toInt(); wm.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - dy0
                    if (!moved) onTap()
                    else if (dy > dp(60) && abs(e.rawX - dx0) < dp(50)) { // swipe down = hide this card from the pill
                        shown?.let { hidden.add(it.id) }; lp.x = sx; lp.y = sy; wm.updateViewLayout(root, lp); render()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(root, lp)
        render()
    }

    private fun onTap() {
        val k = shown
        val i = if (k != null) Intent(this, CardActivity::class.java).putExtra("id", k.id) else Intent(this, PillActivity::class.java)
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun render() {
        val k = CardStore.all(this).filter { it.id !in hidden }.maxByOrNull { CardStore.score(it) }?.takeIf { CardStore.score(it) >= 40 }
        shown = k
        val bg = root.background as GradientDrawable
        if (k == null) {
            icon.text = "G"; title.text = CalendarHelper.next(this)?.let { "next: ${it.title.take(16)} ${it.timeLabel}" } ?: "all good"; value.text = ""
            bg.setColor(0xE61C1C1E.toInt()); return
        }
        icon.text = CardViews.ICON[k.kind] ?: "•"
        title.text = if (k.kind == "MOVIE" || k.kind == "METRO") "${k.title} · tap to show" else "${k.title} · ${k.sub}"
        value.text = when {
            k.kind == "OTP" || (k.kind == "RIDE" && k.value.all { it.isDigit() }) -> k.value.chunked(1).joinToString(" ")
            k.kind == "MOVIE" -> Parser.timeLabel(k.whenMs).substringAfter(" ")
            else -> k.value
        }
        bg.setColor((CardViews.COLOR[k.kind] ?: 0xFF1C1C1E.toInt()) and 0x00FFFFFF or (0xF2 shl 24))
    }
}
