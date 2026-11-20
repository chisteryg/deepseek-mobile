package com.deepseek.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.deepseek.mobile.R
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseek.mobile.chat.ChatViewModel
import com.deepseek.mobile.data.AppContainer
import com.deepseek.mobile.data.Settings
import com.deepseek.mobile.harness.HarnessViewModel
import com.deepseek.mobile.ui.chat.ChatSection
import com.deepseek.mobile.ui.harness.HarnessSection
import com.deepseek.mobile.ui.settings.SettingsScreen
import com.deepseek.mobile.ui.theme.DeepSeekTheme
import com.deepseek.mobile.ui.theme.LocalDsPalette
import com.deepseek.mobile.chat.ROLE_ASSISTANT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.withContext
import okhttp3.Request

@Composable
fun AppRoot(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val darkTheme = when (settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    DeepSeekTheme(darkTheme = darkTheme) {
        AppShell(container = container, settings = settings)
    }
}

@Composable
private fun AppShell(container: AppContainer, settings: Settings) {
    val palette = LocalDsPalette.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var innerScreen by remember { mutableStateOf(false) }

    val factory = remember(container) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                    ChatViewModel(container.chatStore, container.settings, container.deepSeek, container.balance) as T
                modelClass.isAssignableFrom(HarnessViewModel::class.java) ->
                    HarnessViewModel(container.bridge, container.settings) as T
                else -> error("未知的 ViewModel: ${modelClass.name}")
            }
        }
    }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val balance by container.balance.state.collectAsStateWithLifecycle()
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val todaySpend = remember(chatState.conversations) {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        chatState.conversations.sumOf { conversation ->
            conversation.messages
                .filter { it.role == ROLE_ASSISTANT && it.createdAt >= start }
                .sumOf { it.costYuan ?: 0.0 }
        }
    }
    val harnessViewModel: HarnessViewModel = viewModel(factory = factory)

    Scaffold(
        containerColor = palette.background,
        bottomBar = {
            if (!innerScreen) {
                NavigationBar(containerColor = palette.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(painterResource(R.drawable.ic_nav_chat), contentDescription = null) },
                        label = { Text("对话") },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(painterResource(R.drawable.ic_nav_harness), contentDescription = null) },
                        label = { Text("Harness") },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(painterResource(R.drawable.ic_nav_settings), contentDescription = null) },
                        label = { Text("设置") },
                        colors = navItemColors(),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatSection(
                    vm = chatViewModel,
                    settings = settings,
                    balance = balance,
                    onOpenSettings = { tab = 2 },
                    onUpdateSettings = container.settings::update,
                    onInnerScreen = { innerScreen = it },
                )
                1 -> HarnessSection(
                    vm = harnessViewModel,
                    defaultUrl = settings.bridgeUrl,
                    onOpenSettings = { tab = 2 },
                    onInnerScreen = { innerScreen = it },
                )
                else -> {
                    innerScreen = false
                    SettingsScreen(
                        settings = settings,
                        onUpdate = container.settings::update,
                        balance = balance,
                        onRefreshBalance = { scope.launch { container.balance.refresh() } },
                        todaySpend = todaySpend,
                        onTestApi = {
                            val current = container.settings.current
                            if (current.apiKey.isBlank()) {
                                "请先填写 API Key"
                            } else {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val request = Request.Builder()
                                            .url(current.baseUrl.trimEnd('/') + "/models")
                                            .addHeader("Authorization", "Bearer ${current.apiKey}")
                                            .build()
                                        container.http.newCall(request).execute().use { response ->
                                            if (response.isSuccessful) {
                                                "连接成功"
                                            } else {
                                                "失败：HTTP ${response.code} ${response.body?.string()?.take(200).orEmpty()}"
                                            }
                                        }
                                    }.getOrElse { "失败：${it.message}" }
                                }
                            }
                        },
                        onTestBridge = {
                            runCatching { container.bridge.health() }
                                .getOrElse { "失败：${it.message}" }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = LocalDsPalette.current.brand,
    selectedTextColor = LocalDsPalette.current.brand,
    indicatorColor = LocalDsPalette.current.brandSoft,
    unselectedIconColor = LocalDsPalette.current.textTertiary,
    unselectedTextColor = LocalDsPalette.current.textTertiary,
)
