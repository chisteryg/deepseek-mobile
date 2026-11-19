package com.deepseek.mobile.data

import android.content.Context
import com.deepseek.mobile.chat.ChatStore
import com.deepseek.mobile.chat.DeepSeekClient
import com.deepseek.mobile.harness.BridgeClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide object graph. Created once by [com.deepseek.mobile.DshApp] so
 * every screen shares one HTTP client, one settings owner and one chat store.
 */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val chatStore = ChatStore(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val deepSeek = DeepSeekClient(http)
    var balance: BalanceStore = BalanceStore(context, http, settings)
    var bridge: BridgeClient = BridgeClient(http, settings)
}
