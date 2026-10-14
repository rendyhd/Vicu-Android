package com.rendyhd.vicu.worker

import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.VikunjaProblemDto
import kotlinx.coroutines.CancellationException

/** What a refused label action turned out to be about. */
enum class LabelActionTarget { TASK_GONE, LABEL_GONE, UNKNOWN }

/**
 * Decides whether a 404 on a queued change really means "that thing no longer exists".
 *
 * Vikunja answers a missing resource with a problem body whose code names it (4002 task, 3001
 * project, 7002 label). Anything else is not enough to throw a user's change away: a reverse
 * proxy or a mis-set base path answers every request with a bare 404. So a bare 404 only counts
 * when asking for the resource again also says it is gone and the API still answers other calls
 * ([GET /user][VikunjaApiService.getCurrentUser]). When that cannot be established the caller
 * keeps the action as failed, where the user can retry or discard it.
 */
class MissingResourceCheck(private val api: VikunjaApiService) {

    companion object {
        const val PROJECT_DOES_NOT_EXIST = 3001L
        const val TASK_DOES_NOT_EXIST = 4002L
        const val LABEL_ALREADY_ON_TASK = 7001L
        const val LABEL_DOES_NOT_EXIST = 7002L
    }

    suspend fun taskGone(taskId: Long, e: VikunjaApiException): Boolean =
        confirmed(e, TASK_DOES_NOT_EXIST, "task") { api.getTask(taskId) }

    suspend fun labelGone(labelId: Long, e: VikunjaApiException): Boolean =
        confirmed(e, LABEL_DOES_NOT_EXIST, "label") { api.getLabel(labelId) }

    suspend fun projectGone(projectId: Long, e: VikunjaApiException): Boolean =
        confirmed(e, PROJECT_DOES_NOT_EXIST, "project") { api.getProject(projectId) }

    /** Which of the two [e] (a refused add-label request) says is missing. */
    suspend fun labelActionTarget(taskId: Long, labelId: Long, e: VikunjaApiException): LabelActionTarget = when {
        e.httpStatus != 404 -> LabelActionTarget.UNKNOWN
        names(e.problem, TASK_DOES_NOT_EXIST, "task") -> LabelActionTarget.TASK_GONE
        names(e.problem, LABEL_DOES_NOT_EXIST, "label") -> LabelActionTarget.LABEL_GONE
        hasForeignCode(e.problem) -> LabelActionTarget.UNKNOWN
        taskGone(taskId, e) -> LabelActionTarget.TASK_GONE
        labelGone(labelId, e) -> LabelActionTarget.LABEL_GONE
        else -> LabelActionTarget.UNKNOWN
    }

    private suspend fun confirmed(
        e: VikunjaApiException,
        missingCode: Long,
        noun: String,
        recheck: suspend () -> Unit,
    ): Boolean {
        if (e.httpStatus != 404) return false
        if (names(e.problem, missingCode, noun)) return true
        if (hasForeignCode(e.problem)) return false
        // A bare 404 does not say what is missing: ask for the resource itself.
        return try {
            recheck()
            false
        } catch (c: CancellationException) {
            throw c
        } catch (g: VikunjaApiException) {
            when {
                g.httpStatus != 404 -> false
                names(g.problem, missingCode, noun) -> true
                hasForeignCode(g.problem) -> false
                else -> apiAnswersOtherCalls()
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun apiAnswersOtherCalls(): Boolean = try {
        api.getCurrentUser()
        true
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        false
    }

    /** The problem says [noun] is missing: by its code, or by Vikunja's "This task does not exist" text. */
    private fun names(problem: VikunjaProblemDto?, code: Long, noun: String): Boolean {
        if (problem == null) return false
        if (problem.code == code) return true
        val text = "${problem.detail} ${problem.title}".lowercase()
        return "$noun does not exist" in text
    }

    /** A problem body with a code of its own: the 404 is about something else. */
    private fun hasForeignCode(problem: VikunjaProblemDto?): Boolean = problem != null && problem.code != 0L
}
