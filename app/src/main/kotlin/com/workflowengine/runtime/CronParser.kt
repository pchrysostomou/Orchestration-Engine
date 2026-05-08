package com.workflowengine.runtime

import java.time.LocalDateTime

object CronParser {

    /**
     * Computes the next fire time for a 5-field cron expression (min hour dom month dow).
     * Supported field syntax: * | n | *\/n
     */
    fun nextFireTime(cron: String, from: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val parts = cron.trim().split("\\s+".toRegex())
        require(parts.size == 5) { "Expected 5-field cron expression (min hour dom month dow), got: '$cron'" }
        val (minF, hourF, domF, monF, dowF) = parts

        var candidate = from.withSecond(0).withNano(0).plusMinutes(1)
        repeat(366 * 24 * 60) {
            if (matches(candidate, minF, hourF, domF, monF, dowF)) return candidate
            candidate = candidate.plusMinutes(1)
        }
        throw IllegalArgumentException("No next fire time found for cron: '$cron'")
    }

    private fun matches(
        dt: LocalDateTime,
        min: String, hour: String, dom: String, mon: String, dow: String
    ) = matchField(dt.minute,              min,  0, 59) &&
        matchField(dt.hour,                hour, 0, 23) &&
        matchField(dt.dayOfMonth,          dom,  1, 31) &&
        matchField(dt.monthValue,          mon,  1, 12) &&
        matchField(dt.dayOfWeek.value % 7, dow,  0,  6)

    private fun matchField(value: Int, field: String, min: Int, @Suppress("UNUSED_PARAMETER") max: Int): Boolean {
        if (field == "*") return true
        if (field.startsWith("*/")) {
            val step = field.removePrefix("*/").toInt()
            return (value - min) % step == 0
        }
        return field.toIntOrNull() == value
    }
}
