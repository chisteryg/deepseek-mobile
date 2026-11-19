package com.deepseek.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val thinking: Boolean = true,
    val webSearch: Boolean = false,
    val readUrl: Boolean = false,
    val safetyCheck: Boolean = false,
    val autoTitle: Boolean = true,
    val themeMode: String = "system",
    val bridgeUrl: String = "",
    val bridgeToken: String = "",
) {
    val chatReady: Boolean get() = apiKey.isNotBlank()
    val harnessReady: Boolean get() = bridgeUrl.isNotBlank() && bridgeToken.isNotBlank()
}

/**
 * Single owner of persisted preferences. Secrets go through [CryptoBox]; the
 * rest stay in plain SharedPreferences because they are not sensitive.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh_mobile_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private fun load(): Settings {
        fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
        fun text(key: String, def: String) = prefs.getString(key, def) ?: def
        return Settings(
            apiKey = CryptoBox.decrypt(text("apiKey", "")),
            baseUrl = text("baseUrl", "https://api.deepseek.com"),
            model = text("model", "deepseek-flash"),
            thinking = bool("thinking", true),
            webSearch = bool("webSearch", false),
            readUrl = bool("readUrl", false),
            safetyCheck = bool("safetyCheck", false),
            autoTitle = bool("autoTitle", true),
            themeMode = text("themeMode", "system"),
            bridgeUrl = text("bridgeUrl", ""),
            bridgeToken = CryptoBox.decrypt(text("bridgeToken", "")),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit().apply {
            putString("apiKey", CryptoBox.encrypt(next.apiKey))
            putString("baseUrl", next.baseUrl)
            putString("model", next.model)
            putBoolean("thinking", next.thinking)
            putBoolean("webSearch", next.webSearch)
            putBoolean("readUrl", next.readUrl)
            putBoolean("safetyCheck", next.safetyCheck)
            putBoolean("autoTitle", next.autoTitle)
            putString("themeMode", next.themeMode)
            putString("bridgeUrl", next.bridgeUrl)
            putString("bridgeToken", CryptoBox.encrypt(next.bridgeToken))
        }.apply()
        _state.value = next
    }
}
