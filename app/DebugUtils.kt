package com.example.syncaudio

import android.util.Log

object DebugUtils {
    private const val TAG = "SyncAudioDebug"
    private var isDebugEnabled = true

    fun log(message: String) {
        if (isDebugEnabled) {
            Log.d(TAG, "📱 $message")
        }
    }

    fun logError(message: String, error: Exception? = null) {
        if (isDebugEnabled) {
            Log.e(TAG, "❌ $message", error)
        }
    }

    fun logSuccess(message: String) {
        if (isDebugEnabled) {
            Log.i(TAG, "✅ $message")
        }
    }

    fun logWarning(message: String) {
        if (isDebugEnabled) {
            Log.w(TAG, "⚠️ $message")
        }
    }

    fun logNetwork(message: String) {
        if (isDebugEnabled) {
            Log.d(TAG, "🌐 $message")
        }
    }

    fun logFile(message: String) {
        if (isDebugEnabled) {
            Log.d(TAG, "📁 $message")
        }
    }
}