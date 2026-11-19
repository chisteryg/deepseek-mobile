package com.deepseek.mobile.chat

import com.deepseek.mobile.util.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

const val ROLE_USER = "user"
const val ROLE_ASSISTANT = "assistant"

@Serializable
data class ChatFile(
    val id: String,
    val name: String,
    val purpose: String,
)

const val STATUS_DONE = "done"
const val STATUS_STREAMING = "streaming"
const val STATUS_STOPPED = "stopped"
const val STATUS_ERROR = "error"

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String = "",
    val images: List<String> = emptyList(),
    val files: List<ChatFile> = emptyList(),
    val blocks: List<JsonObject> = emptyList(),
    val status: String = STATUS_DONE,
    val error: String? = null,
    val usage: JsonObject? = null,
    val stopReason: String? = null,
    val model: String? = null,
    val costYuan: Double? = null,
    val thinkingMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val model: String = "deepseek-flash",
    val messages: List<ChatMessage> = emptyList(),
) {
    val preview: String
        get() = messages.lastOrNull { it.role == ROLE_ASSISTANT && it.textPreview().isNotBlank() }
            ?.textPreview()
            ?: messages.lastOrNull()?.text?.take(60)
            ?: ""
}

fun ChatMessage.textPreview(): String {
    val raw = if (role == ROLE_USER) {
        text.ifBlank {
            when {
                files.isNotEmpty() -> "「${files.first().name}」"
                images.isNotEmpty() -> "「图片」"
                else -> ""
            }
        }
    } else {
        blocks.joinToString(" ") { blockText(it) }
    }
    return raw
        .replace(Regex("```[\\s\\S]*?```"), "「代码」")
        .replace("\\n", " ")
        .replace(Regex("[#*_`>~\\[\\]()]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(90)
}

fun blockText(block: JsonObject): String = block.str("text") ?: ""

/**
 * Project one stored message onto the Messages API wire shape. Images travel as
 * base64 image blocks ahead of the text, matching the documented content-block
 * order, and unfinished assistant turns are skipped because their content can
 * lack the signatures the server needs on replay.
 */
fun ChatMessage.toWireMessage(): JsonObject? {
    if (role == ROLE_USER) {
        val parts = mutableListOf<JsonObject>()
        files.forEach { file ->
            parts.add(
                buildJsonObject {
                    put("type", "document")
                    put(
                        "source",
                        buildJsonObject {
                            put("type", "file")
                            put("file_id", file.id)
                        },
                    )
                },
            )
        }
        images.forEach { data ->
            parts.add(
                buildJsonObject {
                    put("type", "image")
                    put(
                        "source",
                        buildJsonObject {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", data)
                        },
                    )
                },
            )
        }
        if (text.isNotBlank()) {
            parts.add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        if (parts.isEmpty()) return null
        return buildJsonObject {
            put("role", "user")
            put("content", JsonArray(parts))
        }
    }
    if (status != STATUS_DONE) return null
    val content = blocks.filter { it.isNotEmpty() }
    if (content.isEmpty()) return null
    return buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(content))
    }
}
