package com.droiddeck.launcher.input

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TouchpadGesturesTest {
    private val calls = mutableListOf<String>()
    private lateinit var pad: TouchpadGestures
    private var time = 1000L

    @Before fun setUp() {
        pad = TouchpadGestures(10f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) { calls += "move ${x.toInt()},${y.toInt()}" }
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { calls += "button $button $pressed" }
            override fun onWheel(steps: Int) { calls += "wheel $steps" }
            override fun onScroll(units: Float) { calls += "scroll ${Math.round(units)}" }
            override fun onLongPress() { calls += "long" }
        })
        pad.bounds = android.graphics.RectF(0f, 0f, 2000f, 2000f)
        pad.place(1000f, 1000f)
        pad.restPx = 150f
    }

    @Test fun withAThumbRestingInTheStripTheOtherFingerMovesThePointerFromWhereItWas() {
        touch(MotionEvent.ACTION_DOWN, 0, 40f to 900f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 40f to 900f, 1200f to 600f)
        touch(MotionEvent.ACTION_MOVE, 0, 41f to 902f, 1200f to 640f)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 41f to 902f, 1200f to 640f)
        touch(MotionEvent.ACTION_UP, 0, 41f to 902f)
        // Relative, from (1000, 1000): no jump to the finger at (1200, 600), no button, no scroll.
        val move = calls.single()
        assertTrue(move, move.startsWith("move 1000,") && move.substringAfter(",").toInt() > 1000)
    }

    @Test fun withAThumbRestingInTheStripTheOtherFingersTapIsAClick() {
        touch(MotionEvent.ACTION_DOWN, 0, 40f to 900f)
        time += 400
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 40f to 900f, 1200f to 600f)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 40f to 900f, 1200f to 600f)
        time += 400
        touch(MotionEvent.ACTION_UP, 0, 40f to 900f)
        assertEquals(listOf("button ${PointerGestures.BTN_LEFT} true", "button ${PointerGestures.BTN_LEFT} false"), calls)
    }

    @Test fun twoFingersBesideARestingThumbStillScroll() {
        touch(MotionEvent.ACTION_DOWN, 0, 40f to 900f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 40f to 900f, 600f to 900f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 40f to 900f, 600f to 900f, 800f to 900f)
        touch(MotionEvent.ACTION_MOVE, 0, 40f to 900f, 600f to 930f, 800f to 930f)
        touch(MotionEvent.ACTION_MOVE, 0, 40f to 900f, 600f to 966f, 800f to 966f)
        assertEquals(listOf("scroll -10"), calls)
    }

    @Test fun twoFingersScrollSmoothlyOncePastTheSlop() {
        touch(MotionEvent.ACTION_DOWN, 0, 600f to 900f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 600f to 900f, 800f to 900f)
        touch(MotionEvent.ACTION_MOVE, 0, 600f to 905f, 800f to 905f)
        touch(MotionEvent.ACTION_MOVE, 0, 600f to 930f, 800f to 930f)
        touch(MotionEvent.ACTION_MOVE, 0, 600f to 966f, 800f to 966f)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 600f to 966f, 800f to 966f)
        touch(MotionEvent.ACTION_UP, 0, 600f to 966f)
        assertEquals(listOf("scroll -10"), calls)
    }

    @Test fun outsideTheStripAHeldFingerStillDragsWithTheLeftButton() {
        touch(MotionEvent.ACTION_DOWN, 0, 600f to 900f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 600f to 900f, 1200f to 600f)
        touch(MotionEvent.ACTION_MOVE, 0, 600f to 900f, 1200f to 700f)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 600f to 900f, 1200f to 700f)
        touch(MotionEvent.ACTION_UP, 0, 600f to 900f)
        assertEquals("button ${PointerGestures.BTN_LEFT} true", calls.first())
        assertTrue(calls.none { it.startsWith("scroll") })
    }

    @Test fun aTapInTheStripIsStillAClick() {
        touch(MotionEvent.ACTION_DOWN, 0, 40f to 900f)
        touch(MotionEvent.ACTION_UP, 0, 40f to 900f)
        assertEquals(listOf("button ${PointerGestures.BTN_LEFT} true", "button ${PointerGestures.BTN_LEFT} false"), calls)
    }

    @Test fun aFingerSlidingOutOfTheStripMovesThePointer() {
        touch(MotionEvent.ACTION_DOWN, 0, 40f to 900f)
        touch(MotionEvent.ACTION_MOVE, 0, 50f to 900f)
        assertTrue(calls.isEmpty())
        touch(MotionEvent.ACTION_MOVE, 0, 90f to 900f)
        touch(MotionEvent.ACTION_MOVE, 0, 130f to 900f)
        touch(MotionEvent.ACTION_UP, 0, 130f to 900f)
        assertTrue(calls.isNotEmpty() && calls.all { it.startsWith("move ") })
    }

    /** One event; [index] is the action index, the pairs every finger's x to y, ids 0, 1, … */
    private fun touch(action: Int, index: Int, vararg fingers: Pair<Float, Float>) {
        time += 20
        val properties = Array(fingers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(fingers.size) { MotionEvent.PointerCoords().apply { x = fingers[it].first; y = fingers[it].second } }
        val masked = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(1000L, time, masked, fingers.size, properties, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        pad.onTouch(event)
        event.recycle()
    }
}
