package com.bulldogsnake.stayawake.car

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Turns the Car App Library's click / scroll callbacks into MotionEvents and feeds them to
 * the Presentation that hosts the WebView, so the page can be tapped and scrolled from the
 * car's touch screen.
 */
class TouchInjector(private val target: () -> Dialog?) {

    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var downTime = 0L
    private var x = 0f
    private var y = 0f
    private val endDragRunnable = Runnable { endDrag() }

    fun tap(px: Float, py: Float) {
        endDrag()
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, t, t, px, py)
        handler.postDelayed({ send(MotionEvent.ACTION_UP, t, SystemClock.uptimeMillis(), px, py) }, 60)
    }

    /**
     * distanceX / distanceY follow GestureDetector semantics: positive means the finger moved
     * towards the top-left, so the synthetic pointer moves by the negated distance.
     */
    fun scroll(distanceX: Float, distanceY: Float, startX: Float, startY: Float) {
        val now = SystemClock.uptimeMillis()
        if (!dragging) {
            dragging = true
            downTime = now
            x = startX
            y = startY
            send(MotionEvent.ACTION_DOWN, downTime, now, x, y)
        }
        x -= distanceX
        y -= distanceY
        send(MotionEvent.ACTION_MOVE, downTime, now, x, y)
        handler.removeCallbacks(endDragRunnable)
        handler.postDelayed(endDragRunnable, 250)
    }

    fun endDrag() {
        handler.removeCallbacks(endDragRunnable)
        if (!dragging) return
        dragging = false
        send(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y)
    }

    private fun send(action: Int, down: Long, eventTime: Long, px: Float, py: Float) {
        val dialog = target() ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = px
            this.y = py
            pressure = 1f
            size = 1f
        })
        val event = MotionEvent.obtain(
            down, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            dialog.dispatchTouchEvent(event)
        } catch (t: Throwable) {
            // The presentation may be tearing down; dropping one event is harmless.
        } finally {
            event.recycle()
        }
    }
}
