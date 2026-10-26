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
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO)
    }

    fun handleResult(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        onFilePicked: (List<Uri>) -> Unit
    ) {
        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == Activity.RESULT_OK) {
            val clips = data?.clipData
            val uris = if (clips != null) {
                (0 until clips.itemCount).map { clips.getItemAt(it).uri }
            } else {
                listOfNotNull(data?.data)
            }
            if (uris.isEmpty()) return

            // Persist permission
            uris.forEach { uri ->
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            onFilePicked(uris)
        }
    }
}
