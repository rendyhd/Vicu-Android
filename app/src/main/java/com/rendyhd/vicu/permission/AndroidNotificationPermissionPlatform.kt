package com.rendyhd.vicu.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The Android side of the notification permission. The activity registers the function that
 * shows the system prompt while it is on screen ([attach]) and withdraws it when it goes ([detach]).
 */
class AndroidNotificationPermissionPlatform(
    private val context: Context,
) : NotificationPermissionPlatform {

    @Volatile
    private var launcher: (() -> Unit)? = null

    override val needsRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun isGranted(): Boolean =
        !needsRuntimePermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun launchSystemRequest(): Boolean {
        val launch = launcher ?: return false
        launch()
        return true
    }

    override fun openSystemSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun attach(launch: () -> Unit) {
        launcher = launch
    }

    /** Withdraws [launch] only if it is still the current one (a new activity may have attached already). */
    fun detach(launch: () -> Unit) {
        if (launcher === launch) launcher = null
    }
}
