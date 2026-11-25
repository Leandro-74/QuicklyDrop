package dev.leandro.quicklydrop.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single log for the app. Writes to logcat (tag QuicklyDropDiag) and keeps the list the UI shows,
 * so the same report can be read via `adb logcat` without depending on the screen.
 */
object DiagLog {

    const val TAG = "QuicklyDropDiag"

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(message: String) {
        Log.i(TAG, message)
        val line = "${time.format(Date())}  $message"
        _lines.update { it + line }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
