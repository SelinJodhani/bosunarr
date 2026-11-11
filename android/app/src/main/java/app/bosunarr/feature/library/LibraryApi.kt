package app.bosunarr.feature.library

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Direct Sonarr / Radarr v3 API calls for the Library, Add and Calendar screens. */
object LibraryApi {

    /* ------------------------------------------------------------ lookups (cached briefly) */

    private class Cached<T>(val at: Long, val value: T)
    private val profileCache = ConcurrentHashMap<String, Cached<List<ArrProfile>>>()
    private val rootCache = ConcurrentHashMap<String, Cached<List<ArrRootFolder>>>()
    private const val LOOKUP_TTL = 10 * 60_000L

    suspend fun profiles(s: ServiceConfig, fresh: Boolean = false): List<ArrProfile> {
        profileCache[s.id]?.takeIf { !fresh && System.currentTimeMillis() - it.at < LOOKUP_TTL }?.let { return it.value }
        val list: List<ArrProfile> = app.client.get(s, "/api/v3/qualityprofile")
        profileCache[s.id] = Cached(System.currentTimeMillis(), list)
        return list
    }

    suspend fun rootFolders(s: ServiceConfig, fresh: Boolean = false): List<ArrRootFolder> {
        rootCache[s.id]?.takeIf { !fresh && System.currentTimeMillis() - it.at < LOOKUP_TTL }?.let { return it.value }
        val list: List<ArrRootFolder> = app.client.get(s, "/api/v3/rootfolder")
        rootCache[s.id] = Cached(System.currentTimeMillis(), list)
        return list
    }

    /** Profile names by id; empty when the lookup fails (it only decorates). */
    suspend fun profileNames(s: ServiceConfig): Map<Int, String> =
        try { profiles(s).associate { it.id to it.name } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }

    /* ------------------------------------------------------------ library */

    data class ServiceError(val service: ServiceConfig, val message: String)
    data class LibraryData(val items: List<LibItem>, val errors: List<ServiceError>, val fetchedAt: Long)

    /** Every series and movie across all Sonarr/Radarr instances. Fails only when every instance fails. */
    suspend fun library(services: List<ServiceConfig>): LibraryData = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val results = fanOut(services) { s ->
            coroutineScope {
                val names = async { profileNames(s) }
                if (s.serviceType == ServiceType.Sonarr) {
                    val rows: List<ArrSeries> = app.client.get(s, "/api/v3/series")
                    val p = names.await()
                    rows.map { seriesItem(s, it, p) }
                } else {
                    val rows: List<ArrMovie> = app.client.get(s, "/api/v3/movie")
                    val p = names.await()
                    rows.map { movieItem(s, it, p) }
                }
            }
        }
        val errors = results.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { ServiceError(s, it.userMessage()) } }
        if (services.isNotEmpty() && errors.size == services.size) throw ServiceException(errors.first().message)
        val items = results.flatMap { it.second.getOrNull().orEmpty() }.sortedBy { it.sortTitle }
        LibraryStore.remember(items)
        LibraryData(items, errors, startedAt)
    }

    /* ------------------------------------------------------------ detail */

    data class SeriesDetail(
        val series: ArrSeries,
        val episodes: List<ArrEpisode>,
        val files: Map<Int, ArrEpisodeFile>,
        val profile: String?,
    )

    suspend fun series(s: ServiceConfig, id: Int): SeriesDetail = withContext(Dispatchers.Default) {
        coroutineScope {
            val names = async { profileNames(s) }
            val episodes = async { app.client.get<List<ArrEpisode>>(s, "/api/v3/episode?seriesId=$id") }
            val files = async {
                try { app.client.get<List<ArrEpisodeFile>>(s, "/api/v3/episodefile?seriesId=$id") } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            val x: ArrSeries = app.client.get(s, "/api/v3/series/$id")
            val detail = SeriesDetail(x, episodes.await(), files.await().associateBy { it.id }, x.qualityProfileId?.let { names.await()[it] })
            LibraryStore.items[libKey(s.id, LibKind.Series, id)] = seriesItem(s, x, names.await())
            detail
        }
    }

    data class MovieDetail(val movie: ArrMovie, val profile: String?)

    suspend fun movie(s: ServiceConfig, id: Int): MovieDetail = withContext(Dispatchers.Default) {
        coroutineScope {
            val names = async { profileNames(s) }
            val m: ArrMovie = app.client.get(s, "/api/v3/movie/$id")
            LibraryStore.items[libKey(s.id, LibKind.Movie, id)] = movieItem(s, m, names.await())
            MovieDetail(m, m.qualityProfileId?.let { names.await()[it] })
        }
    }

    /* ------------------------------------------------------------ edits */

    private fun JsonElement.asObject(s: ServiceConfig): JsonObject = this as? JsonObject ?: throw ServiceException("${s.name} gave an unexpected answer")

    /** Read the full resource, change it, PUT it back (the arrs want the whole object). */
    private suspend fun edit(s: ServiceConfig, path: String, change: (JsonObject) -> JsonObject) {
        val current = app.client.json(s, path).asObject(s)
        app.client.send(s, path, "PUT", change(current))
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    suspend fun setSeriesMonitored(s: ServiceConfig, id: Int, monitored: Boolean) =
        edit(s, "/api/v3/series/$id") { it.with("monitored", JsonPrimitive(monitored)) }

    /** Sonarr updates the season's episodes to match when a season's monitoring changes. */
    suspend fun setSeasonMonitored(s: ServiceConfig, id: Int, season: Int, monitored: Boolean) =
        edit(s, "/api/v3/series/$id") { o ->
            val seasons = (o["seasons"] as? JsonArray)?.map { el ->
                val so = el as? JsonObject ?: return@map el
                if ((so["seasonNumber"] as? JsonPrimitive)?.content?.toIntOrNull() == season) so.with("monitored", JsonPrimitive(monitored)) else so
            } ?: throw ServiceException("${s.name} gave an unexpected answer")
            o.with("seasons", JsonArray(seasons))
        }

    suspend fun setEpisodesMonitored(s: ServiceConfig, episodeIds: List<Int>, monitored: Boolean) {
        app.client.send(s, "/api/v3/episode/monitor", "PUT", buildJsonObject {
            putJsonArray("episodeIds") { episodeIds.forEach { add(it) } }
            put("monitored", monitored)
        })
    }

    suspend fun setMovieMonitored(s: ServiceConfig, id: Int, monitored: Boolean) =
        edit(s, "/api/v3/movie/$id") { it.with("monitored", JsonPrimitive(monitored)) }

    suspend fun deleteSeries(s: ServiceConfig, id: Int, deleteFiles: Boolean) {
        app.client.send(s, "/api/v3/series/$id?deleteFiles=$deleteFiles&addImportListExclusion=false", "DELETE")
    }

    suspend fun deleteMovie(s: ServiceConfig, id: Int, deleteFiles: Boolean) {
        app.client.send(s, "/api/v3/movie/$id?deleteFiles=$deleteFiles&addImportExclusion=false", "DELETE")
    }

    /* ------------------------------------------------------------ commands */

    private suspend fun command(s: ServiceConfig, body: JsonObject) {
        app.client.send(s, "/api/v3/command", "POST", body)
    }

    suspend fun searchSeries(s: ServiceConfig, id: Int) = command(s, buildJsonObject { put("name", "SeriesSearch"); put("seriesId", id) })
    suspend fun searchSeason(s: ServiceConfig, id: Int, season: Int) =
        command(s, buildJsonObject { put("name", "SeasonSearch"); put("seriesId", id); put("seasonNumber", season) })
    suspend fun searchEpisodes(s: ServiceConfig, episodeIds: List<Int>) =
        command(s, buildJsonObject { put("name", "EpisodeSearch"); put("episodeIds", buildJsonArray { episodeIds.forEach { add(it) } }) })
    suspend fun refreshSeries(s: ServiceConfig, id: Int) = command(s, buildJsonObject { put("name", "RefreshSeries"); put("seriesId", id) })
    suspend fun searchMovie(s: ServiceConfig, id: Int) =
        command(s, buildJsonObject { put("name", "MoviesSearch"); put("movieIds", buildJsonArray { add(id) }) })
    suspend fun refreshMovie(s: ServiceConfig, id: Int) =
        command(s, buildJsonObject { put("name", "RefreshMovie"); put("movieIds", buildJsonArray { add(id) }) })

    /* ------------------------------------------------------------ add */

    /** One lookup result as each instance sees it: [raw] is what gets POSTed back when adding. */
    data class Found(
        val kind: LibKind,
        val title: String,
        val year: Int?,
        val overview: String?,
        val network: String?,
        val runtime: Int,
        val tvdbId: Int,
        val tmdbId: Int,
        val seasonCount: Int,
        val status: String?,
        /** serviceId → that instance's lookup object (carries its own image proxy URLs). */
        val raw: Map<String, JsonObject>,
        /** serviceId → id in that instance's library (absent when not added there). */
        val inLibrary: Map<String, Int>,
        /** Poster path on [posterService]. */
        val poster: String?,
        val posterService: String?,
    ) {
        /** Unique within one lookup (results are merged on the same identity). */
        val key: String get() = "$kind:$tvdbId:$tmdbId:$title:$year"
    }

    data class LookupResult(val results: List<Found>, val errors: List<ServiceError>)

    suspend fun lookup(kind: LibKind, services: List<ServiceConfig>, term: String): LookupResult = withContext(Dispatchers.Default) {
        val q = URLEncoder.encode(term.trim(), "UTF-8").replace("+", "%20")
        val path = if (kind == LibKind.Series) "/api/v3/series/lookup?term=$q" else "/api/v3/movie/lookup?term=$q"
        val per = fanOut(services) { s ->
            val arr = app.client.json(s, path) as? JsonArray ?: throw ServiceException("${s.name} gave an unexpected answer")
            arr.mapNotNull { it as? JsonObject }
        }
        val errors = per.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { ServiceError(s, it.userMessage()) } }
        if (errors.size == services.size && services.isNotEmpty()) throw ServiceException(errors.first().message)
        // Merge the same title across instances, keeping the first instance's ordering (relevance).
        val merged = LinkedHashMap<String, Found>()
        for ((s, r) in per) {
            for (o in r.getOrNull().orEmpty()) {
                val f = if (kind == LibKind.Series) {
                    val x = runCatching { AppJson.decodeFromJsonElement(ArrSeries.serializer(), o) }.getOrNull() ?: continue
                    if (x.title.isBlank()) continue
                    Found(
                        kind, x.title, x.year.takeIf { it > 0 }, x.overview, x.network?.takeIf { it.isNotBlank() }, x.runtime,
                        x.tvdbId, x.tmdbId, x.seasons.count { it.seasonNumber > 0 }, x.status,
                        mapOf(s.id to o), if (x.id > 0) mapOf(s.id to x.id) else emptyMap(),
                        arrImage(s, x.images, "poster", 250), s.id,
                    )
                } else {
                    val x = runCatching { AppJson.decodeFromJsonElement(ArrMovie.serializer(), o) }.getOrNull() ?: continue
                    if (x.title.isBlank()) continue
                    Found(
                        kind, x.title, x.year.takeIf { it > 0 }, x.overview, x.studio?.takeIf { it.isNotBlank() }, x.runtime,
                        0, x.tmdbId, 0, x.status,
                        mapOf(s.id to o), if (x.id > 0) mapOf(s.id to x.id) else emptyMap(),
                        arrImage(s, x.images, "poster", 250), s.id,
                    )
                }
                val id = if (kind == LibKind.Series) (if (f.tvdbId > 0) "tvdb:${f.tvdbId}" else "t:${f.title}:${f.year}") else (if (f.tmdbId > 0) "tmdb:${f.tmdbId}" else "t:${f.title}:${f.year}")
                val hit = merged[id]
                merged[id] = if (hit == null) f else hit.copy(
                    raw = hit.raw + f.raw,
                    inLibrary = hit.inLibrary + f.inLibrary,
                    poster = hit.poster ?: f.poster,
                    posterService = if (hit.poster == null) f.posterService else hit.posterService,
                )
            }
        }
        LookupResult(merged.values.toList(), errors)
    }

    data class AddOptions(
        val qualityProfileId: Int,
        val rootFolderPath: String,
        val search: Boolean,
        /** Sonarr: all, future, missing, existing, pilot, firstSeason, lastSeason, none. */
        val monitor: String = "all",
        /** Sonarr: standard, daily, anime. */
        val seriesType: String = "standard",
        val seasonFolder: Boolean = true,
        /** Radarr: announced, inCinemas, released. */
        val minimumAvailability: String = "released",
        val monitored: Boolean = true,
    )

    /**
     * Fields of a lookup object that belong to an existing library entry (when the object came from another
     * instance that already has the title). Without `path` the arr builds it from the root folder.
     */
    private val STALE = listOf("id", "path", "movieFile", "movieFileId", "statistics", "added", "lastInfoSync")

    /** Add to [s]; returns the new id in that instance. */
    suspend fun add(s: ServiceConfig, found: Found, o: AddOptions): Int = withContext(Dispatchers.Default) {
        val base = found.raw[s.id] ?: found.raw.values.first()
        val body: JsonObject
        val path: String
        if (found.kind == LibKind.Series) {
            // Sonarr v3 needs a language profile; v4 ignores it (and may not have the endpoint).
            val languageProfile = try {
                (app.client.json(s, "/api/v3/languageprofile") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("id") as? JsonPrimitive }?.content?.toIntOrNull()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            body = JsonObject(base.toMutableMap().apply {
                STALE.forEach { remove(it) }
                put("qualityProfileId", JsonPrimitive(o.qualityProfileId))
                if (languageProfile != null) put("languageProfileId", JsonPrimitive(languageProfile))
                put("rootFolderPath", JsonPrimitive(o.rootFolderPath))
                put("seriesType", JsonPrimitive(o.seriesType))
                put("seasonFolder", JsonPrimitive(o.seasonFolder))
                put("monitored", JsonPrimitive(o.monitor != "none"))
                put("tags", JsonArray(emptyList()))
                put("addOptions", buildJsonObject {
                    put("monitor", o.monitor)
                    put("searchForMissingEpisodes", o.search)
                    put("searchForCutoffUnmetEpisodes", false)
                })
            })
            path = "/api/v3/series"
        } else {
            body = JsonObject(base.toMutableMap().apply {
                STALE.forEach { remove(it) }
                put("qualityProfileId", JsonPrimitive(o.qualityProfileId))
                put("rootFolderPath", JsonPrimitive(o.rootFolderPath))
                put("monitored", JsonPrimitive(o.monitored))
                put("minimumAvailability", JsonPrimitive(o.minimumAvailability))
                put("tags", JsonArray(emptyList()))
                put("addOptions", buildJsonObject {
                    put("searchForMovie", o.search)
                    put("monitor", if (o.monitored) "movieOnly" else "none")
                })
            })
            path = "/api/v3/movie"
        }
        val text = app.client.send(s, path, "POST", body)
        val created = app.client.decode(s, text, JsonObject.serializer())
        val id = (created["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw ServiceException("${s.name} didn't confirm the add")
        LibraryStore.invalidate()
        id
    }
}
