package com.kelofred.keloray.engine

import com.kelofred.keloray.data.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.ZoneId
import java.time.ZonedDateTime

class RuleTimeTest {
    private val all = DayOfWeek.entries.toSet()
    private val weekdays = DayOfWeek.entries.take(5).toSet()
    private fun rule(at: String? = null, cron: Map<String, Any?> = emptyMap()) = Rule("r", "D", "sunrise", at = at, cron = cron)

    @Test
    fun parsesAtAndCron() {
        assertEquals(RuleTime(6, 30, all), RuleTime.of(rule(at = "06:30")))
        assertEquals(RuleTime(22, 0, all), RuleTime.of(rule(at = "22:00")))
        assertEquals(RuleTime(6, 30, weekdays), RuleTime.of(rule(cron = mapOf("hour" to 6, "minute" to 30, "day_of_week" to "mon-fri"))))
        // config.example.yaml's reef daylight rule
        assertEquals(RuleTime(0, 5, all), RuleTime.of(rule(cron = mapOf("hour" to 0L, "minute" to 5L))))
        assertEquals(RuleTime(7, 0, all), RuleTime.of(rule(cron = mapOf("hour" to "7"))))
        assertNull(RuleTime.of(rule(cron = mapOf("minute" to "*/5"))))
        assertNull(RuleTime.of(rule(at = "25:00")))
    }

    @Test
    fun parsesApschedulerDays() {
        assertEquals(setOf(SATURDAY, SUNDAY), RuleTime.parseDays("sat,sun"))
        assertEquals(weekdays, RuleTime.parseDays("0-4"))
        assertEquals(setOf(FRIDAY, SATURDAY, SUNDAY, MONDAY), RuleTime.parseDays("fri-mon"))
        assertEquals(all, RuleTime.parseDays("*"))
        assertNull(RuleTime.parseDays("someday"))
    }

    @Test
    fun nextOccurrence() {
        val zone = ZoneId.of("Africa/Johannesburg")
        val saturday = ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, zone)
        assertEquals(SATURDAY, saturday.dayOfWeek)
        val wake = RuleTime(6, 30, weekdays)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 6, 30, 0, 0, zone), wake.next(saturday))
        val daily = RuleTime(22, 0, all)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 22, 0, 0, 0, zone), daily.next(saturday))
        // exactly at the time -> the next one, not now
        assertEquals(saturday.plusDays(1), RuleTime(10, 0, all).next(saturday))
    }

    @Test
    fun ruleFieldsRoundTrip() {
        assertEquals("06:30" to emptyMap<String, Any?>(), RuleTime(6, 30, all).toRuleFields())
        val (at, cron) = RuleTime(6, 30, weekdays).toRuleFields()
        assertNull(at)
        assertEquals(mapOf("hour" to 6, "minute" to 30, "day_of_week" to "mon,tue,wed,thu,fri"), cron)
        assertEquals(RuleTime(6, 30, weekdays), RuleTime.of(rule(cron = cron)))
    }
}
