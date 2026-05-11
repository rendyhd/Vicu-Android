package com.rendyhd.vicu.util

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a short sound when the user completes a task. Default is the system's
 * notification sound; the user can swap in a custom audio file via Settings.
 *
 * - Reads enabled / URI from [BehaviorPrefsStore] on every play() call so changes
 *   in Settings take effect immediately.
 * - Any failure (missing file, codec issue, no notification sound configured)
 *   is logged and swallowed — completion sound is a non-essential affordance.
 */
@Singleton
class CompletionSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: BehaviorPrefsStore,
) {
    companion object {
        private const val TAG = "CompletionSoundPlayer"
    }

    suspend fun play() {
        val current = prefs.getPrefs().first()
        if (!current.completionSoundEnabled) return

        val uri: Uri? = current.completionSoundUri
            ?.takeIf { it.isNotBlank() }
            ?.toUri()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) {
            Log.d(TAG, "No completion sound URI resolved; silent")
            return
        }

        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnErrorListener { player, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    player.release()
                    true
                }
                setOnCompletionListener { it.release() }
                prepare()
            }
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play completion sound: ${e.message}")
        }
    }
}
