package com.rendyhd.vicu.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

object FileUtils {

    fun uriToMultipartPart(context: Context, uri: Uri): MultipartBody.Part? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = getFileName(context, uri) ?: "file"

            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }

            val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            MultipartBody.Part.createFormData("files", fileName, requestBody)
        } catch (_: Exception) {
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }
}
