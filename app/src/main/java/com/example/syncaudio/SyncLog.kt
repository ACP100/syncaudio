// File: SyncLog.kt
package com.example.syncaudio

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short log of what the sync engine is doing, shown on screen so it can be copied into a bug report. */
object SyncLog {
    private const val MAX_LINES = 400

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(message: String) {
        synchronized(this) {
            val line = "${timeFormat.format(Date())} $message"
            _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        }
        Log.d("SyncLog", message)
        DebugUtils.log(message)
    }

    fun clear() {
        synchronized(this) { _lines.value = emptyList() }
    }

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Everything worth pasting: which build and phone, then the log. */
    fun report(context: Context): String = buildString {
        appendLine("SyncAudio v${appVersion(context)}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("---")
        lines.value.forEach { appendLine(it) }
    }
}
