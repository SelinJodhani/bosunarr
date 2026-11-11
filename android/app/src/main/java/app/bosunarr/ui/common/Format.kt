package app.bosunarr.ui.common

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

fun bytes(n: Long?, digits: Int = 1): String {
    if (n == null || n < 0) return "—"
    if (n < 1024) return "$n B"
    val e = (ln(n.toDouble()) / ln(1024.0)).toInt().coerceAtMost(UNITS.lastIndex)
    val v = n / 1024.0.pow(e)
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.${digits}f %s", v, UNITS[e])
}

fun speed(bps: Long?): String = if (bps == null || bps <= 0) "0 B/s" else bytes(bps) + "/s"

/** "2h 5m", "3d 4h", "45s". qBittorrent uses 8640000 for ∞. */
fun eta(seconds: Long?): String {
    if (seconds == null || seconds < 0 || seconds >= 8_640_000) return "∞"
    val d = seconds / 86400; val h = seconds % 86400 / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

fun parseInstant(iso: String?): Instant? = iso?.let {
    runCatching { Instant.parse(it) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(it.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
}

/** "just now", "5 min ago", "in 3 days", "2 hours ago", "1 month ago". */
fun relative(iso: String?): String = parseInstant(iso)?.let { relative(it) } ?: ""

fun relative(at: Instant): String {
    val diff = Duration.between(Instant.now(), at).seconds
    val past = diff < 0
    val s = abs(diff)
    val text = when {
        s < 45 -> return "just now"
        s < 3600 -> "${(s / 60).coerceAtLeast(1)} min"
        s < 86400 -> unit(s / 3600, "hour")
        s < 86400 * 30 -> unit(s / 86400, "day")
        s < 86400 * 365 -> unit(s / (86400 * 30), "month")
        else -> unit(s / (86400 * 365), "year")
    }
    return if (past) "$text ago" else "in $text"
}

private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun date(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(DATE) ?: ""
fun dateTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(DATE_TIME) ?: ""

/** "1h 52m" from minutes. */
fun runtime(minutes: Int?): String = when {
    minutes == null || minutes <= 0 -> ""
    minutes < 60 -> "${minutes}m"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

fun percent(f: Double?): String = if (f == null) "" else "${(f * 100).toInt().coerceIn(0, 100)}%"

fun plural(n: Int, one: String, many: String = one + "s") = "${String.format(Locale.getDefault(), "%,d", n)} ${if (n == 1) one else many}"

private fun unit(n: Long, one: String) = "$n ${if (n == 1L) one else one + "s"}"
