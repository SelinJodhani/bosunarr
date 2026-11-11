package app.bosunarr.feature.discover

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap

/* ------------------------------------------------------------------ Seerr API shapes (only what we use) */

@Serializable
data class SeerrUser(
    val id: Int = 0,
    val displayName: String? = null,
    val username: String? = null,
    val plexUsername: String? = null,
    val jellyfinUsername: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val permissions: Long = 0,
) {
    val label: String get() = listOf(displayName, username, jellyfinUsername, plexUsername, email).firstOrNull { !it.isNullOrBlank() } ?: "Unknown"
}

@Serializable
data class SeerrSeasonStatus(val seasonNumber: Int = 0, val status: Int = 1, val status4k: Int = 1)

@Serializable
data class SeerrRequestSeason(val seasonNumber: Int = 0, val status: Int = 1)

@Serializable
data class SeerrMedia(
    val id: Int = 0,
    val tmdbId: Int = 0,
    val tvdbId: Int? = null,
    val mediaType: String? = null,
    val status: Int = 1,
    val status4k: Int = 1,
    val seasons: List<SeerrSeasonStatus> = emptyList(),
    val requests: List<SeerrRequest> = emptyList(),
)

@Serializable
data class SeerrRequest(
    val id: Int = 0,
    val status: Int = 1,
    val is4k: Boolean = false,
    val type: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val requestedBy: SeerrUser? = null,
    val modifiedBy: SeerrUser? = null,
    val seasons: List<SeerrRequestSeason> = emptyList(),
    /** Absent on the requests embedded in a media row (that would be circular). */
    val media: SeerrMedia? = null,
) {
    val mediaType: String get() = type ?: media?.mediaType ?: "movie"
    /** Availability of the copy this request is for (4K request → 4K copy). */
    val ownCopy: Avail get() = avail(if (is4k) media?.status4k else media?.status)
    val otherCopy: Avail get() = avail(if (is4k) media?.status else media?.status4k)
}

@Serializable
data class SeerrResult(
    val id: Int = 0,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double? = null,
    val mediaInfo: SeerrMedia? = null,
)

@Serializable
data class SeerrPage(val page: Int = 1, val totalPages: Int = 1, val totalResults: Int = 0, val results: List<SeerrResult> = emptyList())

@Serializable data class SeerrGenre(val id: Int = 0, val name: String = "")
@Serializable data class SeerrNamed(val name: String? = null)
@Serializable data class SeerrCast(val id: Int = 0, val name: String = "", val character: String? = null, val profilePath: String? = null, val order: Int = 0)
@Serializable data class SeerrCrew(val id: Int = 0, val name: String = "", val job: String? = null)
@Serializable data class SeerrCredits(val cast: List<SeerrCast> = emptyList(), val crew: List<SeerrCrew> = emptyList())
@Serializable data class SeerrExternalIds(val imdbId: String? = null, val tvdbId: Int? = null)
@Serializable data class SeerrSeason(val seasonNumber: Int = 0, val name: String? = null, val episodeCount: Int = 0, val airDate: String? = null)

@Serializable
data class SeerrDetail(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val episodeRunTime: List<Int> = emptyList(),
    /** "Released", "Returning Series", … */
    val status: String? = null,
    val genres: List<SeerrGenre> = emptyList(),
    val networks: List<SeerrNamed> = emptyList(),
    val productionCompanies: List<SeerrNamed> = emptyList(),
    val credits: SeerrCredits = SeerrCredits(),
    val externalIds: SeerrExternalIds = SeerrExternalIds(),
    val seasons: List<SeerrSeason> = emptyList(),
    val mediaInfo: SeerrMedia? = null,
)

@Serializable data class ArrServer(val id: Int = 0, val name: String = "", val is4k: Boolean = false, val isDefault: Boolean = false, val activeProfileId: Int? = null, val activeDirectory: String? = null)
@Serializable data class ArrProfile(val id: Int = 0, val name: String = "")
@Serializable data class ArrRootFolder(val id: Int = 0, val path: String = "", val freeSpace: Long? = null)
@Serializable data class ArrServerDetail(val server: ArrServer = ArrServer(), val profiles: List<ArrProfile> = emptyList(), val rootFolders: List<ArrRootFolder> = emptyList())

@Serializable data class SeerrPageInfo(val pages: Int = 0, val pageSize: Int = 0, val results: Int = 0, val page: Int = 1)
@Serializable data class SeerrRequestPage(val pageInfo: SeerrPageInfo = SeerrPageInfo(), val results: List<SeerrRequest> = emptyList())
@Serializable data class SeerrRequestCount(val total: Int = 0, val pending: Int = 0, val approved: Int = 0, val declined: Int = 0, val processing: Int = 0, val available: Int = 0)

@Serializable data class RtRatings(val criticsScore: Int? = null, val audienceScore: Int? = null)
@Serializable data class ImdbRating(val criticsScore: Double? = null)
@Serializable data class CombinedRatings(val rt: RtRatings? = null, val imdb: ImdbRating? = null)

@Serializable data class TitleInfo(val title: String? = null, val name: String? = null, val posterPath: String? = null, val releaseDate: String? = null, val firstAirDate: String? = null)

@Serializable
data class CreateRequestBody(
    val mediaType: String,
    val mediaId: Int,
    val is4k: Boolean,
    val seasons: List<Int>? = null,
    val tvdbId: Int? = null,
    val serverId: Int? = null,
    val profileId: Int? = null,
    val rootFolder: String? = null,
)

/* ------------------------------------------------------------------ Status semantics */

/**
 * Seerr's MediaStatus collapsed: 5 available, 4 partially available, 3 processing (approved, downloading),
 * 2 pending (awaiting approval); 1 unknown, 6 blocklisted and 7 deleted read as None (requestable again).
 * A media row has two independent ones: `status` (regular copy) and `status4k` (the 4K server's copy).
 */
enum class Avail(val rank: Int) { None(0), Pending(1), Processing(2), Partial(3), Available(4) }

fun avail(n: Int?): Avail = when (n) {
    5 -> Avail.Available
    4 -> Avail.Partial
    3 -> Avail.Processing
    2 -> Avail.Pending
    else -> Avail.None
}

val Avail.label: String get() = when (this) {
    Avail.Available -> "Available"
    Avail.Partial -> "Partially available"
    Avail.Processing -> "Requested"
    Avail.Pending -> "Pending approval"
    Avail.None -> "Not requested"
}

/** Label for one copy's status; `qualify` names the HD copy too (when both copies have a status). */
fun copyLabel(a: Avail, uhd: Boolean, qualify: Boolean = false): String = when {
    uhd && (a == Avail.Available || a == Avail.Partial) -> "${a.label} in 4K"
    uhd -> "4K · ${a.label}"
    qualify -> "HD · ${a.label}"
    else -> a.label
}

/** The further-along of the two copies (ties go to the regular copy). */
fun headline(hd: Avail, uhd: Avail): Pair<Avail, Boolean> = if (uhd.rank > hd.rank) uhd to true else hd to false

/** Short poster badge text, or null for no badge. */
fun posterBadge(hd: Avail, uhd: Avail): Pair<String, Tone>? {
    val (a, is4k) = headline(hd, uhd)
    val text = when (a) {
        Avail.Available -> if (is4k) "In 4K" else "Available"
        Avail.Partial -> if (is4k) "Partial 4K" else "Partial"
        Avail.Processing -> if (is4k) "4K requested" else "Requested"
        Avail.Pending -> if (is4k) "4K pending" else "Pending"
        Avail.None -> return null
    }
    return text to a.tone
}

/** One colour scheme for every status badge (cards, detail, sheet, requests). */
enum class Tone { Ok, Partial, Progress, Wait, Bad }

val Avail.tone: Tone get() = when (this) {
    Avail.Available -> Tone.Ok
    Avail.Partial -> Tone.Partial
    Avail.Processing -> Tone.Progress
    Avail.Pending, Avail.None -> Tone.Wait
}

/** Seerr request status: 1 pending approval, 2 approved, 3 declined, 4 failed, 5 completed. */
enum class ReqStatus(val code: Int, val label: String, val tone: Tone) {
    Pending(1, "Pending approval", Tone.Wait),
    Approved(2, "Approved", Tone.Progress),
    Declined(3, "Declined", Tone.Bad),
    Failed(4, "Failed", Tone.Bad),
    Completed(5, "Completed", Tone.Ok);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Pending
    }
}

val SeerrRequest.reqStatus: ReqStatus get() = ReqStatus.of(status)

/* ------------------------------------------------------------------ Small helpers */

private val TMDB_PATH = Regex("^/[\\w.-]+$")

/** TMDb image through Seerr's image proxy (TMDb itself is unreachable for some users). Never image.tmdb.org. */
fun tmdbImage(path: String?, size: String): String? = path?.takeIf { TMDB_PATH.matches(it) }?.let { "/imageproxy/tmdb/t/p/$size$it" }

fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it > 1800 }

/** Seerr rejects some characters unless strictly percent-encoded (RFC 3986 unreserved only; spaces as %20, never '+'). */
fun strictEncode(q: String): String {
    val sb = StringBuilder()
    for (b in q.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') sb.append(ch)
        else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
    }
    return sb.toString()
}

/** A movie / series card in any list. `key` is unique across movies and series (TMDb ids overlap). */
data class MediaItem(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val status: Avail,
    val status4k: Avail,
) {
    val key: String get() = "$mediaType-$tmdbId"
}

fun SeerrResult.toItem(fallbackType: String? = null): MediaItem? {
    val type = mediaType ?: fallbackType
    if (type != "movie" && type != "tv") return null
    if (id <= 0) return null
    return MediaItem(
        tmdbId = id, mediaType = type, title = title ?: name ?: "TMDb $id", year = yearOf(releaseDate ?: firstAirDate),
        posterPath = posterPath, backdropPath = backdropPath, rating = voteAverage?.takeIf { it > 0 },
        status = avail(mediaInfo?.status), status4k = avail(mediaInfo?.status4k),
    )
}

/** Normalise and de-duplicate (TMDb paging can repeat items; duplicate keys crash lazy lists). */
fun List<SeerrResult>.toItems(fallbackType: String? = null, seen: MutableSet<String> = HashSet()): List<MediaItem> =
    mapNotNull { it.toItem(fallbackType) }.filter { seen.add(it.key) }

/** Items seen in lists this session, so a detail screen can paint its header before the fetch finishes. */
object Peek {
    private val map = object : LinkedHashMap<String, MediaItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > 1500
    }

    fun remember(items: List<MediaItem>) = synchronized(map) { items.forEach { map[it.key] = it } }
    fun get(type: String, id: Int): MediaItem? = synchronized(map) { map["$type-$id"] }
}

/** Bumped after every write (request, approve, …) so other screens refresh their counts / statuses. */
val seerrWrites = MutableStateFlow(0)

/** The Seerr instance the user picked (when several are configured); null = the first one. */
val selectedSeerr = MutableStateFlow<String?>(null)

/* ------------------------------------------------------------------ Calls */

object Seerr {
    data class HomeRow(val id: String, val title: String, val items: List<MediaItem>)

    private val SECTIONS = listOf(
        Triple("trending", "Trending", "/api/v1/discover/trending"),
        Triple("movies", "Popular movies", "/api/v1/discover/movies"),
        Triple("movies-upcoming", "Upcoming movies", "/api/v1/discover/movies/upcoming"),
        Triple("tv", "Popular series", "/api/v1/discover/tv"),
        Triple("tv-upcoming", "Upcoming series", "/api/v1/discover/tv/upcoming"),
    )

    /** All carousels (page 1). Fails only if every row fails. */
    suspend fun home(s: ServiceConfig): List<HomeRow> = coroutineScope {
        val results = SECTIONS.map { (id, title, path) ->
            async {
                runCatchingCancellable {
                    val fallback = when { id.startsWith("movies") -> "movie"; id.startsWith("tv") -> "tv"; else -> null }
                    HomeRow(id, title, app.client.get<SeerrPage>(s, "$path?page=1").results.toItems(fallback))
                }
            }
        }.awaitAll()
        val rows = results.mapNotNull { it.getOrNull() }.filter { it.items.isNotEmpty() }
        if (rows.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        rows.forEach { Peek.remember(it.items) }
        rows
    }

    suspend fun search(s: ServiceConfig, query: String, page: Int): SeerrPage =
        app.client.get<SeerrPage>(s, "/api/v1/search?query=${strictEncode(query)}&page=$page")

    suspend fun detail(s: ServiceConfig, type: String, id: Int): SeerrDetail = app.client.get(s, "/api/v1/$type/$id")

    /** `kind` = "recommendations" | "similar". */
    suspend fun related(s: ServiceConfig, type: String, id: Int, kind: String): List<MediaItem> =
        app.client.get<SeerrPage>(s, "/api/v1/$type/$id/$kind?page=1").results.toItems(type).also { Peek.remember(it) }

    data class Rating(val label: String, val value: String, val star: Boolean)

    suspend fun ratings(s: ServiceConfig, type: String, id: Int): List<Rating> {
        var rt: RtRatings? = null
        var imdb: Double? = null
        if (type == "movie") {
            val combined = runCatchingCancellable { app.client.get<CombinedRatings>(s, "/api/v1/movie/$id/ratingscombined") }.getOrNull()
            if (combined != null) { rt = combined.rt; imdb = combined.imdb?.criticsScore }
            else rt = runCatchingCancellable { app.client.get<RtRatings>(s, "/api/v1/movie/$id/ratings") }.getOrNull()
        } else rt = runCatchingCancellable { app.client.get<RtRatings>(s, "/api/v1/tv/$id/ratings") }.getOrNull()
        return buildList {
            imdb?.takeIf { it > 0 }?.let { add(Rating("IMDb", String.format(java.util.Locale.US, "%.1f", it), true)) }
            rt?.criticsScore?.let { add(Rating("Critics", "$it%", false)) }
            rt?.audienceScore?.let { add(Rating("Audience", "$it%", false)) }
        }
    }

    private data class Timed<T>(val at: Long, val value: T)
    private val serverCache = ConcurrentHashMap<String, Timed<List<ArrServer>>>()
    private val meCache = ConcurrentHashMap<String, Timed<SeerrUser>>()

    /** The Radarr ("movie") / Sonarr ("tv") servers configured in Seerr (cached 10 min). Empty if unavailable. */
    suspend fun servers(s: ServiceConfig, type: String): List<ArrServer> {
        val key = "${s.id}:$type"
        serverCache[key]?.takeIf { System.currentTimeMillis() - it.at < 600_000 }?.let { return it.value }
        val list = runCatchingCancellable { app.client.get<List<ArrServer>>(s, "/api/v1/service/${if (type == "movie") "radarr" else "sonarr"}") }.getOrElse { emptyList() }
        serverCache[key] = Timed(System.currentTimeMillis(), list)
        return list
    }

    suspend fun serverDetail(s: ServiceConfig, type: String, serverId: Int): ArrServerDetail =
        app.client.get(s, "/api/v1/service/${if (type == "movie") "radarr" else "sonarr"}/$serverId")

    /** The Seerr user behind the API key (the admin unless the key is scoped). */
    suspend fun me(s: ServiceConfig): SeerrUser? {
        meCache[s.id]?.takeIf { System.currentTimeMillis() - it.at < 600_000 }?.let { return it.value }
        val me = runCatchingCancellable { app.client.get<SeerrUser>(s, "/api/v1/auth/me") }.getOrNull() ?: return null
        meCache[s.id] = Timed(System.currentTimeMillis(), me)
        return me
    }

    /** Admin (2) or Manage requests (16). If we can't tell, assume yes: an API key acts as the admin. */
    suspend fun canManage(s: ServiceConfig): Boolean = me(s)?.let { it.permissions and (2L or 16L) != 0L } ?: true

    suspend fun count(s: ServiceConfig): SeerrRequestCount = app.client.get(s, "/api/v1/request/count")

    suspend fun requests(s: ServiceConfig, filter: String, take: Int, skip: Int): SeerrRequestPage =
        app.client.get(s, "/api/v1/request?take=$take&skip=$skip&filter=$filter&sort=added")

    suspend fun create(s: ServiceConfig, body: CreateRequestBody) {
        try {
            notHtml(s, app.client.send(s, "/api/v1/request", "POST", AppJson.encodeToJsonElement(body)))
        } catch (e: ServiceException) {
            if (e.status == 409) throw ServiceException("Already requested", 409)
            throw e
        } finally {
            seerrWrites.update { it + 1 }
        }
    }

    /** A web page (login portal, proxy error) is never a successful API answer. */
    private fun notHtml(s: ServiceConfig, text: String) {
        if (text.trimStart().startsWith("<")) throw ServiceException("${s.name} answered with a web page instead of its API — check the address")
    }

    enum class Action(val done: String) { Approve("Approved"), Decline("Declined"), Delete("Deleted request for"), Retry("Retrying") }

    suspend fun act(s: ServiceConfig, requestId: Int, action: Action) {
        try {
            val text = when (action) {
                Action.Delete -> app.client.send(s, "/api/v1/request/$requestId", "DELETE")
                Action.Approve -> app.client.send(s, "/api/v1/request/$requestId/approve", "POST")
                Action.Decline -> app.client.send(s, "/api/v1/request/$requestId/decline", "POST")
                Action.Retry -> app.client.send(s, "/api/v1/request/$requestId/retry", "POST")
            }
            notHtml(s, text)
        } finally {
            seerrWrites.update { it + 1 }
        }
    }

    /* Request rows only carry media.tmdbId: titles + posters come from /movie|/tv/{id}, cached, ≤4 at a time. */
    private val titles = ConcurrentHashMap<String, TitleInfo>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TitleInfo>>()
    private val titleLimit = Semaphore(4)

    fun cachedTitle(s: ServiceConfig, type: String, tmdbId: Int): TitleInfo? = titles["${s.id}:$type:$tmdbId"]

    suspend fun title(s: ServiceConfig, type: String, tmdbId: Int): TitleInfo {
        val key = "${s.id}:$type:$tmdbId"
        titles[key]?.let { return it }
        val mine = CompletableDeferred<TitleInfo>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            try {
                return existing.await()
            } catch (e: CancellationException) {
                // The fetch we were waiting on was cancelled (its screen went away), not us: fetch it ourselves.
                currentCoroutineContext().ensureActive()
                return title(s, type, tmdbId)
            }
        }
        try {
            val info = titleLimit.withPermit { app.client.get<TitleInfo>(s, "/api/v1/$type/$tmdbId") }
            if (titles.size > 2000) titles.clear()
            titles[key] = info
            mine.complete(info)
            return info
        } catch (e: Throwable) {
            if (e is CancellationException) mine.cancel() else mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }
}

inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
