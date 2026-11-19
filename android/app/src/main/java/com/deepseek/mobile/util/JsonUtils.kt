package com.deepseek.mobile.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

fun JsonObject.str(key: String): String? = this[key].asString()

fun JsonObject.int(key: String): Int? = this[key].asString()?.toIntOrNull()

fun JsonObject.long(key: String): Long? = this[key].asString()?.toLongOrNull()

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonArray.objects(): List<JsonObject> = this.mapNotNull { it as? JsonObject }

/** Human label for one server-side tool call block. */
fun toolActionLabel(block: JsonObject): String {
    val input = block.obj("input") ?: return "工具调用"
    return when (input.str("action")) {
        "search" -> "联网搜索"
        "open_page" -> "打开网页"
        "find_in_page" -> "页内查找"
        "read_link" -> "阅读链接"
        else -> block.str("name") ?: "工具调用"
    }
}

fun toolActionDetail(block: JsonObject): String {
    val input = block.obj("input") ?: return ""
    val queries = input.arr("queries")?.mapNotNull { it.asString() } ?: emptyList()
    if (queries.isNotEmpty()) return queries.joinToString("、")
    val url = input.str("url")
    val pattern = input.str("pattern")
    return when {
        url != null && pattern != null -> "$url · 查找「$pattern」"
        url != null -> url
        else -> ""
    }
}
