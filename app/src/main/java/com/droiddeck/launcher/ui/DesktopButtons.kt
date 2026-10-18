package com.droiddeck.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The desktop session's own buttons over the picture: the soft keyboard, and Send, which types
 * Ctrl+Enter - what sends in the Claude Code extension set to Ctrl+Enter, and in most chat boxes -
 * since reaching a program's own Send button with the touchpad is a long trip. Nothing else of the
 * app's is on the desktop's screen, and the drawer's Android button is two gestures away.
 *
 *  - tap ..................... the button's action
 *  - slide up or down ........ (the keyboard button) the scroll wheel under the pointer, held like a
 *                              joystick: about one button's height from where the finger came down
 *                              scrolls slowly, two at a normal pace, three fast, until it lifts
 *  - hold still two seconds .. picks the button up: it goes where it is dropped, against the nearer
 *                              edge, remembered apart for portrait and landscape. Two seconds keeps
 *                              a slow start of a scroll from turning into a move.
 */
@Composable
internal fun DesktopButtons(
    onKeyboard: () -> Unit,
    onSend: () -> Unit,
    onScroll: (steps: Int) -> Unit,
    onBuzz: (ms: Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DesktopButton("keyboard", Icons.Outlined.Keyboard, stringResource(R.string.pckb_android), 44.dp,
            onKeyboard, onScroll, onBuzz)
        DesktopButton("send", Icons.AutoMirrored.Outlined.Send, stringResource(R.string.desktop_send), (-16).dp,
            onSend, null, onBuzz)
    }
}

/** The scroll pace, signed like the wheel (down = positive), for a finger [dy] pixels from where it
 *  came down: none inside the touch slop, then slow, normal and fast by the button's height. */
internal fun scrollPace(dy: Float, button: Float, slop: Float): Int {
    val distance = abs(dy)
    val pace = when {
        distance <= slop -> 0
        distance < button * 1.5f -> 1
        distance < button * 2.5f -> 2
        else -> 3
    }
    return pace * dy.sign.toInt()
}

/** Wheel steps per pace: one every this many milliseconds, slow to fast. */
private val SCROLL_EVERY_MS = longArrayOf(250, 100, 40)
private const val MOVE_HOLD_MS = 2000L
private val BUTTON = 44.dp
private val EDGE_MARGIN = 10.dp

private enum class Start { TAP, SLIDE, NONE }

/** One button; [fromMiddle] places it until it is moved, its middle that far below the screen's. */
@Composable
private fun BoxWithConstraintsScope.DesktopButton(
    name: String,
    icon: ImageVector,
    label: String,
    fromMiddle: Dp,
    onClick: () -> Unit,
    onScroll: ((Int) -> Unit)?,
    onBuzz: (Long) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val portrait = maxHeight > maxWidth
    val width = constraints.maxWidth.toFloat()
    val height = constraints.maxHeight.toFloat()
    val size = with(density) { BUTTON.toPx() }
    val margin = with(density) { EDGE_MARGIN.toPx() }
    val saved = remember(portrait) { SessionPrefs.desktopButton(context, name, portrait) }
    var right by remember(portrait) { mutableStateOf(saved?.first ?: true) }
    var middle by remember(portrait) { mutableStateOf(saved?.second ?: ((height / 2 + with(density) { fromMiddle.toPx() }) / height)) }
    var carried by remember { mutableStateOf<Offset?>(null) }
    var pace by remember { mutableIntStateOf(0) }
    val click by rememberUpdatedState(onClick)
    val scroll by rememberUpdatedState(onScroll)

    fun placed() = Offset(
        if (right) width - margin - size else margin,
        (middle * height - size / 2).coerceIn(margin, (height - margin - size).coerceAtLeast(margin)),
    )

    // Started from the gesture itself, not from a recomposition: the wheel turns on the move that
    // asked for it, a frame sooner.
    val scope = rememberCoroutineScope()
    var wheel by remember { mutableStateOf<Job?>(null) }
    fun turn(next: Int) {
        if (next == pace) return
        wheel?.cancel()
        wheel = if (next == 0) null else scope.launch {
            while (true) {
                scroll?.invoke(next.sign)
                delay(SCROLL_EVERY_MS[abs(next) - 1])
            }
        }
        pace = next
    }

    val at = carried ?: placed()
    Surface(
        shape = CircleShape,
        color = if (carried != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = if (carried != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(BUTTON)
            .testTag("desktop-$name")
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { click(); true }
            }
            .pointerInput(portrait, width, height) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var lastY = down.position.y
                    val start = withTimeoutOrNull(MOVE_HOLD_MS) {
                        var seen: Start? = null
                        while (seen == null) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            seen = when {
                                change == null -> Start.NONE
                                !change.pressed ->
                                    if (change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) Start.TAP else Start.NONE
                                abs(change.position.y - down.position.y) > viewConfiguration.touchSlop ||
                                    abs(change.position.x - down.position.x) > viewConfiguration.touchSlop -> Start.SLIDE
                                else -> null
                            }
                            change?.let { lastY = it.position.y; it.consume() }
                        }
                        seen
                    }
                    fun steer(y: Float) {
                        if (scroll == null) return
                        val next = scrollPace(y - down.position.y, size, viewConfiguration.touchSlop)
                        if (next != 0 && abs(next) != abs(pace)) onBuzz(8)
                        turn(next)
                    }
                    when (start) {
                        Start.TAP -> click()
                        Start.SLIDE -> {
                            steer(lastY)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) break
                                change.consume()
                                steer(change.position.y)
                            }
                        }
                        Start.NONE -> {}
                        null -> {
                            // Held still: carried until dropped.
                            onBuzz(30)
                            var spot = placed()
                            carried = spot
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) break
                                spot = Offset(
                                    (spot.x + change.positionChange().x).coerceIn(0f, width - size),
                                    (spot.y + change.positionChange().y).coerceIn(0f, height - size),
                                )
                                carried = spot
                                change.consume()
                            }
                            right = spot.x + size / 2 > width / 2
                            middle = (spot.y + size / 2) / height
                            carried = null
                            SessionPrefs.setDesktopButton(context, name, portrait, right, middle)
                        }
                    }
                    turn(0)
                }
            },
    ) {
        Icon(if (pace != 0) Icons.Outlined.SwapVert else icon, contentDescription = null, modifier = Modifier.padding(10.dp))
    }
}
