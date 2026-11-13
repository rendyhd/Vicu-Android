package com.rendyhd.vicu.ai.provider

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.ProviderPreset
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls the user's model provider straight from the device (`docs/ai-assistant-v1.md`, section 2):
 * the two wire protocols, streamed answers, the model list, timeouts, and the request fields some
 * models refuse (temperature, reasoning effort), which are left out after the first refusal and
 * remembered per model. Keys never reach a log.
 */
class LlmClient(
    engine: HttpClientEngine,
) {
    private val http = HttpClient(engine) {
        install(HttpTimeout)
        expectSuccess = false
        // The key travels in a header: a redirect must never carry it to another host. Ktor's
        // redirect handling is client-level, so this covers the OkHttp and the Darwin engines
        // alike (both leave redirects to it); a redirect then reads as the provider's answer.
        followRedirects = false
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Per provider and model: the optional fields it refused, so they are not sent again. */
    private val refused = mutableMapOf<String, MutableSet<String>>()

    /** Streams one answer. Throws [LlmException]. */
    fun stream(connection: ProviderConnection, request: ChatRequest): Flow<ChatEvent> = flow {
        val protocol = protocolFor(connection.preset)
        val quirkKey = "${connection.preset.id}|${connection.base}|${request.model}"
        var attempt = 0
        while (true) {
            val omit = refused[quirkKey].orEmpty()
            val body = protocol.body(request, omit)
            val retryWithout = sendStreaming(connection, protocol, request, body, this)
            if (retryWithout == null) return@flow
            refused.getOrPut(quirkKey) { mutableSetOf() } += retryWithout
            if (++attempt > OPTIONAL_FIELDS.size) throw LlmException(LlmException.Kind.BAD_REQUEST, "The provider refused the request.")
        }
    }

    /** The whole answer of [stream]. */
    suspend fun complete(connection: ProviderConnection, request: ChatRequest): ChatEvent.Finished {
        var finished: ChatEvent.Finished? = null
        stream(connection, request).collect { if (it is ChatEvent.Finished) finished = it }
        return finished ?: throw LlmException(LlmException.Kind.BAD_ANSWER, "The answer ended early.")
    }

    /** The models the provider lists (section 2.1), sorted. */
    suspend fun listModels(connection: ProviderConnection): List<String> {
        val ids = mutableListOf<String>()
        var after: String? = null
        repeat(MAX_MODEL_PAGES) {
            val url = connection.base + AiContract.protocolPath(connection.preset.protocol, "models")
            val page = guarded {
                http.prepareGet(url) {
                    authorize(connection)
                    if (connection.preset.isAnthropic) {
                        parameter("limit", 1000)
                        after?.let { parameter("after_id", it) }
                    }
                    timeout { connectTimeoutMillis = connectMs(); socketTimeoutMillis = connectMs() * 3 }
                }.execute { response ->
                    failOn(response)
                    json.parseToJsonElement(response.bodyAsText()).jsonObject
                }
            }
            val data = (page["data"] ?: page["models"])?.jsonArray.orEmpty()
            data.mapNotNullTo(ids) { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            val more = page["has_more"]?.jsonPrimitive?.contentOrNull == "true"
            after = page["last_id"]?.jsonPrimitive?.contentOrNull
            if (!more || after == null) return ids.distinct().sorted()
        }
        return ids.distinct().sorted()
    }

    /** Sends [body]; returns the optional field to leave out when the provider refused it, else null. */
    private suspend fun sendStreaming(
        connection: ProviderConnection,
        protocol: WireProtocol,
        request: ChatRequest,
        body: JsonObject,
        out: FlowCollector<ChatEvent>,
    ): String? = guarded {
        val url = connection.base + AiContract.protocolPath(connection.preset.protocol, "chat")
        http.preparePost(url) {
            authorize(connection)
            contentType(ContentType.Application.Json)
            header("Accept", "text/event-stream")
            setBody(body.toString())
            timeout {
                connectTimeoutMillis = connectMs()
                socketTimeoutMillis = request.idleTimeoutSeconds * 1_000L
            }
        }.execute { response ->
            if (response.status.value == 400) {
                val text = response.bodyAsText()
                val field = OPTIONAL_FIELDS.firstOrNull { (field, words) ->
                    sends(body, field) && words.any { text.contains(it, ignoreCase = true) }
                }?.first
                if (field != null) return@execute field
                throw LlmException(LlmException.Kind.BAD_REQUEST, errorMessage(text, 400), 400)
            }
            failOn(response)
            val reader = protocol.reader()
            val isStream = response.headers["Content-Type"]?.contains("event-stream") == true
            if (isStream) {
                val channel = response.bodyAsChannel()
                val data = StringBuilder()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                reader.onData(data.toString())?.let { out.emit(it) }
                                data.clear()
                            }
                        }
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").removePrefix(" "))
                        }
                    }
                    if (reader.done) break
                }
                if (data.isNotEmpty() && !reader.done) reader.onData(data.toString())?.let { out.emit(it) }
            } else {
                reader.onWhole(response.bodyAsText())?.let { out.emit(it) }
            }
            out.emit(reader.finish())
            null
        }
    }

    private fun sends(body: JsonObject, field: String): Boolean =
        if (field == "effort") (body["output_config"] as? JsonObject)?.containsKey("effort") == true else field in body

    private fun HttpRequestBuilder.authorize(connection: ProviderConnection) {
        val preset = connection.preset
        val key = connection.key?.trim().orEmpty()
        if (key.isNotEmpty()) header(preset.authHeader, preset.authPrefix + key)
        for ((name, value) in preset.headers) header(name, value)
    }

    private suspend fun failOn(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val kind = when (status) {
            401, 403 -> LlmException.Kind.AUTH
            404 -> LlmException.Kind.NOT_FOUND
            408 -> LlmException.Kind.TIMEOUT
            429 -> LlmException.Kind.RATE_LIMIT
            in 500..599 -> LlmException.Kind.SERVER
            else -> LlmException.Kind.BAD_REQUEST
        }
        throw LlmException(kind, errorMessage(text, status), status)
    }

    /** The provider's own error text when it gave one (OpenAI and Anthropic both nest it in `error`). */
    private fun errorMessage(text: String, status: Int): String {
        val detail = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            val error = o["error"]
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                else -> error?.jsonPrimitive?.contentOrNull ?: o["message"]?.jsonPrimitive?.contentOrNull ?: o["detail"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
        return detail?.take(300) ?: "The provider answered with error $status."
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        throw LlmException(LlmException.Kind.TIMEOUT, "The model did not answer in time.")
    } catch (e: SocketTimeoutException) {
        throw LlmException(LlmException.Kind.TIMEOUT, "The model did not answer in time.")
    } catch (e: ConnectTimeoutException) {
        throw LlmException(LlmException.Kind.NETWORK, "Could not reach the provider.")
    } catch (e: Exception) {
        throw LlmException(LlmException.Kind.NETWORK, "Could not reach the provider.")
    }

    private fun connectMs() = AiContract.limits.connectTimeoutSeconds * 1_000L

    private fun protocolFor(preset: ProviderPreset): WireProtocol =
        if (preset.isAnthropic) AnthropicMessagesProtocol(json) else OpenAiChatProtocol(json)

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

    companion object {
        private const val MAX_MODEL_PAGES = 10

        /** The optional fields a model may refuse, and the words its error names them with. */
        internal val OPTIONAL_FIELDS: List<Pair<String, List<String>>> = listOf(
            "temperature" to listOf("temperature"),
            "reasoning_effort" to listOf("reasoning_effort", "reasoning effort", "reasoning"),
            "effort" to listOf("effort"),
        )
    }
}
