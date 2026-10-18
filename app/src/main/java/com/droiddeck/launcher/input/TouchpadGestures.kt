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
 *  - one finger resting in the left ... scroll wheel where the other finger touches: the pointer
 *    edge strip ([edgePx]), another     jumps to it and stays while it slides up or down. It may
 *    slides                             lift and touch again; lifting the resting one ends it.
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
    /** Width of the left edge strip that holds a finger for edge scrolling, in view pixels; 0 = off. */
    var edgePx = 0f

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
    // Edge scrolling: the first finger rests in the strip, and a second one scrolls where it is.
    private var downX = 0f
    private var downY = 0f
    private var edgeRest = false
    private var edgeScroll = false
    private var scrollerId = -1
    private var scrollerLastY = 0f
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
        edgeRest = false
        edgeScroll = false
        scrollerId = -1
    }

    fun place(px: Float, py: Float) {
        x = px.coerceIn(bounds.left, bounds.right)
        y = py.coerceIn(bounds.top, bounds.bottom)
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downAt = event.eventTime
                travelled = 0f; held = false; twoFingers = false; scrolled = false; secondWasTap = false
                anchorId = event.getPointerId(0); moverId = -1; holdDrag = false
                downX = event.x; downY = event.y
                edgeScroll = false; scrollerId = -1
                edgeRest = false
                // A tap shortly before this touch: holding now drags (double-tap-and-hold).
                if (event.eventTime - lastTapAt < DOUBLE_TAP_MS) {
                    dragging = true
                    listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                } else {
                    dragging = false
                    // A thumb resting in the edge strip waits for the other hand, not a long press.
                    edgeRest = event.x < edgePx
                    if (!edgeRest) handler.postDelayed(longPress, HOLD_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                val i = event.actionIndex
                if (edgeRest || edgeScroll) {
                    if (edgeRest && scrollerId == -1) {
                        edgeScroll = true
                        scrollerId = event.getPointerId(i)
                        scrollerLastY = event.getY(i)
                        place(event.getX(i), event.getY(i))
                        listener.onMove(x, y)
                    }
                    return true
                }
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
                if (edgeScroll) {
                    val si = if (edgeRest && scrollerId != -1) event.findPointerIndex(scrollerId) else -1
                    if (si >= 0) {
                        // Finger down = content down, as the two-finger scroll.
                        listener.onScroll(-(event.getY(si) - scrollerLastY) * SCROLL_UNITS_PER_PX)
                        scrollerLastY = event.getY(si)
                    }
                    return true
                }
                if (edgeRest) {
                    // Alone, the resting finger may still turn out to be a drag of the pointer.
                    if (abs(event.x - downX) + abs(event.y - downY) <= slopPx * EDGE_REST_SLOP) {
                        travelled += abs(event.x - lastX) + abs(event.y - lastY)
                        lastX = event.x; lastY = event.y
                        return true
                    }
                    edgeRest = false
                }
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
                if (edgeScroll) {
                    // The scrolling finger may touch again; once the resting one lifts, nothing more
                    // happens until every finger is up.
                    if (gone == scrollerId) scrollerId = -1
                    if (gone == anchorId) edgeRest = false
                    return true
                }
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
                    edgeScroll -> {}
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
                edgeRest = false; edgeScroll = false; scrollerId = -1
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
        private const val EDGE_REST_SLOP = 2f
    }
}
