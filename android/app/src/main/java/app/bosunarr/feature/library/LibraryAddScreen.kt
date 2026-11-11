package app.bosunarr.feature.library

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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tv
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import app.bosunarr.ui.common.toggleRow
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.nav.LibraryAddRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.friendlyMessage
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBanner
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.plural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Search state for the Add screen; survives going to a detail screen and back. */
class AddSearchViewModel : ViewModel() {
    data class State(val term: String = "", val kind: LibKind? = null, val loading: Boolean = false, val result: LibraryApi.LookupResult? = null, val error: String? = null)

    val state = MutableStateFlow(State())
    private var job: Job? = null

    fun search(kind: LibKind, services: List<ServiceConfig>, term: String, force: Boolean = false) {
        val t = term.trim()
        val cur = state.value
        if (!force && t == cur.term && kind == cur.kind && (cur.loading || cur.result != null)) return
        job?.cancel()
        if (t.isEmpty()) {
            state.value = State(kind = kind)
            return
        }
        // Keep the previous results on screen while the new ones load (unless the kind changed).
        state.value = cur.copy(term = t, kind = kind, loading = true, error = null, result = cur.result.takeIf { cur.kind == kind })
        job = viewModelScope.launch {
            state.value = try {
                State(t, kind, false, LibraryApi.lookup(kind, services, t), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value.copy(loading = false, error = e.friendlyMessage(), result = null)
            }
        }
    }
}

/** Last choices per service, so adding several titles in a row doesn't mean re-picking everything. */
private object AddPrefs {
    val profile = HashMap<String, Int>()
    val root = HashMap<String, String>()
    var monitor = "all"
    var seriesType = "standard"
    var availability = "released"
    var search = true
}

private val MONITOR = listOf(
    "All episodes" to "all", "Future episodes" to "future", "Missing episodes" to "missing", "Existing episodes" to "existing",
    "First season" to "firstSeason", "Last season" to "lastSeason", "Pilot episode" to "pilot", "Nothing" to "none",
)
private val SERIES_TYPES = listOf("Standard" to "standard", "Daily" to "daily", "Anime" to "anime")
private val AVAILABILITY = listOf("Announced" to "announced", "In cinemas" to "inCinemas", "Released" to "released")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryAddScreen(nav: NavController, route: LibraryAddRoute) {
    val loaded by app.state.collectAsState()
    val all by app.services.collectAsState()
    val sonarrs = remember(all) { all.filter { it.serviceType == ServiceType.Sonarr } }
    val adult = route.kind == "adult"
    // Whisparr (Radarr-based) adds through the same movie flow, kept apart from Radarr.
    val radarrs = remember(all, adult) { all.filter { it.serviceType == if (adult) ServiceType.Whisparr else ServiceType.Radarr } }
    val initial = if (route.kind == "movie" || adult) LibKind.Movie else LibKind.Series
    var chosenKind by rememberSaveable { mutableStateOf(initial) }
    // Fall back to whichever kind is configured.
    val kind = when {
        adult -> LibKind.Movie
        chosenKind == LibKind.Series && sonarrs.isEmpty() && radarrs.isNotEmpty() -> LibKind.Movie
        chosenKind == LibKind.Movie && radarrs.isEmpty() && sonarrs.isNotEmpty() -> LibKind.Series
        else -> chosenKind
    }
    val services = if (kind == LibKind.Series) sonarrs else radarrs

    var query by rememberSaveable { mutableStateOf(route.term) }
    val vm: AddSearchViewModel = viewModel()
    val s by vm.state.collectAsState()
    var picked by remember { mutableStateOf<LibraryApi.Found?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(query, kind, services) {
        if (services.isEmpty()) return@LaunchedEffect
        if (query.trim() != s.term || kind != s.kind) delay(450)
        vm.search(kind, services, query)
    }
    LaunchedEffect(Unit) { if (route.term.isBlank()) runCatching { focus.requestFocus() } }

    AppScaffold(if (adult) "Add to Whisparr" else if (kind == LibKind.Series) "Add series" else "Add movie", onBack = { nav.back() }) { p ->
        if (loaded != null && services.isEmpty()) {
            Column(Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState())) {
                MessageBox(
                    Icons.Rounded.Search, "No Sonarr or Radarr yet", hint = "Add a Sonarr or Radarr service to add series and movies.",
                    action = "Open Settings", onAction = { nav.navigate(SettingsRoute) },
                )
            }
            return@AppScaffold
        }
        Column(Modifier.padding(p).fillMaxSize()) {
            if (!adult && sonarrs.isNotEmpty() && radarrs.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf(LibKind.Series to "Series", LibKind.Movie to "Movies").forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = kind == k, onClick = { chosenKind = k }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (kind == LibKind.Series) "Search for a series" else "Search for a movie") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; runCatching { focus.requestFocus() } }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(kind, services, query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            // Reserve the progress bar's space so results don't jump when it appears.
            Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            s.result?.errors?.takeIf { it.isNotEmpty() }?.let { errs -> ErrorBanner(errs.joinToString("\n") { it.message }) }
            val result = s.result
            when {
                query.isBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MessageBox(
                        if (kind == LibKind.Series) Icons.Rounded.Tv else Icons.Rounded.Movie,
                        if (kind == LibKind.Series) "Find a series" else "Find a movie",
                        hint = "Search by title, or paste an id like ${if (kind == LibKind.Series) "tvdb:81189" else "tmdb:603 or imdb:tt0133093"}.",
                    )
                }
                s.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ErrorBox(s.error!!, onRetry = { vm.search(kind, services, query, force = true) }) }
                result == null -> Unit
                result.results.isEmpty() && !s.loading -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MessageBox(Icons.Rounded.SearchOff, "No results", hint = "Nothing found for “${s.term}”. Check the spelling or try the original title.")
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
                    items(result.results, key = { it.key }) { f ->
                        ResultRow(f, services) {
                            keyboard?.hide()
                            val missingFrom = services.filter { it.id !in f.inLibrary }
                            if (missingFrom.isEmpty()) {
                                val (sid, id) = f.inLibrary.entries.first()
                                nav.navigate(if (f.kind == LibKind.Series) SeriesRoute(sid, id) else MovieRoute(sid, id))
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
            AddSheet(
                f, services,
                onOpen = { sid, id ->
                    picked = null
                    nav.navigate(if (f.kind == LibKind.Series) SeriesRoute(sid, id) else MovieRoute(sid, id))
                },
                onAdded = { sid, id ->
                    picked = null
                    app.scope.launch(Dispatchers.Main) { snackbar.showSnackbar("Added ${f.title}") }
                    nav.navigate(if (f.kind == LibKind.Series) SeriesRoute(sid, id) else MovieRoute(sid, id)) {
                        popUpTo<LibraryAddRoute> { inclusive = true }
                    }
                },
            )
        }
    }
}

@Composable
private fun ResultRow(f: LibraryApi.Found, services: List<ServiceConfig>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val posterService = services.firstOrNull { it.id == f.posterService }
    val inAll = services.all { it.id in f.inLibrary }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Poster(posterService, f.poster, width = 72.dp, title = f.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                f.year?.toString(),
                f.network,
                if (f.kind == LibKind.Series && f.seasonCount > 0) plural(f.seasonCount, "season") else null,
            ).joinToString(" · ")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    f: LibraryApi.Found,
    services: List<ServiceConfig>,
    onOpen: (serviceId: String, id: Int) -> Unit,
    onAdded: (serviceId: String, id: Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val available = services.filter { it.id !in f.inLibrary }
    var targetId by rememberSaveable { mutableStateOf(available.firstOrNull()?.id) }
    val target = available.firstOrNull { it.id == targetId } ?: available.firstOrNull()

    // Profiles and root folders of the chosen instance.
    var options by remember(target?.id) { mutableStateOf<Result<Pair<List<ArrProfile>, List<ArrRootFolder>>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(target?.id, reload) {
        val t = target ?: return@LaunchedEffect
        options = null
        options = try {
            Result.success(LibraryApi.profiles(t, fresh = reload > 0) to LibraryApi.rootFolders(t, fresh = reload > 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val profiles = options?.getOrNull()?.first.orEmpty()
    val roots = options?.getOrNull()?.second.orEmpty()

    var profileId by remember(target?.id, profiles) { mutableStateOf(target?.let { AddPrefs.profile[it.id] }?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id) }
    var rootPath by remember(target?.id, roots) { mutableStateOf(target?.let { AddPrefs.root[it.id] }?.takeIf { p -> roots.any { it.path == p } } ?: roots.firstOrNull()?.path) }
    var monitor by rememberSaveable { mutableStateOf(AddPrefs.monitor) }
    var seriesType by rememberSaveable { mutableStateOf(f.raw.values.firstOrNull()?.get("seriesType")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.takeIf { t -> SERIES_TYPES.any { it.second == t } && t != "standard" } ?: AddPrefs.seriesType) }
    var seasonFolder by rememberSaveable { mutableStateOf(true) }
    var availability by rememberSaveable { mutableStateOf(AddPrefs.availability) }
    var monitored by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf(AddPrefs.search) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(services.firstOrNull { it.id == f.posterService }, f.poster, width = 56.dp, title = f.title)
            Column(Modifier.weight(1f)) {
                Text(f.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(f.year?.toString(), f.network).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Instance picker (only when there's a choice to make or a copy elsewhere to open).
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

        when {
            target == null -> Unit
            options == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            options!!.isFailure -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(options!!.exceptionOrNull()?.friendlyMessage() ?: "Couldn't load options", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { reload++ }) { Text("Try again") }
            }
            else -> {
                if (roots.isEmpty()) Text("${target.name} has no root folders yet — add one in its Media Management settings.", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                PickerField(
                    "Quality profile", profiles.firstOrNull { it.id == profileId }?.name ?: "Choose…",
                    profiles.map { it.name to it.id }, onPick = { profileId = it },
                )
                PickerField(
                    "Root folder", rootPath ?: "Choose…",
                    roots.map { it.path to it.path }, onPick = { rootPath = it },
                    supporting = { path -> roots.firstOrNull { it.path == path }?.freeSpace?.let { "${bytes(it)} free" } },
                )
                if (f.kind == LibKind.Series) {
                    PickerField("Monitor", MONITOR.firstOrNull { it.second == monitor }?.first ?: monitor, MONITOR, onPick = { monitor = it })
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Series type", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SERIES_TYPES.forEachIndexed { i, (label, v) ->
                                SegmentedButton(selected = seriesType == v, onClick = { seriesType = v }, shape = SegmentedButtonDefaults.itemShape(i, SERIES_TYPES.size)) { Text(label) }
                            }
                        }
                    }
                    SwitchRow("Season folders", null, seasonFolder) { seasonFolder = it }
                } else {
                    PickerField("Minimum availability", AVAILABILITY.firstOrNull { it.second == availability }?.first ?: availability, AVAILABILITY, onPick = { availability = it })
                    SwitchRow("Monitored", "Download it when it becomes available", monitored) { monitored = it }
                }
                SwitchRow(
                    "Start search for missing",
                    if (f.kind == LibKind.Series) "Look for monitored episodes right away" else "Look for the movie right away",
                    search && (f.kind == LibKind.Series || monitored),
                    enabled = f.kind == LibKind.Series || monitored,
                ) { search = it }

                error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }

                Button(
                    onClick = {
                        val pid = profileId ?: return@Button
                        val root = rootPath ?: return@Button
                        adding = true
                        error = null
                        AddPrefs.profile[target.id] = pid
                        AddPrefs.root[target.id] = root
                        AddPrefs.search = search
                        if (f.kind == LibKind.Series) { AddPrefs.monitor = monitor; AddPrefs.seriesType = seriesType } else AddPrefs.availability = availability
                        scope.launch {
                            try {
                                val id = LibraryApi.add(
                                    target, f,
                                    LibraryApi.AddOptions(
                                        qualityProfileId = pid, rootFolderPath = root,
                                        search = search && (f.kind == LibKind.Series || monitored),
                                        monitor = monitor, seriesType = seriesType, seasonFolder = seasonFolder,
                                        minimumAvailability = availability, monitored = monitored,
                                    ),
                                )
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
                    enabled = !adding && profileId != null && rootPath != null,
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

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleRow(checked, enabled = enabled, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
