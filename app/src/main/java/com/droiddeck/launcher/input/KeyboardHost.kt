package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class KeyboardHost(
    context: Context,
    private val sendKeyEvent: (KeyEvent) -> Boolean,
) : View(context) {
    constructor(activity: Activity) : this(activity, { activity.dispatchKeyEvent(it) })

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val map: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    var shown = false
        private set

    /** The soft keyboard settled shown (with its height in window pixels) or hidden. */
    var onImeChanged: ((visible: Boolean, height: Int) -> Unit)? = null

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = VISIBLE
        layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = shown

    private val guestFocusChanged = { if (shown) imm.restartInput(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GuestTextInput.addListener(guestFocusChanged)
        GuestTextInput.connect(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>) = insets
                override fun onEnd(animation: WindowInsetsAnimation) {
                    if ((animation.typeMask and WindowInsets.Type.ime()) != 0) imeSettled()
                }
            })
        }
    }

    // The system hides the keyboard too (back, its own hide key); then the state still says shown
    // and the next toggle would hide nothing. Seen only once the animation ends: during it the
    // insets already read hidden.
    private fun imeSettled() {
        val insets = rootWindowInsets ?: return
        val visible = insets.isVisible(WindowInsets.Type.ime())
        if (shown && !visible) dismissed()
        onImeChanged?.invoke(visible, if (visible) insets.getInsets(WindowInsets.Type.ime()).bottom else 0)
    }

    override fun onDetachedFromWindow() {
        GuestTextInput.removeListener(guestFocusChanged)
        super.onDetachedFromWindow()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!shown) return null
        // A text field under the guest's input method takes text as it is, so the keyboard may
        // compose, in any of its languages; elsewhere it sends what was pressed, key by key.
        outAttrs.inputType = if (GuestTextInput.focused) InputType.TYPE_CLASS_TEXT
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return KeyConnection()
    }

    fun toggle() = if (shown && imeVisible()) hide() else show()

    private fun imeVisible() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false

    fun show() {
        shown = true
        GuestTextInput.connect(context)
        GuestTextInput.keyboardShown(true)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        imm.hideSoftInputFromWindow(windowToken, 0)
        dismissed()
    }

    private fun dismissed() {
        shown = false
        GuestTextInput.keyboardShown(false)
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
    }

    private inner class KeyConnection : BaseInputConnection(this, false) {
        private var composing = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val guest = GuestTextInput.focused
            if (guest && composing.isNotEmpty() && text.isNullOrEmpty()) GuestTextInput.preedit("", 0)
            composing = ""
            if (text.isNullOrEmpty()) return true
            if (guest && GuestTextInput.commit(text)) return true
            val events = map.getEvents(text.toString().toCharArray())
            if (events != null) {
                for (event in events) sendKeyEvent(event)
            } else {
                for (ch in text) {
                    val t = System.currentTimeMillis()
                    val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, 0)
                    sendKeyEvent(CharKeyEvent(down, ch))
                    sendKeyEvent(CharKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), ch))
                }
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!GuestTextInput.focused) return true
            composing = text?.toString().orEmpty()
            val cursor = if (newCursorPosition > 0) composing.codePointCount(0, composing.length) else 0
            GuestTextInput.preedit(composing, cursor)
            return true
        }

        override fun finishComposingText(): Boolean {
            val text = composing
            composing = ""
            if (text.isNotEmpty() && GuestTextInput.focused) GuestTextInput.commit(text)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) {
                composing = ""
                if (GuestTextInput.focused) GuestTextInput.preedit("", 0)
            }
            repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = this@KeyboardHost.sendKeyEvent(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            tap(KeyEvent.KEYCODE_ENTER)
            return true
        }

        private fun tap(keyCode: Int) {
            val t = System.currentTimeMillis()
            sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    private class CharKeyEvent(origin: KeyEvent, private val ch: Char) : KeyEvent(origin) {
        override fun getUnicodeChar(): Int = ch.code
        override fun getUnicodeChar(metaState: Int): Int = ch.code
    }
}
