package io.github.pizzimenti.scootdash

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Palette and type for a high-desert dusk instrument panel. The basalt-blue night
 * sky is the ground. Pumice-white is the ink. The sage and signal-red bands come
 * from test-gauge dials. Ponderosa orange is kept for "cruise is holding speed".
 */
object Ui {
    const val DUSK = 0xFF121C24.toInt()       // window background
    const val FACE = 0xFF16232C.toInt()       // dial face
    const val BASALT = 0xFF1B2832.toInt()     // raised rows
    const val RIDGE = 0xFF2C3D49.toInt()      // tracks, outlines
    const val SLATE = 0xFF8798A4.toInt()      // secondary ink
    const val PUMICE = 0xFFE6E1D3.toInt()     // primary ink
    const val SAGE = 0xFF9DB58A.toInt()       // within limit
    const val PONDEROSA = 0xFFF08A3C.toInt()  // cruise / active
    const val CAUTION = 0xFFE9C44A.toInt()    // near limit, experiments
    const val SIGNAL = 0xFFE5533D.toInt()     // over limit, locked, errors

    var density = 3f
        private set
    var fontScale = 1f
        private set
    lateinit var body: Typeface
    lateinit var bodyMedium: Typeface
    lateinit var bodySemi: Typeface
    lateinit var cond: Typeface
    lateinit var condSemi: Typeface

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        density = dm.density
        fontScale = dm.scaledDensity / dm.density
        body = font(ctx, "Barlow_400Regular.ttf", Typeface.NORMAL)
        bodyMedium = font(ctx, "Barlow_500Medium.ttf", Typeface.NORMAL)
        bodySemi = font(ctx, "Barlow_600SemiBold.ttf", Typeface.BOLD)
        cond = font(ctx, "BarlowCondensed_500Medium.ttf", Typeface.NORMAL)
        condSemi = font(ctx, "BarlowCondensed_600SemiBold.ttf", Typeface.BOLD)
    }

    private fun font(ctx: Context, file: String, fallbackStyle: Int): Typeface =
        try { Typeface.createFromAsset(ctx.assets, "fonts/$file") } catch (e: RuntimeException) { Typeface.create(Typeface.SANS_SERIF, fallbackStyle) }

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density
    fun sp(v: Float): Float = v * density * fontScale

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    fun text(ctx: Context, sizeSp: Float, color: Int, face: Typeface): TextView {
        val t = TextView(ctx)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        t.typeface = face
        t.includeFontPadding = false
        return t
    }

    fun box(fill: Int, radiusDp: Float, strokeDp: Float = 0f, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setCornerRadius(dpf(radiusDp))
        if (strokeDp > 0f) g.setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), stroke)
        return g
    }

    fun ripple(content: Drawable, radiusDp: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(alpha(PUMICE, 0x33)), content, box(0xFFFFFFFF.toInt(), radiusDp))

    /** Flat button: filled for primary actions, outlined otherwise. */
    fun button(ctx: Context, label: String, kind: Int = QUIET): TextView {
        val t = text(ctx, 16f, PUMICE, bodySemi)
        t.text = label
        t.gravity = Gravity.CENTER
        t.minHeight = dp(46)
        t.setPadding(dp(16), dp(10), dp(16), dp(10))
        styleButton(t, kind)
        t.isClickable = true
        t.isFocusable = true
        return t
    }

    const val QUIET = 0
    const val PRIMARY = 1
    const val RISKY = 2

    fun styleButton(t: TextView, kind: Int) {
        when (kind) {
            PRIMARY -> { t.background = ripple(box(PUMICE, 10f), 10f); t.setTextColor(DUSK) }
            RISKY -> { t.background = ripple(box(alpha(CAUTION, 0x22), 10f, 1.5f, CAUTION), 10f); t.setTextColor(CAUTION) }
            else -> { t.background = ripple(box(BASALT, 10f, 1f, RIDGE), 10f); t.setTextColor(PUMICE) }
        }
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.setGravity(Gravity.CENTER_VERTICAL)
        return l
    }

    fun column(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun weighted(w: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)

    fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun full(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}

/** A row of LED-style segments for 0..n levels (battery, acceleration, brake). */
class SegBar(ctx: Context, private val segments: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var lit = 0f
    private var color = Ui.SAGE

    /** [fraction] 0..1 of the bar to light. */
    fun set(fraction: Float, c: Int) {
        lit = fraction.coerceIn(0f, 1f) * segments
        color = c
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(8))
    }

    override fun onDraw(c: Canvas) {
        val gap = Ui.dpf(3f)
        val w = (width - gap * (segments - 1)) / segments
        val h = height.toFloat()
        for (i in 0 until segments) {
            val x = i * (w + gap)
            r.set(x, 0f, x + w, h)
            paint.color = if (i + 0.5f <= lit) color else Ui.RIDGE
            c.drawRoundRect(r, h / 3, h / 3, paint)
        }
    }
}

/** Dashboard telltale: a lamp that lights in its colour when its flag is set. */
class Telltale(ctx: Context, label: String, private val litColor: Int) : TextView(ctx) {
    var on = false
        private set

    init {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Ui.bodySemi
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(Ui.dp(10), Ui.dp(7), Ui.dp(10), Ui.dp(7))
        setOn(false, true)
    }

    fun setOn(v: Boolean, force: Boolean = false) {
        if (v == on && !force) return
        on = v
        if (v) {
            background = Ui.box(litColor, 8f)
            setTextColor(Ui.DUSK)
        } else {
            background = Ui.box(0, 8f, 1f, Ui.RIDGE)
            setTextColor(Ui.SLATE)
        }
    }
}

/** Label over a big condensed value, with an optional bar and caption. */
class Readout(ctx: Context, label: String, withBar: Int = 0, valueSp: Float = 30f) : LinearLayout(ctx) {
    val value: TextView
    val caption: TextView
    val bar: SegBar?

    init {
        orientation = VERTICAL
        setPadding(0, Ui.dp(8), Ui.dp(10), Ui.dp(8))
        val l = Ui.text(ctx, 14f, Ui.SLATE, Ui.bodyMedium)
        l.text = label
        addView(l)
        value = Ui.text(ctx, valueSp, Ui.PUMICE, Ui.condSemi)
        value.text = "–"
        value.maxLines = 1
        value.setPadding(0, Ui.dp(4), 0, Ui.dp(2))
        addView(value)
        if (withBar > 0) {
            bar = SegBar(ctx, withBar)
            val lp = Ui.full()
            lp.topMargin = Ui.dp(4)
            lp.bottomMargin = Ui.dp(4)
            addView(bar, lp)
        } else bar = null
        caption = Ui.text(ctx, 12.5f, Ui.SLATE, Ui.body)
        caption.visibility = GONE
        addView(caption)
    }

    fun note(s: String?) {
        if (s == null || s.isEmpty()) caption.visibility = GONE
        else { caption.text = s; caption.visibility = VISIBLE }
    }
}

/** Lays children out left to right, wrapping to a new line when the row is full. */
class FlowRow(ctx: Context, private val hGapDp: Int, private val vGapDp: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        val hg = Ui.dp(hGapDp)
        val vg = Ui.dp(vGapDp)
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += lineH + vg; lineH = 0 }
            x += ch.measuredWidth + hg
            lineH = Math.max(lineH, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        val hg = Ui.dp(hGapDp)
        val vg = Ui.dp(vGapDp)
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += lineH + vg; lineH = 0 }
            ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth + hg
            lineH = Math.max(lineH, ch.measuredHeight)
        }
    }
}
