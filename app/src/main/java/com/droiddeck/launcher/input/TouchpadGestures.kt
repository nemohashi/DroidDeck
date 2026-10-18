package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs

/**
 * The screen as a touchpad, the way Bannerlator's desktop containers work: the arrow is where it
 * was, and a finger dragged anywhere moves it from there.
 *
 *  - drag one finger .................. moves the pointer (relative, with a little acceleration)
 *  - tap .............................. left click, where the pointer is
 *  - second finger taps while one holds  right click
 *  - two-finger tap ................... right click
 *  - two fingers up / down ............ scroll wheel
 *  - hold still, then drag ............ left-button drag
 *  - double-tap, hold, drag ........... left-button drag as well
 *  - one finger held, another slides .. left-button drag, as on a laptop's pad: the held finger is
 *                                       the button, the other moves the pointer (a window by its
 *                                       title bar or edge, a desktop icon). The sliding finger may
 *                                       lift and slide again; lifting the held one drops.
 *  - a finger staying in the left edge  left out: the thumb of the hand holding the device ([restPx])
 *    strip
 *
 * Positions handed to the listener are the pointer's, in the view's pixels; the caller keeps them
 * inside the picture.
 */
class TouchpadGestures(
    private val slopPx: Float,
    private val listener: PointerGestures.Listener,
) {
    /** The pointer, tracked here since every move is relative. */
    var x = 0f
        private set
    var y = 0f
        private set
    var bounds: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f)
    var sensitivity = 1.15f
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
    private var scrollAnchorY = 0f
    private var scrolled = false
    // Hold-and-slide: the first finger stays as the button, the second moves the pointer.
    private var anchorId = -1
    private var anchorStartX = 0f
    private var anchorStartY = 0f
    private var moverId = -1
    private var moverStartX = 0f
    private var moverStartY = 0f
    private var moverLastX = 0f
    private var moverLastY = 0f
    private var holdDrag = false
    private val longPress = Runnable {
        held = true
        dragging = true
        listener.onLongPress()
        listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        if (dragging) listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
        dragging = false
        held = false
        holdDrag = false
        anchorId = -1
        moverId = -1
        lastTapAt = 0L
        restingId = -1
    }

    fun place(px: Float, py: Float) {
        x = px.coerceIn(bounds.left, bounds.right)
        y = py.coerceIn(bounds.top, bounds.bottom)
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
                if (restAlone && event.eventTime - restDownAt < TAP_MS) {
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
                lastX = event.x; lastY = event.y
                downAt = event.eventTime
                travelled = 0f; held = false; twoFingers = false; scrolled = false; secondWasTap = false
                anchorId = event.getPointerId(0); moverId = -1; holdDrag = false
                // A tap shortly before this touch: holding now drags (double-tap-and-hold).
                if (event.eventTime - lastTapAt < DOUBLE_TAP_MS) {
                    dragging = true
                    listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                } else {
                    dragging = false
                    handler.postDelayed(longPress, HOLD_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                val i = event.actionIndex
                if (moverId == -1 && event.getPointerId(i) != anchorId) {
                    moverId = event.getPointerId(i)
                    moverStartX = event.getX(i); moverStartY = event.getY(i)
                    moverLastX = moverStartX; moverLastY = moverStartY
                    event.findPointerIndex(anchorId).takeIf { it >= 0 }?.let {
                        anchorStartX = event.getX(it); anchorStartY = event.getY(it)
                    }
                }
                // Mid-drag, a finger coming back only picks the sliding up again.
                if (holdDrag) return true
                twoFingers = true
                secondDownAt = event.eventTime
                secondWasTap = true
                scrollAnchorY = (event.getY(0) + event.getY(1)) / 2f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val ai = event.findPointerIndex(anchorId)
                val mi = if (moverId == -1) -1 else event.findPointerIndex(moverId)
                if (ai >= 0 && mi >= 0) {
                    val mx = event.getX(mi); val my = event.getY(mi)
                    // One finger still, the other on the move: that is a drag, not a scroll (which
                    // moves both). Decided once, before anything has scrolled.
                    if (!holdDrag && !scrolled) {
                        val anchorMoved = abs(event.getX(ai) - anchorStartX) + abs(event.getY(ai) - anchorStartY)
                        val moverMoved = abs(mx - moverStartX) + abs(my - moverStartY)
                        if (moverMoved > slopPx && anchorMoved < slopPx) {
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
                    // Only the held finger is down: the drag waits for the next slide.
                    return true
                }
                if (twoFingers && event.pointerCount >= 2) {
                    // Smooth: each move scrolls by what the fingers moved, once they are past the slop.
                    val mid = (event.getY(0) + event.getY(1)) / 2f
                    if (scrolled) {
                        listener.onScroll(-(mid - scrollAnchorY) * SCROLL_UNITS_PER_PX)
                        scrollAnchorY = mid
                    } else if (abs(mid - scrollAnchorY) > slopPx) {
                        scrollAnchorY = mid
                        scrolled = true
                        secondWasTap = false
                    }
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                travelled += abs(dx) + abs(dy)
                if (travelled > slopPx) {
                    handler.removeCallbacks(longPress)
                    // A little acceleration: a slow finger is precise, a fast one crosses the screen.
                    val speed = (abs(dx) + abs(dy))
                    val gain = sensitivity * (1f + minOf(speed / 40f, 1.5f))
                    place(x + dx * gain, y + dy * gain)
                    listener.onMove(x, y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val gone = event.getPointerId(event.actionIndex)
                if (holdDrag) {
                    if (gone == anchorId) {
                        // The held finger lifted: drop. The other finger carries on as the pointer.
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
                    // The first finger left a two-finger gesture: the other one is the pointer now.
                    anchorId = event.getPointerId(if (event.actionIndex == 0) 1 else 0)
                    event.findPointerIndex(anchorId).takeIf { it >= 0 }?.let {
                        lastX = event.getX(it); lastY = event.getY(it)
                    }
                }
                // The second finger lifting quickly, with nothing scrolled: a right click.
                if (twoFingers && secondWasTap && event.eventTime - secondDownAt < TAP_MS && !scrolled) {
                    listener.onButton(PointerGestures.BTN_RIGHT, true, x, y)
                    listener.onButton(PointerGestures.BTN_RIGHT, false, x, y)
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
                        // Both fingers down and up together, no scroll: a right click.
                        if (secondWasTap && !scrolled && !cancelled && event.eventTime - downAt < TAP_MS * 2) {
                            listener.onButton(PointerGestures.BTN_RIGHT, true, x, y)
                            listener.onButton(PointerGestures.BTN_RIGHT, false, x, y)
                        }
                    }
                    !cancelled && travelled <= slopPx && event.eventTime - downAt < TAP_MS -> {
                        listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                        listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
                        lastTapAt = event.eventTime
                    }
                }
                dragging = false; held = false; twoFingers = false
                holdDrag = false; anchorId = -1; moverId = -1
                return true
            }
        }
        return false
    }

    companion object {
        private const val HOLD_MS = 500L
        private const val TAP_MS = 300L
        private const val DOUBLE_TAP_MS = 300L
        /** Scroll axis units per pixel the fingers move: a wheel notch (10) every 36 px. */
        private const val SCROLL_UNITS_PER_PX = 10f / 36f
        /** How far a resting edge finger may drift, in touch slops, before it is a pointer drag. */
        private const val REST_SLOP = 2f
    }
}
