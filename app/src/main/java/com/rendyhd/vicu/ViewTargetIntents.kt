package com.rendyhd.vicu

import android.content.Intent
import com.rendyhd.vicu.ui.navigation.ViewTarget

/** Writes [target] into this intent the way [MainActivity] reads it (see [ViewTarget]). */
fun Intent.putViewTarget(target: ViewTarget): Intent = apply {
    putExtra(ViewTarget.EXTRA_TYPE, target.typeName)
    putExtra(ViewTarget.EXTRA_ID, target.idOrEmpty)
}

/** The screen this intent asks the app to open, or null when it asks for none. */
fun Intent.viewTargetOrNull(): ViewTarget? =
    ViewTarget.parse(getStringExtra(ViewTarget.EXTRA_TYPE), getStringExtra(ViewTarget.EXTRA_ID))
