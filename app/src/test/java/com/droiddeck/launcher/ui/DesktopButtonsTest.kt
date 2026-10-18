package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h900dp")
class DesktopButtonsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private var scrolled = 0f

    @Before fun setUp() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            DesktopButtons(
                onKeyboard = { calls += "keyboard" },
                onSend = { calls += "send" },
                onScroll = { scrolled += it },
                onBuzz = {},
            )
        }
    }

    @Test fun speedRisesSmoothlyThroughSlowNormalAndFast() {
        assertEquals(0f, scrollSpeed(5f, 100f, 10f), 0f)
        assertEquals(30f, scrollSpeed(100f, 100f, 10f), 0.01f)
        assertEquals(65f, scrollSpeed(150f, 100f, 10f), 0.01f)
        assertEquals(-100f, scrollSpeed(-200f, 100f, 10f), 0.01f)
        assertEquals(250f, scrollSpeed(300f, 100f, 10f), 0.01f)
        assertEquals(250f, scrollSpeed(900f, 100f, 10f), 0.01f)
        assertTrue(scrollSpeed(20f, 100f, 10f) in 5f..15f)
        assertEquals(listOf(0, 1, 2, 3, 3), listOf(50f, 120f, 250f, 300f, 700f).map { scrollTier(it, 100f) })
    }

    @Test fun aTapIsTheButtonsAction() {
        compose.onNodeWithTag("desktop-keyboard").performTouchInput { down(center); up() }
        compose.onNodeWithTag("desktop-send").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(listOf("keyboard", "send"), calls)
    }

    @Test fun aSlideFromTheKeyboardButtonScrollsUntilTheFingerLifts() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("desktop-keyboard").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithTag("desktop-keyboard").performTouchInput { moveBy(Offset(0f, 88.dp.toPx())) }
        compose.mainClock.advanceTimeBy(1000)
        // Two button heights: the normal pace, ten notches (100 units) a second, a frame at a time.
        val second = scrolled
        assertTrue("about 100 units in a second: $second", second in 70f..130f)
        compose.onNodeWithTag("desktop-keyboard").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(second, scrolled, 0.01f)
        assertTrue(calls.none { it == "keyboard" })
    }

    @Test fun aButtonHeldStillForTwoSecondsMovesToWhereItIsDropped() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("desktop-send").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2100)
        compose.onNodeWithTag("desktop-send").performTouchInput {
            moveBy(Offset(-width * 12f, height * 3f))
            up()
        }
        compose.mainClock.advanceTimeBy(100)
        val spot = SessionPrefs.desktopButton(context, "send", portrait = true)
        assertEquals(false, spot?.first)
        assertTrue(calls.isEmpty())
    }
}
