package ink.kuke.chat.core.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 服务端时间是 ISO（可能不带时区，按 UTC） */
fun parseInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
        .getOrNull()
}

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val monthDay = DateTimeFormatter.ofPattern("MM/dd")
private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 会话列表的时间：今天 21:08 · 昨天 · 本周内 周一 · 更早 10/12（同电脑版） */
fun listTime(iso: String?): String {
    val instant = parseInstant(iso) ?: return ""
    val zone = ZoneId.systemDefault()
    val time = instant.atZone(zone)
    val today = LocalDateTime.now(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), today)
    return when {
        days <= 0L -> time.format(clock)
        days == 1L -> "昨天"
        days < 7L -> weekdays[time.dayOfWeek.value - 1]
        else -> time.format(monthDay)
    }
}

fun clockTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(clock).orEmpty()
