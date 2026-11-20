package com.deepseek.mobile.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobile.chat.ChatFile
import com.deepseek.mobile.chat.ChatMessage
import com.deepseek.mobile.chat.ChatViewModel
import com.deepseek.mobile.chat.Conversation
import com.deepseek.mobile.chat.ROLE_USER
import com.deepseek.mobile.chat.STATUS_ERROR
import com.deepseek.mobile.chat.STATUS_STREAMING
import com.deepseek.mobile.data.BalanceState
import com.deepseek.mobile.data.Billing
import com.deepseek.mobile.data.Settings
import com.deepseek.mobile.ui.MarkdownText
import com.deepseek.mobile.ui.components.DsComposer
import com.deepseek.mobile.ui.components.DsEmptyState
import com.deepseek.mobile.ui.components.DsPill
import com.deepseek.mobile.ui.components.DsTopBar
import com.deepseek.mobile.ui.components.MessageActionRow
import com.deepseek.mobile.ui.theme.LocalDsPalette
import com.deepseek.mobile.util.ImageUtil
import com.deepseek.mobile.util.arr
import com.deepseek.mobile.util.int
import com.deepseek.mobile.util.obj
import com.deepseek.mobile.util.objects
import com.deepseek.mobile.util.str
import com.deepseek.mobile.util.toolActionDetail
import com.deepseek.mobile.util.toolActionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun ChatSection(
    vm: ChatViewModel,
    settings: Settings,
    balance: BalanceState,
    onOpenSettings: () -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onInnerScreen: (Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state.current
    LaunchedEffect(current?.id) { onInnerScreen(current != null) }

    if (current == null) {
        ChatListScreen(
            conversations = state.conversations,
            balance = balance,
            onOpen = vm::open,
            onNew = vm::newConversation,
            onDeleteMany = vm::deleteMany,
            onOpenSettings = onOpenSettings,
        )
    } else {
        ChatDetailScreen(
            vm = vm,
            settings = settings,
            balance = balance,
            conversation = current,
            streaming = state.streaming,
            error = state.error,
            onUpdateSettings = onUpdateSettings,
        )
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun ChatListScreen(
    conversations: List<Conversation>,
    balance: BalanceState,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDeleteMany: (Set<String>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalDsPalette.current
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        DsTopBar(
            title = if (selecting) "已选 ${selected.size}" else "对话",
            centered = true,
            onBack = if (selecting) ({ exitSelection() }) else null,
            actions = {
                if (selecting) {
                    TextAction("全选", palette.brand) {
                        selected = if (selected.size == conversations.size) {
                            emptySet()
                        } else {
                            conversations.map { it.id }.toSet()
                        }
                    }
                    TextAction("删除", palette.danger, enabled = selected.isNotEmpty()) {
                        confirmDelete = true
                    }
                } else {
                    IconButton(onClick = onNew) {
                        Icon(Icons.Filled.Add, contentDescription = "新对话", tint = palette.textPrimary)
                    }
                    IconButton(onClick = { selecting = true }) {
                        Icon(Icons.Filled.List, contentDescription = "多选", tint = palette.textPrimary)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = palette.textPrimary)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("设置") }, onClick = {
                                menuOpen = false
                                onOpenSettings()
                            })
                        }
                    }
                }
            },
        )
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DsEmptyState(
                    title = "你好，我能帮什么忙吗？",
                    description = "打字，或发一张图片给我看；\n先去设置里填好 API Key。",
                    actionLabel = "去设置",
                    onAction = onOpenSettings,
                )
            }
        } else {
            val grouped = conversations.groupBy { bucketOf(it.updatedAt) }
            Box(Modifier.weight(1f)) {
                LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                    listOf("今天", "昨天", "本周", "更早").forEach { bucket ->
                        val items = grouped[bucket].orEmpty()
                        if (items.isNotEmpty()) {
                            item(key = "header-$bucket") {
                                Text(
                                    bucket,
                                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textTertiary,
                                )
                            }
                            items(items, key = { it.id }) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    selecting = selecting,
                                    selected = conversation.id in selected,
                                    onClick = {
                                        if (selecting) {
                                            selected = if (conversation.id in selected) {
                                                selected - conversation.id
                                            } else {
                                                selected + conversation.id
                                            }
                                        } else {
                                            onOpen(conversation.id)
                                        }
                                    },
                                    onLongClick = {
                                        selecting = true
                                        selected = selected + conversation.id
                                    },
                                )
                            }
                        }
                    }
                }
                if (!selecting) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.surface)
                            .border(0.5.dp, palette.border, RoundedCornerShape(999.dp))
                            .clickable { onNew() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = palette.brand,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("开启新对话", style = MaterialTheme.typography.labelLarge, color = palette.brand)
                    }
                }
            }
        }
        balance.amount?.let { amount ->
            Text(
                "余额 ¥${Billing.formatBalance(amount)}",
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除对话") },
            text = { Text("将永久删除选中的 ${selected.size} 个对话。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteMany(selected)
                    exitSelection()
                }) { Text("删除", color = palette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TextAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else LocalDsPalette.current.textTertiary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalDsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) palette.brand else palette.surface)
                    .border(1.2.dp, if (selected) palette.brand else palette.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                conversation.preview.ifBlank { "暂无内容" },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                timeLabel(conversation.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
            )
            if (conversation.messages.lastOrNull()?.status == STATUS_STREAMING) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(palette.brand),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailScreen(
    vm: ChatViewModel,
    settings: Settings,
    balance: BalanceState,
    conversation: Conversation,
    streaming: Boolean,
    error: String?,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    val palette = LocalDsPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var attachSheet by remember { mutableStateOf(false) }
    var input by rememberSaveable(conversation.id) { mutableStateOf("") }
    var attached by remember(conversation.id) { mutableStateOf<List<String>>(emptyList()) }
    var attachedFiles by remember(conversation.id) { mutableStateOf<List<ChatFile>>(emptyList()) }
    var uploading by remember(conversation.id) { mutableStateOf<String?>(null) }
    var attachError by remember(conversation.id) { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) { ImageUtil.encodeBase64(context, uri) }
                if (encoded != null) attached = (attached + encoded).take(4)
            }
        }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) { ImageUtil.encodeBase64(context, uri) }
                if (encoded != null) attached = (attached + encoded).take(4)
            }
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                uploading = displayName(context, uri)
                attachError = null
                try {
                    val payload = withContext(Dispatchers.IO) {
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("无法读取文件")
                        if (bytes.size > 32 * 1024 * 1024) throw IllegalStateException("文件超过 32 MiB")
                        Triple(bytes, mime, displayName(context, uri))
                    }
                    val file = vm.uploadDocument(
                        bytes = payload.first,
                        name = payload.third,
                        mimeType = payload.second,
                        purpose = filePurpose(payload.third),
                    )
                    attachedFiles = (attachedFiles + file).take(4)
                } catch (error: Exception) {
                    attachError = error.message ?: "文件上传失败"
                } finally {
                    uploading = null
                }
            }
        }
    }

    BackHandler { if (!streaming) vm.close() }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                // A finger is on the list: stop following immediately so the
                // next streaming block cannot yank the viewport back.
                follow = false
            } else {
                follow = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToBottom()
    }
    val contentTick = conversation.messages.size to conversation.messages.lastOrNull()?.let {
        it.text.length + it.blocks.size + it.status.hashCode()
    }
    LaunchedEffect(contentTick) {
        if (follow) listState.scrollToBottom()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .imePadding(),
    ) {
        DsTopBar(
            title = conversation.title,
            centered = true,
            onBack = { if (!streaming) vm.close() },
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = palette.textPrimary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                        menuOpen = false
                        renameOpen = true
                    })
                    DropdownMenuItem(text = { Text("重新生成") }, onClick = {
                        menuOpen = false
                        vm.regenerate()
                    })
                    DropdownMenuItem(text = { Text("删除") }, onClick = {
                        menuOpen = false
                        deleteOpen = true
                    })
                }
            },
        )

        if (error != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::dismissError) { Text("知道了") }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (conversation.messages.isEmpty()) {
                DsEmptyState(
                    title = "你好，我能帮什么忙吗？",
                    description = "打字，或发一张图片给我看。",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    items(conversation.messages, key = { it.id }) { message ->
                        if (message.role == ROLE_USER) {
                            UserTurn(message)
                        } else {
                            AssistantTurn(
                                message = message,
                                balance = balance,
                                streaming = streaming && message.status == STATUS_STREAMING,
                                onCopy = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            message.blocks.joinToString("\n\n") { it.str("text").orEmpty() },
                                        ),
                                    )
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                onRetry = vm::regenerate,
                            )
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
                            .clickable { scope.launch { listState.scrollToBottom() } },
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
        }

        ComposerArea(
            input = input,
            onInputChange = { input = it },
            attached = attached,
            attachedFiles = attachedFiles,
            uploading = uploading,
            attachError = attachError,
            onDismissAttachError = { attachError = null },
            onRemoveImage = { index -> attached = attached.filterIndexed { i, _ -> i != index } },
            onRemoveFile = { index -> attachedFiles = attachedFiles.filterIndexed { i, _ -> i != index } },
            settings = settings,
            streaming = streaming,
            onUpdateSettings = onUpdateSettings,
            onAttach = { attachSheet = true },
            onSend = {
                val text = input
                val images = attached
                val files = attachedFiles
                input = ""
                attached = emptyList()
                attachedFiles = emptyList()
                follow = true
                vm.send(text, images, files)
            },
            onStop = vm::stop,
        )
    }

    if (attachSheet) {
        ModalBottomSheet(
            onDismissRequest = { attachSheet = false },
            containerColor = palette.background,
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AttachTile(
                    label = "拍照",
                    symbol = "◉",
                    modifier = Modifier.weight(1f),
                ) {
                    attachSheet = false
                    launchCamera()
                }
                AttachTile(
                    label = "相册",
                    symbol = "▣",
                    modifier = Modifier.weight(1f),
                ) {
                    attachSheet = false
                    galleryLauncher.launch("image/*")
                }
                AttachTile(
                    label = "文件",
                    symbol = "▤",
                    modifier = Modifier.weight(1f),
                ) {
                    attachSheet = false
                    documentLauncher.launch(
                        arrayOf(
                            "application/pdf",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.ms-powerpoint",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/*",
                            "application/json",
                            "application/epub+zip",
                        ),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (renameOpen) {
        RenameDialog(
            initial = conversation.title,
            onDismiss = { renameOpen = false },
            onConfirm = {
                vm.rename(conversation.id, it)
                renameOpen = false
            },
            onDelete = {
                renameOpen = false
                deleteOpen = true
            },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除对话") },
            text = { Text("这个对话将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    vm.delete(conversation.id)
                }) { Text("删除", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AttachTile(
    label: String,
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalDsPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surfaceMuted)
            .clickable { onClick() }
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(symbol, fontSize = 24.sp, color = palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerArea(
    input: String,
    onInputChange: (String) -> Unit,
    attached: List<String>,
    attachedFiles: List<ChatFile>,
    uploading: String?,
    attachError: String?,
    onDismissAttachError: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
    settings: Settings,
    streaming: Boolean,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = LocalDsPalette.current
    Surface(color = palette.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (attached.isNotEmpty()) {
                ImageStrip(attached, onRemoveImage)
                Spacer(Modifier.height(8.dp))
            }
            if (attachedFiles.isNotEmpty() || uploading != null || attachError != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachedFiles.forEachIndexed { index, file ->
                        FileChip(
                            name = file.name,
                            busy = false,
                            onRemove = { onRemoveFile(index) },
                        )
                    }
                    if (uploading != null) {
                        FileChip(name = uploading, busy = true, onRemove = null)
                    }
                    if (attachError != null) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .clickable { onDismissAttachError() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                attachError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            DsComposer(
                value = input,
                onValueChange = onInputChange,
                placeholder = "给 DeepSeek 发消息…",
                busy = streaming,
                onSendOrStop = { if (streaming) onStop() else onSend() },
                onAttach = onAttach,
                disclaimer = "回答由 AI 生成，仅供参考",
                sendEnabled = input.isNotBlank() || attached.isNotEmpty() || attachedFiles.isNotEmpty(),
                above = {
                    DsPill(
                        text = "深度思考",
                        selected = settings.thinking,
                        onClick = { onUpdateSettings { it.copy(thinking = !it.thinking) } },
                        leading = {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = if (settings.thinking) palette.brand else palette.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                    DsPill(
                        text = "智能搜索",
                        selected = settings.webSearch,
                        onClick = { onUpdateSettings { it.copy(webSearch = !it.webSearch) } },
                        leading = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = if (settings.webSearch) palette.brand else palette.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun FileChip(name: String, busy: Boolean, onRemove: (() -> Unit)?) {
    val palette = LocalDsPalette.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.surfaceMuted)
            .padding(start = 10.dp, end = if (onRemove != null) 6.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp, color = palette.brand)
        } else {
            Text("▤", style = MaterialTheme.typography.labelMedium, color = palette.brand)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp),
        )
        if (onRemove != null) {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
            }
        }
    }
}

@Composable
private fun ImageStrip(images: List<String>, onRemove: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { index, encoded ->
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalDsPalette.current.surfaceMuted),
            ) {
                val bitmap = remember(encoded) { ImageUtil.decodeThumbnail(encoded, 160) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- messages

@Composable
private fun UserTurn(message: ChatMessage) {
    val palette = LocalDsPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 40.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(palette.userBubble)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (message.files.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    message.files.forEach { file ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surface)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("▤", style = MaterialTheme.typography.labelMedium, color = palette.brand)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                file.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        }
                    }
                }
                if (message.text.isNotBlank() || message.images.isNotEmpty()) Spacer(Modifier.height(8.dp))
            }
            if (message.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.images.take(3).forEach { encoded ->
                        val bitmap = remember(encoded) { ImageUtil.decodeThumbnail(encoded, 320) }
                        Box(
                            Modifier
                                .size(if (message.images.size == 1) 190.dp else 96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.surfaceMuted),
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 29.sp, letterSpacing = 0.2.sp),
                    color = palette.userBubbleText,
                )
            }
        }
    }
}

@Composable
private fun AssistantTurn(
    message: ChatMessage,
    balance: BalanceState,
    streaming: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalDsPalette.current

    Column(Modifier.fillMaxWidth()) {
        if (message.blocks.isEmpty() && streaming) {
            Text(
                "正在思考…",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textTertiary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message.blocks.forEach { block ->
                BlockView(block, streaming, message.thinkingMs)
            }
        }

        if (message.status == STATUS_ERROR && message.error != null) {
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    message.error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (message.stopReason == "refusal") {
            Spacer(Modifier.height(10.dp))
            Surface(color = palette.surfaceMuted, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "这次请求被模型的内容安全策略拒绝。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        }

        if (!streaming && message.blocks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MessageActionRow(onCopy = onCopy, onRetry = onRetry)
            Spacer(Modifier.height(5.dp))
            val parts = buildList {
                add(message.model ?: "deepseek-flash")
                message.costYuan?.let { add("本次 ¥${Billing.formatCost(it)}") }
                balance.amount?.let { add("余额 ¥${Billing.formatBalance(it)}") }
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
            )
            message.usage?.let { usage ->
                val input = usage.int("input_tokens") ?: 0
                val cached = usage.int("cache_read_input_tokens") ?: 0
                val output = usage.int("output_tokens") ?: 0
                if (input + cached + output > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "输入 ${count(input + cached)}（命中 ${count(cached)} · 未命中 ${count(input)}）· 输出 ${count(output)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockView(block: JsonObject, streaming: Boolean, thinkingMs: Long? = null) {
    when (block.str("type")) {
        "thinking" -> ThinkingBlock(block, streaming, thinkingMs)
        "text" -> TextBlock(block)
        "server_tool_use" -> ToolUseStrip(block)
        "web_search_tool_result" -> SearchResultStrip(block)
        "web_fetch_tool_result" -> FetchStrip(block)
        "redacted_thinking" -> Text(
            "（一段思考被安全策略隐藏）",
            style = MaterialTheme.typography.labelSmall,
            color = LocalDsPalette.current.textTertiary,
        )
        else -> Unit
    }
}

@Composable
private fun ThinkingBlock(block: JsonObject, streaming: Boolean, thinkingMs: Long? = null) {
    val palette = LocalDsPalette.current
    val thinking = block.str("thinking").orEmpty()
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
                when {
                    streaming -> "正在思考…"
                    thinkingMs != null -> "已思考（用时 ${durationLabel(thinkingMs)}）"
                    else -> "已思考"
                },
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
        if (expanded && thinking.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(palette.border, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 25.sp),
                    color = palette.textSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextBlock(block: JsonObject) {
    val palette = LocalDsPalette.current
    val context = LocalContext.current
    val text = block.str("text").orEmpty()
    if (text.isBlank()) return
    Column {
        MarkdownText(text)
        val citations = block.arr("citations")?.objects().orEmpty()
        if (citations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                citations.take(5).forEachIndexed { index, citation ->
                    val url = citation.str("url").orEmpty()
                    val host = url.substringAfter("://").substringBefore('/').ifBlank { url }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.surfaceMuted)
                            .clickable {
                                if (url.isNotBlank()) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                Uri.parse(url),
                                            ),
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            host,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp),
                        )
                    }
                }
                if (citations.size > 5) {
                    Text(
                        "+${citations.size - 5}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolUseStrip(block: JsonObject) {
    val palette = LocalDsPalette.current
    val label = toolActionLabel(block)
    val detail = toolActionDetail(block)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = palette.brand)
        if (detail.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchResultStrip(block: JsonObject) {
    val palette = LocalDsPalette.current
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val results = block.arr("content")?.objects().orEmpty()
    if (results.isEmpty()) return
    Column {
        Text(
            "搜索到 ${results.size} 个网页 · ${if (expanded) "收起" else "展开"}",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = palette.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                results.forEach { result ->
                    val url = result.str("url").orEmpty()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surfaceMuted)
                            .clickable {
                                if (url.isNotBlank()) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)),
                                        )
                                    }
                                }
                            }
                            .padding(10.dp),
                    ) {
                        Text(
                            result.str("title") ?: url,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(result.str("site_name"), url).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FetchStrip(block: JsonObject) {
    val palette = LocalDsPalette.current
    val content = block.obj("content")
    val error = content?.str("error_code")
    val url = content?.str("url").orEmpty()
    val title = content?.obj("content")?.str("title") ?: url
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (error != null) "阅读失败" else "已浏览网页",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = if (error != null) palette.danger else palette.brand,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            error ?: title,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- helpers

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom() {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    scrollToItem(total - 1)
    scrollBy(100_000f)
}

private fun durationLabel(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(1)
    return if (totalSeconds < 60) {
        "$totalSeconds 秒"
    } else {
        "${totalSeconds / 60} 分 ${totalSeconds % 60} 秒"
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDelete) {
                    Text("删除这个对话", color = LocalDsPalette.current.danger)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun count(value: Int): String =
    java.text.NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun displayName(context: android.content.Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) {
            val name = it.getString(index)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
}

private fun filePurpose(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf", "doc", "docx", "ppt", "pptx" -> "text_recognition"
        "txt", "md", "csv", "json", "xlsx", "xls", "epub", "mobi",
        "kt", "java", "py", "js", "ts", "html", "xml", "yml", "yaml", "log",
        -> "user_data"
        else -> "text_recognition"
    }
}

private fun bucketOf(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "今天"
        1L -> "昨天"
        in 2L..6L -> "本周"
        else -> "更早"
    }
}

private fun timeLabel(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return if (date == today) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(
            Instant.ofEpochMilli(timestamp).atZone(zone),
        )
    } else {
        DateTimeFormatter.ofPattern("MM-dd", Locale.getDefault()).format(
            Instant.ofEpochMilli(timestamp).atZone(zone),
        )
    }
}
