package com.droiddeck.launcher.input

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The guest's input method, for text the soft keyboard composes that key presses cannot carry.
 * The ibus engine in tools/android-ime listens on a socket in the Wayland runtime dir, shared
 * with the guest, and says whether a program has a text field under it; while one does, composed
 * and committed text is sent there as-is. One JSON object per line, both ways.
 */
object GuestTextInput {
    private const val TAG = "GuestTextInput"
    private const val SOCKET = ".wayland-rt/droiddeck-ime.sock"

    @Volatile var focused = false
        private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var socket: LocalSocket? = null
    private var out: OutputStream? = null
    private var connecting = false
    private var wantShown = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    @Synchronized fun connect(context: Context) {
        if (socket != null || connecting) return
        val path = File(context.filesDir, SOCKET)
        if (!path.exists()) return
        connecting = true
        Thread({ serve(path) }, "guest-text-input").apply { isDaemon = true }.start()
    }

    fun keyboardShown(shown: Boolean) {
        synchronized(this) { wantShown = shown }
        send(JSONObject().put("t", if (shown) "show" else "hide"))
    }

    fun commit(text: CharSequence): Boolean =
        send(JSONObject().put("t", "commit").put("text", text.toString()))

    fun preedit(text: CharSequence, cursor: Int): Boolean =
        send(JSONObject().put("t", "preedit").put("text", text.toString()).put("cursor", cursor))

    private fun serve(path: File) {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
        } catch (e: IOException) {
            Log.i(TAG, "no guest input method at ${path.name}: $e")
            synchronized(this) { connecting = false }
            return
        }
        val shown = synchronized(this) { socket = s; out = s.outputStream; connecting = false; wantShown }
        Log.i(TAG, "connected to the guest input method")
        if (shown) send(JSONObject().put("t", "show"))
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                val msg = JSONObject(line)
                if (msg.optString("t") == "focus") setFocused(msg.optBoolean("on"))
            }
        } catch (e: Exception) {
            Log.i(TAG, "guest input method: $e")
        }
        synchronized(this) { if (socket === s) { socket = null; out = null } }
        runCatching { s.close() }
        setFocused(false)
        Log.i(TAG, "guest input method gone")
    }

    private fun setFocused(on: Boolean) {
        if (focused == on) return
        focused = on
        main.post { for (l in listeners) l() }
    }

    @Synchronized private fun send(msg: JSONObject): Boolean {
        val o = out ?: return false
        return try {
            o.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
            true
        } catch (e: IOException) {
            Log.w(TAG, "guest input method: $e")
            runCatching { socket?.close() }
            false
        }
    }
}
