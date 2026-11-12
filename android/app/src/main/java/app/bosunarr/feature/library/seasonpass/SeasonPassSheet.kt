package app.bosunarr.feature.library.seasonpass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.library.ArrEpisode
import app.bosunarr.feature.library.ArrSeries
import app.bosunarr.feature.library.LibraryApi
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.toggleRow
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** What the season pass changes: only the seasons whose monitoring differs, and the new-season setting if changed. */
data class SeasonPassChange(val monitored: Boolean, val seasons: Map<Int, Boolean>, val monitorNewItems: String?)

private fun seasonName(n: Int) = if (n == 0) "Specials" else "Season $n"

/**
 * Season pass for one series: monitor every season at once (All / None / Latest / Future / Specials off) or per
 * season, plus Sonarr v4's "monitor new seasons". Nothing is sent until Save; [onSave] gets just the changes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeasonPassSheet(
    service: ServiceConfig,
    series: ArrSeries,
    episodes: List<ArrEpisode>,
    onDismiss: () -> Unit,
    onSave: (SeasonPassChange) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val original = remember(series) { series.seasons.associate { it.seasonNumber to it.monitored } }
    // Keyed on the id, not the object: a background refresh of the series mustn't wipe the user's edits.
    var seasons by remember(series.id) { mutableStateOf(original) }
    var monitored by remember(series.id) { mutableStateOf(series.monitored) }
    // null until loaded, "" when this Sonarr (v3) has no such setting.
    var newOriginal by remember { mutableStateOf<String?>(null) }
    var monitorNew by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(series.id) {
        val v = try { LibraryApi.monitorNewItems(service, series.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        newOriginal = v ?: ""
        monitorNew = v ?: ""
    }
    val supportsNew = !newOriginal.isNullOrEmpty()

    val regular = series.seasons.map { it.seasonNumber }.filter { it > 0 }.sorted()
    val now = Instant.now()
    // Seasons still airing or not started: no episodes listed yet, or any episode with no air date or one in the future.
    val airing = remember(episodes, regular) {
        val bySeason = episodes.groupBy { it.seasonNumber }
        regular.filter { n -> bySeason[n].orEmpty().let { eps -> eps.isEmpty() || eps.any { e -> parseInstant(e.airDateUtc)?.isAfter(now) ?: true } } }.toSet()
    }

    fun preset(map: (Int) -> Boolean, seriesOn: Boolean, newSeasons: String?) {
        seasons = seasons.mapValues { (n, cur) -> if (n == 0) cur else map(n) }
        monitored = seriesOn
        if (newSeasons != null && supportsNew) monitorNew = newSeasons
    }

    val changed = seasons.filter { (n, v) -> original[n] != v }
    val newChanged = supportsNew && monitorNew != newOriginal
    val dirty = changed.isNotEmpty() || monitored != series.monitored || newChanged

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, contentWindowInsets = { WindowInsets(0) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("Season pass", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Choose which seasons ${service.name} monitors for ${series.title}. Monitoring a season monitors all its episodes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetChip("All", Icons.Rounded.DoneAll) { preset({ true }, true, "all") }
                PresetChip("None", Icons.Rounded.Block) {
                    preset({ false }, false, "none")
                    if (0 in seasons) seasons = seasons + (0 to false)
                }
                if (regular.isNotEmpty()) PresetChip("Latest season", Icons.Rounded.Star) { preset({ it == regular.last() }, true, null) }
                PresetChip("Future", Icons.Rounded.Schedule) { preset({ it in airing }, true, "all") }
                if (0 in seasons) PresetChip("Specials off", Icons.Rounded.NewReleases) { seasons = seasons + (0 to false) }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("Monitor series", "Off: nothing is searched for, whatever the seasons say", monitored) { monitored = it }
            when {
                newOriginal == null -> Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking new-season setting…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                supportsNew -> SwitchRow("Monitor new seasons", "Seasons added later start monitored", monitorNew == "all") { monitorNew = if (it) "all" else "none" }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val ordered = series.seasons.sortedWith(compareBy<app.bosunarr.feature.library.ArrSeason> { it.seasonNumber == 0 }.thenByDescending { it.seasonNumber })
            ordered.forEach { s ->
                val n = s.seasonNumber
                val st = s.statistics
                val all = st?.totalEpisodeCount?.takeIf { it > 0 } ?: episodes.count { it.seasonNumber == n }
                val have = st?.episodeFileCount ?: episodes.count { it.seasonNumber == n && it.hasFile }
                val info = listOfNotNull(
                    if (all > 0) "$have of ${plural(all, "episode")}" else "No episodes yet",
                    if (n in airing) "Airing" else null,
                    if (original[n] != seasons[n]) "Changed" else null,
                ).joinToString(" · ")
                SwitchRow(seasonName(n), info, seasons[n] == true) { seasons = seasons + (n to it) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = dirty,
                    onClick = {
                        onSave(SeasonPassChange(monitored, changed, if (newChanged) monitorNew else null))
                        onDismiss()
                    },
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleRow(value) { onChange(it) }.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(value, onCheckedChange = null)
    }
}
