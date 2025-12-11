package com.example.syncaudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

object FilePickerHelper {

    const val REQUEST_CODE_PICK_AUDIO = 101

    fun openAudioPicker(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO)
    }

    fun handleResult(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        onFilePicked: (Uri) -> Unit
    ) {
        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return

            // Persist permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            onFilePicked(uri)
        }
    }
}
