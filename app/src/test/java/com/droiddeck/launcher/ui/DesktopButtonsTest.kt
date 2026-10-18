package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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

    @Before fun setUp() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            DesktopButtons(
                onKeyboard = { calls += "keyboard" },
                onSend = { calls += "send" },
                onScroll = { calls += "scroll $it" },
                onBuzz = {},
            )
        }
    }

    @Test fun paceGrowsWithEachButtonHeightOfTheSlide() {
        assertEquals(0, scrollPace(5f, 100f, 10f))
        assertEquals(1, scrollPace(60f, 100f, 10f))
        assertEquals(2, scrollPace(200f, 100f, 10f))
        assertEquals(3, scrollPace(400f, 100f, 10f))
        assertEquals(-2, scrollPace(-200f, 100f, 10f))
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
        compose.onNodeWithTag("desktop-keyboard").performTouchInput { moveBy(Offset(0f, height * 2f)) }
        compose.mainClock.advanceTimeBy(1000)
        val scrolled = calls.count { it == "scroll 1" }
        assertTrue("normal pace, about ten a second: $calls", scrolled in 6..14)
        compose.onNodeWithTag("desktop-keyboard").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(scrolled, calls.count { it == "scroll 1" })
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
