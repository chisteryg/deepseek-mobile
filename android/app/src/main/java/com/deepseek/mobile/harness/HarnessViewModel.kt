package com.deepseek.mobile.harness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.mobile.data.SettingsStore
import com.deepseek.mobile.util.arr
import com.deepseek.mobile.util.bool
import com.deepseek.mobile.util.int
import com.deepseek.mobile.util.obj
import com.deepseek.mobile.util.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Event types kept for the phone transcript; everything else is log noise. */
private val RENDERED_EVENTS = setOf(
    "user/message",
    "assistant/message",
    "tool/call",
    "tool/result",
)

data class TranscriptEntry(
    val seq: Int,
    val type: String,
    val data: JsonObject,
)

/** One tool invocation attached to a turn, with its live status and result. */
data class HarnessTool(
    val callId: String,
    val name: String,
    val args: String,
    val status: String,
    val result: String = "",
)

/** One user prompt plus every assistant step that answered it. */
data class HarnessTurn(
    val key: Int,
    val userText: String,
    val blocks: List<JsonObject>,
    val tools: List<HarnessTool> = emptyList(),
    val streaming: Boolean,
)

data class ModelSelection(
    val provider: String,
    val model: String,
    val effort: String?,
)

data class HarnessUiState(
    val paired: Boolean = false,
    val pairing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val sessions: List<BridgeSession> = emptyList(),
    val interactions: List<BridgeInteraction> = emptyList(),
    val session: BridgeSession? = null,
    val state: BridgeSessionState = BridgeSessionState(),
    val turns: List<HarnessTurn> = emptyList(),
    val connected: Boolean = false,
    val catalog: ModelCatalog? = null,
    val selection: ModelSelection? = null,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val hostName: String? = null,
    val lastSync: Long = 0L,
    val connectionError: String? = null,
    val permissions: SessionPermissions? = null,
    val permissionsLoading: Boolean = false,
    val pendingImages: List<String> = emptyList(),
    val pendingFiles: List<BridgeUpload> = emptyList(),
    val uploadingFile: String? = null,
)

class HarnessViewModel(
    private val client: BridgeClient,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HarnessUiState(paired = settings.current.harnessReady))
    val state: StateFlow<HarnessUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var events: List<TranscriptEntry> = emptyList()

    init {
        if (settings.current.harnessReady) refresh()
    }

    fun pair(url: String, code: String) {
        if (_state.value.pairing) return
        _state.value = _state.value.copy(pairing = true, error = null)
        viewModelScope.launch {
            try {
                val result = client.pair(url, code, "Android")
                settings.update {
                    it.copy(bridgeUrl = url.trim(), bridgeToken = result.token)
                }
                _state.value = _state.value.copy(
                    paired = true,
                    pairing = false,
                    notice = "已连接 ${result.name}",
                )
                refresh()
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    pairing = false,
                    error = error.message ?: "配对失败",
                )
            }
        }
    }

    fun unpair() {
        streamJob?.cancel()
        settings.update { it.copy(bridgeToken = "") }
        events = emptyList()
        _state.value = HarnessUiState(paired = false)
    }

    fun dismiss() {
        _state.value = _state.value.copy(error = null, notice = null, modelsError = null)
    }

    fun refresh() {
        if (!settings.current.harnessReady) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val sessions = client.sessions()
                val interactions = runCatching { client.interactions() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    sessions = sessions,
                    interactions = interactions,
                    loading = false,
                    paired = true,
                    error = null,
                    lastSync = System.currentTimeMillis(),
                    connectionError = null,
                )
                val info = runCatching { client.info() }.getOrNull()
                if (info != null) {
                    _state.value = _state.value.copy(
                        hostName = info.name,
                        lastSync = System.currentTimeMillis(),
                    )
                }
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message ?: "无法连接桌面端",
                    connectionError = error.message ?: "无法连接桌面端",
                )
            }
        }
    }

    fun open(session: BridgeSession) {
        events = emptyList()
        _state.value = _state.value.copy(
            session = session,
            turns = emptyList(),
            state = BridgeSessionState(running = session.running),
            error = null,
            modelsError = null,
            permissions = null,
            pendingImages = emptyList(),
            pendingFiles = emptyList(),
            uploadingFile = null,
        )
        startStream(session.id)
        loadModels()
        loadPermissions()
    }

    fun close() {
        streamJob?.cancel()
        streamJob = null
        events = emptyList()
        _state.value = _state.value.copy(
            session = null,
            turns = emptyList(),
            connected = false,
            catalog = null,
            selection = null,
            modelsError = null,
            permissions = null,
            pendingImages = emptyList(),
            pendingFiles = emptyList(),
            uploadingFile = null,
        )
        refresh()
    }

    fun send(text: String) {
        val session = _state.value.session ?: return
        val images = _state.value.pendingImages
        val files = _state.value.pendingFiles.map { it.receiptId }
        if (text.isBlank() && images.isEmpty() && files.isEmpty()) return
        viewModelScope.launch {
            try {
                client.prompt(session.id, text.trim(), files = files, images = images)
                _state.value = _state.value.copy(pendingImages = emptyList(), pendingFiles = emptyList())
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "发送失败")
            }
        }
    }

    fun addImage(base64: String) {
        _state.value = _state.value.copy(
            pendingImages = (_state.value.pendingImages + base64).take(4),
        )
    }

    fun removeImage(index: Int) {
        _state.value = _state.value.copy(
            pendingImages = _state.value.pendingImages.filterIndexed { i, _ -> i != index },
        )
    }

    fun removeFile(index: Int) {
        _state.value = _state.value.copy(
            pendingFiles = _state.value.pendingFiles.filterIndexed { i, _ -> i != index },
        )
    }

    fun uploadFile(name: String, bytes: ByteArray) {
        val session = _state.value.session ?: return
        if (_state.value.uploadingFile != null) return
        _state.value = _state.value.copy(uploadingFile = name)
        viewModelScope.launch {
            try {
                val uploaded = client.uploadDocument(session.id, bytes, name)
                _state.value = _state.value.copy(
                    pendingFiles = (_state.value.pendingFiles + uploaded).take(4),
                    uploadingFile = null,
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    uploadingFile = null,
                    error = error.message ?: "文件上传失败",
                )
            }
        }
    }

    fun loadPermissions() {
        val session = _state.value.session ?: return
        if (_state.value.permissionsLoading) return
        _state.value = _state.value.copy(permissionsLoading = true)
        viewModelScope.launch {
            try {
                val value = client.permissions(session.id)
                _state.value = _state.value.copy(permissions = value, permissionsLoading = false)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    permissionsLoading = false,
                    error = error.message ?: "读取权限失败",
                )
            }
        }
    }

    fun applyPreset(value: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                val next = client.setPermissions(session.id, preset = value)
                _state.value = _state.value.copy(permissions = next, notice = "权限已切换为 $value")
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "切换权限失败")
            }
        }
    }

    fun applyApprovalPolicy(policy: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                val next = client.setPermissions(session.id, approvalPolicy = policy)
                _state.value = _state.value.copy(
                    permissions = next,
                    notice = if (policy == "never") "已关闭审批询问" else "已开启审批询问",
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "切换审批策略失败")
            }
        }
    }

    fun stop() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                client.cancel(session.id)
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "停止失败")
            }
        }
    }

    fun decide(interaction: BridgeInteraction, decision: String) {
        viewModelScope.launch {
            try {
                client.decide(interaction.id, decision)
                val remaining = _state.value.interactions.filterNot { it.id == interaction.id }
                _state.value = _state.value.copy(interactions = remaining, notice = "已处理")
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "处理失败")
            }
        }
    }

    fun loadModels() {
        if (_state.value.catalog != null || _state.value.modelsLoading) return
        _state.value = _state.value.copy(modelsLoading = true, modelsError = null)
        viewModelScope.launch {
            try {
                val catalog = client.models()
                val selection = _state.value.selection ?: ModelSelection(
                    provider = catalog.defaultProvider,
                    model = catalog.defaultModel,
                    effort = catalog.defaultEffort,
                )
                _state.value = _state.value.copy(
                    catalog = catalog,
                    selection = selection,
                    modelsLoading = false,
                    modelsError = null,
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    modelsLoading = false,
                    modelsError = error.message ?: "无法读取模型列表",
                )
            }
        }
    }

    fun selectModel(provider: String, model: String, effort: String?) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                client.selectModel(session.id, provider, model, effort)
                val catalog = _state.value.catalog
                val label = catalog?.label(provider, model, effort) ?: model
                _state.value = _state.value.copy(
                    selection = ModelSelection(provider, model, effort),
                    notice = "已切换到 $label",
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "切换模型失败")
            }
        }
    }

    private fun startStream(sessionId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var attempts = 0
            while (isActive) {
                try {
                    client.stream(sessionId).collect { event -> handle(event) }
                    attempts += 1
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    attempts += 1
                    _state.value = _state.value.copy(
                        error = error.message ?: "连接中断",
                        connected = false,
                    )
                }
                if (attempts > 5 || !isActive) break
                delay((1500L * attempts).coerceAtMost(8000L))
                _state.value = _state.value.copy(connected = false)
            }
        }
    }

    private fun handle(event: HarnessEvent) {
        when (event) {
            is HarnessEvent.Snapshot -> {
                events = event.records.mapNotNull { record ->
                    val wire = record.obj("event") ?: record.takeIf { it.str("type") != null }
                    val type = wire?.str("type") ?: return@mapNotNull null
                    if (type !in RENDERED_EVENTS) return@mapNotNull null
                    val seq = wire.int("seq") ?: return@mapNotNull null
                    TranscriptEntry(seq, type, wire.obj("data") ?: JsonObject(emptyMap()))
                }.sortedBy { it.seq }
                _state.value = _state.value.copy(
                    turns = buildTurns(events, _state.value.state.running),
                    connected = true,
                    error = null,
                    connectionError = null,
                )
            }
            is HarnessEvent.WireEvent -> {
                val type = event.event.str("type") ?: return
                if (type !in RENDERED_EVENTS) return
                val seq = event.event.int("seq") ?: return
                val data = event.event.obj("data") ?: JsonObject(emptyMap())
                events = (events.filterNot { it.seq == seq } + TranscriptEntry(seq, type, data))
                    .sortedBy { it.seq }
                _state.value = _state.value.copy(
                    turns = buildTurns(events, _state.value.state.running),
                    connected = true,
                    error = null,
                    connectionError = null,
                )
            }
            is HarnessEvent.StateChanged -> {
                _state.value = _state.value.copy(
                    state = event.state,
                    turns = buildTurns(events, event.state.running),
                )
            }
            is HarnessEvent.AssistantChunk -> Unit
            is HarnessEvent.Closed -> {
                _state.value = _state.value.copy(
                    connected = false,
                    connectionError = "连接已断开",
                )
            }
            is HarnessEvent.Failure -> {
                _state.value = _state.value.copy(
                    connected = false,
                    error = event.message,
                    connectionError = event.message,
                )
            }
        }
    }

    /** Fold user prompts and the assistant steps that follow into read turns. */
    private class TurnBuilder(val key: Int) {
        var userText: String = ""
        val blocks = mutableListOf<JsonObject>()
        val tools = linkedMapOf<String, HarnessTool>()

        fun addTool(tool: HarnessTool) {
            tools[tool.callId] = tool
        }

        fun finishTool(callId: String, status: String, result: String) {
            val current = tools[callId] ?: return
            tools[callId] = current.copy(status = status, result = result)
        }

        fun build(streaming: Boolean): HarnessTurn = HarnessTurn(
            key = key,
            userText = userText,
            blocks = blocks.toList(),
            tools = tools.values.toList(),
            streaming = streaming,
        )
    }

    private fun buildTurns(entries: List<TranscriptEntry>, running: Boolean): List<HarnessTurn> {
        val turns = mutableListOf<TurnBuilder>()

        fun currentTurn(seq: Int): TurnBuilder =
            turns.lastOrNull() ?: TurnBuilder(seq).also { turns.add(it) }

        for (entry in entries) {
            when (entry.type) {
                "user/message" -> {
                    val text = entry.data.arr("content")
                        ?.mapNotNull { block -> (block as? JsonObject)?.str("text") }
                        ?.joinToString("\n")
                        .orEmpty()
                    if (text.isNotBlank()) {
                        turns.add(TurnBuilder(entry.seq).also { it.userText = text })
                    }
                }
                "assistant/message" -> {
                    val blocks = entry.data.obj("message")?.arr("content")
                        ?.mapNotNull { it as? JsonObject }
                        ?: emptyList()
                    if (blocks.isNotEmpty()) currentTurn(entry.seq).blocks.addAll(blocks)
                }
                "tool/call" -> {
                    val callId = entry.data.str("callId") ?: "call-${entry.seq}"
                    currentTurn(entry.seq).addTool(
                        HarnessTool(
                            callId = callId,
                            name = entry.data.str("name").orEmpty().ifBlank { "tool" },
                            args = entry.data.str("arguments").orEmpty(),
                            status = "running",
                        ),
                    )
                }
                "tool/result" -> {
                    val message = entry.data.obj("message")
                    val callId = message?.str("toolCallId")
                        ?: entry.data.str("callId")
                        ?: continue
                    val text = message?.arr("content")
                        ?.mapNotNull { block -> (block as? JsonObject)?.str("text") }
                        ?.joinToString("\n")
                        .orEmpty()
                    val failed = message?.bool("isError") == true || entry.data.bool("isError") == true
                    for (builder in turns.asReversed()) {
                        if (builder.tools.containsKey(callId)) {
                            builder.finishTool(callId, if (failed) "error" else "ok", text)
                            break
                        }
                    }
                }
            }
        }
        return turns.mapIndexed { index, builder ->
            builder.build(streaming = running && index == turns.lastIndex)
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
