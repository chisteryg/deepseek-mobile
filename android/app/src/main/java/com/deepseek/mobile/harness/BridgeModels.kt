package com.deepseek.mobile.harness

data class BridgeSession(
    val id: String,
    val title: String,
    val running: Boolean,
    val pending: List<String>,
    val updatedAt: Long,
    val cwd: String,
    val agentAvailable: Boolean,
) {
    val hasPending: Boolean get() = pending.isNotEmpty()
    val pendingLabel: String
        get() = when {
            pending.contains("approval") -> "等你批准"
            pending.contains("question") -> "等你回答"
            running -> "运行中"
            else -> "空闲"
        }
}

data class BridgeSessionState(
    val running: Boolean = false,
    val active: Boolean = false,
    val pendingInput: List<String> = emptyList(),
    val queued: Int = 0,
)

data class BridgeInteraction(
    val id: String,
    val kind: String,
    val sessionId: String,
    val title: String,
    val detail: String,
)

data class PairResult(val token: String, val host: String, val port: Int, val name: String)

data class BridgeHealth(val name: String, val sessions: Int, val running: Int)

data class ModelEffort(val id: String, val name: String)

data class ModelChoice(
    val provider: String,
    val id: String,
    val name: String,
    val description: String?,
    val efforts: List<ModelEffort>,
    val defaultEffort: String?,
)

data class ModelGroup(val id: String, val name: String, val models: List<ModelChoice>)

data class ModelCatalog(
    val defaultProvider: String,
    val defaultModel: String,
    val defaultEffort: String?,
    val groups: List<ModelGroup>,
) {
    val allModels: List<ModelChoice> get() = groups.flatMap { it.models }

    fun find(provider: String, model: String): ModelChoice? =
        allModels.firstOrNull { it.provider == provider && it.id == model }

    fun label(provider: String, model: String, effort: String?): String {
        val choice = find(provider, model)
        val base = choice?.name ?: model
        val effortName = effort?.let { id -> choice?.efforts?.firstOrNull { it.id == id }?.name ?: id }
        return if (effortName != null) "$base · $effortName" else base
    }
}

sealed interface HarnessEvent {
    data class Snapshot(
        val records: List<kotlinx.serialization.json.JsonObject>,
        val cursor: Int,
        val header: kotlinx.serialization.json.JsonObject?,
    ) : HarnessEvent

    data class WireEvent(val event: kotlinx.serialization.json.JsonObject) : HarnessEvent
    data class StateChanged(val state: BridgeSessionState) : HarnessEvent
    data class AssistantChunk(val frame: kotlinx.serialization.json.JsonObject) : HarnessEvent
    data class Closed(val reason: String) : HarnessEvent
    data class Failure(val message: String) : HarnessEvent
}
