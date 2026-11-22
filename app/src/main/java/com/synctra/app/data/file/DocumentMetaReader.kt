package com.synctra.app.data.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

data class SelectedFileInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

object DocumentMetaReader {

    fun readInfo(context: Context, uri: Uri): SelectedFileInfo {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var sizeBytes = -1L

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    displayName = cursor.getString(nameIdx)
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        }

        return SelectedFileInfo(
            uri = uri,
            displayName = displayName,
            sizeBytes = sizeBytes,
        )
    }

    @Throws(IOException::class)
    fun readAllBytes(context: Context, uri: Uri, maxBytes: Long = 10L * 1024L * 1024L): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Tidak bisa membuka file yang dipilih.")
        return stream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > maxBytes) {
                throw IOException("File subtitle terlalu besar (> ${maxBytes / (1024 * 1024)} MB).")
            }
            bytes
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return ""
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
