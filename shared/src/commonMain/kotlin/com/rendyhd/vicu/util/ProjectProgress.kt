package com.rendyhd.vicu.util

/**
 * How far a project is: [done] of [total] tasks (decision 11, the drawer's progress ring). Same
 * rule as the desktop's `project-progress.ts`.
 */
data class ProjectProgress(val done: Long, val total: Long) {
    /** 0 to 1; the share of the project's tasks that are done. */
    val fraction: Float get() = (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()

    /** What a screen reader says for the ring. */
    val label: String get() = "$done of $total done"
}

/**
 * The ring of a project, or null when it says nothing: a count is not known yet, a count is
 * negative, or the project has no tasks at all.
 */
fun projectProgress(done: Long?, open: Int?): ProjectProgress? {
    if (done == null || open == null || done < 0 || open < 0) return null
    val total = done + open
    return if (total == 0L) null else ProjectProgress(done, total)
}
