package app.grab.pill

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run setup. One permission per screen: what it unlocks, why, what Grab never does, one big button.
 * The button goes straight to Grab's own switch where Android allows it, and the screen moves on by
 * itself the moment the permission is granted. Optional steps can be skipped and turned on later.
 */
class OnboardingActivity : AppCompatActivity() {
    private val req = PermRequester(this) { refresh() }
    private lateinit var host: FrameLayout
    private var idx = 0                         // 0 = welcome, 1..N = permissions, N+1 = done
    private val tried = HashMap<String, Int>()  // how many times each was attempted (for the "restricted setting" helper)
    private var advancing = false
    private val perms = Perms.ALL
    private val doneIdx get() = perms.size + 1

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        D.window(this)
        idx = b?.getInt("idx") ?: intent.getIntExtra("start", 0)
        host = FrameLayout(this).apply { setBackgroundColor(D.p(this@OnboardingActivity).bg) }
        setContentView(host)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (idx > 0) go(idx - 1, forward = false) else finish() }
        })
        render(animate = false)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putInt("idx", idx) }
    override fun onResume() { super.onResume(); refresh() }

    private fun perm(): Perm? = perms.getOrNull(idx - 1)

    /** Back from a settings page / dialog: if it worked, celebrate briefly and move on. */
    private fun refresh() {
        val p = perm() ?: run { render(animate = false); return }
        if (Perms.granted(this, p.id) && (tried[p.id] ?: 0) > 0 && !advancing) {
            advancing = true
            render(animate = false)
            host.postDelayed({ advancing = false; next() }, 750)
        } else if (!advancing) render(animate = false)
    }

    private fun next() {
        var i = idx + 1
        // skip anything already granted (e.g. re-running setup)
        while (i in 1..perms.size && Perms.granted(this, perms[i - 1].id) && (tried[perms[i - 1].id] ?: 0) == 0) i++
        go(i, forward = true)
    }

    private fun go(i: Int, forward: Boolean) { idx = i.coerceIn(0, doneIdx); render(animate = true, forward = forward) }

    private fun allow(p: Perm) { tried[p.id] = (tried[p.id] ?: 0) + 1; req.request(p.id) }

    private fun finishSetup(demo: Boolean) {
        Store.put(this, "onboarded", "1")
        if (Perms.granted(this, Perms.OVERLAY)) startForegroundService(Intent(this, PillService::class.java))
        startActivity(Intent(this, HubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        if (demo) host.postDelayed({
            CardStore.put(applicationContext, Card(Card.newId(), "RIDE", "Rapido ride", "2 min away · TS09 EK 2231", value = "4471", src = "Demo", key = "ride:demo"))
        }, 1200)
        finish()
    }

    private fun render(animate: Boolean, forward: Boolean = true) {
        val v: View = when (idx) {
            0 -> OnboardingUi.welcome(this) { next() }
            doneIdx -> OnboardingUi.done(this, perms.map { it to Perms.granted(this, it.id) },
                onFix = { p -> idx = perms.indexOf(p) + 1; render(true) }, onStart = { finishSetup(false) }, onDemo = { finishSetup(true) })
            else -> {
                val p = perm()!!
                val granted = Perms.granted(this, p.id)
                val restricted = p.id == Perms.NOTIF && Build.VERSION.SDK_INT >= 33 && !granted && (tried[p.id] ?: 0) > 0
                OnboardingUi.step(this, p, idx, perms.size,
                    OnboardingUi.State(granted, Perms.progress(this, p.id), restricted,
                        if (p.id == Perms.BATTERY) Perms.autostartIntent(this)?.let { Perms.oemName() } else null),
                    onAllow = { if (granted) next() else allow(p) },
                    onSkip = { next() },
                    onBack = { go(idx - 1, false) },
                    onAppInfo = { startActivity(Perms.appInfo(this)) },
                    onAutostart = { Perms.autostartIntent(this)?.let { try { startActivity(it) } catch (_: Exception) { startActivity(Perms.appInfo(this)) } } })
            }
        }
        host.removeAllViews(); host.addView(v)
        if (animate) {
            v.alpha = 0f; v.translationX = D.dpf(this, if (forward) 40 else -40)
            v.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }
}

/** The setup screens as plain views (also rendered to PNG in tests). */
object OnboardingUi {
    data class State(val granted: Boolean, val progress: String?, val restrictedHelp: Boolean, val oem: String?)

    /** Screen = scrolling content + a bottom area that stays put (buttons live in the thumb zone). */
    private fun scaffold(c: Context, content: View, bottom: View, top: View? = null): View {
        val root = D.col(c).apply { setBackgroundColor(D.p(c).bg) }
        if (top != null) root.addView(top, D.wide(c))
        root.addView(ScrollView(c).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom, D.wide(c))
        return root
    }

    fun welcome(c: Context, onStart: () -> Unit): View {
        val p = D.p(c)
        val content = D.col(c, 24, 12).apply { gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(D.space(c, 28))
        content.addView(hero(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.dp(c, 196)))
        content.addView(D.space(c, 26))
        content.addView(D.display(c, "Your day,\none tap away").apply { gravity = Gravity.CENTER })
        content.addView(D.space(c, 10))
        content.addView(D.body(c, "Grab notices what's happening (a rider arriving, a metro gate, a spam call) and puts what you need at the bottom of your screen.").apply { gravity = Gravity.CENTER })
        content.addView(D.space(c, 22))
        val list = D.card(c, 18)
        list.addView(D.bullet(c, "🚇", "Metro QR pops up when you reach the station"))
        list.addView(D.bullet(c, "₹", "Scan & pay with the amount on the same screen"))
        list.addView(D.bullet(c, "🔢", "OTPs and ride codes right under your thumb"))
        list.addView(D.bullet(c, "🛡", "Spam calls muted, with a Decline button"))
        list.addView(D.bullet(c, "📄", "Documents ready when someone asks"))
        content.addView(list, D.wide(c))
        content.addView(D.space(c, 14))
        content.addView(D.row(c).apply {
            gravity = Gravity.CENTER
            addView(D.small(c, "🔒  Everything stays on this phone", p.ink2))
        })
        val bottom = D.col(c, 20, 16)
        bottom.addView(D.primary(c, "Get started") { onStart() })
        bottom.addView(D.small(c, "About 2 minutes · you choose what to allow", p.ink3).apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 10), 0, D.dp(c, 4)) }, D.wide(c))
        return scaffold(c, content, bottom)
    }

    /** A little picture of the product: a phone screen with a pop-up and the pill at the bottom. */
    private fun hero(c: Context): View {
        val p = D.p(c)
        val f = FrameLayout(c)
        val phone = D.col(c, 14, 14).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            background = D.round(c, D.alpha(p.brand, if (p.dark) 0x30 else 0x14), 34)
        }
        val pop = D.row(c).apply {
            setPadding(D.dp(c, 12), D.dp(c, 10), D.dp(c, 12), D.dp(c, 10))
            background = D.round(c, p.surface, 18, D.alpha(D.kind("RIDE"), 0xAA), 2); elevation = D.dpf(c, 6)
            addView(D.bubble(c, "🛵", D.kind("RIDE"), 34))
            addView(D.col(c).apply {
                setPadding(D.dp(c, 10), 0, D.dp(c, 6), 0)
                addView(D.title(c, "Rapido · 2 min away").apply { textSize = 14f })
                addView(D.small(c, "TS09 EK 2231"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(D.mono(c, "4471", 20f))
        }
        phone.addView(pop, D.wide(c))
        phone.addView(D.space(c, 12))
        val pill = D.row(c).apply {
            setPadding(D.dp(c, 14), D.dp(c, 8), D.dp(c, 16), D.dp(c, 8))
            background = D.round(c, 0xF21C1C22.toInt(), 99); elevation = D.dpf(c, 6)
            addView(TextView(c).apply { text = "🚇"; textSize = 14f })
            addView(TextView(c).apply { text = "  Ameerpet · tap to show"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()) })
        }
        phone.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        f.addView(phone, FrameLayout.LayoutParams(D.dp(c, 280), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        return f
    }

    fun step(
        c: Context, perm: Perm, index: Int, total: Int, s: State,
        onAllow: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit, onAppInfo: () -> Unit, onAutostart: () -> Unit,
    ): View {
        val p = D.p(c)
        // top: back · progress · skip
        val top = D.col(c, 16, 10)
        val bar = D.row(c)
        bar.addView(TextView(c).apply {
            text = "‹"; textSize = 30f; setTextColor(p.ink); gravity = Gravity.CENTER
            D.tappable(c, this, android.graphics.Color.TRANSPARENT, 99); setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(D.dp(c, 44), D.dp(c, 44)))
        bar.addView(D.steps(c, index, total), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = D.dp(c, 8); marginEnd = D.dp(c, 8) })
        bar.addView(TextView(c).apply {
            text = if (perm.required || s.granted) "" else "Skip"; textSize = 15f; setTextColor(p.ink2); gravity = Gravity.CENTER
            if (!perm.required && !s.granted) { D.tappable(c, this, android.graphics.Color.TRANSPARENT, 99); setOnClickListener { onSkip() } }
        }, LinearLayout.LayoutParams(D.dp(c, 56), D.dp(c, 44)))
        top.addView(bar)

        val content = D.col(c, 24, 8)
        content.addView(D.space(c, 18))
        val badge = if (s.granted) D.bubble(c, "✓", p.good, 88, square = false, solid = true).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 40f }
                    else D.bubble(c, perm.emoji, perm.color, 88)
        content.addView(badge)
        if (s.granted) badge.post { badge.scaleX = 0.6f; badge.scaleY = 0.6f; badge.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(OvershootInterpolator()).start() }
        content.addView(D.space(c, 20))
        content.addView(D.row(c).apply {
            addView(D.pill(c, "Step $index of $total", p.ink2))
            addView(D.space(c, 0), LinearLayout.LayoutParams(D.dp(c, 8), 1))
            addView(if (perm.required) D.pill(c, "Needed", p.brand) else D.pill(c, "Optional", p.ink3))
        })
        content.addView(D.space(c, 12))
        content.addView(D.h1(c, if (s.granted) "Done! ${perm.title}" else perm.title))
        content.addView(D.space(c, 8))
        content.addView(D.body(c, perm.why))
        content.addView(D.space(c, 18))
        if (s.restrictedHelp) {
            val h = D.card(c, 16, D.alpha(p.warn, if (p.dark) 0x26 else 0x14))
            h.background = D.round(c, D.alpha(p.warn, if (p.dark) 0x26 else 0x14), 20, D.alpha(p.warn, 0x55))
            h.addView(D.title(c, "Seeing \"Restricted setting\"?"))
            h.addView(D.small(c, "Android blocks this for apps installed outside the Play Store. It takes 10 seconds to unlock:", p.ink2).apply { setPadding(0, D.dp(c, 4), 0, D.dp(c, 6)) })
            h.addView(D.bullet(c, "1️⃣", "Tap Open app info below"))
            h.addView(D.bullet(c, "2️⃣", "Tap ⋮ (top right) → Allow restricted settings"))
            h.addView(D.bullet(c, "3️⃣", "Come back here and tap the button again"))
            h.addView(D.outline(c, "Open app info") { onAppInfo() }, D.wide(c, 48, 8))
            content.addView(h, D.wide(c))
            content.addView(D.space(c, 14))
        }
        val what = D.card(c, 16)
        perm.bullets.forEach { (e, t) -> what.addView(D.bullet(c, e, t)) }
        content.addView(what, D.wide(c))
        content.addView(D.space(c, 12))
        content.addView(D.row(c).apply {
            gravity = Gravity.TOP; setPadding(D.dp(c, 4), 0, D.dp(c, 4), 0)
            addView(TextView(c).apply { text = "🔒"; textSize = 14f; setPadding(0, 0, D.dp(c, 10), 0) })
            addView(D.small(c, perm.promise), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        if (s.progress != null) {
            content.addView(D.space(c, 14))
            content.addView(note(c, "👉", s.progress, p.warn), D.wide(c))
        }
        if (s.oem != null && !s.granted) {
            content.addView(D.space(c, 12))
            content.addView(D.ghost(c, "${s.oem} phone? Also turn on Autostart ›") { onAutostart() })
        }
        content.addView(D.space(c, 24))

        val bottom = D.col(c, 20, 14)
        bottom.addView(D.primary(c, if (s.granted) "Continue" else if (s.progress != null && perm.id == Perms.LOCATION) "Allow all the time" else perm.button,
            if (s.granted) p.good else p.brand) { onAllow() })
        if (!perm.required && !s.granted) bottom.addView(D.ghost(c, "Not now", p.ink2) { onSkip() }, D.wide(c, 44, 4))
        else bottom.addView(D.small(c, if (s.granted) "" else "You'll come right back here", p.ink3).apply { gravity = Gravity.CENTER; setPadding(0, D.dp(c, 10), 0, D.dp(c, 4)) }, D.wide(c))
        return scaffold(c, content, bottom, top)
    }

    private fun note(c: Context, emoji: String, t: String, color: Int) = D.row(c).apply {
        setPadding(D.dp(c, 14), D.dp(c, 12), D.dp(c, 14), D.dp(c, 12))
        background = D.round(c, D.alpha(color, if (D.p(c).dark) 0x26 else 0x16), 16)
        addView(TextView(c).apply { text = emoji; textSize = 16f; setPadding(0, 0, D.dp(c, 10), 0) })
        addView(D.body(c, t, D.p(c).ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun done(c: Context, status: List<Pair<Perm, Boolean>>, onFix: (Perm) -> Unit, onStart: () -> Unit, onDemo: () -> Unit): View {
        val p = D.p(c)
        val on = status.count { it.second }
        val content = D.col(c, 24, 12).apply { gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(D.space(c, 36))
        content.addView(D.bubble(c, "🎉", p.good, 96, square = false))
        content.addView(D.space(c, 18))
        content.addView(D.display(c, "You're all set").apply { gravity = Gravity.CENTER })
        content.addView(D.space(c, 8))
        content.addView(D.body(c, if (on == status.size) "Everything is on. Grab will start helping right away."
            else "$on of ${status.size} turned on. You can switch on the rest any time in Settings.").apply { gravity = Gravity.CENTER })
        content.addView(D.space(c, 22))
        val list = D.col(c)
        status.forEach { (perm, ok) ->
            list.addView(D.listRow(c, perm.emoji, perm.color, perm.title, if (ok) null else "Tap to turn on",
                if (ok) D.pill(c, "On", p.good) else D.pill(c, "Off", p.ink3), if (ok) null else ({ onFix(perm) })), D.wide(c, top = 8))
        }
        content.addView(list, D.wide(c))
        content.addView(D.space(c, 18))
        content.addView(D.card(c, 16).apply {
            addView(D.title(c, "How to use the pill"))
            addView(D.space(c, 4))
            addView(D.bullet(c, "👆", "Tap: show what's most urgent"))
            addView(D.bullet(c, "🤚", "Hold: Scan & pay"))
            addView(D.bullet(c, "⬆️", "Swipe up: quick panel"))
            addView(D.bullet(c, "⬇️", "Swipe down: hide that card"))
        }, D.wide(c))
        content.addView(D.space(c, 20))
        val bottom = D.col(c, 20, 14)
        bottom.addView(D.primary(c, "Start using Grab") { onStart() })
        bottom.addView(D.ghost(c, "Show me a demo pop-up") { onDemo() }, D.wide(c, 44, 4))
        return scaffold(c, content, bottom)
    }
}
