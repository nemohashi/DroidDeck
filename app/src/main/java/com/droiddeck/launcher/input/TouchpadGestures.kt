package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadGestures(
    private val slopPx: Float,
    private val listener: PointerGestures.Listener,
) {
    var x = 0f
        private set
    var y = 0f
        private set
    var bounds: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f)
    var sensitivity = 1.15f
    var desktop = false
    /** Width of the left edge strip where a finger that stays is the hand holding the device, in view
     *  pixels; 0 = none (see [onTouch]). */
    var restPx = 0f

    // The resting finger, left out of the touchpad's events until it lifts.
    private var restingId = -1
    private var restDownX = 0f
    private var restDownY = 0f
    private var restDownAt = 0L
    private var restAlone = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var travelled = 0f
    private var dragging = false
    private var held = false
    private var secondDownAt = 0L
    private var secondWasTap = false
    private var twoFingers = false
    private var scrollAnchorX = 0f
    private var scrollAnchorY = 0f
    private var fingers = 0
    private var pinching = false
    private var pinchSpan = 0f
    private var scrolled = false
    private var anchorId = -1
    private var anchorStartX = 0f
    private var anchorStartY = 0f
    private var moverId = -1
    private var moverStartX = 0f
    private var moverStartY = 0f
    private var moverLastX = 0f
    private var moverLastY = 0f
    private var holdDrag = false
    private var scrollLocked = false
    private var clicked = 0
    private val longPress = Runnable {
        held = true
        dragging = true
        listener.onLongPress()
        listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
    }
    private val clickUp = Runnable { releaseClick() }

    private fun click(button: Int) {
        releaseClick()
        listener.onButton(button, true, x, y)
        clicked = button
        handler.postDelayed(clickUp, TouchTiming.CLICK_MS)
    }

    private fun releaseClick() {
        handler.removeCallbacks(clickUp)
        if (clicked != 0) listener.onButton(clicked, false, x, y)
        clicked = 0
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        releaseClick()
        if (dragging) listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
        dragging = false
        held = false
        holdDrag = false
        scrollLocked = false
        anchorId = -1
        moverId = -1
        lastTapAt = 0L
        restingId = -1
    }

    fun place(px: Float, py: Float) {
        x = px.coerceIn(bounds.left, bounds.right)
        y = py.coerceIn(bounds.top, bounds.bottom)
    }

    private fun trackMove(px: Float, py: Float) {
        val dx = px - lastX
        val dy = py - lastY
        lastX = px; lastY = py
        travelled += abs(dx) + abs(dy)
        if (travelled > slopPx) {
            handler.removeCallbacks(longPress)
            val speed = abs(dx) + abs(dy)
            val gain = sensitivity * (1f + minOf(speed / 40f, 1.5f))
            place(x + dx * gain, y + dy * gain)
            listener.onMove(x, y)
        }
    }

    /**
     * Hands the touch on to [gesture] without a finger resting in the left strip ([restPx]): holding
     * an unfolded Fold leaves the left thumb on the screen's edge, and taken for a finger it turns the
     * other hand's touches into two-finger gestures and long presses. A finger that comes down in the
     * strip waits: lifted at once it is a tap, slid off it moves the pointer, and while it stays, the
     * other fingers work the touchpad as if it were not there.
     */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                restingId = -1
                if (event.x < restPx) {
                    restingId = event.getPointerId(0)
                    restDownX = event.x; restDownY = event.y; restDownAt = event.eventTime; restAlone = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (restingId != -1 && restAlone && event.pointerCount == 1) {
                if (abs(event.x - restDownX) + abs(event.y - restDownY) <= slopPx * REST_SLOP) return true
                // Slid off the strip: a pointer drag after all, from here.
                restingId = -1
                val down = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
                try { return gesture(down) } finally { down.recycle() }
            }
            MotionEvent.ACTION_UP -> if (restingId != -1 && event.getPointerId(0) == restingId) {
                restingId = -1
                // Lifted at once, alone: a tap in the strip, a click as anywhere else.
                if (restAlone && event.eventTime - restDownAt < TouchTiming.TAP_MS) {
                    val down = MotionEvent.obtain(restDownAt, restDownAt, MotionEvent.ACTION_DOWN, restDownX, restDownY, 0)
                    gesture(down)
                    down.recycle()
                    return gesture(event)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> restingId = -1
        }
        if (restingId == -1) return gesture(event)
        restAlone = false
        val rest = event.findPointerIndex(restingId)
        if (rest < 0) return gesture(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == rest) {
            restingId = -1
            return true
        }
        val others = without(event, rest) ?: return true
        try { return gesture(others) } finally { others.recycle() }
    }

    /** [event] without the pointer at index [drop], its action renumbered; null when nothing is left. */
    private fun without(event: MotionEvent, drop: Int): MotionEvent? {
        val keep = (0 until event.pointerCount).filter { it != drop }
        if (keep.isEmpty()) return null
        val masked = event.actionMasked
        val action = when {
            masked != MotionEvent.ACTION_POINTER_DOWN && masked != MotionEvent.ACTION_POINTER_UP -> masked
            event.actionIndex == drop -> MotionEvent.ACTION_MOVE
            keep.size == 1 -> if (masked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            else -> masked or (keep.indexOf(event.actionIndex) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val properties = Array(keep.size) { MotionEvent.PointerProperties().also { p -> event.getPointerProperties(keep[it], p) } }
        val coords = Array(keep.size) { MotionEvent.PointerCoords().also { c -> event.getPointerCoords(keep[it], c) } }
        return MotionEvent.obtain(event.downTime, event.eventTime, action, keep.size, properties, coords, event.metaState,
            event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
    }

    private fun gesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseClick()
                lastX = event.x; lastY = event.y
                downAt = event.eventTime
                travelled = 0f; held = false; twoFingers = false; scrolled = false; secondWasTap = false
                fingers = 1; pinching = false
                anchorId = event.getPointerId(0); moverId = -1; holdDrag = false; scrollLocked = false
                if (event.eventTime - lastTapAt < TouchTiming.DOUBLE_TAP_MS) {
                    dragging = true
                    listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                } else {
                    dragging = false
                    handler.postDelayed(longPress, TouchTiming.HOLD_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                fingers = maxOf(fingers, event.pointerCount)
                val i = event.actionIndex
                if (moverId == -1 && event.getPointerId(i) != anchorId) {
                    moverId = event.getPointerId(i)
                    moverStartX = event.getX(i); moverStartY = event.getY(i)
                    moverLastX = moverStartX; moverLastY = moverStartY
                    event.findPointerIndex(anchorId).takeIf { it >= 0 }?.let {
                        anchorStartX = event.getX(it); anchorStartY = event.getY(it)
                    }
                }
                if (holdDrag) return true
                twoFingers = true
                secondDownAt = event.eventTime
                secondWasTap = true
                scrollAnchorX = (event.getX(0) + event.getX(1)) / 2f
                scrollAnchorY = (event.getY(0) + event.getY(1)) / 2f
                pinchSpan = span(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val ai = event.findPointerIndex(anchorId)
                val mi = if (moverId == -1) -1 else event.findPointerIndex(moverId)
                if (ai >= 0 && mi >= 0) {
                    val mx = event.getX(mi); val my = event.getY(mi)
                    if (!holdDrag && !scrolled && !scrollLocked) {
                        val anchorMoved = abs(event.getX(ai) - anchorStartX) + abs(event.getY(ai) - anchorStartY)
                        val moverMoved = abs(mx - moverStartX) + abs(my - moverStartY)
                        if (anchorMoved >= slopPx) {
                            scrollLocked = true
                        } else if (moverMoved > slopPx * HOLD_DRAG_MOVER_SLOPS && anchorMoved < slopPx * HOLD_DRAG_ANCHOR_SLOPS &&
                            secondDownAt - downAt >= HOLD_DRAG_MIN_MS) {
                            // Only a finger that was already resting when the second one landed is a held
                            // button: two fingers landing together are a scroll, however unevenly they
                            // start moving.
                            holdDrag = true
                            secondWasTap = false
                            if (!dragging) {
                                dragging = true
                                listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                            }
                        }
                    }
                    if (holdDrag) {
                        val dx = mx - moverLastX
                        val dy = my - moverLastY
                        moverLastX = mx; moverLastY = my
                        val speed = abs(dx) + abs(dy)
                        val gain = sensitivity * (1f + minOf(speed / 40f, 1.5f))
                        place(x + dx * gain, y + dy * gain)
                        listener.onMove(x, y)
                        return true
                    }
                } else if (holdDrag) {
                    return true
                }
                if (twoFingers && event.pointerCount >= 2 && desktop) {
                    if (fingers >= 3) return true
                    val span = span(event)
                    if (!scrolled && !pinching && abs(span - pinchSpan) > slopPx * PINCH_SLOPS) {
                        pinching = true
                        secondWasTap = false
                        pinchSpan = span
                    }
                    if (pinching) {
                        val steps = ((span - pinchSpan) / PINCH_STEP_PX).toInt()
                        if (steps != 0) {
                            listener.onZoom(-steps)
                            pinchSpan += steps * PINCH_STEP_PX
                        }
                        return true
                    }
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = scrollAnchorX - midX
                    val dy = scrollAnchorY - midY
                    if (scrolled || abs(dx) + abs(dy) > slopPx) {
                        listener.onScroll(dx, dy)
                        scrollAnchorX = midX; scrollAnchorY = midY
                        scrolled = true
                        secondWasTap = false
                    }
                    return true
                }
                if (twoFingers && event.pointerCount >= 2) {
                    val mid = (event.getY(0) + event.getY(1)) / 2f
                    val dy = mid - scrollAnchorY
                    val steps = (dy / TouchTiming.SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        listener.onWheel(-steps)
                        scrollAnchorY += steps * TouchTiming.SCROLL_STEP_PX
                        scrolled = true
                        secondWasTap = false
                    }
                    return true
                }
                // The finger left behind when the other one lifts first after a two-finger gesture
                // does not move the pointer: a scroll would push it up the screen a little each time.
                if (twoFingers) return true
                for (h in 0 until event.historySize)
                    trackMove(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
                trackMove(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val gone = event.getPointerId(event.actionIndex)
                if (holdDrag) {
                    if (gone == anchorId) {
                        listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
                        dragging = false; holdDrag = false; twoFingers = false
                        anchorId = moverId; moverId = -1
                        event.findPointerIndex(anchorId).takeIf { it >= 0 }?.let {
                            lastX = event.getX(it); lastY = event.getY(it)
                        }
                        travelled = slopPx + 1f
                    } else if (gone == moverId) {
                        moverId = -1
                    }
                    return true
                }
                if (gone == moverId) moverId = -1
                if (gone == anchorId) {
                    anchorId = event.getPointerId(if (event.actionIndex == 0) 1 else 0)
                }
                event.findPointerIndex(anchorId).takeIf { it >= 0 && it != event.actionIndex }?.let {
                    lastX = event.getX(it); lastY = event.getY(it)
                }
                if (desktop && scrolled && event.pointerCount == 2) listener.onScrollEnd()
                if (fingers >= 3) {
                    if (event.pointerCount == 2 && secondWasTap && !scrolled && event.eventTime - downAt < TouchTiming.TAP_MS * 2 &&
                        !listener.onThreeFingerTap())
                        click(PointerGestures.BTN_MIDDLE)
                    if (event.pointerCount == 2) secondWasTap = false
                } else if (twoFingers && secondWasTap && event.eventTime - secondDownAt < TouchTiming.TAP_MS && !scrolled) {
                    click(PointerGestures.BTN_RIGHT)
                    secondWasTap = false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    dragging -> listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
                    twoFingers -> {
                        if (secondWasTap && !scrolled && !cancelled && event.eventTime - downAt < TouchTiming.TAP_MS * 2) {
                            click(PointerGestures.BTN_RIGHT)
                        }
                    }
                    !cancelled && travelled <= slopPx && event.eventTime - downAt < TouchTiming.TAP_MS -> {
                        click(PointerGestures.BTN_LEFT)
                        lastTapAt = event.eventTime
                    }
                }
                dragging = false; held = false; twoFingers = false
                holdDrag = false; scrollLocked = false; anchorId = -1; moverId = -1
                pinching = false; fingers = 0
                return true
            }
        }
        return false
    }

    private fun span(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    companion object {
        private const val PINCH_SLOPS = 3f
        private const val PINCH_STEP_PX = 60f
        private const val HOLD_DRAG_MOVER_SLOPS = 2f
        private const val HOLD_DRAG_ANCHOR_SLOPS = 0.5f
        /** How long the first finger must have been down before a second one can make it a held
         *  button; fingers landing closer together than this are a two-finger gesture. */
        private const val HOLD_DRAG_MIN_MS = 150L
        /** How far a resting edge finger may drift, in touch slops, before it is a pointer drag. */
        private const val REST_SLOP = 2f
        /** Scroll axis units per pixel the fingers move: a wheel notch (10) every 36 px. */
        const val SCROLL_UNITS_PER_PX = 10f / 36f
    }
}
