package io.github.pizzimenti.scootdash

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Two full-width panels you swipe between. On a wide screen (tablet, unfolded
 * phone, landscape) both panels sit side by side and swiping is off. Vertical
 * drags are left to the panels' own scroll views. Sliders inside opt out of
 * the swipe with [holdTouch].
 */
class PanelPager(ctx: Context) : HorizontalScrollView(ctx) {
    interface Listener { fun onPanel(index: Int, sideBySide: Boolean) }

    var listener: Listener? = null
    private val strip = LinearLayout(ctx)
    private val panels = ArrayList<View>()
    var page = 0
        private set
    var sideBySide = false
        private set
    private var flung = false
    private var downX = 0f
    private var downY = 0f
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 2

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        strip.orientation = LinearLayout.HORIZONTAL
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private val notifyPanel = object : Runnable {
        override fun run() { listener?.onPanel(page, sideBySide) }
    }

    fun addPanel(v: View) {
        panels.add(v)
        strip.addView(v, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val wide = w >= Ui.dp(760)
        val pw = if (wide) w / 2 else w
        for (p in panels) {
            val lp = p.layoutParams
            if (lp.width != pw) lp.width = pw
        }
        if (wide != sideBySide) {
            sideBySide = wide
            post(notifyPanel)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) scrollTo(if (sideBySide) 0 else page * (r - l), 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (sideBySide) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                super.onInterceptTouchEvent(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(ev.x - downX)
                val dy = Math.abs(ev.y - downY)
                if (dy > dx) return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (sideBySide) return false
        val handled = super.onTouchEvent(ev)
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (!flung) show(Math.round(scrollX.toFloat() / Math.max(1, width)), true)
            flung = false
        }
        return handled
    }

    override fun fling(velocityX: Int) {
        if (sideBySide) return
        flung = true
        val pos = scrollX.toFloat() / Math.max(1, width)
        val target = when {
            velocityX > minFling -> Math.ceil(pos.toDouble()).toInt()
            velocityX < -minFling -> Math.floor(pos.toDouble()).toInt()
            else -> Math.round(pos)
        }
        show(target, true)
    }

    // Don't let a focused text field scroll the pager off a page boundary.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0

    fun show(index: Int, animate: Boolean) {
        page = Math.max(0, Math.min(panels.size - 1, index))
        if (!sideBySide) {
            if (animate) smoothScrollTo(page * width, 0) else scrollTo(page * width, 0)
        }
        listener?.onPanel(page, sideBySide)
    }

    companion object {
        /**
         * Keep the pager from stealing horizontal drags that start on [v] (sliders), but hand
         * mostly-vertical drags back to the page's scroll view so scrolling past a slider
         * never moves it.
         */
        fun holdTouch(v: View) {
            val slop = ViewConfiguration.get(v.context).scaledTouchSlop
            v.setOnTouchListener(object : OnTouchListener {
                var x0 = 0f
                var y0 = 0f
                var released = false
                override fun onTouch(view: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            x0 = e.x; y0 = e.y; released = false
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> if (!released) {
                            val dx = Math.abs(e.x - x0)
                            val dy = Math.abs(e.y - y0)
                            if (dy > slop && dy > dx) {
                                released = true
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                    // Once handed to the scroll view, keep the rest of the gesture (except the
                    // final CANCEL) away from the slider so a lift can't tap-seek and write.
                    return released && e.actionMasked != MotionEvent.ACTION_CANCEL
                }
            })
        }
    }
}
