package com.deepseek.mobile.chat

import com.deepseek.mobile.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlinx.coroutines.withContext

sealed interface ChatEvent {
    data class BlockStart(val index: Int, val block: JsonObject) : ChatEvent
    data class TextDelta(val index: Int, val text: String) : ChatEvent
    data class ThinkingDelta(val index: Int, val text: String) : ChatEvent
    data class SignatureDelta(val index: Int, val signature: String) : ChatEvent
    data class CitationsDelta(val index: Int, val citation: JsonObject) : ChatEvent
    data class BlockStop(val index: Int) : ChatEvent
    data class Finished(val stopReason: String?, val title: String?, val usage: JsonObject?) : ChatEvent
    data class Failure(val message: String) : ChatEvent
}

class DeepSeekApiException(message: String) : Exception(message)

/**
 * DeepSeek Messages API (`/anthropic/v1/messages`) streaming client. The API
 * returns server-side tool blocks (web search / link reading) inline, so the
 * conversation keeps each assistant content block verbatim for replay.
 */
class DeepSeekClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun buildPayload(settings: Settings, messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", settings.model)
        put("stream", true)
        putJsonObject("thinking") {
            put("type", if (settings.thinking) "enabled" else "disabled")
        }
        if (settings.webSearch || settings.readUrl) {
            put("tools", buildJsonArray {
                if (settings.webSearch) {
                    add(buildJsonObject {
                        put("type", "deepseek_web_search")
                        put("name", "deepseek_web_search")
                    })
                }
                if (settings.readUrl) {
                    add(buildJsonObject {
                        put("type", "deepseek_read_url")
                        put("name", "deepseek_read_url")
                    })
                }
            })
        }
        if (settings.safetyCheck) {
            putJsonObject("safety_check") { put("level", "high") }
        }
        if (settings.autoTitle) {
            putJsonObject("title") { put("enabled", true) }
        }
        put("messages", buildJsonArray { messages.forEach { add(it) } })
    }

    fun stream(settings: Settings, messages: List<JsonObject>): Flow<ChatEvent> = flow {        if (settings.apiKey.isBlank()) {
            emit(ChatEvent.Failure("还没有填写 API Key，请先到「设置」里填写。"))
            return@flow
        }
        val payload = buildPayload(settings, messages)
        val builder = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/anthropic/v1/messages")
            .addHeader("x-api-key", settings.apiKey)
            .addHeader("Accept", "text/event-stream")
        if (messages.any { message -> message.hasDocumentBlock() }) {
            builder.addHeader("anthropic-beta", "files-api-2025-04-14")
        }
        val request = builder
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val call = http.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        val handle: suspend (String?, String) -> Unit = { eventName, data ->
            if (data.isNotBlank()) {
                val parsed = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                if (parsed != null) {
                    when (eventName ?: parsed["type"]?.jsonPrimitive?.content) {
                        "message_start" -> Unit
                        "content_block_start" -> {
                            val index = parsed["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val block = parsed["content_block"]?.jsonObject ?: JsonObject(emptyMap())
                            emit(ChatEvent.BlockStart(index, block))
                        }
                        "content_block_delta" -> {
                            val index = parsed["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val delta = parsed["delta"]?.jsonObject
                            when (delta?.get("type")?.jsonPrimitive?.content) {
                                "text_delta" -> emit(
                                    ChatEvent.TextDelta(index, delta["text"]?.jsonPrimitive?.content ?: ""),
                                )
                                "thinking_delta" -> emit(
                                    ChatEvent.ThinkingDelta(index, delta["thinking"]?.jsonPrimitive?.content ?: ""),
                                )
                                "signature_delta" -> emit(
                                    ChatEvent.SignatureDelta(index, delta["signature"]?.jsonPrimitive?.content ?: ""),
                                )
                                "citations_delta" -> delta["citation"]?.jsonObject?.let {
                                    emit(ChatEvent.CitationsDelta(index, it))
                                }
                                else -> Unit
                            }
                        }
                        "content_block_stop" -> {
                            val index = parsed["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            emit(ChatEvent.BlockStop(index))
                        }
                        "message_delta" -> {
                            val delta = parsed["delta"]?.jsonObject
                            val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                            val title = delta?.get("title")?.jsonPrimitive?.content
                            emit(ChatEvent.Finished(stopReason, title, parsed["usage"]?.jsonObject))
                        }
                        "message_stop" -> Unit
                        "error" -> {
                            val message = parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                            emit(ChatEvent.Failure(message ?: "服务端返回错误"))
                        }
                        "ping" -> Unit
                        else -> Unit
                    }
                }
            }
        }

        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    emit(ChatEvent.Failure(readErrorMessage(body) ?: "HTTP ${resp.code} ${resp.message}"))
                    return@use
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(ChatEvent.Failure("服务端没有返回数据流"))
                    return@use
                }
                var eventName: String? = null
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            handle(eventName, data.toString())
                            eventName = null
                            data.setLength(0)
                        }
                        line.startsWith("event:") -> eventName = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).trimStart())
                        }
                        line.startsWith(":") -> Unit
                    }
                }
                if (data.isNotEmpty()) handle(eventName, data.toString())
            }
        } catch (io: IOException) {
            if (currentCoroutineContext().job.isActive) {
                emit(ChatEvent.Failure(io.message ?: "网络请求失败"))
            }
            throw io
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Upload and parse one document through the Files API. `purpose` must be a
     * form field before the file part, as the API documents.
     */
    suspend fun uploadFile(
        settings: Settings,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        purpose: String,
    ): ChatFile = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) throw DeepSeekApiException("请先在设置里填写 API Key")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/files")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DeepSeekApiException(readErrorMessage(text) ?: "上传失败 HTTP ${response.code}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val id = root["id"]?.jsonPrimitive?.content
                ?: throw DeepSeekApiException("上传响应缺少 file id")
            ChatFile(id = id, name = fileName, purpose = purpose)
        }
    }

    private fun JsonObject.hasDocumentBlock(): Boolean {
        val content = this["content"] as? JsonArray ?: return false
        return content.any { block ->
            (block as? JsonObject)?.get("type")?.jsonPrimitive?.content == "document"
        }
    }

    private fun readErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}
