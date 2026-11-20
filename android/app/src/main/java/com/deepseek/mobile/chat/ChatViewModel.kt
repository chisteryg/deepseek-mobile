package com.deepseek.mobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.mobile.data.BalanceState
import com.deepseek.mobile.data.BalanceStore
import com.deepseek.mobile.data.Billing
import com.deepseek.mobile.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val current: Conversation? = null,
    val streaming: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(
    private val store: ChatStore,
    private val settings: SettingsStore,
    private val client: DeepSeekClient,
    private val balanceStore: BalanceStore,
) : ViewModel() {

    val balance: StateFlow<BalanceState> = balanceStore.state

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var firstTextAt: Long = 0L
    private var blocks: MutableList<JsonObject> = mutableListOf()
    private val textBuffers = mutableMapOf<Int, StringBuilder>()
    private val thinkingBuffers = mutableMapOf<Int, StringBuilder>()
    private val signatureBuffers = mutableMapOf<Int, StringBuilder>()
    private val citationBuffers = mutableMapOf<Int, MutableList<JsonObject>>()

    init {
        viewModelScope.launch { runCatching { balanceStore.refresh() } }
        viewModelScope.launch {
            val all = store.load().filter { it.messages.isNotEmpty() }.sortedByDescending { it.updatedAt }
            _state.value = _state.value.copy(
                conversations = all,
                current = null,
                loaded = true,
            )
        }
    }

    fun newConversation() {
        val model = settings.current.model
        val conversation = Conversation(model = model)
        // Kept as a draft until the first message arrives, so an untouched
        // conversation never reaches the list or the store.
        _state.value = _state.value.copy(current = conversation, error = null)
    }

    fun open(id: String) {
        val conversation = _state.value.conversations.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(current = conversation, error = null)
    }

    fun close() {
        val current = _state.value.current
        val cleaned = if (current != null && current.messages.isEmpty()) {
            _state.value.conversations.filterNot { it.id == current.id }
        } else {
            _state.value.conversations
        }
        val changed = cleaned.size != _state.value.conversations.size
        _state.value = _state.value.copy(conversations = cleaned, current = null)
        if (changed) viewModelScope.launch { persist(cleaned) }
    }

    fun rename(id: String, title: String) {
        val next = _state.value.conversations.map {
            if (it.id == id) it.copy(title = title.ifBlank { "新对话" }, updatedAt = System.currentTimeMillis()) else it
        }
        _state.value = _state.value.copy(
            conversations = next,
            current = next.firstOrNull { it.id == _state.value.current?.id },
        )
        viewModelScope.launch { persist(next) }
    }

    fun delete(id: String) {
        streamJob?.cancel()
        val next = _state.value.conversations.filterNot { it.id == id }
        _state.value = _state.value.copy(
            conversations = next,
            current = if (_state.value.current?.id == id) null else _state.value.current,
            streaming = false,
        )
        viewModelScope.launch { persist(next) }
    }

    fun deleteMany(ids: Set<String>) {
        if (ids.isEmpty()) return
        streamJob?.cancel()
        val next = _state.value.conversations.filterNot { it.id in ids }
        _state.value = _state.value.copy(
            conversations = next,
            current = _state.value.current?.takeIf { it.id !in ids },
            streaming = false,
        )
        viewModelScope.launch { persist(next) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun send(
        text: String,
        images: List<String> = emptyList(),
        files: List<ChatFile> = emptyList(),
    ) {
        val body = text.trim()
        if (body.isEmpty() && images.isEmpty() && files.isEmpty()) return
        val conversation = _state.value.current ?: return
        if (_state.value.streaming) return

        val userMessage = ChatMessage(role = ROLE_USER, text = body, images = images, files = files)
        val assistant = ChatMessage(
            role = ROLE_ASSISTANT,
            status = STATUS_STREAMING,
            model = settings.current.model,
        )
        var updated = conversation.copy(
            messages = conversation.messages + userMessage + assistant,
            updatedAt = System.currentTimeMillis(),
        )
        if (updated.title == "新对话") {
            updated = updated.copy(
                title = body.ifBlank { files.firstOrNull()?.name ?: "图片对话" }.take(24),
            )
        }
        blocks = mutableListOf()
        textBuffers.clear()
        thinkingBuffers.clear()
        signatureBuffers.clear()
        citationBuffers.clear()
        firstTextAt = 0L

        val listed = _state.value.conversations
        val promoted = if (listed.any { it.id == updated.id }) {
            listed.map { if (it.id == updated.id) updated else it }
        } else {
            listOf(updated) + listed
        }
        _state.value = _state.value.copy(
            conversations = promoted,
            current = updated,
            streaming = true,
            error = null,
        )
        streamJob = viewModelScope.launch {
            val history = buildHistory(updated.messages.dropLast(1))
            runStream(updated, assistant.id, history)
        }
    }

    suspend fun uploadDocument(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        purpose: String,
    ): ChatFile = client.uploadFile(settings.current, bytes, name, mimeType, purpose)

    fun stop() {
        streamJob?.cancel()
        val conversation = _state.value.current ?: return
        val next = conversation.copy(
            messages = conversation.messages.mapIndexed { index, message ->
                if (index == conversation.messages.lastIndex && message.role == ROLE_ASSISTANT) {
                    message.copy(status = STATUS_STOPPED)
                } else {
                    message
                }
            },
        )
        _state.value = _state.value.copy(current = next, streaming = false)
        viewModelScope.launch { persist() }
    }

    fun regenerate() {
        val conversation = _state.value.current ?: return
        if (_state.value.streaming) return
        val messages = conversation.messages.toMutableList()
        if (messages.isNotEmpty() && messages.last().role == ROLE_ASSISTANT) {
            messages.removeAt(messages.lastIndex)
        }
        if (messages.isEmpty() || messages.last().role != ROLE_USER) {
            _state.value = _state.value.copy(current = conversation.copy(messages = messages))
            return
        }
        val assistant = ChatMessage(role = ROLE_ASSISTANT, status = STATUS_STREAMING, model = settings.current.model)
        val updated = conversation.copy(messages = messages + assistant, updatedAt = System.currentTimeMillis())
        blocks = mutableListOf()
        textBuffers.clear()
        thinkingBuffers.clear()
        signatureBuffers.clear()
        citationBuffers.clear()
        firstTextAt = 0L
        _state.value = _state.value.copy(current = updated, streaming = true, error = null)
        streamJob = viewModelScope.launch {
            runStream(updated, assistant.id, buildHistory(messages))
        }
    }

    private suspend fun runStream(conversation: Conversation, assistantId: String, history: List<JsonObject>) {
        var failure: String? = null
        try {
            client.stream(settings.current, history).collect { event ->
                when (event) {
                    is ChatEvent.BlockStart -> {
                        while (blocks.size <= event.index) blocks.add(JsonObject(emptyMap()))
                        blocks[event.index] = event.block
                        pushBlocks(conversation.id, assistantId, STATUS_STREAMING)
                    }
                    is ChatEvent.TextDelta -> {
                        if (firstTextAt == 0L) firstTextAt = System.currentTimeMillis()
                        val buffer = textBuffers.getOrPut(event.index) { StringBuilder() }
                        buffer.append(event.text)
                        setBlockField(event.index, "text", JsonPrimitive(buffer.toString()))
                        pushBlocks(conversation.id, assistantId, STATUS_STREAMING)
                    }
                    is ChatEvent.ThinkingDelta -> {
                        val buffer = thinkingBuffers.getOrPut(event.index) { StringBuilder() }
                        buffer.append(event.text)
                        setBlockField(event.index, "thinking", JsonPrimitive(buffer.toString()))
                        pushBlocks(conversation.id, assistantId, STATUS_STREAMING)
                    }
                    is ChatEvent.SignatureDelta -> {
                        val buffer = signatureBuffers.getOrPut(event.index) { StringBuilder() }
                        buffer.append(event.signature)
                        setBlockField(event.index, "signature", JsonPrimitive(buffer.toString()))
                    }
                    is ChatEvent.CitationsDelta -> {
                        val list = citationBuffers.getOrPut(event.index) { mutableListOf() }
                        list.add(event.citation)
                        setBlockField(
                            event.index,
                            "citations",
                            JsonArray(list.toList()),
                        )
                    }
                    is ChatEvent.BlockStop -> Unit
                    is ChatEvent.Finished -> {
                        finish(conversation.id, assistantId, event.stopReason, event.title, event.usage)
                    }
                    is ChatEvent.Failure -> {
                        failure = event.message
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "请求失败"
        }
        if (failure != null) {
            failMessage(conversation.id, assistantId, failure!!)
        }
        _state.value = _state.value.copy(streaming = false)
        persist()
        runCatching { balanceStore.refresh() }
    }

    private fun setBlockField(index: Int, field: String, value: kotlinx.serialization.json.JsonElement) {
        while (blocks.size <= index) blocks.add(JsonObject(emptyMap()))
        val current = blocks[index].toMutableMap()
        current[field] = value
        if (field != "signature" && current["type"] == null) {
            current["type"] = JsonPrimitive(
                when (field) {
                    "thinking" -> "thinking"
                    else -> "text"
                },
            )
        }
        blocks[index] = JsonObject(current)
    }

    private fun pushBlocks(conversationId: String, assistantId: String, status: String) {
        val conversation = _state.value.current ?: return
        if (conversation.id != conversationId) return
        val messages = conversation.messages.map {
            if (it.id == assistantId) it.copy(blocks = blocks.toList(), status = status) else it
        }
        _state.value = _state.value.copy(current = conversation.copy(messages = messages))
    }

    private fun finish(
        conversationId: String,
        assistantId: String,
        stopReason: String?,
        title: String?,
        usage: JsonObject?,
    ) {
        val conversation = _state.value.current ?: return
        if (conversation.id != conversationId) return
        val startedAt = conversation.messages.firstOrNull { it.id == assistantId }?.createdAt ?: 0L
        val thinkingMs = if (firstTextAt > 0L && startedAt > 0L && firstTextAt >= startedAt) {
            firstTextAt - startedAt
        } else {
            null
        }
        val messages = conversation.messages.map {
            if (it.id == assistantId) {
                it.copy(
                    blocks = blocks.toList(),
                    status = STATUS_DONE,
                    stopReason = stopReason,
                    usage = usage,
                    costYuan = Billing.costOf(usage, settings.current.model),
                    thinkingMs = thinkingMs,
                )
            } else {
                it
            }
        }
        val resolvedTitle = when {
            title != null && title.isNotBlank() && settings.current.autoTitle -> title
            else -> conversation.title
        }
        val next = conversation.copy(
            messages = messages,
            title = resolvedTitle,
            updatedAt = System.currentTimeMillis(),
            model = settings.current.model,
        )
        val conversations = _state.value.conversations.map { if (it.id == next.id) next else it }
        _state.value = _state.value.copy(current = next, conversations = conversations)
    }

    private fun failMessage(conversationId: String, assistantId: String, message: String) {
        val conversation = _state.value.current ?: return
        if (conversation.id != conversationId) return
        val messages = conversation.messages.map {
            if (it.id == assistantId) {
                it.copy(blocks = blocks.toList(), status = STATUS_ERROR, error = message)
            } else {
                it
            }
        }
        val conversations = _state.value.conversations.map {
            if (it.id == conversationId) conversation.copy(messages = messages) else it
        }
        _state.value = _state.value.copy(
            current = conversation.copy(messages = messages),
            conversations = conversations,
            error = message,
        )
    }

    private fun buildHistory(messages: List<ChatMessage>): List<JsonObject> =
        messages.mapNotNull { it.toWireMessage() }

    private suspend fun persist(list: List<Conversation>? = null) {
        val current = _state.value.current
        val conversations = (list ?: _state.value.conversations).map {
            if (current != null && it.id == current.id) current else it
        }
        store.save(conversations)
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
