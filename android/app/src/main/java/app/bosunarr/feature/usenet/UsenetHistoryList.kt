package app.bosunarr.feature.usenet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.services.InfoLine
import app.bosunarr.feature.services.InlineError
import app.bosunarr.feature.services.okColor
import app.bosunarr.feature.services.rememberActionRunner
import app.bosunarr.feature.services.warnColor
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.eta
import app.bosunarr.ui.common.expandTopBarFirst
import app.bosunarr.ui.common.relative
import app.bosunarr.ui.common.rememberLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Immutable
data class UsenetHistoryData(val items: List<NzbHistoryItem>, val errors: List<String>)

/** History of every Usenet client in [services], newest first. Throws only when all of them failed. */
suspend fun loadUsenetHistory(services: List<ServiceConfig>, limit: Int = 60, failedOnly: Boolean = false): UsenetHistoryData {
    val r = fanOut(services.filter { isUsenet(it.serviceType) }) { UsenetApi.history(it, limit, failedOnly) }
    val ok = r.mapNotNull { it.second.getOrNull() }
    if (r.isNotEmpty() && ok.isEmpty()) throw r.first().second.exceptionOrNull() ?: ServiceException("Usenet clients are unreachable")
    val errors = r.mapNotNull { (s, x) -> x.exceptionOrNull()?.let { "${s.name}: ${it.userMessage()}" } }
    return UsenetHistoryData(ok.flatten().sortedByDescending { it.completedAt }, errors)
}

/**
 * Self-contained history for the Usenet clients in [services]: All / Failed filter, tap for details with Retry and
 * Remove. Polls every 15 s while visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsenetHistoryList(
    services: List<ServiceConfig>,
    modifier: Modifier = Modifier,
    showClient: Boolean = services.count { isUsenet(it.serviceType) } > 1,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
) {
    var failedOnly by rememberSaveable { mutableStateOf(false) }
    val ids = services.filter { isUsenet(it.serviceType) }.joinToString(",") { it.id }
    val loader = rememberLoader("usenet:history:$ids:$failedOnly", refreshMs = 15_000) { loadUsenetHistory(services, failedOnly = failedOnly) }
    val runner = rememberActionRunner()
    // Removed entries stay hidden until a refresh no longer lists them.
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var open by remember { mutableStateOf<String?>(null) }
    val st = loader.state
    LaunchedEffect(st.value) {
        val keys = st.value?.items?.mapTo(HashSet()) { it.key } ?: return@LaunchedEffect
        if (hidden.isNotEmpty()) hidden = hidden.filterTo(HashSet()) { it in keys }
    }

    fun remove(items: List<NzbHistoryItem>, purge: Boolean) {
        hidden = hidden + items.map { it.key }
        runner.run("hist-del:" + items.joinToString(",") { it.key }, success = if (items.size == 1) "Removed from history" else "Removed ${items.size} entries", after = { loader.refresh() }) {
            try {
                items.groupBy { it.serviceId }.forEach { (sid, l) ->
                    val s = app.service(sid) ?: throw ServiceException("${l.first().serviceName} was removed")
                    UsenetApi.deleteHistory(s, l.map { it.id }, purge)
                }
                null
            } catch (e: Exception) {
                hidden = hidden - items.map { it.key }.toSet()
                throw e
            }
        }
    }

    fun retry(item: NzbHistoryItem) {
        runner.run("hist-retry:${item.key}", success = "Retrying ${item.name}", after = { loader.refresh() }) {
            val s = app.service(item.serviceId) ?: throw ServiceException("${item.serviceName} was removed")
            UsenetApi.retry(s, item.id)
            null
        }
    }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = st.userRefreshing && st.value != null, onRefresh = { loader.pullRefresh() }, modifier = Modifier.fillMaxSize()) {
            val data = st.value
            LazyColumn(Modifier.fillMaxSize().expandTopBarFirst(), contentPadding = contentPadding) {
                item("filter") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = !failedOnly, onClick = { failedOnly = false }, label = { Text("All") })
                        FilterChip(selected = failedOnly, onClick = { failedOnly = true }, label = { Text("Failed") })
                    }
                }
                when {
                    data != null -> {
                        (listOfNotNull(st.error) + data.errors).distinct().forEach { e -> item("err:$e") { InlineError(e, compact = true) } }
                        val items = data.items.filter { it.key !in hidden }
                        if (items.isEmpty()) item("empty") {
                            MessageBox(Icons.Rounded.History, if (failedOnly) "No failed downloads" else "History is empty", hint = if (failedOnly) "Nice." else "Finished downloads show up here.")
                        }
                        items(items, key = { it.key }, contentType = { "hist" }) { h ->
                            HistoryRow(h, showClient, onClick = { open = h.key }, modifier = Modifier.animateItem())
                        }
                    }
                    st.error != null -> item("error") { ErrorBox(st.error, onRetry = { loader.refresh() }) }
                    else -> item("loading") { LoadingBox() }
                }
            }
        }
    }
    val item = open?.let { k -> st.value?.items?.firstOrNull { it.key == k } }
    if (item != null) {
        HistorySheet(item, onRetry = { retry(item) }, onRemove = { purge -> remove(listOf(item), purge) }, onDismiss = { open = null })
    }
}

@Composable
private fun outcomeLook(o: NzbOutcome): Pair<ImageVector, Color> = when (o) {
    NzbOutcome.Success -> Icons.Rounded.CheckCircle to okColor()
    NzbOutcome.Warning -> Icons.Rounded.WarningAmber to warnColor()
    NzbOutcome.Failed -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
    NzbOutcome.Deleted -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.outline
    NzbOutcome.Processing -> Icons.Rounded.Schedule to MaterialTheme.colorScheme.secondary
}

private fun whenText(epochSec: Long): String? = epochSec.takeIf { it > 0 }?.let { relative(Instant.ofEpochSecond(it)) }

@Composable
fun HistoryRow(item: NzbHistoryItem, showClient: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (icon, color) = outcomeLook(item.outcome)
    Row(
        modifier.fillMaxWidth().background(cs.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, item.stateLabel, tint = color, modifier = Modifier.padding(top = 2.dp, end = 12.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.reason != null) {
                Spacer(Modifier.height(2.dp))
                Text(item.reason, style = MaterialTheme.typography.bodySmall, color = if (item.outcome == NzbOutcome.Failed) cs.error else cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    item.stateLabel,
                    item.size.takeIf { it > 0 }?.let { bytes(it) },
                    whenText(item.completedAt),
                    item.category.takeIf { it.isNotEmpty() },
                    if (showClient) item.serviceName else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(item: NzbHistoryItem, onRetry: () -> Unit, onRemove: (purge: Boolean) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirm by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val (icon, color) = outcomeLook(item.outcome)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp, end = 12.dp).size(24.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                }
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            InfoLine("Status", item.stateLabel)
            if (item.reason != null) InfoLine(if (item.outcome == NzbOutcome.Failed) "Reason" else "Note", item.reason)
            if (item.size > 0) InfoLine("Size", bytes(item.size))
            if (item.completedAt > 0) InfoLine("Finished", Instant.ofEpochSecond(item.completedAt).atZone(ZoneId.systemDefault()).format(DATE_TIME))
            if (item.downloadSeconds > 0) InfoLine("Download time", eta(item.downloadSeconds))
            InfoLine("Category", item.category.ifEmpty { "None" })
            InfoLine("Client", item.serviceName)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            if (item.canRetry) {
                ListItem(
                    headlineContent = { Text(if (item.type == ServiceType.Nzbget) "Download again" else "Retry") },
                    supportingContent = {
                        Text(if (item.type == ServiceType.Nzbget) "Puts it back in the queue and downloads it from scratch" else "Tries again with what was already downloaded")
                    },
                    leadingContent = { Icon(Icons.Rounded.RestartAlt, null, tint = cs.onSurfaceVariant) },
                    colors = colors,
                    modifier = Modifier.clickable { onRetry(); onDismiss() },
                )
            }
            ListItem(
                headlineContent = { Text("Remove from history", color = cs.error) },
                leadingContent = { Icon(Icons.Rounded.Delete, null, tint = cs.error) },
                colors = colors,
                modifier = Modifier.clickable { confirm = true },
            )
        }
    }
    if (confirm) DeleteHistoryDialog(listOf(item), onConfirm = { purge -> onRemove(purge); onDismiss() }, onDismiss = { confirm = false })
}
