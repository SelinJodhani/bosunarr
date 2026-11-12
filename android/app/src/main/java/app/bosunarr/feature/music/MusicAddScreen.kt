package app.bosunarr.feature.music

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.library.ArrProfile
import app.bosunarr.feature.library.PickerField
import app.bosunarr.nav.ArtistRoute
import app.bosunarr.nav.LibraryAddRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBanner
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.FullScreenScroll
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.friendlyMessage
import app.bosunarr.ui.common.toggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Artist search state; survives going to an artist and back. */
class MusicSearchViewModel : ViewModel() {
    data class State(val term: String = "", val loading: Boolean = false, val result: MusicApi.LookupResult? = null, val error: String? = null)

    val state = MutableStateFlow(State())
    private var job: Job? = null

    fun search(services: List<ServiceConfig>, term: String, force: Boolean = false) {
        val t = term.trim()
        val cur = state.value
        if (!force && t == cur.term && (cur.loading || cur.result != null)) return
        job?.cancel()
        if (t.isEmpty()) {
            state.value = State()
            return
        }
        state.value = cur.copy(term = t, loading = true, error = null)
        job = viewModelScope.launch {
            state.value = try {
                State(t, false, MusicApi.lookup(services, t), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value.copy(loading = false, error = e.friendlyMessage(), result = null)
            }
        }
    }
}

/** Last choices per Lidarr, so adding several artists in a row doesn't mean re-picking everything. */
private object MusicAddPrefs {
    val profile = HashMap<String, Int>()
    val metadata = HashMap<String, Int>()
    val root = HashMap<String, String>()
    var monitor: String? = null
    var monitorNew: String? = null
    var search = true
}

private val MONITOR = listOf(
    "All albums" to "all", "Future albums" to "future", "Missing albums" to "missing", "Existing albums" to "existing",
    "Latest album" to "latest", "First album" to "first", "None" to "none",
)
private val MONITOR_NEW = listOf("All new albums" to "all", "Only new releases" to "new", "None" to "none")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicAddScreen(nav: NavController, initialTerm: String) {
    val loaded by app.state.collectAsState()
    val all by app.services.collectAsState()
    val services = remember(all) { all.filter { it.serviceType == ServiceType.Lidarr } }

    var query by rememberSaveable { mutableStateOf(initialTerm) }
    val vm: MusicSearchViewModel = viewModel()
    val s by vm.state.collectAsState()
    var picked by remember { mutableStateOf<MusicApi.Found?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(query, services) {
        if (services.isEmpty()) return@LaunchedEffect
        if (query.trim() != s.term) delay(450)
        vm.search(services, query)
    }
    LaunchedEffect(Unit) { if (initialTerm.isBlank()) runCatching { focus.requestFocus() } }

    AppScaffold("Add artist", onBack = { nav.back() }) { p ->
        if (loaded != null && services.isEmpty()) {
            FullScreenScroll(Modifier.padding(p)) { m ->
                MessageBox(
                    Icons.Rounded.LibraryMusic, "No Lidarr yet", m, hint = "Add a Lidarr service to add artists.",
                    action = "Open Settings", onAction = { nav.navigate(SettingsRoute) },
                    scene = SeaScene.Harbor,
                )
            }
            return@AppScaffold
        }
        Column(Modifier.padding(p).fillMaxSize()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search for an artist") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; runCatching { focus.requestFocus() } }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(services, query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            s.result?.errors?.takeIf { it.isNotEmpty() }?.let { errs -> ErrorBanner(errs.joinToString("\n") { it.message }) }
            val result = s.result
            when {
                query.isBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MessageBox(
                        Icons.Rounded.LibraryMusic, "Find an artist",
                        hint = "Search by name, or paste a MusicBrainz id like lidarr:cc197bad-dc9c-440d-a5b5-d52ba2e14234.",
                        scene = SeaScene.Spyglass,
                        fullBleed = false,
                    )
                }
                s.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ErrorBox(s.error!!, onRetry = { vm.search(services, query, force = true) }, fullBleed = false) }
                result == null -> Unit
                result.results.isEmpty() && !s.loading -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MessageBox(Icons.Rounded.SearchOff, "Nothing in sight", hint = "No artists match “${s.term}”. Check the spelling.", scene = SeaScene.Spyglass, fullBleed = false)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
                    items(result.results, key = { it.key }) { f ->
                        ResultRow(f, services) {
                            keyboard?.hide()
                            if (services.all { it.id in f.inLibrary }) {
                                val (sid, id) = f.inLibrary.entries.first()
                                nav.navigate(ArtistRoute(sid, id))
                            } else picked = f
                        }
                    }
                }
            }
        }
    }

    picked?.let { f ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val snackbar = LocalSnackbar.current
        ModalBottomSheet(onDismissRequest = { picked = null }, sheetState = sheet) {
            AddArtistSheet(
                f, services,
                onOpen = { sid, id -> picked = null; nav.navigate(ArtistRoute(sid, id)) },
                onAdded = { sid, id ->
                    picked = null
                    app.scope.launch(Dispatchers.Main) { snackbar.showSnackbar("Added ${f.name}") }
                    nav.navigate(ArtistRoute(sid, id)) { popUpTo<LibraryAddRoute> { inclusive = true } }
                },
            )
        }
    }
}

@Composable
private fun ResultRow(f: MusicApi.Found, services: List<ServiceConfig>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val inAll = services.all { it.id in f.inLibrary }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SquareCover(services.firstOrNull { it.id == f.posterService }, f.poster, 64.dp, f.name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(f.artistType, f.disambiguation, f.genres.firstOrNull()).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!f.overview.isNullOrBlank()) Text(f.overview, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (f.inLibrary.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Tag(
                    if (inAll || services.size == 1) "In library" else "In " + services.filter { it.id in f.inLibrary }.joinToString(", ") { it.name },
                    container = cs.primaryContainer, content = cs.onPrimaryContainer,
                )
            }
        }
        Icon(
            if (inAll) Icons.Rounded.ChevronRight else Icons.Rounded.Add,
            if (inAll) "Open" else "Add",
            tint = if (inAll) cs.onSurfaceVariant else cs.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private data class AddChoices(val profiles: List<ArrProfile>, val metadata: List<LidarrMetadataProfile>, val roots: List<LidarrRootFolder>)

@Composable
private fun AddArtistSheet(
    f: MusicApi.Found,
    services: List<ServiceConfig>,
    onOpen: (serviceId: String, id: Int) -> Unit,
    onAdded: (serviceId: String, id: Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val available = services.filter { it.id !in f.inLibrary }
    var targetId by rememberSaveable { mutableStateOf(available.firstOrNull()?.id) }
    val target = available.firstOrNull { it.id == targetId } ?: available.firstOrNull()

    var options by remember(target?.id) { mutableStateOf<Result<AddChoices>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(target?.id, reload) {
        val t = target ?: return@LaunchedEffect
        options = null
        options = try {
            val fresh = reload > 0
            Result.success(AddChoices(MusicApi.profiles(t, fresh), MusicApi.metadataProfiles(t, fresh), MusicApi.rootFolders(t, fresh)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val choices = options?.getOrNull()
    val profiles = choices?.profiles.orEmpty()
    val metas = choices?.metadata.orEmpty()
    val roots = choices?.roots.orEmpty()

    var rootPath by remember(target?.id, roots) { mutableStateOf(target?.let { MusicAddPrefs.root[it.id] }?.takeIf { p -> roots.any { it.path == p } } ?: roots.firstOrNull()?.path) }
    val root = roots.firstOrNull { it.path == rootPath }
    // The root folder carries Lidarr's own defaults for new artists; the user's last pick wins over them.
    var profileId by remember(target?.id, profiles, root?.path) {
        mutableStateOf(
            target?.let { MusicAddPrefs.profile[it.id] }?.takeIf { id -> profiles.any { it.id == id } }
                ?: root?.defaultQualityProfileId?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id,
        )
    }
    var metadataId by remember(target?.id, metas, root?.path) {
        mutableStateOf(
            target?.let { MusicAddPrefs.metadata[it.id] }?.takeIf { id -> metas.any { it.id == id } }
                ?: root?.defaultMetadataProfileId?.takeIf { id -> metas.any { it.id == id } }
                ?: metas.firstOrNull()?.id,
        )
    }
    var monitor by remember(root?.path) { mutableStateOf(MusicAddPrefs.monitor ?: root?.defaultMonitorOption?.takeIf { m -> MONITOR.any { it.second == m } } ?: "all") }
    var monitorNew by remember(root?.path) { mutableStateOf(MusicAddPrefs.monitorNew ?: root?.defaultNewItemMonitorOption?.takeIf { m -> MONITOR_NEW.any { it.second == m } } ?: "all") }
    var search by rememberSaveable { mutableStateOf(MusicAddPrefs.search) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SquareCover(services.firstOrNull { it.id == f.posterService }, f.poster, 56.dp, f.name)
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(f.artistType, f.disambiguation).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (services.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Add to", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { svc ->
                        FilterChip(
                            selected = svc.id == target?.id,
                            onClick = { targetId = svc.id; error = null },
                            label = { Text(svc.name) },
                            leadingIcon = if (svc.id == target?.id) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                        )
                    }
                }
                services.filter { it.id in f.inLibrary }.forEach { svc ->
                    ListItem(
                        headlineContent = { Text("Already in ${svc.name}") },
                        trailingContent = { TextButton(onClick = { onOpen(svc.id, f.inLibrary.getValue(svc.id)) }) { Text("Open") } },
                    )
                }
            }
        }

        val opts = options
        when {
            target == null -> Unit
            opts == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            opts.isFailure -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(opts.exceptionOrNull()?.friendlyMessage() ?: "Couldn't load options", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { reload++ }) { Text("Try again") }
            }
            else -> {
                if (roots.isEmpty()) Text("${target.name} has no root folders yet — add one in its Media Management settings.", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                PickerField(
                    "Root folder", rootPath ?: "Choose…",
                    roots.map { (it.name?.takeIf { n -> n.isNotBlank() }?.let { n -> "$n (${it.path})" } ?: it.path) to it.path }, onPick = { rootPath = it },
                    supporting = { path -> roots.firstOrNull { it.path == path }?.freeSpace?.let { "${bytes(it)} free" } },
                )
                PickerField("Quality profile", profiles.firstOrNull { it.id == profileId }?.name ?: "Choose…", profiles.map { it.name to it.id }, onPick = { profileId = it })
                PickerField("Metadata profile", metas.firstOrNull { it.id == metadataId }?.name ?: "Choose…", metas.map { it.name to it.id }, onPick = { metadataId = it })
                PickerField("Monitor", MONITOR.firstOrNull { it.second == monitor }?.first ?: monitor, MONITOR, onPick = { monitor = it })
                PickerField("Monitor new albums", MONITOR_NEW.firstOrNull { it.second == monitorNew }?.first ?: monitorNew, MONITOR_NEW, onPick = { monitorNew = it })
                val canSearch = monitor != "none"
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleRow(search && canSearch, enabled = canSearch, onValueChange = { search = it }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Start search for missing", style = MaterialTheme.typography.bodyLarge, color = if (canSearch) cs.onSurface else cs.onSurface.copy(alpha = 0.38f))
                        Text("Look for monitored albums right away", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = search && canSearch, onCheckedChange = null, enabled = canSearch)
                }

                error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }

                Button(
                    onClick = {
                        val pid = profileId ?: return@Button
                        val mid = metadataId ?: return@Button
                        val path = rootPath ?: return@Button
                        adding = true
                        error = null
                        MusicAddPrefs.profile[target.id] = pid
                        MusicAddPrefs.metadata[target.id] = mid
                        MusicAddPrefs.root[target.id] = path
                        MusicAddPrefs.monitor = monitor
                        MusicAddPrefs.monitorNew = monitorNew
                        MusicAddPrefs.search = search
                        scope.launch {
                            try {
                                val id = MusicApi.add(target, f, MusicApi.AddOptions(pid, mid, path, monitor, monitorNew, search && canSearch))
                                onAdded(target.id, id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.friendlyMessage()
                            } finally {
                                adding = false
                            }
                        }
                    },
                    enabled = !adding && profileId != null && metadataId != null && rootPath != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (adding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                    else {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (services.size > 1) "Add to ${target.name}" else "Add", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
