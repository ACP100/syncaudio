// File: FileSelectionManager.kt
package com.example.syncaudio

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FileSelectionManager {
    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    fun setSelectedFile(uri: Uri?) {
        _selectedFileUri.value = uri
        DebugUtils.log("📁 Selected file URI updated: $uri")
    }

    fun getSelectedFile(): Uri? {
        return _selectedFileUri.value
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
        DebugUtils.log("📁 Selected file URI cleared")
    }
}