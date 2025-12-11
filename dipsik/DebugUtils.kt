// File: DebugUtils.kt (enhanced version)
package com.example.syncaudio

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object DebugUtils {
    private const val TAG = "SyncAudioDebug"
    private var isDebugEnabled = true
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Log levels
    private const val LOG_VERBOSE = 0
    private const val LOG_DEBUG = 1
    private const val LOG_INFO = 2
    private const val LOG_WARN = 3
    private const val LOG_ERROR = 4

    private var currentLogLevel = LOG_DEBUG
    private var maxLogEntries = 1000

    fun enableDebugging(enabled: Boolean) {
        isDebugEnabled = enabled
        log("🔧 Debug logging ${if (enabled) "enabled" else "disabled"}")
    }

    fun setLogLevel(level: Int) {
        currentLogLevel = level
        log("🔧 Log level set to: $level")
    }

    fun setMaxLogEntries(max: Int) {
        maxLogEntries = max
        log("🔧 Max log entries set to: $max")
    }

    fun getLogs(): List<String> {
        return logQueue.toList()
    }

    fun clearLogs() {
        logQueue.clear()
        log("🧹 Debug logs cleared")
    }

    private fun addToQueue(message: String, emoji: String = "📱") {
        val timestamp = dateFormat.format(Date())
        val logEntry = "$emoji [$timestamp] $message"
        logQueue.add(logEntry)

        // Keep only the latest entries
        while (logQueue.size > maxLogEntries) {
            logQueue.poll()
        }
    }

    fun log(message: String, level: Int = LOG_DEBUG) {
        if (isDebugEnabled && level >= currentLogLevel) {
            addToQueue(message)
            Log.d(TAG, message)
        }
    }

    fun logError(message: String, error: Exception? = null) {
        if (isDebugEnabled) {
            addToQueue("❌ $message", "❌")
            Log.e(TAG, message, error)
        }
    }

    fun logSuccess(message: String) {
        if (isDebugEnabled) {
            addToQueue("✅ $message", "✅")
            Log.i(TAG, message)
        }
    }

    fun logWarning(message: String) {
        if (isDebugEnabled) {
            addToQueue("⚠️ $message", "⚠️")
            Log.w(TAG, message)
        }
    }

    fun logNetwork(message: String) {
        if (isDebugEnabled) {
            addToQueue("🌐 $message", "🌐")
            Log.d(TAG, message)
        }
    }

    fun logFile(message: String) {
        if (isDebugEnabled) {
            addToQueue("📁 $message", "📁")
            Log.d(TAG, message)
        }
    }

    fun logTime(message: String) {
        if (isDebugEnabled) {
            addToQueue("⏰ $message", "⏰")
            Log.d(TAG, message)
        }
    }

    fun logAudio(message: String) {
        if (isDebugEnabled) {
            addToQueue("🎵 $message", "🎵")
            Log.d(TAG, message)
        }
    }

    fun logConnection(message: String) {
        if (isDebugEnabled) {
            addToQueue("🔗 $message", "🔗")
            Log.d(TAG, message)
        }
    }

    fun logPerformance(message: String) {
        if (isDebugEnabled) {
            addToQueue("⚡ $message", "⚡")
            Log.d(TAG, message)
        }
    }

    fun logState(message: String) {
        if (isDebugEnabled) {
            addToQueue("🔄 $message", "🔄")
            Log.d(TAG, message)
        }
    }

    // Utility to log method entry/exit with timing
    inline fun <T> trace(blockName: String, block: () -> T): T {
        logState("▶️ Entering: $blockName")
        val startTime = System.currentTimeMillis()
        try {
            val result = block()
            val duration = System.currentTimeMillis() - startTime
            logPerformance("⏱️  $blockName completed in ${duration}ms")
            logState("◀️ Exiting: $blockName")
            return result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logError("💥 $blockName failed after ${duration}ms", e)
            logState("◀️ Exiting (with error): $blockName")
            throw e
        }
    }
}