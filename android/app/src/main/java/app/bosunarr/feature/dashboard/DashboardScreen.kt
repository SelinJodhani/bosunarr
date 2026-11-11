package app.bosunarr.feature.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.services.CardEmpty
import app.bosunarr.feature.services.CardLoad
import app.bosunarr.feature.services.DiskRow
import app.bosunarr.feature.services.HealthRow
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.ImageRef
import app.bosunarr.feature.services.InlineError
import app.bosunarr.feature.services.RefreshHub
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.feature.services.SkeletonBox
import app.bosunarr.feature.services.SkeletonRows
import app.bosunarr.feature.services.StatRow
import app.bosunarr.feature.services.StatTile
import app.bosunarr.feature.services.StatusDot
import app.bosunarr.feature.services.navigateTab
import app.bosunarr.feature.services.okColor
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.feature.services.warnColor
import app.bosunarr.nav.ActivityRoute
import app.bosunarr.nav.CalendarRoute
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.nav.RequestsRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import app.bosunarr.ui.common.speed
import app.bosunarr.ui.settings.ServiceBadge
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun DashboardScreen(nav: NavController) {
    val state by app.state.collectAsState()
    AppScaffold("Bosunarr", actions = { IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") } }) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            val st = state ?: return@Box // still reading the encrypted store: show nothing rather than flash "welcome"
            val services = remember(st.services) {
                st.services.sortedWith(compareBy({ it.serviceType?.ordinal ?: 99 }, { it.sort }, { it.name.lowercase() }))
            }
            if (services.isEmpty()) Welcome(nav) else Dashboard(services, nav)
        }
    }
}

/* ================================================================ first run */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(nav: NavController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 96.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.WavingHand, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Welcome to Bosunarr", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Manage your media stack from your phone. Bosunarr talks to each service directly — your addresses and keys stay on this device, encrypted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ServiceType.entries.forEach { t ->
                        Tag(t.label, container = t.color.copy(alpha = 0.16f), content = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { nav.navigate(SettingsRoute) }) { Text("Add your first service") }
            }
        }
    }
}

/* ================================================================ dashboard */

private fun signature(list: List<ServiceConfig>): String =
    list.joinToString("|") { "${it.id}@${it.url}@${it.altUrl}" }.hashCode().toString(16)

@Composable
private fun Dashboard(services: List<ServiceConfig>, nav: NavController) {
    val hub = rememberRefreshHub()
    val arrs = services.filter { it.serviceType == ServiceType.Sonarr || it.serviceType == ServiceType.Radarr }
    val qbits = services.filter { it.serviceType == ServiceType.QBittorrent }
    val seerrs = services.filter { it.serviceType == ServiceType.Jellyseerr || it.serviceType == ServiceType.Overseerr }
    val jellyfins = services.filter { it.serviceType == ServiceType.Jellyfin }
    val healthServices = services.filter { it.serviceType == ServiceType.Sonarr || it.serviceType == ServiceType.Radarr || it.serviceType == ServiceType.Prowlarr }

    HubRefreshBox(hub) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServicesCard(hub, services, nav)
            if (qbits.isNotEmpty() || arrs.isNotEmpty()) DownloadsCard(hub, qbits, arrs, nav)
            if (seerrs.isNotEmpty()) RequestsCard(hub, seerrs, nav)
            if (healthServices.isNotEmpty()) HealthCard(hub, healthServices, nav)
            if (arrs.isNotEmpty()) UpNextCard(hub, arrs, nav)
            if (arrs.isNotEmpty() || jellyfins.isNotEmpty()) RecentCard(hub, arrs, jellyfins, nav)
            if (arrs.isNotEmpty()) DiskCard(hub, arrs)
        }
    }
}

/** Partial failures (some instances answered): one quiet line under the card's data. */
@Composable
private fun PartialErrors(errors: List<String>) {
    if (errors.isNotEmpty()) InlineError(errors.distinct().joinToString(" · "), compact = true)
}

/* ---------------------------------------------------------------- services */

@Composable
private fun ServicesCard(hub: RefreshHub, services: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:status:${signature(services)}") { loadStatuses(services) }
    val statuses = loader.value
    val online = statuses?.values?.count { it.ok }
    SectionCard(
        "Services",
        icon = Icons.Rounded.Dns,
        trailing = {
            if (online != null) Text(
                "$online of ${services.size} online",
                style = MaterialTheme.typography.labelLarge,
                color = if (online == services.size) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    ) {
        // The tiles come from the saved list, so they never jump: only their status line fills in.
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            services.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s ->
                        ServiceTile(s, statuses?.get(s.id), loader.state.error != null && statuses == null, Modifier.weight(1f).fillMaxHeight()) {
                            nav.navigate(ServiceRoute(s.id))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ServiceTile(s: ServiceConfig, status: ServiceStatus?, failed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceBadge(s.serviceType)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, text, color) = when {
                        status == null && failed -> Triple(MaterialTheme.colorScheme.outline, "Unknown", MaterialTheme.colorScheme.onSurfaceVariant)
                        status == null -> Triple(MaterialTheme.colorScheme.outlineVariant, "Checking…", MaterialTheme.colorScheme.onSurfaceVariant)
                        status.ok -> Triple(
                            okColor(),
                            listOfNotNull(status.version?.let { "v" + it.removePrefix("v") }, status.ms?.let { "$it ms" }).joinToString(" · ").ifEmpty { "Online" },
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Triple(MaterialTheme.colorScheme.error, status.error ?: "Offline", MaterialTheme.colorScheme.error)
                    }
                    StatusDot(dot)
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- downloads */

@Composable
private fun DownloadsCard(hub: RefreshHub, qbits: List<ServiceConfig>, arrs: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:downloads:${signature(qbits + arrs)}", refreshMs = 10_000) { loadDownloads(qbits, arrs) }
    SectionCard("Downloads", icon = Icons.Rounded.Download, onClick = { nav.navigateTab(ActivityRoute) }) {
        CardLoad(loader, placeholder = {
            StatRow {
                SkeletonBox(Modifier.weight(1f).height(68.dp), RoundedCornerShape(16.dp))
                SkeletonBox(Modifier.weight(1f).height(68.dp), RoundedCornerShape(16.dp))
            }
            SkeletonBox(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(180.dp).height(24.dp))
        }) { d ->
            StatRow {
                if (d.hasQbit) {
                    StatTile("Downloading", speed(d.down), Modifier.weight(1f), icon = Icons.Rounded.ArrowDownward, tint = if (d.down > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    StatTile("Uploading", speed(d.up), Modifier.weight(1f), icon = Icons.Rounded.ArrowUpward)
                } else {
                    StatTile("In arr queue", d.queue.toString(), Modifier.weight(1f))
                    StatTile("Status", if (d.queueErrors) "Errors" else if (d.queueWarnings) "Warnings" else "OK", Modifier.weight(1f), tint = queueTint(d))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.hasQbit) {
                    Tag(plural(d.downloading, "downloading", "downloading"))
                    Tag(plural(d.seeding, "seeding", "seeding"))
                }
                if (d.hasArr && d.hasQbit) {
                    Tag(
                        "Queue ${d.queue}",
                        container = if (d.queueErrors) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        content = if (d.queueErrors) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            PartialErrors(d.errors)
        }
    }
}

@Composable
private fun queueTint(d: Downloads): Color = when {
    d.queueErrors -> MaterialTheme.colorScheme.error
    d.queueWarnings -> warnColor()
    else -> MaterialTheme.colorScheme.onSurface
}

/* ---------------------------------------------------------------- requests */

@Composable
private fun RequestsCard(hub: RefreshHub, seerrs: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:requests:${signature(seerrs)}") { loadRequests(seerrs) }
    SectionCard("Requests", icon = Icons.Rounded.Inbox, onClick = { nav.navigate(RequestsRoute) }) {
        CardLoad(loader, placeholder = {
            StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(68.dp), RoundedCornerShape(16.dp)) } }
        }) { r ->
            StatRow {
                StatTile("Pending", r.counts.pending.toString(), Modifier.weight(1f), tint = if (r.counts.pending > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                StatTile("Processing", r.counts.processing.toString(), Modifier.weight(1f))
                StatTile("Available", r.counts.available.toString(), Modifier.weight(1f))
            }
            PartialErrors(r.errors)
        }
    }
}

/* ---------------------------------------------------------------- health */

@Composable
private fun HealthCard(hub: RefreshHub, services: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:health:${signature(services)}", refreshMs = 60_000) { loadHealth(services) }
    SectionCard("Health", icon = Icons.Rounded.HealthAndSafety) {
        CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 28.dp) }) { h ->
            if (h.issues.isEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = okColor(), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("No issues reported", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val shown = h.issues.take(5)
                shown.forEach { i ->
                    HealthRow(i.type, i.message, source = "${i.service.name}${i.source?.let { " · $it" } ?: ""}", onClick = { nav.navigate(ServiceRoute(i.service.id)) })
                }
                if (h.issues.size > shown.size) CardEmpty("+${h.issues.size - shown.size} more")
            }
            PartialErrors(h.errors)
        }
    }
}

/* ---------------------------------------------------------------- poster rails */

private val RAIL_WIDTH = 108.dp

@Composable
private fun RailSkeleton() {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) {
            Column(Modifier.width(RAIL_WIDTH)) {
                SkeletonBox(Modifier.fillMaxWidth().height(RAIL_WIDTH * 1.5f), RoundedCornerShape(12.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.9f).height(14.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(12.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp))
            }
        }
    }
}

@Composable
private fun RailItem(service: ServiceConfig, poster: ImageRef?, title: String, line2: String?, line3: String?, done: Boolean, onClick: (() -> Unit)?) {
    Column(
        Modifier.width(RAIL_WIDTH).clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box {
            Poster(poster?.service ?: service, poster?.path, Modifier.fillMaxWidth(), title = title)
            if (done) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(line2.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(line3.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM")

private fun whenLabel(item: UpNextItem): String {
    val zone = ZoneId.systemDefault()
    val at = item.at.atZone(zone)
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), at.toLocalDate())
    val day = when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days in 2..6 -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> at.format(SHORT_DATE)
    }
    return if (item.allDay) day else "$day · ${at.format(TIME)}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpNextCard(hub: RefreshHub, arrs: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:upnext:${signature(arrs)}", refreshMs = 5 * 60_000) { loadUpNext(arrs) }
    SectionCard("Up next", icon = Icons.Rounded.Event, onClick = { nav.navigateTab(CalendarRoute) }) {
        CardLoad(loader, placeholder = { RailSkeleton() }) { u ->
            if (u.items.isEmpty()) CardEmpty("Nothing airing or releasing in the next two weeks")
            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(u.items, key = { it.key }) { i ->
                    RailItem(i.service, i.poster, i.title, i.subtitle, whenLabel(i), i.hasFile) { nav.navigate(i.route) }
                }
            }
            PartialErrors(u.errors)
        }
    }
}

@Composable
private fun RecentCard(hub: RefreshHub, arrs: List<ServiceConfig>, jellyfins: List<ServiceConfig>, nav: NavController) {
    val fromArrs = arrs.isNotEmpty()
    val sources = if (fromArrs) arrs else jellyfins
    val loader = rememberHubLoader(hub, "dash:recent:${if (fromArrs) "arr" else "jf"}:${signature(sources)}", refreshMs = 2 * 60_000) {
        if (fromArrs) loadRecentFromArrs(sources) else loadRecentFromJellyfin(sources)
    }
    SectionCard(
        "Recently added",
        icon = Icons.Rounded.NewReleases,
        onClick = { if (fromArrs) nav.navigateTab(LibraryRoute) else nav.navigate(ServiceRoute(sources.first().id)) },
    ) {
        CardLoad(loader, placeholder = { RailSkeleton() }) { r ->
            if (r.items.isEmpty()) CardEmpty("Nothing imported recently")
            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.items, key = { it.key }) { i ->
                    val route = i.route
                    RailItem(i.service, i.poster, i.title, i.subtitle, i.at?.let { relative(it) }, false, if (route != null) ({ nav.navigate(route) }) else null)
                }
            }
            PartialErrors(r.errors)
        }
    }
}

/* ---------------------------------------------------------------- disk */

@Composable
private fun DiskCard(hub: RefreshHub, arrs: List<ServiceConfig>) {
    val loader = rememberHubLoader(hub, "dash:disk:${signature(arrs)}", refreshMs = 5 * 60_000) { loadDisks(arrs) }
    SectionCard("Disk space", icon = Icons.Rounded.Storage) {
        CardLoad(loader, placeholder = { SkeletonRows(2, rowHeight = 32.dp) }) { d ->
            if (d.disks.isEmpty()) CardEmpty("No disks reported")
            d.disks.forEach { DiskRow(it.path, it.free, it.total) }
            PartialErrors(d.errors)
        }
    }
}
