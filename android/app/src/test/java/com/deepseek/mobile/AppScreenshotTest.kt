package com.deepseek.mobile

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.deepseek.mobile.chat.ChatMessage
import com.deepseek.mobile.chat.ChatStore
import com.deepseek.mobile.chat.Conversation
import com.deepseek.mobile.chat.ROLE_ASSISTANT
import com.deepseek.mobile.chat.ROLE_USER
import com.deepseek.mobile.data.BalanceState
import com.deepseek.mobile.data.BalanceStore
import com.deepseek.mobile.harness.BridgeClient
import com.deepseek.mobile.harness.BridgeHealth
import com.deepseek.mobile.harness.BridgeInteraction
import com.deepseek.mobile.harness.BridgeSession
import com.deepseek.mobile.harness.BridgeSessionState
import com.deepseek.mobile.harness.HarnessEvent
import com.deepseek.mobile.harness.ModelCatalog
import com.deepseek.mobile.harness.ModelChoice
import com.deepseek.mobile.harness.ModelEffort
import com.deepseek.mobile.harness.ModelGroup
import com.deepseek.mobile.harness.PermissionCatalog
import com.deepseek.mobile.harness.PermissionOption
import com.deepseek.mobile.harness.SessionPermissions
import com.deepseek.mobile.harness.BridgeUpload
import com.deepseek.mobile.ui.AppRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Off-device screenshot pass over the real app shell: seeded chat history, a
 * fake bridge client for Harness screens, light and dark themes. Runs without
 * a device and writes PNGs under app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: DshApp = ApplicationProvider.getApplicationContext()

    private fun sampleImageBase64(): String {
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#4D6BFE"))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            isAntiAlias = true
        }
        canvas.drawText("sample", 60f, 118f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun seedChat() {
        val image = sampleImageBase64()
        val assistantBlocks: List<JsonObject> = listOf(
            buildJsonObject {
                put("type", "thinking")
                put("thinking", "用户想要一个手机上的 DeepSeek，既要能用自己的 Key 聊天，也要能连桌面端的 Harness。先确认两条路线都能走通，再给结论。")
            },
            buildJsonObject {
                put("type", "server_tool_use")
                put("name", "deepseek_web_search")
                put("input", buildJsonObject {
                    put("action", "search")
                    put("queries", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("DeepSeek API 安卓客户端"))
                        add(kotlinx.serialization.json.JsonPrimitive("DeepSeek 联网搜索"))
                    })
                })
            },
            buildJsonObject {
                put("type", "text")
                put("text", "两条路线都可行，推荐先做**对话模式**，再接 Harness：\n\n1. 对话模式直连官方 API，图片也能一起发；\n2. Harness 走局域网桥接，能看实时状态。\n\n```kotlin\nval client = OkHttpClient()\n```\n\n细节见 [官方文档](https://api-docs.deepseek.com)。")
                put("citations", buildJsonArray {
                    add(buildJsonObject {
                        put("url", "https://api-docs.deepseek.com")
                        put("title", "DeepSeek API 文档")
                    })
                })
            },
        )
        val conversation = Conversation(
            title = "安卓版 DeepSeek 方案",
            model = "deepseek-flash",
            messages = listOf(
                ChatMessage(
                    role = ROLE_USER,
                    text = "这是界面截图，帮我看看哪里需要打磨？",
                    images = listOf(image),
                ),
                ChatMessage(
                    role = ROLE_ASSISTANT,
                    blocks = assistantBlocks,
                    model = "deepseek-flash",
                    usage = buildJsonObject {
                        put("input_tokens", 1034)
                        put("cache_read_input_tokens", 0)
                        put("output_tokens", 253)
                    },
                    costYuan = 0.0021,
                    thinkingMs = 5000,
                ),
                ChatMessage(
                    role = ROLE_USER,
                    text = "顺便看下这份季度报告，帮我提炼三个要点。",
                    files = listOf(com.deepseek.mobile.chat.ChatFile("file-api-demo", "2026Q3-财报.pdf", "text_recognition")),
                ),
            ),
        )
        runBlocking { ChatStore(app).save(listOf(conversation)) }
    }

    private class FakeBalance(settings: com.deepseek.mobile.data.SettingsStore) : BalanceStore(
        ApplicationProvider.getApplicationContext(),
        OkHttpClient(),
        settings,
    ) {
        override suspend fun refresh(): BalanceState = BalanceState(
            amount = 12.34,
            currency = "CNY",
            updatedAt = System.currentTimeMillis(),
        )
    }

    private class FakeBridge(settings: com.deepseek.mobile.data.SettingsStore) : BridgeClient(OkHttpClient(), settings) {
        override suspend fun sessions(): List<BridgeSession> {
            val now = System.currentTimeMillis()
            return listOf(
                BridgeSession("s1", "安卓版 DeepSeek 客户端方案", false, emptyList(), now - 60_000, "C:\\Users\\Mr_YG\\Desktop\\ds-for-android", true),
                BridgeSession("s2", "拉取 MemOS 项目并连接 flet-mcp", false, emptyList(), now - 3_600_000, "C:\\Users\\Mr_YG\\Desktop\\BiliListener", true),
                BridgeSession("s3", "整理会议纪要", false, listOf("approval"), now - 90_000_000, "C:\\Users\\Mr_YG\\Documents\\notes", true),
            )
        }

        override suspend fun info(): BridgeHealth = BridgeHealth("imini-g", 3, 1)

        override suspend fun health(): String = "已连接 imini-g · 3 个会话，1 个在运行"

        override suspend fun interactions(): List<BridgeInteraction> = emptyList()

        override suspend fun permissions(sessionId: String): SessionPermissions = SessionPermissions(
            preset = "workspace-write",
            approvalPolicy = "ask",
            catalog = PermissionCatalog(
                options = listOf(
                    PermissionOption("read-only", "read-only", "只能读取工作区"),
                    PermissionOption("workspace-write", "workspace-write", "可写当前工作区"),
                    PermissionOption("danger-full-access", "danger-full-access", "不受沙箱限制"),
                ),
                defaultPreset = "workspace-write",
            ),
        )

        override suspend fun setPermissions(
            sessionId: String,
            preset: String?,
            approvalPolicy: String?,
        ): SessionPermissions {
            val base = permissions(sessionId)
            return base.copy(
                preset = preset ?: base.preset,
                approvalPolicy = approvalPolicy ?: base.approvalPolicy,
            )
        }

        override suspend fun uploadDocument(
            sessionId: String,
            bytes: ByteArray,
            name: String,
        ): BridgeUpload = BridgeUpload("receipt-demo", name, bytes.size)

        override suspend fun models(): ModelCatalog = ModelCatalog(
            defaultProvider = "deepseek-official",
            defaultModel = "deepseek-flash",
            defaultEffort = "high",
            groups = listOf(
                ModelGroup(
                    id = "deepseek-official",
                    name = "DeepSeek",
                    models = listOf(
                        ModelChoice(
                            provider = "deepseek-official",
                            id = "deepseek-flash",
                            name = "deepseek-flash",
                            description = "快，适合日常对话",
                            efforts = listOf(ModelEffort("low", "低"), ModelEffort("high", "高")),
                            defaultEffort = "high",
                        ),
                        ModelChoice(
                            provider = "deepseek-official",
                            id = "deepseek-reasoner",
                            name = "deepseek-reasoner",
                            description = "更深的长链推理",
                            efforts = listOf(ModelEffort("low", "低"), ModelEffort("medium", "中"), ModelEffort("high", "高")),
                            defaultEffort = "medium",
                        ),
                    ),
                ),
            ),
        )

        override fun stream(sessionId: String): Flow<HarnessEvent> = flow {
            emit(
                HarnessEvent.Snapshot(
                    records = listOf(
                        record("user/message", 1, buildJsonObject {
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", "把对话页再打磨一下，顺便加图片上传。") })
                            })
                        }),
                        record("assistant/message", 2, buildJsonObject {
                            put("turn", 1)
                            put("step", 1)
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "reasoning")
                                        put("text", "先看现有布局的问题，再决定怎么改配色和间距。")
                                    })
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", "改好了：统一了圆角和间距，输入栏加了图片入口，键盘弹出时不再遮挡正文。")
                                    })
                                })
                            })
                        }),
                        record("tool/call", 3, buildJsonObject {
                            put("turn", 1)
                            put("step", 1)
                            put("callId", "call-1")
                            put("name", "edit")
                            put("arguments", "{\"file_path\":\"C:\\\\app\\\\ChatScreens.kt\"}")
                        }),
                        record("tool/result", 4, buildJsonObject {
                            put("turn", 1)
                            put("step", 1)
                            put("message", buildJsonObject {
                                put("role", "tool")
                                put("toolCallId", "call-1")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", "Updated ChatScreens.kt (+42 -18)")
                                    })
                                })
                            })
                        }),
                    ),
                    cursor = 4,
                    header = null,
                ),
            )
            emit(HarnessEvent.StateChanged(BridgeSessionState(running = false, active = true)))
            awaitCancellation()
        }

        private fun record(type: String, seq: Int, data: JsonObject): JsonObject = buildJsonObject {
            put("type", "event")
            put("event", buildJsonObject {
                put("type", type)
                put("seq", seq)
                put("data", data)
            })
        }
    }

    private fun prepare(themeMode: String) {
        app.container.settings.update {
            it.copy(
                apiKey = "sk-test",
                themeMode = themeMode,
                bridgeUrl = "http://192.168.1.5:4318",
                bridgeToken = "token",
            )
        }
        app.container.bridge = FakeBridge(app.container.settings)
        app.getSharedPreferences("dsh_mobile_balance", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("amount", "12.34")
            .putString("currency", "CNY")
            .putLong("updatedAt", System.currentTimeMillis())
            .commit()
        app.container.balance = FakeBalance(app.container.settings)
        seedChat()
    }

    @Test
    fun captureLightScreens() {
        prepare("light")
        compose.setContent { AppRoot(app.container) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-01-chat-list.png")

        compose.onNodeWithContentDescription("多选").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-09-chat-select.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("安卓版 DeepSeek 方案").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-02-chat-detail.png")

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Harness").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-03-harness-list.png")

        compose.onNodeWithText("安卓版 DeepSeek 客户端方案").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-04-harness-detail.png")

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("设置").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-05-settings.png")

        compose.onNodeWithText("测试连接").performScrollTo()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-07-settings-harness.png")
    }

    @Test
    fun captureLauncherIcon() {
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .size(144.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFFEAF1FF)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.deepseek.mobile.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(144.dp),
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-08-icon.png")
    }

    @Test
    fun captureMascot() {
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .size(240.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFFEAF1FF)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                com.deepseek.mobile.ui.components.WhaleMascot(size = 168)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/light-06-mascot.png")
    }

    @Test
    fun captureDarkScreens() {
        prepare("dark")
        compose.setContent { AppRoot(app.container) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/dark-01-chat-list.png")

        compose.onNodeWithText("安卓版 DeepSeek 方案").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/dark-02-chat-detail.png")

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Harness").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("安卓版 DeepSeek 客户端方案").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/dark-03-harness-detail.png")
    }
}
