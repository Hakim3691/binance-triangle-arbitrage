package com.hakim3691.bta.ui.screens

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hakim3691.bta.log.LogRepository
import java.io.File

/**
 * Publishes an export into the public Downloads folder.
 *
 * Exports used to be written under `getExternalFilesDir(null)`, which resolves
 * to `Android/data/<package>/files/`. Since Android 11 no file manager is
 * permitted to enumerate `Android/data`, so a file written there is invisible
 * to the person who just asked for it - the folder listing comes back empty
 * even though the write succeeded and the app logged an absolute path. Verified
 * on a real device: the save succeeded, the path was logged, and the file
 * manager showed no `files` directory at all.
 *
 * MediaStore writes into the shared Downloads collection, needs no storage
 * permission on API 29+, and is visible to every file manager.
 *
 * @return a user-visible location string, or null if publishing failed.
 */
fun publishToDownloads(context: Context, fileName: String, bytes: ByteArray): String? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                // Hide from the gallery; this is a log, not a photo.
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$fileName"
        } else {
            // API 26-28: WRITE_EXTERNAL_STORAGE still applies, but the app already
            // targets 34 so this branch only runs on older devices where it is
            // granted at install time.
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val out = File(dir, fileName)
            out.writeBytes(bytes)
            out.absolutePath
        }
    } catch (e: Exception) {
        LogRepository.error("logs", "Could not publish $fileName to Downloads: " + e.message)
        null
    }
}

/** Logs where an export actually ended up, in a form a person can act on. */
fun reportExportOutcome(label: String, privateCopy: File?, publicLocation: String?) {
    when {
        publicLocation != null ->
            LogRepository.info("logs", "$label saved to $publicLocation")
        privateCopy != null ->
            LogRepository.info(
                "logs",
                "$label saved to app storage only (" + privateCopy.absolutePath +
                    ") - the Downloads folder was not writable"
            )
        else ->
            LogRepository.error("logs", "Could not save $label: no writable location")
    }
}
