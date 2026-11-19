package com.deepseek.mobile.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable conversation storage: one JSON file holding every conversation.
 * Writes go through a temp file + rename so a crash mid-save cannot truncate
 * the history.
 */
class ChatStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "conversations.json")
    private val mutex = Mutex()

    suspend fun load(): List<Conversation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(Conversation.serializer()), file.readText())
            }.getOrDefault(emptyList())
        }
    }

    suspend fun save(conversations: List<Conversation>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(ListSerializer(Conversation.serializer()), conversations))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
