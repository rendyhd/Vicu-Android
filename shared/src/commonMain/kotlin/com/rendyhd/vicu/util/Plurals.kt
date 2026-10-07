package com.rendyhd.vicu.util

/**
 * "1 change", "3 changes". The app's text is English and lives in common code, which has no
 * Android plural resources, so the form is picked here.
 */
fun countOf(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
