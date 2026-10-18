package com.droiddeck.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
 *  - slide up or down ........ (the keyboard button) scrolls what is under the pointer, smoothly,
 *                              held like a joystick: the further from where the finger came down,
 *                              the faster - slow at about one button's height, normal at two, fast
 *                              at three, and at four fast enough to run to the end of a long page,
 *                              with a buzz at each - until it lifts
 *  - hold still two seconds .. picks the button up: it goes where it is dropped, against the nearer
 *                              edge, remembered apart for portrait and landscape. Two seconds keeps
 *                              a slow start of a scroll from turning into a move.
 *
 * The buttons sit on the screen's edge, inside Android's back-gesture strip, so they are excluded
 * from it: a thumb that drifts sideways mid-scroll would otherwise be taken for Back, which cancels
 * the touch and the scroll with it.
 */
@Composable
internal fun DesktopButtons(
    onKeyboard: () -> Unit,
    onSend: () -> Unit,
    onScroll: (units: Float) -> Unit,
    /** A finger came down on a button (true) or the last one lifted (false). */
    onHold: (Boolean) -> Unit,
    onBuzz: (ms: Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DesktopButton("keyboard", Icons.Outlined.Keyboard, stringResource(R.string.pckb_android), 44.dp,
            onKeyboard, onScroll, onHold, onBuzz)
        DesktopButton("send", Icons.AutoMirrored.Outlined.Send, stringResource(R.string.desktop_send), (-16).dp,
            onSend, null, onHold, onBuzz)
    }
}

/** Wheel notches a second at one, two, three and four button heights from where the finger came down. */
private val SCROLL_NOTCHES = floatArrayOf(3f, 10f, 25f, 80f)

/**
 * The joystick's speed in scroll axis units a second (a wheel notch is 10, down = positive) for a
 * finger [dy] pixels from where it came down: nothing inside the touch slop, then rising smoothly
 * from half a notch a second through [SCROLL_NOTCHES], and no faster past the last of them.
 */
internal fun scrollSpeed(dy: Float, button: Float, slop: Float): Float {
    val distance = abs(dy)
    if (distance <= slop) return 0f
    val heights = distance / button
    val notches = when {
        heights >= SCROLL_NOTCHES.size -> SCROLL_NOTCHES.last()
        heights < 1f -> 0.5f + (SCROLL_NOTCHES[0] - 0.5f) * heights
        else -> {
            val i = heights.toInt()
            SCROLL_NOTCHES[i - 1] + (SCROLL_NOTCHES[i] - SCROLL_NOTCHES[i - 1]) * (heights - i)
        }
    }
    return notches * 10f * dy.sign
}

/** Which button height [dy] has reached, 0 to the number of paces: the joystick buzzes as it crosses each. */
internal fun scrollTier(dy: Float, button: Float): Int = (abs(dy) / button).toInt().coerceAtMost(SCROLL_NOTCHES.size)

private const val MOVE_HOLD_MS = 2000L
private val BUTTON = 44.dp
/** What a thumb has to hit: wider than the button drawn, since a touch that misses it lands on the
 *  touchpad under it. The button is drawn [EDGE_GAP] from the screen's edge. */
private val TOUCH = 64.dp
private val EDGE_GAP = 10.dp

private enum class Start { TAP, SLIDE, NONE }

/** Where the buttons may go: the screen they sit on, the touch area's size and its margin from the
 *  edge, and the drawn button's size, in pixels. */
private data class Area(val width: Float, val height: Float, val size: Float, val margin: Float, val button: Float, val portrait: Boolean)

/** One button; [fromMiddle] places it until it is moved, its middle that far below the screen's. */
@Composable
private fun BoxWithConstraintsScope.DesktopButton(
    name: String,
    icon: ImageVector,
    label: String,
    fromMiddle: Dp,
    onClick: () -> Unit,
    onScroll: ((Float) -> Unit)?,
    onHold: (Boolean) -> Unit,
    onBuzz: (Long) -> Unit,
) {
    val context = LocalContext.current
    val hold by rememberUpdatedState(onHold)
    val density = LocalDensity.current
    val portrait = maxHeight > maxWidth
    val height = constraints.maxHeight.toFloat()
    val area by rememberUpdatedState(Area(constraints.maxWidth.toFloat(), height, with(density) { TOUCH.toPx() },
        with(density) { (EDGE_GAP - (TOUCH - BUTTON) / 2).coerceAtLeast(0.dp).toPx() }, with(density) { BUTTON.toPx() }, portrait))
    val saved = remember(portrait) { SessionPrefs.desktopButton(context, name, portrait) }
    var right by remember(portrait) { mutableStateOf(saved?.first ?: true) }
    var middle by remember(portrait) { mutableStateOf(saved?.second ?: ((height / 2 + with(density) { fromMiddle.toPx() }) / height)) }
    var carried by remember { mutableStateOf<Offset?>(null) }
    var scrolling by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val scroll by rememberUpdatedState(onScroll)

    fun placed(a: Area) = Offset(
        if (right) a.width - a.margin - a.size else a.margin,
        (middle * a.height - a.size / 2).coerceIn(a.margin, (a.height - a.margin - a.size).coerceAtLeast(a.margin)),
    )

    // The wheel turns a frame at a time while the finger is out, at the speed the finger last set:
    // a little each frame is what makes it smooth.
    val scope = rememberCoroutineScope()
    val speed = remember { mutableFloatStateOf(0f) }
    var wheel by remember { mutableStateOf<Job?>(null) }
    var sent = 0f
    fun startWheel() {
        if (wheel != null) return
        scrolling = true
        sent = 0f
        wheel = scope.launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val units = speed.floatValue * seconds
                if (units != 0f) scroll?.invoke(units)
                sent += units
            }
        }
    }
    fun stopWheel() {
        if (wheel != null) android.util.Log.i("DesktopInput", "button $name: scrolled ${sent.toInt()} units")
        wheel?.cancel()
        wheel = null
        speed.floatValue = 0f
        scrolling = false
    }

    val at = carried ?: placed(area)
    Box(
        modifier = Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(TOUCH)
            .systemGestureExclusion()
            .testTag("desktop-$name")
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { click(); true }
            }
            .pointerInput(portrait) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    hold(true)
                    try {
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
                        var tier = 0
                        fun steer(y: Float) {
                            val dy = y - down.position.y
                            val next = scrollTier(dy, area.button)
                            if (next != tier && next > 0) onBuzz(8)
                            tier = next
                            speed.floatValue = scrollSpeed(dy, area.button, viewConfiguration.touchSlop)
                        }
                        android.util.Log.i("DesktopInput", "button $name: ${start ?: "held"} (down at ${down.position.x.toInt()},${down.position.y.toInt()} of ${area.size.toInt()})")
                        when (start) {
                            Start.TAP -> click()
                            Start.SLIDE -> if (scroll != null) {
                                steer(lastY)
                                startWheel()
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
                                var spot = placed(area)
                                carried = spot
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    val a = area
                                    spot = Offset(
                                        (spot.x + change.positionChange().x).coerceIn(0f, a.width - a.size),
                                        (spot.y + change.positionChange().y).coerceIn(0f, a.height - a.size),
                                    )
                                    carried = spot
                                    change.consume()
                                }
                                val a = area
                                right = spot.x + a.size / 2 > a.width / 2
                                middle = (spot.y + a.size / 2) / a.height
                                SessionPrefs.setDesktopButton(context, name, a.portrait, right, middle)
                            }
                        }
                    } finally {
                        // A gesture cut short (the window resized, the touch cancelled) stops too.
                        stopWheel()
                        carried = null
                        hold(false)
                    }
                }
            },
    ) {
        Surface(
            shape = CircleShape,
            color = if (carried != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            contentColor = if (carried != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center).size(BUTTON),
        ) {
            Icon(if (scrolling) Icons.Outlined.SwapVert else icon, contentDescription = null, modifier = Modifier.padding(10.dp))
        }
    }
}
