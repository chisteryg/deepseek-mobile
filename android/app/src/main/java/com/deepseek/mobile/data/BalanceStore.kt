package com.deepseek.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class BalanceState(
    val amount: Double? = null,
    val currency: String = "CNY",
    val updatedAt: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Reads `GET /user/balance` with the user's own API key. The last successful
 * reading is cached so the UI can show something before the network answers.
 */
open class BalanceStore(
    context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
) {
    private val prefs = context.getSharedPreferences("dsh_mobile_balance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        BalanceState(
            amount = prefs.getString("amount", null)?.toDoubleOrNull(),
            currency = prefs.getString("currency", "CNY") ?: "CNY",
            updatedAt = prefs.getLong("updatedAt", 0L),
        ),
    )
    val state: StateFlow<BalanceState> = _state.asStateFlow()

    open suspend fun refresh(): BalanceState = withContext(Dispatchers.IO) {
        val current = settings.current
        if (current.apiKey.isBlank()) {
            val next = _state.value.copy(loading = false, error = "还没有填写 API Key")
            _state.value = next
            return@withContext next
        }
        _state.value = _state.value.copy(loading = true, error = null)
        val request = Request.Builder()
            .url(current.baseUrl.trimEnd('/') + "/user/balance")
            .addHeader("Authorization", "Bearer ${current.apiKey}")
            .get()
            .build()
        val next = runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} ${body.take(120)}")
                }
                val root = Json.parseToJsonElement(body).jsonObject
                val infos = root["balance_infos"]
                val first = infos?.let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject
                }
                val amount = first?.get("total_balance")?.jsonPrimitive?.content?.toDoubleOrNull()
                val currency = first?.get("currency")?.jsonPrimitive?.content ?: "CNY"
                if (amount == null) throw IllegalStateException("余额响应缺少 total_balance")
                BalanceState(
                    amount = amount,
                    currency = currency,
                    updatedAt = System.currentTimeMillis(),
                    loading = false,
                    error = null,
                )
            }
        }.getOrElse { error ->
            _state.value.copy(loading = false, error = error.message ?: "查询余额失败")
        }
        _state.value = next
        if (next.error == null && next.amount != null) {
            prefs.edit()
                .putString("amount", next.amount.toString())
                .putString("currency", next.currency)
                .putLong("updatedAt", next.updatedAt)
                .apply()
        }
        next
    }
}
