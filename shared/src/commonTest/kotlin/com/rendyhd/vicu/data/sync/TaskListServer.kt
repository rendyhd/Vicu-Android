package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.authTestJsonHeaders
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode

/** One request a test server saw. */
data class Req(val method: String, val path: String, val params: Map<String, List<String>>) {
    val filter: String? get() = params["filter"]?.firstOrNull()
    val query: String? get() = params["q"]?.firstOrNull()
    fun param(name: String): String? = params[name]?.firstOrNull()
    override fun toString() = "$method $path ${params.entries.joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }}"
}

/**
 * A small in-memory Vikunja task list: `GET /tasks` with the filters, sorting and paging the
 * refresh code uses (`done = true|false`, `updated >= '...'`, `done_at >= '...'`, `q`, `sort_by`,
 * `order_by`, `page`, `per_page`) and `GET /tasks/{id}`. Everything else is a 404 the test can
 * override with [override].
 */
class TaskListServer {
    class T(
        val id: Long,
        var title: String = "Task $id",
        var done: Boolean = false,
        var updated: String = "2026-10-01T00:00:00Z",
        var doneAt: String = "",
        var description: String = "",
        var projectId: Long = 7,
        var dueDate: String = "",
        var remindersJson: String = "[]",
    )

    val tasks = LinkedHashMap<Long, T>()
    val requests = mutableListOf<Req>()

    /** Answers a request instead of the server logic; null lets it through. */
    var override: suspend MockRequestHandleScope.(Req) -> HttpResponseData? = { null }

    fun put(
        id: Long,
        title: String = "Task $id",
        done: Boolean = false,
        updated: String = "2026-10-01T00:00:00Z",
        doneAt: String = if (done) updated else "",
        description: String = "",
        dueDate: String = "",
        remindersJson: String = "[]",
    ): T = T(id, title, done, updated, doneAt, description, 7, dueDate, remindersJson).also { tasks[id] = it }

    fun remove(id: Long) {
        tasks.remove(id)
    }

    /** The GET /tasks requests, in order. */
    fun lists(): List<Req> = requests.filter { it.method == "GET" && it.path == "/tasks" }

    private fun T.toJson(): String =
        """{"id":$id,"title":${quote(title)},"description":${quote(description)},"done":$done,""" +
            """"done_at":${quote(doneAt)},"due_date":${quote(dueDate)},"project_id":$projectId,""" +
            """"updated":${quote(updated)},"reminders":$remindersJson}"""

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun matches(task: T, clause: String): Boolean {
        val c = clause.trim()
        Regex("^done = (true|false)$").matchEntire(c)?.let { return task.done == (it.groupValues[1] == "true") }
        Regex("^(updated|done_at) >= '(.+)'$").matchEntire(c)?.let {
            val value = if (it.groupValues[1] == "updated") task.updated else task.doneAt
            return value.isNotEmpty() && value >= it.groupValues[2]
        }
        error("TaskListServer does not understand the filter clause: $c")
    }

    suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
        val params = request.url.parameters.entries().associate { it.key to it.value.toList() }
        val req = Req(request.method.value, request.url.encodedPath, params)
        requests += req
        override(req)?.let { return it }

        val single = Regex("^/tasks/(\\d+)$").matchEntire(req.path)?.groupValues?.get(1)?.toLong()
        when {
            req.method == "GET" && req.path == "/tasks" -> {
                var found = tasks.values.toList()
                req.filter?.split("&&")?.forEach { clause -> found = found.filter { matches(it, clause) } }
                req.query?.lowercase()?.let { needle ->
                    found = found.filter { needle in it.title.lowercase() || needle in it.description.lowercase() }
                }
                val comparator = when (req.param("sort_by")) {
                    "updated" -> compareBy<T> { it.updated }
                    "done_at" -> compareBy<T> { it.doneAt }
                    else -> compareBy<T> { it.id }
                }
                found = found.sortedWith(if (req.param("order_by") == "desc") comparator.reversed() else comparator)
                val perPage = req.param("per_page")?.toInt() ?: 50
                val page = req.param("page")?.toInt() ?: 1
                val totalPages = maxOf(1, (found.size + perPage - 1) / perPage)
                val items = found.drop((page - 1) * perPage).take(perPage)
                respond(
                    """{"items":[${items.joinToString(",") { it.toJson() }}],"total":${found.size},""" +
                        """"page":$page,"per_page":$perPage,"total_pages":$totalPages}""",
                    HttpStatusCode.OK,
                    authTestJsonHeaders,
                )
            }
            req.method == "GET" && single != null ->
                tasks[single]?.let { respond(it.toJson(), HttpStatusCode.OK, authTestJsonHeaders) }
                    ?: respond(
                        """{"title":"Not Found","status":404,"detail":"This task does not exist","code":4002}""",
                        HttpStatusCode.NotFound,
                        authTestJsonHeaders,
                    )
            else -> respond("", HttpStatusCode.NotFound)
        }
    }
}
