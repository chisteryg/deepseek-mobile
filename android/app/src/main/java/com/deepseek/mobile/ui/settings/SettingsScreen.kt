package com.deepseek.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.mobile.data.BalanceState
import com.deepseek.mobile.data.Billing
import com.deepseek.mobile.data.Settings
import com.deepseek.mobile.ui.components.ActionButton
import com.deepseek.mobile.ui.components.DsPill
import com.deepseek.mobile.ui.components.DsTopBar
import com.deepseek.mobile.ui.components.SectionLabel
import com.deepseek.mobile.ui.components.SoftCard
import com.deepseek.mobile.ui.components.StatusDot
import com.deepseek.mobile.ui.components.WhaleMascot
import com.deepseek.mobile.ui.theme.LocalDsPalette
import kotlinx.coroutines.launch

private data class ModelOption(val id: String, val name: String, val description: String)

private val CHAT_MODELS = listOf(
    ModelOption("deepseek-flash", "deepseek-flash", "快，支持思考与图片理解"),
    ModelOption("deepseek-v4-pro", "deepseek-v4-pro", "更强，适合复杂推理"),
)

private sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data class Done(val text: String, val ok: Boolean) : CheckState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    balance: BalanceState,
    onRefreshBalance: () -> Unit,
    todaySpend: Double,
    onTestApi: suspend () -> String,
    onTestBridge: suspend () -> String,
) {
    val palette = LocalDsPalette.current
    var apiKey by rememberSaveable(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var showKey by remember { mutableStateOf(false) }
    var apiTestResult by remember { mutableStateOf<String?>(null) }
    var modelSheet by remember { mutableStateOf(false) }
    var bridgeCheck by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    var bridgeCheckedAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun runBridgeCheck() {
        if (!settings.harnessReady) {
            bridgeCheck = CheckState.Done("还没有配对桌面端", false)
            return
        }
        bridgeCheck = CheckState.Checking
        scope.launch {
            val result = onTestBridge()
            val ok = !result.startsWith("失败")
            bridgeCheck = CheckState.Done(result, ok)
            bridgeCheckedAt = System.currentTimeMillis()
        }
    }

    LaunchedEffect(settings.bridgeUrl, settings.bridgeToken) {
        if (settings.harnessReady) runBridgeCheck()
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        DsTopBar(title = "设置", centered = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 32.dp),
        ) {
            // ---- account / balance
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("账户余额", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                balance.amount?.let { "¥${Billing.formatBalance(it)}" } ?: "—",
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
                                color = palette.textPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "今日消耗 ¥${Billing.formatCost(todaySpend)}",
                                modifier = Modifier.padding(bottom = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                balance.loading -> "正在查询…"
                                balance.error != null -> balance.error
                                balance.updatedAt > 0 -> "更新于 ${clock(balance.updatedAt)}"
                                else -> "尚未查询"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (balance.error != null) palette.danger else palette.textTertiary,
                        )
                    }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.surfaceMuted)
                            .clickable { onRefreshBalance() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (balance.loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.brand)
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新余额",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // ---- model + api
            SectionLabel("模型与 API")
            SoftCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { modelSheet = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("对话模型", style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
                        Text(
                            CHAT_MODELS.firstOrNull { it.id == settings.model }?.description ?: "自定义模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                        )
                    }
                    Text(
                        settings.model,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(18.dp).rotate(-90f),
                    )
                }
                RowDivider()
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            if (showKey) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.brand,
                            modifier = Modifier.padding(end = 8.dp).clickable { showKey = !showKey },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("服务地址") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        text = "保存并测试",
                        onClick = {
                            onUpdate {
                                it.copy(
                                    apiKey = apiKey.trim(),
                                    baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com" },
                                )
                            }
                            scope.launch {
                                apiTestResult = onTestApi()
                                onRefreshBalance()
                            }
                        },
                    )
                    apiTestResult?.let {
                        Spacer(Modifier.width(12.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Key 只保存在本机（Android Keystore 加密）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                )
            }

            // ---- chat switches
            SectionLabel("对话选项")
            SoftCard {
                ToggleRow("深度思考", "默认开启思考模式", settings.thinking) {
                    onUpdate { current -> current.copy(thinking = it) }
                }
                RowDivider()
                ToggleRow("联网搜索", "服务端搜索，回答带引用", settings.webSearch) {
                    onUpdate { current -> current.copy(webSearch = it) }
                }
                RowDivider()
                ToggleRow("阅读链接", "消息里的网址由服务端读取", settings.readUrl) {
                    onUpdate { current -> current.copy(readUrl = it) }
                }
                RowDivider()
                ToggleRow("自动生成标题", "回答结束时让服务端起标题", settings.autoTitle) {
                    onUpdate { current -> current.copy(autoTitle = it) }
                }
                RowDivider()
                ToggleRow("内容安全审核", "开启官方 safety_check 风控", settings.safetyCheck) {
                    onUpdate { current -> current.copy(safetyCheck = it) }
                }
            }

            // ---- appearance
            SectionLabel("外观")
            SoftCard {
                Text("主题", style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DsPill("跟随系统", settings.themeMode == "system", { onUpdate { it.copy(themeMode = "system") } })
                    DsPill("浅色", settings.themeMode == "light", { onUpdate { it.copy(themeMode = "light") } })
                    DsPill("深色", settings.themeMode == "dark", { onUpdate { it.copy(themeMode = "dark") } })
                }
            }

            // ---- harness
            SectionLabel("桌面 Harness")
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = when (bridgeCheck) {
                            is CheckState.Done -> if ((bridgeCheck as CheckState.Done).ok) palette.success else palette.danger
                            CheckState.Checking -> palette.warning
                            else -> palette.textTertiary
                        },
                        pulsing = bridgeCheck is CheckState.Checking,
                        size = 8,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (val check = bridgeCheck) {
                                CheckState.Checking -> "正在检测连接…"
                                is CheckState.Done -> if (check.ok) "已连接" else "连接失败"
                                else -> if (settings.harnessReady) "未检测" else "未配对"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = palette.textPrimary,
                        )
                        Text(
                            when (val check = bridgeCheck) {
                                is CheckState.Done -> check.text
                                CheckState.Checking -> "正在访问桌面端 /api/health"
                                else -> settings.bridgeUrl.ifBlank { "尚未填写桌面地址" }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bridgeCheck is CheckState.Done && !(bridgeCheck as CheckState.Done).ok) {
                                palette.danger
                            } else {
                                palette.textTertiary
                            },
                        )
                        if (bridgeCheckedAt > 0) {
                            Text(
                                "检测于 ${clock(bridgeCheckedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textTertiary,
                            )
                        }
                    }
                    if (bridgeCheck is CheckState.Checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.brand)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("测试连接", filled = false) { runBridgeCheck() }
                    ActionButton("解除配对", filled = false) {
                        onUpdate { it.copy(bridgeToken = "") }
                        bridgeCheck = CheckState.Done("已解除配对", false)
                    }
                }
            }

            // ---- about
            SectionLabel("关于")
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WhaleMascot(size = 46)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("DeepSeek 移动版 1.0", style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
                        Text(
                            "非官方客户端 · 对话与桌面 Harness",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                        )
                        Text(
                            "鲸鱼娘形象 © TreapGoGo · MIT",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    if (modelSheet) {
        ModalBottomSheet(
            onDismissRequest = { modelSheet = false },
            containerColor = palette.surface,
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    "对话模型",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
                CHAT_MODELS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdate { it.copy(model = option.id) }
                                modelSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option.id == settings.model) palette.brand else palette.textPrimary,
                            )
                            Text(
                                option.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textTertiary,
                            )
                        }
                        if (option.id == settings.model) {
                            Text("当前", style = MaterialTheme.typography.labelSmall, color = palette.brand)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = LocalDsPalette.current.border,
        thickness = 0.5.dp,
    )
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalDsPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.brand,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = palette.border,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

private fun clock(timestamp: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        .format(java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()))
