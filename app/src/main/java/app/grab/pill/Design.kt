package app.grab.pill

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Colours for light and dark mode. Every screen, the pill and the pop-ups read from here. */
data class Palette(
    val dark: Boolean,
    val bg: Int, val surface: Int, val surface2: Int, val line: Int,
    val ink: Int, val ink2: Int, val ink3: Int,
    val brand: Int, val onBrand: Int,
    val good: Int, val warn: Int, val bad: Int,
)

/**
 * Grab's design system, in code. One place for colour, type, spacing and components so every screen
 * looks like one app: soft surfaces, big friendly type, thumb-sized controls (≥ 48 dp), clear hierarchy.
 */
object D {
    private val LIGHT = Palette(false,
        bg = 0xFFF5F5F7.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFEDEDF2.toInt(), line = 0xFFE4E4EA.toInt(),
        ink = 0xFF0B0B0F.toInt(), ink2 = 0xFF5F5F6B.toInt(), ink3 = 0xFF9A9AA5.toInt(),
        brand = 0xFF4F46E5.toInt(), onBrand = Color.WHITE,
        good = 0xFF16A34A.toInt(), warn = 0xFFD97706.toInt(), bad = 0xFFE11D48.toInt())
    private val DARK = Palette(true,
        bg = 0xFF0B0B0F.toInt(), surface = 0xFF17171C.toInt(), surface2 = 0xFF24242B.toInt(), line = 0xFF2C2C34.toInt(),
        ink = 0xFFF5F5F7.toInt(), ink2 = 0xFFB4B4BE.toInt(), ink3 = 0xFF7A7A85.toInt(),
        brand = 0xFF818CF8.toInt(), onBrand = 0xFF0B0B0F.toInt(),
        good = 0xFF4ADE80.toInt(), warn = 0xFFFBBF24.toInt(), bad = 0xFFFB7185.toInt())

    fun p(c: Context): Palette =
        if ((c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT

    /** Colour per card kind (shared by cards, pill and pop-ups). */
    val KIND = mapOf(
        "OTP" to 0xFF6366F1.toInt(), "RIDE" to 0xFFF59E0B.toInt(), "ORDER" to 0xFFEF4444.toInt(),
        "METRO" to 0xFF0EA5E9.toInt(), "MOVIE" to 0xFFA855F7.toInt(), "TICKET" to 0xFF22C55E.toInt(),
        "ASK" to 0xFF14B8A6.toInt(), "CALL" to 0xFFE11D48.toInt(), "MEET" to 0xFF3B82F6.toInt(), "PAY" to 0xFF16A34A.toInt(),
        "UPDATE" to 0xFF4F46E5.toInt(), "TRAVEL" to 0xFF0891B2.toInt()
    )
    fun kind(k: String) = KIND[k] ?: 0xFF64748B.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Int) = v * c.resources.displayMetrics.density
    fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
    /** Readable text colour on top of `bg` (dark text on light buttons, white on dark ones). */
    fun on(bg: Int) = if (Color.luminance(bg) > 0.45f) 0xFF0B0B0F.toInt() else Color.WHITE

    // ------------------------------------------------------------------ window

    /** Status / navigation bars match the screen; icons flip for dark mode. */
    @Suppress("DEPRECATION")
    fun window(a: Activity, color: Int = p(a).bg) {
        val pal = p(a)
        a.window.statusBarColor = color
        a.window.navigationBarColor = pal.bg
        var flags = a.window.decorView.systemUiVisibility
        flags = if (!pal.dark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        a.window.decorView.systemUiVisibility = flags
    }

    // ------------------------------------------------------------------ shapes

    fun round(c: Context, color: Int, radius: Int = 20, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        cornerRadius = dpf(c, radius); setColor(color)
        if (stroke != null) setStroke(dp(c, strokeW), stroke)
    }

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Ripple on top of a rounded background, so every tappable surface responds to touch. */
    fun tappable(c: Context, v: View, color: Int, radius: Int = 20, stroke: Int? = null) {
        val bg = round(c, color, radius, stroke)
        v.background = RippleDrawable(ColorStateList.valueOf(alpha(p(c).ink, 0x1F)), bg, round(c, Color.BLACK, radius))
        v.isClickable = true; v.isFocusable = true
    }

    // ------------------------------------------------------------------ layout helpers

    fun col(c: Context, padH: Int = 0, padV: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(c, padH), dp(c, padV), dp(c, padH), dp(c, padV))
    }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun space(c: Context, h: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, h)) }
    fun wide(c: Context, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (h > 0) dp(c, h) else h).apply { topMargin = dp(c, top) }
    fun weight(c: Context, w: Float = 1f, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, gap: Int = 0) =
        LinearLayout.LayoutParams(0, if (h > 0) dp(c, h) else h, w).apply { marginStart = dp(c, gap / 2); marginEnd = dp(c, gap / 2) }

    // ------------------------------------------------------------------ type

    private fun text(c: Context, t: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = true
    }

    fun display(c: Context, t: CharSequence) = text(c, t, 32f, p(c).ink, true).apply { letterSpacing = -0.02f }
    fun h1(c: Context, t: CharSequence) = text(c, t, 26f, p(c).ink, true).apply { letterSpacing = -0.01f }
    fun h2(c: Context, t: CharSequence) = text(c, t, 19f, p(c).ink, true)
    fun title(c: Context, t: CharSequence) = text(c, t, 16f, p(c).ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    fun body(c: Context, t: CharSequence, color: Int = p(c).ink2) = text(c, t, 15f, color).apply { setLineSpacing(dpf(c, 3), 1f) }
    fun small(c: Context, t: CharSequence, color: Int = p(c).ink2) = text(c, t, 13f, color).apply { setLineSpacing(dpf(c, 2), 1f) }
    fun mono(c: Context, t: CharSequence, size: Float = 30f) = text(c, t, size, p(c).ink, true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = if (t.all { it.isDigit() }) 0.16f else 0.02f
    }

    fun label(c: Context, t: String) = text(c, t.uppercase(), 12f, p(c).ink3, true).apply {
        letterSpacing = 0.08f; setPadding(dp(c, 4), dp(c, 22), 0, dp(c, 8))
    }

    // ------------------------------------------------------------------ components

    private fun button(c: Context, t: String, bg: Int, fg: Int, h: Int, stroke: Int?, onClick: () -> Unit) = MaterialButton(c).apply {
        text = t; isAllCaps = false; textSize = 16f; setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        backgroundTintList = ColorStateList.valueOf(bg)
        rippleColor = ColorStateList.valueOf(alpha(fg, 0x33))
        cornerRadius = dp(c, 16); insetTop = 0; insetBottom = 0; elevation = 0f; stateListAnimator = null
        minHeight = dp(c, h); minimumHeight = dp(c, h)
        if (stroke != null) { strokeColor = ColorStateList.valueOf(stroke); strokeWidth = dp(c, 1) }
        setOnClickListener { onClick() }
        layoutParams = wide(c, h)
    }

    fun primary(c: Context, t: String, color: Int = p(c).brand, onClick: () -> Unit) =
        button(c, t, color, on(color), 56, null, onClick)
    fun secondary(c: Context, t: String, onClick: () -> Unit) = button(c, t, p(c).surface2, p(c).ink, 52, null, onClick)
    fun outline(c: Context, t: String, onClick: () -> Unit) = button(c, t, p(c).surface, p(c).ink, 52, p(c).line, onClick)
    fun ghost(c: Context, t: String, color: Int = p(c).brand, onClick: () -> Unit) =
        button(c, t, Color.TRANSPARENT, color, 48, null, onClick).apply { textSize = 15f }

    /** Emoji in a soft tinted circle (or rounded square). */
    fun bubble(c: Context, emoji: String, color: Int, size: Int = 44, square: Boolean = true, solid: Boolean = false) = TextView(c).apply {
        text = emoji; gravity = Gravity.CENTER; textSize = size * 0.42f
        background = if (square) round(c, if (solid) color else alpha(color, if (p(c).dark) 0x40 else 0x22), size / 3) else circle(if (solid) color else alpha(color, 0x22))
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }

    /** Surface card. Content goes in the returned column. */
    fun card(c: Context, pad: Int = 16, color: Int = p(c).surface) = col(c, pad, pad).apply {
        background = round(c, color, 22, if (p(c).dark) null else alpha(p(c).line, 0xFF))
    }

    /** The workhorse list row: icon · title/subtitle · trailing view. */
    fun listRow(c: Context, emoji: String, color: Int, title: String, sub: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = row(c).apply { setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12)); minimumHeight = dp(c, 64) }
        if (onClick != null) { tappable(c, r, p(c).surface, 20, if (p(c).dark) null else p(c).line); r.setOnClickListener { onClick() } }
        else r.background = round(c, p(c).surface, 20, if (p(c).dark) null else p(c).line)
        r.addView(bubble(c, emoji, color))
        val words = col(c).apply { setPadding(dp(c, 14), 0, dp(c, 8), 0) }
        words.addView(title(c, title).apply { maxLines = 1 })
        if (!sub.isNullOrBlank()) words.addView(small(c, sub).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        r.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        return r
    }

    fun chevron(c: Context) = text(c, "›", 24f, p(c).ink3)

    fun pill(c: Context, t: String, color: Int, solid: Boolean = false) = text(c, t, 12f, if (solid) on(color) else color, true).apply {
        setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 5))
        background = round(c, if (solid) color else alpha(color, if (p(c).dark) 0x40 else 0x1F), 99)
    }

    fun chip(c: Context, t: String, onClick: (() -> Unit)? = null) = text(c, t, 13f, p(c).ink, true).apply {
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        if (onClick != null) { tappable(c, this, p(c).surface, 99, if (p(c).dark) null else p(c).line); setOnClickListener { onClick() } }
        else background = round(c, p(c).surface, 99, if (p(c).dark) null else p(c).line)
    }

    fun switchRow(c: Context, emoji: String, color: Int, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = MaterialSwitch(c).apply { isChecked = checked; setOnCheckedChangeListener { _, on -> onChange(on) } }
        return listRow(c, emoji, color, title, sub, sw) { sw.isChecked = !sw.isChecked }
    }

    /** Segmented progress bar for multi-step flows. */
    fun steps(c: Context, done: Int, total: Int): LinearLayout = row(c).apply {
        for (i in 0 until total) addView(View(c).apply { background = round(c, if (i < done) p(c).brand else p(c).line, 99) },
            LinearLayout.LayoutParams(0, dp(c, 5), 1f).apply { marginEnd = if (i < total - 1) dp(c, 6) else 0 })
    }

    /** Small "• text" bullet line. */
    fun bullet(c: Context, emoji: String, t: String): LinearLayout = row(c).apply {
        gravity = Gravity.TOP; setPadding(0, dp(c, 6), 0, dp(c, 6))
        addView(text(c, emoji, 16f, p(c).ink).apply { gravity = Gravity.CENTER_HORIZONTAL }, LinearLayout.LayoutParams(dp(c, 26), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(c, 12) })
        addView(body(c, t, p(c).ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A bottom bar that floats over content: surface, rounded top, shadow. */
    fun bottomBar(c: Context) = col(c, 16, 12).apply {
        background = GradientDrawable().apply {
            val r = dpf(c, 26); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(p(c).surface)
        }
        elevation = dpf(c, 16)
    }

    fun frame(c: Context) = FrameLayout(c)
}
