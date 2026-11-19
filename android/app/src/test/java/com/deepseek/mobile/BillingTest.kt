package com.deepseek.mobile

import com.deepseek.mobile.data.Billing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BillingTest {

    private val beijing: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun usage(input: Int, cached: Int, output: Int) = buildJsonObject {
        put("input_tokens", input)
        put("cache_read_input_tokens", cached)
        put("output_tokens", output)
    }

    @Test
    fun peakWindowIsWeekdayOfficeHours() {
        fun at(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(beijing).toInstant()

        assertTrue(Billing.isPeak(at("2026-11-16T10:00:00+08:00[Asia/Shanghai]")))
        assertTrue(Billing.isPeak(at("2026-11-16T14:30:00+08:00[Asia/Shanghai]")))
        assertFalse(Billing.isPeak(at("2026-11-16T12:30:00+08:00[Asia/Shanghai]")))
        assertFalse(Billing.isPeak(at("2026-11-16T19:00:00+08:00[Asia/Shanghai]")))
        assertFalse(Billing.isPeak(at("2026-11-21T10:00:00+08:00[Asia/Shanghai]")))
    }

    @Test
    fun flashPricesCoverHitMissAndOutput() {
        val peak = java.time.Instant.parse("2026-11-16T02:00:00Z")
        val cost = Billing.costOf(usage(input = 1_000_000, cached = 1_000_000, output = 1_000_000), "deepseek-flash", peak)
        assertEquals(2.0 + 0.04 + 8.0, cost, 1e-9)
    }

    @Test
    fun offPeakCostsHalf() {
        val night = java.time.Instant.parse("2026-11-16T15:00:00Z")
        val cost = Billing.costOf(usage(input = 0, cached = 0, output = 1_000_000), "deepseek-flash", night)
        assertEquals(4.0, cost, 1e-9)
    }

    @Test
    fun proModelUsesItsOwnPrices() {
        val peak = java.time.Instant.parse("2026-11-16T02:00:00Z")
        val cost = Billing.costOf(usage(input = 1_000_000, cached = 0, output = 0), "deepseek-v4-pro", peak)
        assertEquals(9.0, cost, 1e-9)
    }

    @Test
    fun emptyUsageCostsNothing() {
        val cost = Billing.costOf(usage(0, 0, 0), "deepseek-flash")
        assertEquals(0.0, cost, 0.0)
    }

    @Test
    fun formattingKeepsSmallCostsVisible() {
        assertEquals("0.0021", Billing.formatCost(0.00214))
        assertEquals("12.34", Billing.formatBalance(12.344))
    }
}
