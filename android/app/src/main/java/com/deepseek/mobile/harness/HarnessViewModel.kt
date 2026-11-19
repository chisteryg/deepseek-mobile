package com.deepseek.mobile.harness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.mobile.data.SettingsStore
import com.deepseek.mobile.util.arr
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
)

data class TranscriptEntry(
    val seq: Int,
    val type: String,
    val data: JsonObject,
)

/** One user prompt plus every assistant step that answered it. */
data class HarnessTurn(
    val key: Int,
    val userText: String,
    val blocks: List<JsonObject>,
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
        )
        startStream(session.id)
        loadModels()
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
        )
        refresh()
    }

    fun send(text: String) {
        val session = _state.value.session ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                client.prompt(session.id, text.trim())
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "发送失败")
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
    private fun buildTurns(entries: List<TranscriptEntry>, running: Boolean): List<HarnessTurn> {
        val turns = mutableListOf<HarnessTurn>()
        for (entry in entries) {
            when (entry.type) {
                "user/message" -> {
                    val contentText = entry.data.arr("content")
                        ?.mapNotNull { block -> (block as? JsonObject)?.str("text") }
                        ?.joinToString("\n")
                        .orEmpty()
                    if (contentText.isNotBlank()) {
                        turns.add(
                            HarnessTurn(
                                key = entry.seq,
                                userText = contentText,
                                blocks = emptyList(),
                                streaming = false,
                            ),
                        )
                    }
                }
                "assistant/message" -> {
                    val blocks = entry.data.obj("message")?.arr("content")?.mapNotNull { it as? JsonObject }
                        ?: emptyList()
                    if (blocks.isEmpty()) continue
                    val last = turns.lastOrNull()
                    if (last != null && last.userText.isNotEmpty() && last.blocks.isEmpty()) {
                        turns[turns.lastIndex] = last.copy(blocks = blocks)
                    } else {
                        turns.add(
                            HarnessTurn(
                                key = entry.seq,
                                userText = "",
                                blocks = blocks,
                                streaming = false,
                            ),
                        )
                    }
                }
            }
        }
        return turns.mapIndexed { index, turn ->
            turn.copy(streaming = running && index == turns.lastIndex)
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
