package com.rendyhd.vicu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test of the app: puts a screenshot-like image on the clipboard for
 * `scripts/ui-verify/comment-paste.mjs`, because adb has no way to copy an image. It does nothing in
 * an ordinary `connectedAndroidTest`, only with `-e seedClipboard 1`.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardSeed {
    @Test
    fun putImageOnClipboard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedClipboard") != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_vicu_check_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val image = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(10, 102, 209)) }
        checkNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newUri(resolver, "Screenshot", uri))
    }
}
