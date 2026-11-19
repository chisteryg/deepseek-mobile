package com.deepseek.mobile.data

import kotlinx.serialization.json.JsonObject
import com.deepseek.mobile.util.int
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * DeepSeek token pricing. Prices are CNY per million tokens at peak time; the
 * off-peak price is half. Peak is Beijing time Monday-Friday 09:00-12:00 and
 * 14:00-18:00 (statutory holidays are not modelled).
 */
object Billing {
    private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    data class Price(val hitInput: Double, val missInput: Double, val output: Double)

    private val FLASH = Price(hitInput = 0.04, missInput = 2.0, output = 8.0)
    private val PRO = Price(hitInput = 0.30, missInput = 9.0, output = 27.0)

    fun isPeak(at: Instant = Instant.now()): Boolean {
        val time = ZonedDateTime.ofInstant(at, BEIJING)
        if (time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY) return false
        val hour = time.hour + time.minute / 60.0
        return (hour >= 9.0 && hour < 12.0) || (hour >= 14.0 && hour < 18.0)
    }

    fun priceFor(model: String, peak: Boolean): Price {
        val base = if (model.contains("pro", ignoreCase = true)) PRO else FLASH
        val factor = if (peak) 1.0 else 0.5
        return Price(base.hitInput * factor, base.missInput * factor, base.output * factor)
    }

    /** Cost of one assistant turn in CNY, from the Messages API usage object. */
    fun costOf(usage: JsonObject?, model: String, at: Instant = Instant.now()): Double {
        if (usage == null) return 0.0
        val hit = usage.int("cache_read_input_tokens") ?: 0
        val miss = usage.int("input_tokens") ?: 0
        val output = usage.int("output_tokens") ?: 0
        if (hit + miss + output == 0) return 0.0
        val price = priceFor(model, isPeak(at))
        return (hit * price.hitInput + miss * price.missInput + output * price.output) / 1_000_000.0
    }

    /** Always show four decimals so small turns stay visible. */
    fun formatCost(value: Double): String = String.format(Locale.US, "%.4f", value)

    fun formatBalance(value: Double): String = String.format(Locale.US, "%.2f", value)
}
