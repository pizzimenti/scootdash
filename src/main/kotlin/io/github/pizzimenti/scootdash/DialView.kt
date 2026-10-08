package io.github.pizzimenti.scootdash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.Locale

/**
 * The speedometer, drawn like a test-bench dial. The scale runs from 0 to the
 * highest gear's top speed. A sage band covers the current gear's limit and a
 * signal-red band covers the rest. A caution notch marks the limit and a red
 * pointer marks the session peak (tap the dial to reset it). When cruise control
 * is on, a ponderosa ring lights around the bezel.
 */
class DialView(ctx: Context) : View(ctx) {
    // inputs (km/h)
    var connected = false
    var speedKmh = 0.0
    var maxKmh = 31.0
    var capKmh = -1.0
    var peakKmh = 0.0
    var cruise = false
    var locked = false
    var gear = -1
    var mph = true
    var stateText = "Not connected"

    private var shown = 0.0

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val tri = Path()

    init {
        stroke.style = Paint.Style.STROKE
        fill.style = Paint.Style.FILL
        label.textAlign = Paint.Align.CENTER
        label.typeface = Ui.cond
        digits.typeface = Ui.condSemi
        digits.textAlign = Paint.Align.LEFT
        small.textAlign = Paint.Align.CENTER
        small.typeface = Ui.bodyMedium
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val screenH = resources.displayMetrics.heightPixels
        setMeasuredDimension(w, Math.min(w, Math.max((screenH * 0.5f).toInt(), Ui.dp(300))))
    }

    private val k: Double get() = if (mph) 0.621371 else 1.0
    val unit: String get() = if (mph) "mph" else "km/h"

    private fun angleFor(kmh: Double): Float {
        val f = if (maxKmh <= 0) 0.0 else (kmh / maxKmh).coerceIn(0.0, 1.04)
        return (START + SWEEP * f).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = Math.min(w, h)
        val cx = w / 2
        val cy = h / 2 + size * 0.04f
        val r = size / 2 - Ui.dpf(14f)
        if (r - Ui.dpf(86f) - Ui.dpf(12f) <= 0f) return   // too small to draw a dial
        val ink = if (connected) Ui.PUMICE else Ui.SLATE

        // cruise ring: the one place the accent is allowed to glow
        if (cruise && connected) {
            stroke.strokeCap = Paint.Cap.BUTT
            stroke.color = Ui.alpha(Ui.PONDEROSA, 0x22); stroke.strokeWidth = Ui.dpf(18f); c.drawCircle(cx, cy, r, stroke)
            stroke.color = Ui.alpha(Ui.PONDEROSA, 0x55); stroke.strokeWidth = Ui.dpf(9f); c.drawCircle(cx, cy, r, stroke)
            stroke.color = Ui.PONDEROSA; stroke.strokeWidth = Ui.dpf(4f); c.drawCircle(cx, cy, r, stroke)
        }

        // face and bezel
        val face = r - Ui.dpf(10f)
        fill.color = Ui.FACE
        c.drawCircle(cx, cy, face, fill)
        stroke.color = Ui.RIDGE; stroke.strokeWidth = Ui.dpf(1.5f)
        c.drawCircle(cx, cy, face, stroke)

        // operating bands
        val rb = r - Ui.dpf(24f)
        oval.set(cx - rb, cy - rb, cx + rb, cy + rb)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = Ui.dpf(5f)
        val capA = if (capKmh > 0) angleFor(capKmh) else START + SWEEP
        stroke.color = if (connected) Ui.SAGE else Ui.RIDGE
        c.drawArc(oval, START, capA - START, false, stroke)
        if (capA < START + SWEEP) {
            stroke.color = if (connected) Ui.SIGNAL else Ui.RIDGE
            c.drawArc(oval, capA, START + SWEEP - capA, false, stroke)
        }

        // ticks and numerals in display units
        val maxD = maxKmh * k
        val major = if (maxD > 45) 10 else 5
        val minor = if (maxD > 45) 2 else 1
        val rt = r - Ui.dpf(32f)
        label.textSize = Ui.sp(15f)
        label.color = ink
        var v = 0
        while (v <= maxD + 0.001) {
            val a = Math.toRadians(angleFor(v / k).toDouble())
            val isMajor = v % major == 0
            val len = if (isMajor) Ui.dpf(13f) else Ui.dpf(6f)
            stroke.strokeWidth = if (isMajor) Ui.dpf(2.5f) else Ui.dpf(1.4f)
            stroke.color = if (isMajor) ink else Ui.SLATE
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            c.drawLine(cx + cos * rt, cy + sin * rt, cx + cos * (rt - len), cy + sin * (rt - len), stroke)
            if (isMajor && (maxD - v >= 2.0 || v == 0)) {
                val rl = rt - Ui.dpf(30f)
                c.drawText(v.toString(), cx + cos * rl, cy + sin * rl + label.textSize * 0.35f, label)
            }
            v += minor
        }
        // label the end of the scale with the exact top speed
        run {
            val a = Math.toRadians((START + SWEEP).toDouble())
            val rl = rt - Ui.dpf(30f)
            label.color = Ui.SLATE
            c.drawText(String.format(Locale.US, "%.0f", maxD), cx + Math.cos(a).toFloat() * rl,
                cy + Math.sin(a).toFloat() * rl + label.textSize * 0.35f, label)
        }

        // speed track and fill
        val rp = r - Ui.dpf(86f)
        oval.set(cx - rp, cy - rp, cx + rp, cy + rp)
        stroke.strokeWidth = Ui.dpf(12f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Ui.RIDGE
        c.drawArc(oval, START, SWEEP, false, stroke)
        val diff = speedKmh - shown
        if (Math.abs(diff) > 0.02) { shown += diff * 0.22; postInvalidateOnAnimation() } else shown = speedKmh
        if (connected && shown > 0.05) {
            stroke.color = when {
                capKmh > 0 && shown > capKmh + 0.5 -> Ui.SIGNAL
                capKmh > 0 && shown >= capKmh * 0.9 -> Ui.CAUTION
                else -> Ui.SAGE
            }
            c.drawArc(oval, START, angleFor(shown) - START, false, stroke)
        }

        // limit notch on the band, peak pointer on the track
        if (connected && capKmh > 0) drawPointer(c, cx, cy, rb + Ui.dpf(7f), angleFor(capKmh), Ui.CAUTION, true)
        if (connected && peakKmh > 0.4) drawPointer(c, cx, cy, rp + Ui.dpf(13f), angleFor(peakKmh), Ui.SIGNAL, true)

        // big digits: whole number large, tenths small, centred together
        val inner = rp - Ui.dpf(12f)
        val disp = shown * k
        var whole = Math.floor(disp).toInt()
        var tenth = Math.round((disp - whole) * 10).toInt()
        if (tenth == 10) { whole += 1; tenth = 0 }
        val big = if (connected) whole.toString() else "--"
        val dec = if (connected) "." + tenth else ""
        digits.textSize = inner * 1.15f
        val maxW = inner * 1.55f
        var bw = digits.measureText(big)
        if (bw > maxW) { digits.textSize *= maxW / bw; bw = digits.measureText(big) }
        val bigSize = digits.textSize
        val decSize = bigSize * 0.36f
        digits.textSize = decSize
        val dw = digits.measureText(dec)
        val x0 = cx - (bw + dw) / 2
        val base = cy + bigSize * 0.30f
        digits.color = ink
        digits.textSize = bigSize
        c.drawText(big, x0, base, digits)
        digits.textSize = decSize
        digits.color = Ui.SLATE
        c.drawText(dec, x0 + bw, base, digits)

        small.textSize = Ui.sp(17f)
        small.color = Ui.SLATE
        c.drawText(unit, cx, base + Ui.sp(26f), small)

        // the open bottom of the dial carries the state line and cruise
        val gapY = cy + r * 0.70f
        small.textSize = Ui.sp(16f)
        small.color = if (locked && connected) Ui.SIGNAL else ink
        c.drawText(if (locked && connected) "Locked" else stateText, cx, gapY, small)
        if (cruise && connected) {
            small.color = Ui.PONDEROSA
            small.textSize = Ui.sp(15f)
            c.drawText("Cruise control on", cx, gapY + Ui.sp(22f), small)
        }
    }

    private fun drawPointer(c: Canvas, cx: Float, cy: Float, rad: Float, angleDeg: Float, color: Int, inward: Boolean) {
        val a = Math.toRadians(angleDeg.toDouble())
        val cos = Math.cos(a).toFloat()
        val sin = Math.sin(a).toFloat()
        val tipR = if (inward) rad - Ui.dpf(9f) else rad + Ui.dpf(9f)
        val half = Ui.dpf(6f)
        tri.reset()
        tri.moveTo(cx + cos * tipR, cy + sin * tipR)
        tri.lineTo(cx + cos * rad - sin * half, cy + sin * rad + cos * half)
        tri.lineTo(cx + cos * rad + sin * half, cy + sin * rad - cos * half)
        tri.close()
        fill.color = color
        c.drawPath(tri, fill)
    }

    companion object {
        const val START = 150f   // degrees, clockwise from 3 o'clock
        const val SWEEP = 240f
    }
}
