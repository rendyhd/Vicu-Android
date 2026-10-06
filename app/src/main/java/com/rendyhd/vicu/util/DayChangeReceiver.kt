package com.rendyhd.vicu.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Tells [DayClock] to re-read the date when the system changes the date, the time or the time
 * zone. Registered at runtime for the life of the process: these are protected system
 * broadcasts and the clock only matters while the process is alive.
 */
class DayChangeReceiver(private val dayClock: DayClock) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        dayClock.refresh()
    }

    companion object {
        fun register(context: Context, dayClock: DayClock) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                context,
                DayChangeReceiver(dayClock),
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
