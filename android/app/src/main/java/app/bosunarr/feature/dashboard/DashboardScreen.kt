package app.bosunarr.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.bosunarr.feature.services.navigateTab
import app.bosunarr.feature.services.okColor
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.feature.services.warnColor
import app.bosunarr.nav.ActivityRoute
import app.bosunarr.nav.CalendarRoute
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.nav.OnboardingRoute
import app.bosunarr.nav.RequestsRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.AppMark
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.ServiceStatus
import app.bosunarr.ui.common.ServiceStatuses
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import app.bosunarr.ui.common.speed
import app.bosunarr.ui.settings.ServiceBadge
import app.bosunarr.ui.settings.ServiceBadgeWithStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun DashboardScreen(nav: NavController) {
    val state by app.state.collectAsState()
    val title = remember { greeting() }
    AppScaffold(
        title,
        large = true,
        actions = { IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") } },
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            val st = state ?: return@Box // still reading the encrypted store: show nothing rather than flash "welcome"
            // Adult services (Whisparr) never appear on the shared home screen.
            val services = remember(st.services) {
                st.services.filter { it.serviceType != ServiceType.Whisparr }.sortedWith(compareBy({ it.serviceType?.ordinal ?: 99 }, { it.sort }, { it.name.lowercase() }))
            }
            if (st.services.isEmpty()) Welcome(nav) else Dashboard(services, nav)
        }
    }
}

/* ================================================================ first run */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(nav: NavController) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        AppMark(size = 88.dp)
        Spacer(Modifier.height(24.dp))
        Text("Connect your stack", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add the services you run and this page fills in: downloads, what's coming up, recent imports, requests, health and disk space.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceType.entries.filter { it != ServiceType.Whisparr }.forEach { t -> ServiceBadge(t, size = 36.dp) }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = { nav.navigate(OnboardingRoute) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Connect services") }
        TextButton(onClick = { nav.navigate(SettingsRoute) }, modifier = Modifier.padding(top = 4.dp)) { Text("Open Settings") }
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
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (services.isNotEmpty()) StatusCard(hub, services, healthServices, nav)
            if (qbits.isNotEmpty() || arrs.isNotEmpty()) DownloadsCard(hub, qbits, arrs, nav)
            if (arrs.isNotEmpty()) UpNextCard(hub, arrs, nav)
            if (arrs.isNotEmpty() || jellyfins.isNotEmpty()) RecentCard(hub, arrs, jellyfins, nav)
            if (seerrs.isNotEmpty()) RequestsCard(hub, seerrs, nav)
            if (arrs.isNotEmpty()) DiskCard(hub, arrs)
        }
    }
}

/** Partial failures (some instances answered): one quiet line under the card's data. */
@Composable
private fun PartialErrors(errors: List<String>) {
    if (errors.isNotEmpty()) InlineError(errors.distinct().joinToString(" · "), compact = true)
}

/* ---------------------------------------------------------------- status: services + health */

/** Small rounded summary in a card header ("9 of 9 online"). */
@Composable
private fun HeaderPill(text: String, container: Color, content: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = Modifier.padding(end = 8.dp)) {
        Row(Modifier.padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun StatusCard(hub: RefreshHub, services: List<ServiceConfig>, healthServices: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:status:${signature(services)}") { loadStatuses(services) }
    // Last known statuses (this or an earlier visit, or Settings) until the first check of this screen answers.
    val statuses: Map<String, ServiceStatus> = loader.value ?: services.mapNotNull { s -> ServiceStatuses.get(s)?.let { s.id to it } }.toMap()
    val known = services.count { it.id in statuses }
    val offline = services.count { statuses[it.id]?.ok == false }
    val checkFailed = loader.state.error != null && loader.value == null

    // Offline services first, so they're never scrolled out of view; otherwise the saved order (no jumps).
    val ordered = remember(services, statuses) {
        services.withIndex().sortedWith(compareBy({ if (statuses[it.value.id]?.ok == false) 0 else 1 }, { it.index })).map { it.value }
    }

    SectionCard(
        "Services",
        icon = Icons.Rounded.Dns,
        trailing = {
            when {
                offline > 0 -> HeaderPill("$offline offline", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Rounded.ErrorOutline)
                known == services.size -> HeaderPill("All ${services.size} online", okColor().copy(alpha = 0.16f), MaterialTheme.colorScheme.onSurface, Icons.Rounded.Check)
                checkFailed -> Unit
                else -> Text("Checking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            }
        },
    ) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ordered, key = { it.id }) { s ->
                ServiceChip(s, statuses[s.id], Modifier.animateItem()) { nav.navigate(ServiceRoute(s.id)) }
            }
        }
        if (checkFailed) InlineError(loader.state.error.orEmpty(), compact = true)
        if (healthServices.isNotEmpty()) HealthSection(hub, healthServices, nav)
    }
}

@Composable
private fun ServiceChip(s: ServiceConfig, status: ServiceStatus?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val down = status?.ok == false
    val container = if (down) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val dot = when {
        status == null -> MaterialTheme.colorScheme.outline
        status.ok -> okColor()
        else -> MaterialTheme.colorScheme.error
    }
    val state = when {
        status == null -> "status unknown"
        status.ok -> "online"
        else -> "offline"
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = container, modifier = modifier.semantics { contentDescription = "${s.name}, $state" }) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceBadgeWithStatus(s.serviceType, dot, size = 32.dp, ring = container)
            Spacer(Modifier.width(12.dp))
            Text(
                s.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (down) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
    }
}

@Composable
private fun HealthSection(hub: RefreshHub, services: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:health:${signature(services)}", refreshMs = 60_000) { loadHealth(services) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize()) {
        CardLoad(loader, placeholder = { SkeletonBox(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(160.dp).height(18.dp)) }) { h ->
            if (h.issues.isEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = okColor(), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("No health issues", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val errors = h.issues.count { it.type == "error" }
                val warnings = h.issues.count { it.type == "warning" }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber, null,
                        tint = if (errors > 0) MaterialTheme.colorScheme.error else warnColor(), modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        listOfNotNull(
                            errors.takeIf { it > 0 }?.let { plural(it, "error") },
                            warnings.takeIf { it > 0 }?.let { plural(it, "warning") },
                            (h.issues.size - errors - warnings).takeIf { it > 0 }?.let { plural(it, "notice") },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                val shown = h.issues.take(3)
                shown.forEach { i ->
                    HealthRow(i.type, i.message, source = "${i.service.name}${i.source?.let { " · $it" } ?: ""}", onClick = { nav.navigate(ServiceRoute(i.service.id)) })
                }
                if (h.issues.size > shown.size) CardEmpty("+${h.issues.size - shown.size} more on the service screens")
            }
            PartialErrors(h.errors)
        }
    }
}

/* ---------------------------------------------------------------- downloads */

@Composable
private fun DownloadsCard(hub: RefreshHub, qbits: List<ServiceConfig>, arrs: List<ServiceConfig>, nav: NavController) {
    val loader = rememberHubLoader(hub, "dash:downloads:${signature(qbits + arrs)}", refreshMs = 10_000) { loadDownloads(qbits, arrs) }
    val arrNames = arrs.mapNotNull { it.serviceType?.label }.distinct().joinToString(" & ")
    SectionCard("Downloads", icon = Icons.Rounded.Download, onClick = { nav.navigateTab(ActivityRoute) }) {
        CardLoad(loader, placeholder = {
            StatRow {
                SkeletonBox(Modifier.weight(1f).height(68.dp), RoundedCornerShape(16.dp))
                SkeletonBox(Modifier.weight(1f).height(68.dp), RoundedCornerShape(16.dp))
            }
        }) { d ->
            StatRow {
                if (d.hasQbit) {
                    StatTile(
                        if (d.downloading > 0) plural(d.downloading, "downloading", "downloading") else "Download",
                        speed(d.down), Modifier.weight(1f), icon = Icons.Rounded.ArrowDownward,
                        tint = if (d.down > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    StatTile(
                        if (d.seeding > 0) plural(d.seeding, "seeding", "seeding") else "Upload",
                        speed(d.up), Modifier.weight(1f), icon = Icons.Rounded.ArrowUpward,
                    )
                } else {
                    StatTile("In queue", d.queue.toString(), Modifier.weight(1f))
                    StatTile("Status", if (d.queueErrors) "Errors" else if (d.queueWarnings) "Warnings" else "OK", Modifier.weight(1f), tint = queueTint(d))
                }
            }
            if (d.hasArr && d.hasQbit) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (d.queue == 0) "$arrNames queue is empty" else "${plural(d.queue, "item")} in the $arrNames queue",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (d.queueErrors || d.queueWarnings) {
                        Spacer(Modifier.width(8.dp))
                        Tag(
                            if (d.queueErrors) "Errors" else "Warnings",
                            container = if (d.queueErrors) MaterialTheme.colorScheme.errorContainer else warnColor().copy(alpha = 0.18f),
                            content = if (d.queueErrors) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
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
                ) { Icon(Icons.Rounded.Check, "Downloaded", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
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
