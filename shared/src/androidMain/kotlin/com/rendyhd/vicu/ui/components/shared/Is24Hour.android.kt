package com.rendyhd.vicu.ui.components.shared

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
actual fun rememberIs24HourFormat(): Boolean {
    val context = LocalContext.current
    var is24Hour by remember { mutableStateOf(DateFormat.is24HourFormat(context)) }
    // The setting is changed in system settings, so look again when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { is24Hour = DateFormat.is24HourFormat(context) }
    return is24Hour
}
