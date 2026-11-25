package com.synctra.app.data.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

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
        return stream.use { input -> readBoundedBytes(input, maxBytes) }
    }

    @Throws(IOException::class)
    internal fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var totalRead = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            if (read == 0) continue
            totalRead += read
            if (totalRead > maxBytes) {
                throw IOException("File subtitle terlalu besar (> ${maxBytes / (1024 * 1024)} MB).")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    @Throws(IOException::class)
    fun writeUtf8Text(context: Context, uri: Uri, content: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open destination file for writing.")
        stream.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return ""
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
