package app.bosunarr.feature.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBanner
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.rememberLoader
import app.bosunarr.ui.common.runtime
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private const val DEFAULT_PAST = 1L
private const val WEEK_PAST = 7L

@Composable
fun CalendarScreen(nav: NavController) {
    val loaded by app.state.collectAsState()
    val all by app.services.collectAsState()
    val arrs = remember(all) { all.filter { it.serviceType == ServiceType.Sonarr || it.serviceType == ServiceType.Radarr } }
    when {
        loaded == null -> AppScaffold("Calendar") { p -> LoadingBox(Modifier.padding(p)) }
        arrs.isEmpty() -> AppScaffold("Calendar") { p ->
            Column(Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState())) {
                MessageBox(
                    Icons.Rounded.Dns, "No Sonarr or Radarr yet",
                    hint = "Add a Sonarr or Radarr service to see upcoming episodes and movie releases.",
                    action = "Open settings", onAction = { nav.navigate(SettingsRoute) },
                )
            }
        }
        else -> CalendarContent(nav, arrs)
    }
}

/** Flattened rows of the agenda. */
private sealed interface Agenda {
    data class Header(val day: LocalDate, val count: Int) : Agenda
    data class Item(val entry: CalEntry) : Agenda
    data class Nothing(val day: LocalDate) : Agenda
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CalendarContent(nav: NavController, arrs: List<ServiceConfig>) {
    var showPast by rememberSaveable { mutableStateOf(false) }
    val key = remember(arrs) { arrs.joinToString("|") { "${it.id}@${it.url}#${it.apiKey.hashCode()}" }.hashCode() }
    val loader = rememberLoader("calendar:$key", refreshMs = 10 * 60_000L) { CalendarApi.load(arrs, if (showPast) WEEK_PAST else DEFAULT_PAST) }
    val state = loader.state
    val data = state.value

    // Toggling the past week refetches in place (the current list stays until the new one arrives).
    var fetchedPast by rememberSaveable { mutableStateOf(showPast) }
    LaunchedEffect(showPast) {
        if (showPast != fetchedPast) {
            fetchedPast = showPast
            loader.refresh()
        }
    }

    val today = LocalDate.now()
    val rows = remember(data, today) {
        val out = mutableListOf<Agenda>()
        if (data != null) {
            val byDay = data.entries.groupBy { it.day }
            val days = (byDay.keys + today).filter { !it.isBefore(data.start) || it == today }.toSortedSet()
            for (day in days) {
                val list = byDay[day].orEmpty()
                out += Agenda.Header(day, list.size)
                if (list.isEmpty()) out += Agenda.Nothing(day) else list.forEach { out += Agenda.Item(it) }
            }
        }
        out
    }
    val todayIndex = rows.indexOfFirst { it is Agenda.Header && it.day == today }.coerceAtLeast(0)

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrolledToToday by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(data != null) {
        if (data != null && !scrolledToToday) {
            scrolledToToday = true
            list.scrollToItem(todayIndex)
        }
    }

    AppScaffold(
        "Calendar",
        subtitle = data?.let { d ->
            val n = d.entries.count { !it.day.isBefore(today) }
            if (n == 0) "Nothing in the next 4 weeks" else "$n coming up in the next 4 weeks"
        },
        actions = {
            IconButton(onClick = { scope.launch { list.animateScrollToItem(todayIndex) } }, enabled = data != null) {
                Icon(Icons.Rounded.Today, "Jump to today")
            }
            IconToggleButton(checked = showPast, onCheckedChange = { showPast = it }) {
                Icon(Icons.Rounded.History, if (showPast) "Hide past week" else "Show past week", tint = if (showPast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            data?.errors?.takeIf { it.isNotEmpty() }?.let { errs -> ErrorBanner(errs.joinToString("\n") { it.message }) }
            if (data != null && state.error != null) ErrorBanner(state.error)
            PullToRefreshBox(
                isRefreshing = state.loading && data != null,
                onRefresh = { loader.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    data == null && state.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ErrorBox(state.error, onRetry = { loader.refresh() })
                    }
                    data == null -> Column(Modifier.fillMaxSize()) {
                        DayHeader(today, null)
                        repeat(6) { SkeletonRow() }
                    }
                    data.entries.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MessageBox(Icons.Rounded.CalendarMonth, "Nothing on the calendar", hint = "Monitored episodes and movie releases for the next four weeks show up here.")
                    }
                    else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        rows.forEach { r ->
                            when (r) {
                                is Agenda.Header -> stickyHeader(key = "d${r.day}", contentType = "header") { DayHeader(r.day, r.count) }
                                is Agenda.Nothing -> item(key = "n${r.day}", contentType = "nothing") {
                                    Text(
                                        "Nothing today",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                                is Agenda.Item -> item(key = r.entry.id, contentType = "entry") {
                                    EntryRow(r.entry) { nav.navigate(r.entry.primary.route) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): Pair<String, String?> {
    val full = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault())
    val short = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Locale.getDefault())
    return when (day) {
        today -> "Today" to day.format(short)
        today.plusDays(1) -> "Tomorrow" to day.format(short)
        today.minusDays(1) -> "Yesterday" to day.format(short)
        else -> day.format(full) to null
    }
}

@Composable
private fun DayHeader(day: LocalDate, count: Int?) {
    val today = LocalDate.now()
    val (label, secondary) = dayLabel(day, today)
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = when {
                    day == today -> cs.primary
                    day.isBefore(today) -> cs.onSurfaceVariant
                    else -> cs.onSurface
                },
            )
            if (secondary != null) {
                Text("  $secondary", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (count != null && count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryRow(e: CalEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val timeFormat = remember(ctx) { DateFormat.getTimeFormat(ctx) }
    val status = e.status
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).alpha(if (e.monitored) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Poster(e.posterService, e.poster, width = 48.dp, title = e.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                e.episodeLabel ?: e.releaseLabel,
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                e.time?.let { timeFormat.format(Date.from(it)) },
                e.network,
                e.runtime?.let { runtime(it) }?.takeIf { it.isNotEmpty() },
                if (!e.monitored) "Unmonitored" else null,
            ).joinToString(" · ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.marker != null) Tag(e.marker, container = cs.tertiaryContainer, content = cs.onTertiaryContainer)
                if (e.is4k) Tag("4K")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val (icon, tint) = when (status) {
            CalStatus.Downloaded -> Icons.Rounded.CheckCircle to cs.primary
            CalStatus.Missing -> Icons.Rounded.ErrorOutline to cs.error
            CalStatus.Upcoming -> Icons.Rounded.Schedule to cs.onSurfaceVariant
        }
        Icon(icon, status.label, tint = tint)
    }
}

@Composable
private fun SkeletonRow() {
    val c = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(12.dp)).background(c))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(c))
            Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c))
            Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(c))
        }
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(c))
    }
}
