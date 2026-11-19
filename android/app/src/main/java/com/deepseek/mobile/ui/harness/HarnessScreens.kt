package com.deepseek.mobile.ui.harness

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobile.harness.BridgeInteraction
import com.deepseek.mobile.harness.BridgeSession
import com.deepseek.mobile.harness.HarnessTurn
import com.deepseek.mobile.harness.HarnessUiState
import com.deepseek.mobile.harness.HarnessViewModel
import com.deepseek.mobile.harness.ModelCatalog
import com.deepseek.mobile.harness.ModelChoice
import com.deepseek.mobile.harness.ModelSelection
import com.deepseek.mobile.ui.MarkdownText
import com.deepseek.mobile.ui.components.DsComposer
import com.deepseek.mobile.ui.components.DsEmptyState
import com.deepseek.mobile.ui.components.DsPill
import com.deepseek.mobile.ui.components.DsTopBar
import com.deepseek.mobile.ui.components.StatusDot
import com.deepseek.mobile.ui.theme.LocalDsPalette
import com.deepseek.mobile.util.arr
import com.deepseek.mobile.util.bool
import com.deepseek.mobile.util.obj
import com.deepseek.mobile.util.objects
import com.deepseek.mobile.util.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HarnessSection(
    vm: HarnessViewModel,
    defaultUrl: String,
    onOpenSettings: () -> Unit,
    onInnerScreen: (Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val session = state.session
    LaunchedEffect(session?.id) { onInnerScreen(session != null) }

    if (!state.paired) {
        PairingScreen(
            defaultUrl = defaultUrl,
            pairing = state.pairing,
            error = state.error,
            onPair = { url, code -> vm.pair(url, code) },
            onDismissError = vm::dismiss,
            onOpenSettings = onOpenSettings,
        )
        return
    }
    if (session == null) {
        SessionList(
            sessions = state.sessions,
            interactions = state.interactions,
            loading = state.loading,
            error = state.error,
            notice = state.notice,
            connectionError = state.connectionError,
            hostName = state.hostName,
            lastSync = state.lastSync,
            onRefresh = vm::refresh,
            onOpen = { vm.open(it) },
            onUnpair = vm::unpair,
            onDismiss = vm::dismiss,
            onOpenSettings = onOpenSettings,
        )
    } else {
        SessionDetail(vm = vm, session = session, state = state)
    }
}

@Composable
private fun PairingScreen(
    defaultUrl: String,
    pairing: Boolean,
    error: String?,
    onPair: (String, String) -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalDsPalette.current
    var url by rememberSaveable { mutableStateOf(defaultUrl) }
    var code by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        DsTopBar(title = "连接桌面 Harness")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("让手机看到并操作电脑上的 DSH 会话。", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepLine(1, "在电脑浏览器打开 http://127.0.0.1:4318/pair")
                StepLine(2, "页面会给出服务地址和 6 位配对码")
                StepLine(3, "手机与电脑连同一个 Wi-Fi，填在下面")
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("桌面地址") },
                placeholder = { Text("http://192.168.1.5:4318") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter(Char::isDigit) },
                label = { Text("6 位配对码") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissError) { Text("知道了") }
                    }
                }
            }
            Surface(
                color = if (pairing || code.length != 6) palette.border else palette.brand,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !pairing && code.length == 6 && url.isNotBlank()) { onPair(url, code) },
            ) {
                Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                    if (pairing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(
                            "连接",
                            color = if (code.length == 6) Color.White else palette.textTertiary,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }
                }
            }
            TextButton(onClick = onOpenSettings) { Text("手动填写令牌 / 修改地址") }
        }
    }
}

@Composable
private fun StepLine(index: Int, text: String) {
    val palette = LocalDsPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", style = MaterialTheme.typography.labelSmall, color = palette.brand)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
    }
}

@Composable
private fun SessionList(
    sessions: List<BridgeSession>,
    interactions: List<BridgeInteraction>,
    loading: Boolean,
    error: String?,
    notice: String?,
    connectionError: String?,
    hostName: String?,
    lastSync: Long,
    onRefresh: () -> Unit,
    onOpen: (BridgeSession) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalDsPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val running = sessions.count { it.running }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        DsTopBar(
            title = "桌面 Harness",
            centered = true,
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = palette.textPrimary)
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = palette.textPrimary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("刷新") }, onClick = {
                        menuOpen = false
                        onRefresh()
                    })
                    DropdownMenuItem(text = { Text("配对设置") }, onClick = {
                        menuOpen = false
                        onOpenSettings()
                    })
                    DropdownMenuItem(text = { Text("解除配对") }, onClick = {
                        menuOpen = false
                        confirmUnpair = true
                    })
                }
            },
        )

        ConnectionStrip(
            error = connectionError,
            hostName = hostName,
            sessions = sessions.size,
            running = running,
            lastSync = lastSync,
            onRetry = onRefresh,
        )
        if (error != null) Banner(error, true, onDismiss)
        if (notice != null) Banner(notice, false, onDismiss)
        interactions.forEach { interaction ->
            Surface(color = palette.brandSoft, modifier = Modifier.fillMaxWidth()) {
                Text(
                    interaction.title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.brand,
                )
            }
        }

        when {
            loading && sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.brand)
            }
            sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DsEmptyState(
                    title = "桌面上还没有会话",
                    description = "在电脑上新建一个会话，下拉刷新即可看到。",
                )
            }
            else -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(session) { onOpen(session) }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("解除配对") },
            text = { Text("将清除本机保存的桌面地址与令牌。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text("解除", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ConnectionStrip(
    error: String?,
    hostName: String?,
    sessions: Int,
    running: Int,
    lastSync: Long,
    onRetry: () -> Unit,
) {
    val palette = LocalDsPalette.current
    val connected = error == null && hostName != null
    val background = if (connected) palette.surfaceMuted else MaterialTheme.colorScheme.errorContainer
    val textColor = if (connected) palette.textPrimary else MaterialTheme.colorScheme.onErrorContainer
    val secondary = if (connected) palette.textTertiary else MaterialTheme.colorScheme.onErrorContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = if (connected) palette.success else palette.danger, pulsing = !connected)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    connected -> "已连接 ${hostName}"
                    error != null -> "连接失败"
                    else -> "正在连接桌面端…"
                },
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
            )
            Text(
                when {
                    connected && lastSync > 0 ->
                        "$sessions 个会话 · $running 个在运行 · 同步于 ${clockLabel(lastSync)}"
                    connected -> "$sessions 个会话 · $running 个在运行"
                    error != null -> error
                    else -> "正在请求 /api/health"
                },
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!connected) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("重试", style = MaterialTheme.typography.labelMedium, color = textColor)
            }
        }
    }
}

private fun clockLabel(timestamp: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        .format(java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()))

@Composable
private fun Banner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val palette = LocalDsPalette.current
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else palette.brandSoft) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else palette.brand,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun SessionRow(session: BridgeSession, onClick: () -> Unit) {
    val palette = LocalDsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    color = when {
                        session.hasPending -> palette.warning
                        session.running -> palette.success
                        else -> palette.textTertiary
                    },
                    pulsing = session.running || session.hasPending,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    session.cwd.takeIf { it.isNotBlank() }?.substringAfterLast('\\'),
                    relativeTime(session.updatedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        val statusColor = when {
            session.hasPending -> palette.warning
            session.running -> palette.success
            else -> palette.textTertiary
        }
        Text(
            session.pendingLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetail(
    vm: HarnessViewModel,
    session: BridgeSession,
    state: HarnessUiState,
) {
    val palette = LocalDsPalette.current
    val listState = rememberLazyListState()
    var input by rememberSaveable(session.id) { mutableStateOf("") }
    var modelSheet by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    BackHandler { vm.close() }

    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                wasScrolling = true
            } else if (wasScrolling) {
                wasScrolling = false
                follow = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToBottom()
    }
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.blocks?.size) {
        if (follow) listState.scrollToBottom()
    }

    val sessionInteractions = state.interactions.filter { it.sessionId == session.id }
    val catalog = state.catalog
    val selection = state.selection
    val modelLabel = when {
        catalog != null && selection != null ->
            catalog.find(selection.provider, selection.model)?.name ?: selection.model
        state.modelsError != null -> "模型不可用"
        else -> "模型"
    }
    val effortName = if (catalog != null && selection?.effort != null) {
        val effort = catalog.find(selection.provider, selection.model)
            ?.efforts?.firstOrNull { it.id == selection.effort }
        effortLabel(selection.effort, effort?.name)
    } else {
        null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .imePadding(),
    ) {
        DsTopBar(
            title = session.title,
            centered = true,
            onBack = vm::close,
        )

        ConnectionStrip(
            error = state.connectionError,
            hostName = state.hostName,
            sessions = state.sessions.size,
            running = state.sessions.count { it.running },
            lastSync = state.lastSync,
            onRetry = vm::refresh,
        )
        if (state.error != null) Banner(state.error, true, vm::dismiss)
        if (state.notice != null) Banner(state.notice, false, vm::dismiss)
        if (state.modelsError != null) Banner(state.modelsError, false, vm::dismiss)

        sessionInteractions.forEach { interaction ->
            ApprovalCard(
                interaction = interaction,
                onAllow = { vm.decide(interaction, "allow") },
                onDeny = { vm.decide(interaction, "deny") },
            )
        }

        Box(Modifier.weight(1f)) {
            if (state.turns.isEmpty()) {
                DsEmptyState(
                    title = "还没有对话",
                    description = "在下面发一条消息，桌面端会开始这一轮。",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(state.turns, key = { it.key }) { turn ->
                        TurnView(turn)
                    }
                }
            }
            if (!follow) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 14.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(palette.surface)
                        .border(0.5.dp, palette.border, CircleShape)
                        .clickable {
                            scope.launch { listState.scrollToBottom() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "回到底部",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Surface(color = palette.background) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                DsComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "给桌面端会话发消息…",
                    busy = state.state.running,
                    onSendOrStop = {
                        if (state.state.running) {
                            vm.stop()
                        } else {
                            val text = input
                            input = ""
                            vm.send(text)
                        }
                    },
                    above = {
                        DsPill(
                            text = modelLabel,
                            selected = catalog != null,
                            onClick = {
                                if (catalog == null) vm.loadModels() else modelSheet = true
                            },
                        )
                        if (effortName != null) {
                            DsPill(
                                text = "思考 $effortName",
                                selected = true,
                                onClick = { modelSheet = true },
                            )
                        }
                    },
                    disclaimer = if (state.state.running) {
                        "桌面端这一轮正在运行，可随时停止"
                    } else {
                        "连接到桌面端 Harness"
                    },
                )
            }
        }
    }

    if (modelSheet && state.catalog != null) {
        ModelSheet(
            catalog = state.catalog,
            selection = state.selection,
            onSelect = { provider, model, effort ->
                vm.selectModel(provider, model, effort)
                modelSheet = false
            },
            onDismiss = { modelSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    catalog: ModelCatalog,
    selection: ModelSelection?,
    onSelect: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDsPalette.current
    var pending by remember {
        mutableStateOf(
            selection?.let { catalog.find(it.provider, it.model) },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "选择模型",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            LazyColumn(modifier = Modifier.height(420.dp)) {
                catalog.groups.forEach { group ->
                    item(key = "g-${group.id}") {
                        Text(
                            group.name,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                        )
                    }
                    items(group.models, key = { it.id }) { model ->
                        val selected = selection?.provider == model.provider && selection.model == model.id
                        val expanded = pending?.provider == model.provider && pending?.id == model.id
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (model.efforts.isEmpty()) {
                                            onSelect(model.provider, model.id, null)
                                        } else {
                                            pending = model
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        model.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = if (selected) palette.brand else palette.textPrimary,
                                    )
                                    model.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.textTertiary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (selected) {
                                    Text("当前", style = MaterialTheme.typography.labelSmall, color = palette.brand)
                                }
                            }
                            if (expanded) {
                                Row(
                                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    model.efforts.forEach { effort ->
                                        DsPill(
                                            text = effortLabel(effort.id, effort.name).orEmpty(),
                                            selected = selected && selection?.effort == effort.id,
                                            onClick = { onSelect(model.provider, model.id, effort.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    interaction: BridgeInteraction,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val palette = LocalDsPalette.current
    Surface(
        color = palette.surfaceMuted,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(interaction.title, style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
            if (interaction.detail.isNotBlank()) {
                Text(
                    interaction.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("允许一次", filled = true, onClick = onAllow)
                ActionChip("拒绝", filled = false, onClick = onDeny)
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, filled: Boolean, onClick: () -> Unit) {
    val palette = LocalDsPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) palette.brand else palette.surface)
            .border(0.5.dp, if (filled) Color.Transparent else palette.border, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) Color.White else palette.textSecondary,
        )
    }
}

@Composable
private fun TurnView(turn: HarnessTurn) {
    val palette = LocalDsPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (turn.userText.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 40.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.userBubble)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(
                        turn.userText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
                        color = palette.userBubbleText,
                    )
                }
            }
            if (turn.blocks.isNotEmpty()) Spacer(Modifier.height(16.dp))
        }
        if (turn.blocks.isEmpty()) {
            if (turn.streaming) {
                Text(
                    "正在处理…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textTertiary,
                )
            }
            return
        }

        AssistantBlocks(turn.blocks, turn.streaming)
    }
}

@Composable
private fun AssistantBlocks(blocks: List<JsonObject>, streaming: Boolean) {
    val palette = LocalDsPalette.current
    val thinking = blocks
        .filter { it.str("type") == "reasoning" || it.str("type") == "thinking" }
        .mapNotNull { it.str("text") ?: it.str("thinking") }
        .joinToString("\n\n")
    val texts = blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.filter { it.isNotBlank() }
    val toolCalls = blocks.filter { it.str("type") == "tool-call" || it.str("type") == "tool_call" }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (thinking.isNotBlank()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (streaming) "正在思考…" else "已思考",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = palette.textTertiary,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (expanded) 180f else 0f),
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(palette.border, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            thinking,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 21.sp),
                            color = palette.textSecondary,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        }

        if (toolCalls.isNotEmpty()) {
            ToolSummary(toolCalls)
        }

        texts.forEach { text ->
            MarkdownText(text)
        }
    }
}

@Composable
private fun ToolSummary(calls: List<JsonObject>) {
    val palette = LocalDsPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val names = calls.mapNotNull { it.str("name") }.distinct()
    Column {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surfaceMuted)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "工具调用 × ${calls.size}",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                names.take(3).joinToString("、") + if (names.size > 3) "…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                calls.forEach { call ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.surfaceMuted)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            call.str("name").orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.brand,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            summarizeArguments(call.str("arguments").orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Pull one readable field out of a tool-call argument JSON string. */
private fun summarizeArguments(raw: String): String {
    if (raw.isBlank()) return ""
    val parsed = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull() ?: return raw.take(60)
    val candidates = listOf("description", "file_path", "path", "command", "url", "query", "pattern", "objective")
    for (key in candidates) {
        val value = parsed.str(key)
        if (!value.isNullOrBlank()) return value.replace('\n', ' ').take(70)
    }
    return raw.replace('\n', ' ').take(60)
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom() {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    scrollToItem(total - 1)
    scrollBy(100_000f)
}

private fun effortLabel(id: String?, fallback: String? = null): String? = when (id) {
    null -> null
    "off" -> "关闭"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "max" -> "最高"
    else -> fallback ?: id
}

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault()).format(
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()),
        )
    }
}
