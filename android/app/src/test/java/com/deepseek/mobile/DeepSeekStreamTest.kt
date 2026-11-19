package com.deepseek.mobile

import com.deepseek.mobile.chat.ChatEvent
import com.deepseek.mobile.chat.DeepSeekClient
import com.deepseek.mobile.chat.toWireMessage
import com.deepseek.mobile.data.Settings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end test of the Messages API streaming client against a local server,
 * so the SSE parser and request shape are verified without an API key.
 */
class DeepSeekStreamTest {

    private lateinit var server: MockWebServer
    private val client by lazy { DeepSeekClient(OkHttpClient()) }

    private val sse = buildString {
        append("event: message_start\n")
        append("""data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":10}}}""")
        append("\n\n")
        append("event: content_block_start\n")
        append("""data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先想一想"}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""")
        append("\n\n")
        append("event: content_block_stop\n")
        append("""data: {"type":"content_block_stop","index":0}""")
        append("\n\n")
        append("event: content_block_start\n")
        append("""data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"你好，"}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"世界"}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com","title":"示例"}}}""")
        append("\n\n")
        append("event: content_block_stop\n")
        append("""data: {"type":"content_block_stop","index":1}""")
        append("\n\n")
        append("event: message_delta\n")
        append("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn","title":"问候"},"usage":{"input_tokens":12,"output_tokens":34}}""")
        append("\n\n")
        append("event: message_stop\n")
        append("""data: {"type":"message_stop"}""")
        append("\n\n")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun settings(webSearch: Boolean = true) = Settings(
        apiKey = "test-key",
        baseUrl = server.url("/").toString(),
        model = "deepseek-flash",
        thinking = true,
        webSearch = webSearch,
        readUrl = true,
        safetyCheck = true,
        autoTitle = true,
    )

    @Test
    fun streamsThinkingTextCitationsAndUsage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "text/event-stream")
                .setBody(sse),
        )
        val events = client.stream(settings(), emptyList()).toList()

        val thinking = events.filterIsInstance<ChatEvent.ThinkingDelta>().joinToString("") { it.text }
        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        val signatures = events.filterIsInstance<ChatEvent.SignatureDelta>().map { it.signature }
        val citations = events.filterIsInstance<ChatEvent.CitationsDelta>()
        val finished = events.filterIsInstance<ChatEvent.Finished>().single()

        assertEquals("先想一想", thinking)
        assertEquals("你好，世界", text)
        assertEquals(listOf("sig-1"), signatures)
        assertEquals(1, citations.size)
        assertEquals("https://example.com", citations[0].citation["url"]?.jsonPrimitive?.content)
        assertEquals("end_turn", finished.stopReason)
        assertEquals("问候", finished.title)
        assertEquals(34, finished.usage?.get("output_tokens")?.jsonPrimitive?.content?.toInt())
        assertTrue(events.none { it is ChatEvent.Failure })

        val recorded = server.takeRequest()
        assertEquals("/anthropic/v1/messages", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("deepseek_web_search", "deepseek_read_url"), tools)
        assertEquals("high", body["safety_check"]!!.jsonObject["level"]?.jsonPrimitive?.content)
    }

    @Test
    fun reportsHttpErrorsAsFailureEvents() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("content-type", "application/json")
                .setBody("""{"error":{"message":"Authentication Fails"}}"""),
        )
        val events = client.stream(settings(webSearch = false), emptyList()).toList()
        val failure = events.filterIsInstance<ChatEvent.Failure>().single()
        assertEquals("Authentication Fails", failure.message)
    }

    @Test
    fun missingApiKeyFailsWithoutCallingTheNetwork() = runBlocking {
        val events = client.stream(settings().copy(apiKey = ""), emptyList()).toList()
        assertTrue(events.single() is ChatEvent.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun payloadCarriesHistoryVerbatim() {
        val history = listOf(
            buildJsonObject {
                put("role", "user")
                put("content", "上一轮的问题")
            },
        )
        val payload = client.buildPayload(settings(), history)
        val messages = payload["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("上一轮的问题", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun imageMessagesBecomeBase64ImageBlocks() {
        val message = com.deepseek.mobile.chat.ChatMessage(
            role = "user",
            text = "看图",
            images = listOf("QUJD"),
        )
        val wire = message.toWireMessage()!!
        val content = wire["content"]!!.jsonArray
        assertEquals(2, content.size)
        val image = content[0].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("QUJD", source["data"]?.jsonPrimitive?.content)
        assertEquals("看图", content[1].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun documentsBecomeFileReferencesAndEnableTheBetaHeader() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "text/event-stream")
                .setBody(sse),
        )
        val message = com.deepseek.mobile.chat.ChatMessage(
            role = "user",
            text = "看看这份文档",
            files = listOf(
                com.deepseek.mobile.chat.ChatFile("file-api-123", "report.pdf", "text_recognition"),
            ),
        )
        val wire = message.toWireMessage()!!
        val content = wire["content"]!!.jsonArray
        assertEquals("document", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        val source = content[0].jsonObject["source"]!!.jsonObject
        assertEquals("file", source["type"]?.jsonPrimitive?.content)
        assertEquals("file-api-123", source["file_id"]?.jsonPrimitive?.content)

        client.stream(settings(), listOf(wire)).toList()
        val recorded = server.takeRequest()
        assertEquals("files-api-2025-04-14", recorded.getHeader("anthropic-beta"))
    }

    @Test
    fun unfinishedAssistantTurnsAreNotReplayed() {
        val stopped = com.deepseek.mobile.chat.ChatMessage(
            role = "assistant",
            status = "stopped",
            blocks = listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", "半句话")
                },
            ),
        )
        org.junit.Assert.assertNull(stopped.toWireMessage())
    }
}
