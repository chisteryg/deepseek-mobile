package com.deepseek.mobile.harness

import com.deepseek.mobile.data.SettingsStore
import com.deepseek.mobile.util.arr
import com.deepseek.mobile.util.asString
import com.deepseek.mobile.util.bool
import com.deepseek.mobile.util.int
import com.deepseek.mobile.util.long
import com.deepseek.mobile.util.obj
import com.deepseek.mobile.util.objects
import com.deepseek.mobile.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BridgeException(message: String) : Exception(message)

/**
 * Client for the desktop `mobile-bridge` Host plugin. Every call carries the
 * device token issued by pairing; streams are plain SSE over the LAN.
 */
open class BridgeClient(
    private val http: OkHttpClient,
    private val settings: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun request(path: String, token: String = ""): Request.Builder {
        val base = normalize(settings.current.bridgeUrl)
        if (base.isEmpty()) throw BridgeException("还没有填写桥接地址")
        val builder = Request.Builder().url(base + path)
        if (token.isNotEmpty()) builder.addHeader("Authorization", "Bearer $token")
        return builder
    }

    open suspend fun pair(baseUrl: String, code: String, deviceName: String): PairResult =
        withContext(Dispatchers.IO) {
            val normalized = normalize(baseUrl)
            if (normalized.isEmpty()) throw BridgeException("请填写桌面地址")
            val body = buildJsonObject {
                put("code", code.trim())
                put("name", deviceName)
            }.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(normalized + "/api/pair")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw BridgeException(extractError(text, response.code))
                }
                val root = json.parseToJsonElement(text).jsonObject
                PairResult(
                    token = root.str("token") ?: throw BridgeException("配对响应缺少 token"),
                    host = root.str("host").orEmpty(),
                    port = root.int("port") ?: 0,
                    name = root.str("name").orEmpty(),
                )
            }
        }

    open suspend fun info(): BridgeHealth = withContext(Dispatchers.IO) {
        val current = settings.current
        http.newCall(request("/api/health", current.bridgeToken).get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            val root = json.parseToJsonElement(text).jsonObject
            BridgeHealth(
                name = root.str("name").orEmpty(),
                sessions = root.int("sessions") ?: 0,
                running = root.int("running") ?: 0,
            )
        }
    }

    open suspend fun health(): String {
        val info = info()
        return "已连接 ${info.name} · ${info.sessions} 个会话，${info.running} 个在运行"
    }

    open suspend fun sessions(): List<BridgeSession> = withContext(Dispatchers.IO) {
        val current = settings.current
        http.newCall(request("/api/sessions", current.bridgeToken).get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            val root = json.parseToJsonElement(text).jsonObject
            root.arr("sessions")?.objects()?.map { item ->
                BridgeSession(
                    id = item.str("id").orEmpty(),
                    title = item.str("title").orEmpty().ifBlank { "未命名会话" },
                    running = item.bool("running") ?: false,
                    pending = item.arr("pending")?.mapNotNull { it.asString() } ?: emptyList(),
                    updatedAt = item.long("updatedAt") ?: 0L,
                    cwd = item.str("cwd").orEmpty(),
                    agentAvailable = item.bool("agentAvailable") ?: false,
                )
            } ?: emptyList()
        }
    }

    open suspend fun interactions(): List<BridgeInteraction> = withContext(Dispatchers.IO) {
        val current = settings.current
        http.newCall(request("/api/interactions", current.bridgeToken).get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            val root = json.parseToJsonElement(text).jsonObject
            root.arr("items")?.objects()?.map { item ->
                BridgeInteraction(
                    id = item.str("id").orEmpty(),
                    kind = item.str("kind").orEmpty(),
                    sessionId = item.str("sessionId").orEmpty(),
                    title = item.str("title").orEmpty(),
                    detail = item.str("detail").orEmpty(),
                )
            } ?: emptyList()
        }
    }

    open suspend fun decide(interactionId: String, decision: String) = withContext(Dispatchers.IO) {
        val current = settings.current
        val body = buildJsonObject { put("decision", decision) }.toString().toRequestBody(mediaType)
        http.newCall(
            request("/api/interactions/$interactionId", current.bridgeToken).post(body).build(),
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
        }
    }

    open suspend fun prompt(
        sessionId: String,
        text: String,
        mode: String = "queue",
        files: List<String> = emptyList(),
        images: List<String> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val current = settings.current
        val body = buildJsonObject {
            put("text", text)
            put("mode", mode)
            if (files.isNotEmpty()) {
                put("files", buildJsonArray { files.forEach { add(it) } })
            }
            if (images.isNotEmpty()) {
                put("images", buildJsonArray {
                    images.forEach { encoded ->
                        add(buildJsonObject {
                            put("mediaType", "image/jpeg")
                            put("data", encoded)
                        })
                    }
                })
            }
        }.toString().toRequestBody(mediaType)
        http.newCall(
            request("/api/sessions/$sessionId/prompt", current.bridgeToken).post(body).build(),
        ).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(raw, response.code))
        }
    }

    open suspend fun uploadDocument(
        sessionId: String,
        bytes: ByteArray,
        name: String,
    ): BridgeUpload = withContext(Dispatchers.IO) {
        val current = settings.current
        val body = buildJsonObject {
            put("name", name)
            put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }.toString().toRequestBody(mediaType)
        http.newCall(
            request("/api/sessions/$sessionId/upload", current.bridgeToken).post(body).build(),
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            val root = json.parseToJsonElement(text).jsonObject
            BridgeUpload(
                receiptId = root.str("receiptId") ?: throw BridgeException("上传响应缺少 receiptId"),
                name = root.str("name").orEmpty().ifBlank { name },
                bytes = root.int("bytes") ?: bytes.size,
            )
        }
    }

    open suspend fun permissions(sessionId: String): SessionPermissions = withContext(Dispatchers.IO) {
        val current = settings.current
        http.newCall(
            request("/api/sessions/$sessionId/permissions", current.bridgeToken).get().build(),
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            parsePermissions(json.parseToJsonElement(text).jsonObject)
        }
    }

    open suspend fun setPermissions(
        sessionId: String,
        preset: String? = null,
        approvalPolicy: String? = null,
    ): SessionPermissions = withContext(Dispatchers.IO) {
        val current = settings.current
        val body = buildJsonObject {
            if (preset != null) put("preset", preset)
            if (approvalPolicy != null) put("approvalPolicy", approvalPolicy)
        }.toString().toRequestBody(mediaType)
        http.newCall(
            request("/api/sessions/$sessionId/permissions", current.bridgeToken).post(body).build(),
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            parsePermissions(json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun parsePermissions(root: JsonObject): SessionPermissions {
        val catalog = root.obj("catalog")
        return SessionPermissions(
            preset = root.str("preset"),
            approvalPolicy = root.str("approvalPolicy"),
            catalog = catalog?.let {
                PermissionCatalog(
                    options = it.arr("options")?.objects()?.map { option ->
                        PermissionOption(
                            value = option.str("value").orEmpty(),
                            name = option.str("name").orEmpty(),
                            description = option.str("description"),
                        )
                    } ?: emptyList(),
                    defaultPreset = it.str("defaultPreset").orEmpty(),
                )
            },
        )
    }

    open suspend fun models(): ModelCatalog = withContext(Dispatchers.IO) {
        val current = settings.current
        http.newCall(request("/api/models", current.bridgeToken).get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) {
                throw BridgeException("桌面端插件版本较旧，重启一次 DSH 后就能切模型")
            }
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            val root = json.parseToJsonElement(text).jsonObject
            val default = root.obj("default")
            ModelCatalog(
                defaultProvider = default?.str("provider").orEmpty(),
                defaultModel = default?.str("model").orEmpty(),
                defaultEffort = default?.str("reasoningEffort"),
                groups = root.arr("groups")?.objects()?.map { group ->
                    val providerId = group.str("id").orEmpty()
                    ModelGroup(
                        id = providerId,
                        name = group.str("name").orEmpty(),
                        models = group.arr("models")?.objects()?.map { model ->
                            val reasoning = model.obj("reasoning")
                            ModelChoice(
                                provider = providerId,
                                id = model.str("id").orEmpty(),
                                name = model.str("name").orEmpty(),
                                description = model.str("description"),
                                efforts = reasoning?.arr("efforts")?.objects()?.map { effort ->
                                    ModelEffort(
                                        id = effort.str("id").orEmpty(),
                                        name = effort.str("name").orEmpty(),
                                    )
                                } ?: emptyList(),
                                defaultEffort = reasoning?.str("defaultEffort"),
                            )
                        } ?: emptyList(),
                    )
                } ?: emptyList(),
            )
        }
    }

    open suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        effort: String?,
    ): ModelChoice? = withContext(Dispatchers.IO) {
        val current = settings.current
        val body = buildJsonObject {
            put("provider", provider)
            put("model", model)
            if (!effort.isNullOrBlank()) put("reasoningEffort", effort)
        }.toString().toRequestBody(mediaType)
        http.newCall(
            request("/api/sessions/$sessionId/model", current.bridgeToken).post(body).build(),
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(text, response.code))
            null
        }
    }

    open suspend fun cancel(sessionId: String) = withContext(Dispatchers.IO) {        val current = settings.current
        http.newCall(
            request("/api/sessions/$sessionId/cancel", current.bridgeToken)
                .post("".toRequestBody(mediaType))
                .build(),
        ).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeException(extractError(raw, response.code))
        }
    }

    open fun stream(sessionId: String): Flow<HarnessEvent> = flow {
        val current = settings.current
        val request = request("/api/sessions/$sessionId/stream", current.bridgeToken)
            .addHeader("Accept", "text/event-stream")
            .get()
            .build()
        val call = http.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = call.execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(HarnessEvent.Failure(extractError(resp.body?.string().orEmpty(), resp.code)))
                return@use
            }
            val source = resp.body?.source() ?: return@use
            var eventName: String? = null
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) emit(parseEvent(eventName, data.toString()))
                        eventName = null
                        data.setLength(0)
                    }
                    line.startsWith("event:") -> eventName = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                    else -> Unit
                }
            }
            emit(HarnessEvent.Closed("连接结束"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseEvent(name: String?, raw: String): HarnessEvent {
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return HarnessEvent.Failure("无法解析服务端事件")
        return when (name) {
            "snapshot" -> HarnessEvent.Snapshot(
                records = parsed.arr("records")?.objects().orEmpty(),
                cursor = parsed.int("cursor") ?: 0,
                header = parsed.obj("header"),
            )
            "event" -> HarnessEvent.WireEvent(parsed)
            "state" -> HarnessEvent.StateChanged(parseState(parsed))
            "assistant" -> HarnessEvent.AssistantChunk(parsed)
            "closed" -> HarnessEvent.Closed(parsed.str("reason").orEmpty())
            "failure" -> HarnessEvent.Failure(parsed.str("message") ?: "连接失败")
            else -> HarnessEvent.Closed("未知事件 $name")
        }
    }

    private fun parseState(root: JsonObject): BridgeSessionState = BridgeSessionState(
        running = root.bool("running") ?: false,
        active = root.bool("active") ?: false,
        pendingInput = root.arr("pendingInput")?.mapNotNull { it.asString() } ?: emptyList(),
        queued = root.arr("queue")?.size ?: 0,
    )

    private fun extractError(body: String, code: Int): String {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        return parsed?.str("error") ?: "HTTP $code"
    }
}
