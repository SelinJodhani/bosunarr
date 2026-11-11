package app.bosunarr.feature.library

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import kotlinx.serialization.Serializable
import java.net.URI

/*
 * Sonarr v3/v4 and Radarr v3–v6 API shapes — only the fields the app uses, every field defaulted because
 * versions differ (AppJson ignores unknown keys and coerces nulls).
 */

@Serializable
data class ArrImage(val coverType: String = "", val url: String? = null)

@Serializable
data class RatingValue(val value: Double? = null, val votes: Long? = null)

@Serializable
data class ArrRatings(val value: Double? = null, val votes: Long? = null, val imdb: RatingValue? = null, val tmdb: RatingValue? = null) {
    val best: Double? get() = (imdb?.value?.takeIf { it > 0 } ?: tmdb?.value?.takeIf { it > 0 } ?: value?.takeIf { it > 0 })
    val source: String? get() = when {
        (imdb?.value ?: 0.0) > 0 -> "IMDb"
        (tmdb?.value ?: 0.0) > 0 -> "TMDb"
        else -> null
    }
}

@Serializable
data class SeriesStatistics(
    val episodeFileCount: Int = 0,
    val episodeCount: Int = 0,
    val totalEpisodeCount: Int = 0,
    val sizeOnDisk: Long = 0,
    val seasonCount: Int = 0,
)

@Serializable
data class ArrSeason(val seasonNumber: Int = 0, val monitored: Boolean = false, val statistics: SeriesStatistics? = null)

@Serializable
data class ArrSeries(
    val id: Int = 0,
    val title: String = "",
    val sortTitle: String? = null,
    val titleSlug: String? = null,
    val year: Int = 0,
    val images: List<ArrImage> = emptyList(),
    val monitored: Boolean = false,
    val added: String? = null,
    val ratings: ArrRatings? = null,
    val genres: List<String> = emptyList(),
    val qualityProfileId: Int? = null,
    val overview: String? = null,
    val runtime: Int = 0,
    val certification: String? = null,
    val path: String? = null,
    val rootFolderPath: String? = null,
    val status: String? = null,
    val network: String? = null,
    val seriesType: String? = null,
    val nextAiring: String? = null,
    val previousAiring: String? = null,
    val firstAired: String? = null,
    val tvdbId: Int = 0,
    val tmdbId: Int = 0,
    val imdbId: String? = null,
    val statistics: SeriesStatistics? = null,
    val seasons: List<ArrSeason> = emptyList(),
)

@Serializable
data class QualityName(val name: String? = null, val resolution: Int = 0)

@Serializable
data class ArrQuality(val quality: QualityName? = null)

@Serializable
data class MediaInfo(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Double? = null,
    val resolution: String? = null,
    val videoDynamicRangeType: String? = null,
    val audioLanguages: String? = null,
    val subtitles: String? = null,
)

@Serializable
data class ArrLanguage(val id: Int = 0, val name: String? = null)

@Serializable
data class ArrMovieFile(
    val id: Int = 0,
    val path: String? = null,
    val relativePath: String? = null,
    val size: Long = 0,
    val dateAdded: String? = null,
    val quality: ArrQuality? = null,
    val mediaInfo: MediaInfo? = null,
    val languages: List<ArrLanguage> = emptyList(),
    val releaseGroup: String? = null,
)

@Serializable
data class MovieStatistics(val sizeOnDisk: Long = 0, val movieFileCount: Int = 0)

@Serializable
data class ArrMovie(
    val id: Int = 0,
    val title: String = "",
    val sortTitle: String? = null,
    val titleSlug: String? = null,
    val year: Int = 0,
    val images: List<ArrImage> = emptyList(),
    val monitored: Boolean = false,
    val added: String? = null,
    val ratings: ArrRatings? = null,
    val genres: List<String> = emptyList(),
    val qualityProfileId: Int? = null,
    val overview: String? = null,
    val runtime: Int = 0,
    val certification: String? = null,
    val path: String? = null,
    val rootFolderPath: String? = null,
    val status: String? = null,
    val studio: String? = null,
    val hasFile: Boolean = false,
    val isAvailable: Boolean = true,
    val minimumAvailability: String? = null,
    val sizeOnDisk: Long? = null,
    val statistics: MovieStatistics? = null,
    val inCinemas: String? = null,
    val digitalRelease: String? = null,
    val physicalRelease: String? = null,
    val tmdbId: Int = 0,
    val imdbId: String? = null,
    val movieFile: ArrMovieFile? = null,
) {
    val diskSize: Long get() = sizeOnDisk?.takeIf { it > 0 } ?: statistics?.sizeOnDisk?.takeIf { it > 0 } ?: movieFile?.size ?: 0
}

@Serializable
data class ArrEpisode(
    val id: Int = 0,
    val seriesId: Int = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val airDate: String? = null,
    val airDateUtc: String? = null,
    val overview: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val episodeFileId: Int = 0,
    val runtime: Int = 0,
    val finaleType: String? = null,
)

@Serializable
data class ArrEpisodeFile(val id: Int = 0, val size: Long = 0, val quality: ArrQuality? = null, val relativePath: String? = null)

@Serializable
data class ArrProfile(val id: Int = 0, val name: String = "")

@Serializable
data class ArrRootFolder(val id: Int = 0, val path: String = "", val freeSpace: Long? = null, val accessible: Boolean = true)

@Serializable
data class ArrCommandResult(val id: Int = 0, val name: String? = null, val status: String? = null)

/* ---------------------------------------------------------------- Library list item */

enum class LibKind { Series, Movie }

/** missing = monitored and nothing on disk; partial = some episodes missing; upcoming = not released yet. */
enum class LibStatus(val label: String) {
    Downloaded("Downloaded"), Partial("Partially downloaded"), Missing("Missing"), Upcoming("Not released yet"), Unmonitored("Unmonitored")
}

data class LibItem(
    val serviceId: String,
    val serviceName: String,
    val kind: LibKind,
    val id: Int,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val poster: String?,
    val monitored: Boolean,
    val status: LibStatus,
    val sizeOnDisk: Long,
    val added: String?,
    val have: Int,
    val total: Int,
    val is4k: Boolean,
    val network: String?,
    val tmdbId: Int,
    val tvdbId: Int,
) {
    val key: String get() = libKey(serviceId, kind, id)
    /** Episode completion 0..1 for series with known episodes, else null. */
    val progress: Float? get() = if (kind == LibKind.Series && total > 0) (have.toFloat() / total).coerceIn(0f, 1f) else null
}

fun libKey(serviceId: String, kind: LibKind, id: Int) = "$serviceId-${if (kind == LibKind.Series) "series" else "movie"}-$id"

val UHD = Regex("2160|4k|uhd|ultra[\\s-]?hd", RegexOption.IGNORE_CASE)

fun sortTitleOf(title: String, sort: String?): String =
    (sort?.takeIf { it.isNotBlank() } ?: title).lowercase().replace(Regex("^(the|a|an)\\s+"), "")

fun seriesStatus(x: ArrSeries): LibStatus {
    val have = x.statistics?.episodeFileCount ?: 0
    val want = x.statistics?.episodeCount ?: 0
    return when {
        want == 0 -> if (have > 0) LibStatus.Downloaded else if (x.monitored) LibStatus.Upcoming else LibStatus.Unmonitored
        have >= want -> LibStatus.Downloaded
        !x.monitored -> if (have > 0) LibStatus.Partial else LibStatus.Unmonitored
        have == 0 -> LibStatus.Missing
        else -> LibStatus.Partial
    }
}

fun movieStatus(x: ArrMovie): LibStatus = when {
    x.hasFile -> LibStatus.Downloaded
    !x.monitored -> LibStatus.Unmonitored
    !x.isAvailable -> LibStatus.Upcoming
    else -> LibStatus.Missing
}

fun seriesItem(s: ServiceConfig, x: ArrSeries, profiles: Map<Int, String>): LibItem {
    val qp = x.qualityProfileId?.let { profiles[it] }
    return LibItem(
        serviceId = s.id, serviceName = s.name, kind = LibKind.Series, id = x.id,
        title = x.title, sortTitle = sortTitleOf(x.title, x.sortTitle), year = x.year.takeIf { it > 0 },
        poster = arrImage(s, x.images, "poster", 500, x.id), monitored = x.monitored, status = seriesStatus(x),
        sizeOnDisk = x.statistics?.sizeOnDisk ?: 0, added = x.added,
        have = x.statistics?.episodeFileCount ?: 0, total = x.statistics?.episodeCount ?: 0,
        is4k = UHD.containsMatchIn(s.name) || UHD.containsMatchIn(qp.orEmpty()),
        network = x.network?.takeIf { it.isNotBlank() }, tmdbId = x.tmdbId, tvdbId = x.tvdbId,
    )
}

fun movieItem(s: ServiceConfig, x: ArrMovie, profiles: Map<Int, String>): LibItem {
    val qp = x.qualityProfileId?.let { profiles[it] }
    return LibItem(
        serviceId = s.id, serviceName = s.name, kind = LibKind.Movie, id = x.id,
        title = x.title, sortTitle = sortTitleOf(x.title, x.sortTitle), year = x.year.takeIf { it > 0 },
        poster = arrImage(s, x.images, "poster", 500, x.id), monitored = x.monitored, status = movieStatus(x),
        sizeOnDisk = x.diskSize, added = x.added, have = if (x.hasFile) 1 else 0, total = 1,
        is4k = UHD.containsMatchIn(s.name) || UHD.containsMatchIn(qp.orEmpty()) || (x.movieFile?.quality?.quality?.resolution ?: 0) >= 2160,
        network = x.studio?.takeIf { it.isNotBlank() }, tmdbId = x.tmdbId, tvdbId = 0,
    )
}

/* ---------------------------------------------------------------- Images */

/**
 * Path (relative to the service address) of an arr cover. Only the arr's own `url` is used — never `remoteUrl`
 * (TMDb/TVDB may be unreachable from the phone). The arr includes its URL base (e.g. `/sonarr/MediaCover/…`),
 * which the configured address already ends with, so it's stripped. [ownerId] (series/movie id) enables a
 * fallback to the arr's own cover cache. [size] picks a pre-scaled variant
 * (`poster-500.jpg`, `fanart-360.jpg`); any `?lastWrite=` / `?h=` cache-buster is kept.
 */
fun arrImage(s: ServiceConfig, images: List<ArrImage>?, type: String, size: Int? = null, ownerId: Int = 0): String? {
    // Library items always have their cover cached by the arr at /MediaCover/<id>/: use that when the arr
    // only hands us a remote URL (or nothing).
    val fallback = if (ownerId > 0) "/MediaCover/$ownerId/$type${size?.let { "-$it" } ?: ""}.jpg" else null
    val url = images?.firstOrNull { it.coverType == type }?.url?.takeIf { it.isNotBlank() } ?: return fallback
    if (url.startsWith("http://") || url.startsWith("https://")) return fallback
    var p = if (url.startsWith("/")) url else "/$url"
    val base = basePath(app.client.baseUrl(s)).ifEmpty { basePath(s.url) }
    if (base.isNotEmpty() && p.startsWith("$base/")) p = p.removePrefix(base)
    if (size != null) p = p.replaceFirst("/$type.jpg", "/$type-$size.jpg")
    return p
}

private fun basePath(url: String): String = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault("")
