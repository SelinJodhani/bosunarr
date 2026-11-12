package app.bosunarr.feature.music

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.library.ArrProfile
import app.bosunarr.feature.library.LibItem
import app.bosunarr.feature.library.LibKind
import app.bosunarr.feature.library.LibraryApi
import app.bosunarr.feature.library.LibraryStore
import app.bosunarr.feature.library.arrImage
import app.bosunarr.feature.library.libKey
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.ui.common.friendlyMessage
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

/** Lidarr v1 API calls for the Library (artists, albums, tracks), the Add screen and their actions. */
object MusicApi {
    const val API = "/api/v1"

    /* ------------------------------------------------------------ lookups (cached briefly) */

    private class Cached<T>(val at: Long, val value: T)
    private val profileCache = ConcurrentHashMap<String, Cached<List<ArrProfile>>>()
    private val metadataCache = ConcurrentHashMap<String, Cached<List<LidarrMetadataProfile>>>()
    private val rootCache = ConcurrentHashMap<String, Cached<List<LidarrRootFolder>>>()
    private const val LOOKUP_TTL = 10 * 60_000L

    /** serviceId → (MusicBrainz artist id → Lidarr artist id), from the last library load. */
    private val foreignIds = ConcurrentHashMap<String, Map<String, Int>>()

    private suspend fun <T> cached(cache: ConcurrentHashMap<String, Cached<T>>, s: ServiceConfig, fresh: Boolean, load: suspend () -> T): T {
        cache[s.id]?.takeIf { !fresh && System.currentTimeMillis() - it.at < LOOKUP_TTL }?.let { return it.value }
        val v = load()
        cache[s.id] = Cached(System.currentTimeMillis(), v)
        return v
    }

    suspend fun profiles(s: ServiceConfig, fresh: Boolean = false): List<ArrProfile> =
        cached(profileCache, s, fresh) { app.client.get<List<ArrProfile>>(s, "$API/qualityprofile") }

    suspend fun metadataProfiles(s: ServiceConfig, fresh: Boolean = false): List<LidarrMetadataProfile> =
        cached(metadataCache, s, fresh) { app.client.get<List<LidarrMetadataProfile>>(s, "$API/metadataprofile") }

    suspend fun rootFolders(s: ServiceConfig, fresh: Boolean = false): List<LidarrRootFolder> =
        cached(rootCache, s, fresh) { app.client.get<List<LidarrRootFolder>>(s, "$API/rootfolder") }

    private suspend fun profileNames(s: ServiceConfig): Map<Int, String> =
        try { profiles(s).associate { it.id to it.name } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }

    private suspend fun metadataNames(s: ServiceConfig): Map<Int, String> =
        try { metadataProfiles(s).associate { it.id to it.name } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }

    /* ------------------------------------------------------------ library */

    /** Every artist on one Lidarr, as Library grid items. */
    suspend fun artistItems(s: ServiceConfig): List<LibItem> {
        val rows: List<LidarrArtist> = app.client.get(s, "$API/artist")
        foreignIds[s.id] = rows.mapNotNull { a -> a.foreignArtistId?.takeIf { it.isNotBlank() }?.let { it to a.id } }.toMap()
        return rows.map { artistItem(s, it) }
    }

    data class ArtistDetail(
        val artist: LidarrArtist,
        /** Newest first. */
        val albums: List<LidarrAlbum>,
        val profile: String?,
        val metadataProfile: String?,
    )

    suspend fun artist(s: ServiceConfig, id: Int): ArtistDetail = withContext(Dispatchers.Default) {
        coroutineScope {
            val names = async { profileNames(s) }
            val meta = async { metadataNames(s) }
            val albums = async { app.client.get<List<LidarrAlbum>>(s, "$API/album?artistId=$id") }
            val a: LidarrArtist = app.client.get(s, "$API/artist/$id")
            LibraryStore.items[libKey(s.id, LibKind.Music, id)] = artistItem(s, a)
            ArtistDetail(
                a,
                albums.await().sortedWith(compareByDescending<LidarrAlbum> { it.releaseDate.orEmpty() }.thenBy { it.title.lowercase() }),
                a.qualityProfileId?.let { names.await()[it] },
                a.metadataProfileId?.let { meta.await()[it] },
            )
        }
    }

    data class AlbumDetail(
        val album: LidarrAlbum,
        val artist: LidarrArtist?,
        /** By disc, then track number. */
        val tracks: List<LidarrTrack>,
        val files: Map<Int, LidarrTrackFile>,
    )

    suspend fun album(s: ServiceConfig, id: Int): AlbumDetail = withContext(Dispatchers.Default) {
        coroutineScope {
            val tracks = async { app.client.get<List<LidarrTrack>>(s, "$API/track?albumId=$id") }
            val files = async {
                try { app.client.get<List<LidarrTrackFile>>(s, "$API/trackfile?albumId=$id") } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            val al: LidarrAlbum = app.client.get(s, "$API/album/$id")
            // Some versions embed the artist in the album, others don't.
            val artist = al.artist?.takeIf { it.id > 0 && it.artistName.isNotBlank() } ?: al.artistId.takeIf { it > 0 }?.let {
                try { app.client.get<LidarrArtist>(s, "$API/artist/$it") } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            AlbumDetail(
                al, artist,
                tracks.await().sortedWith(compareBy<LidarrTrack> { it.mediumNumber }.thenBy { it.absoluteTrackNumber }.thenBy { it.trackNumber?.toIntOrNull() ?: Int.MAX_VALUE }),
                files.await().associateBy { it.id },
            )
        }
    }

    /* ------------------------------------------------------------ edits */

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    suspend fun setArtistMonitored(s: ServiceConfig, id: Int, monitored: Boolean) {
        val current = app.client.json(s, "$API/artist/$id") as? JsonObject ?: throw ServiceException("${s.name} gave an unexpected answer")
        app.client.send(s, "$API/artist/$id", "PUT", current.with("monitored", JsonPrimitive(monitored)))
    }

    suspend fun setAlbumsMonitored(s: ServiceConfig, albumIds: List<Int>, monitored: Boolean) {
        app.client.send(s, "$API/album/monitor", "PUT", buildJsonObject {
            putJsonArray("albumIds") { albumIds.forEach { add(it) } }
            put("monitored", monitored)
        })
    }

    suspend fun deleteArtist(s: ServiceConfig, id: Int, deleteFiles: Boolean) {
        app.client.send(s, "$API/artist/$id?deleteFiles=$deleteFiles&addImportListExclusion=false", "DELETE")
        foreignIds.remove(s.id)
    }

    /* ------------------------------------------------------------ commands */

    private suspend fun command(s: ServiceConfig, body: JsonObject) {
        app.client.send(s, "$API/command", "POST", body)
    }

    suspend fun searchArtist(s: ServiceConfig, id: Int) = command(s, buildJsonObject { put("name", "ArtistSearch"); put("artistId", id) })
    suspend fun searchAlbums(s: ServiceConfig, albumIds: List<Int>) =
        command(s, buildJsonObject { put("name", "AlbumSearch"); put("albumIds", buildJsonArray { albumIds.forEach { add(it) } }) })
    suspend fun refreshArtist(s: ServiceConfig, id: Int) = command(s, buildJsonObject { put("name", "RefreshArtist"); put("artistId", id) })
    suspend fun refreshAlbum(s: ServiceConfig, id: Int) = command(s, buildJsonObject { put("name", "RefreshAlbum"); put("albumId", id) })

    /* ------------------------------------------------------------ add */

    /** One artist found by search, merged across Lidarr instances. [raw] is what gets POSTed back when adding. */
    data class Found(
        val name: String,
        val disambiguation: String?,
        val artistType: String?,
        val overview: String?,
        val genres: List<String>,
        val foreignArtistId: String?,
        val raw: Map<String, JsonObject>,
        val inLibrary: Map<String, Int>,
        val poster: String?,
        val posterService: String?,
    ) {
        val key: String get() = foreignArtistId?.takeIf { it.isNotBlank() } ?: "n:$name:$disambiguation"
    }

    data class LookupResult(val results: List<Found>, val errors: List<LibraryApi.ServiceError>)

    private suspend fun knownIds(s: ServiceConfig): Map<String, Int> = foreignIds[s.id] ?: try {
        artistItems(s)
        foreignIds[s.id].orEmpty()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }

    suspend fun lookup(services: List<ServiceConfig>, term: String): LookupResult = withContext(Dispatchers.Default) {
        val q = URLEncoder.encode(term.trim(), "UTF-8").replace("+", "%20")
        val per = fanOut(services) { s ->
            coroutineScope {
                val known = async { knownIds(s) }
                val arr = app.client.json(s, "$API/artist/lookup?term=$q") as? JsonArray ?: throw ServiceException("${s.name} gave an unexpected answer")
                arr.mapNotNull { it as? JsonObject } to known.await()
            }
        }
        val errors = per.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { LibraryApi.ServiceError(s, it.friendlyMessage()) } }
        if (errors.size == services.size && services.isNotEmpty()) throw ServiceException(errors.first().message)
        val merged = LinkedHashMap<String, Found>()
        for ((s, r) in per) {
            val (objects, known) = r.getOrNull() ?: continue
            for (o in objects) {
                val a = runCatching { AppJson.decodeFromJsonElement(LidarrArtist.serializer(), o) }.getOrNull() ?: continue
                if (a.artistName.isBlank()) continue
                val existing = a.id.takeIf { it > 0 } ?: a.foreignArtistId?.let { known[it] }
                val f = Found(
                    a.artistName, a.disambiguation?.takeIf { it.isNotBlank() }, a.artistType?.takeIf { it.isNotBlank() }, a.overview, a.genres,
                    a.foreignArtistId, mapOf(s.id to o), if (existing != null) mapOf(s.id to existing) else emptyMap(),
                    // Lookup results aren't in the library yet: no /MediaCover fallback (owner id 0).
                    arrImage(s, a.images, "poster", 250, existing ?: 0), s.id,
                )
                val hit = merged[f.key]
                merged[f.key] = if (hit == null) f else hit.copy(
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
        val metadataProfileId: Int,
        val rootFolderPath: String,
        /** Albums to monitor now: all, future, missing, existing, latest, first, none. */
        val monitor: String = "all",
        /** Albums added later: all, none, new. */
        val monitorNewItems: String = "all",
        val search: Boolean,
    )

    private val STALE = listOf("id", "path", "statistics", "added", "lastInfoSync")

    /** Add to [s]; returns the new artist id there. */
    suspend fun add(s: ServiceConfig, found: Found, o: AddOptions): Int = withContext(Dispatchers.Default) {
        val base = found.raw[s.id] ?: found.raw.values.first()
        val body = JsonObject(base.toMutableMap().apply {
            STALE.forEach { remove(it) }
            put("qualityProfileId", JsonPrimitive(o.qualityProfileId))
            put("metadataProfileId", JsonPrimitive(o.metadataProfileId))
            put("rootFolderPath", JsonPrimitive(o.rootFolderPath))
            put("monitored", JsonPrimitive(o.monitor != "none"))
            put("monitorNewItems", JsonPrimitive(o.monitorNewItems))
            put("tags", JsonArray(emptyList()))
            put("addOptions", buildJsonObject {
                put("monitor", o.monitor)
                put("monitored", o.monitor != "none")
                put("searchForMissingAlbums", o.search)
            })
        })
        val text = app.client.send(s, "$API/artist", "POST", body)
        val created = app.client.decode(s, text, JsonObject.serializer())
        val id = (created["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw ServiceException("${s.name} didn't confirm the add")
        foreignIds.remove(s.id)
        LibraryStore.invalidate()
        id
    }
}
