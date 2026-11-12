package app.bosunarr.feature.music

import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.library.ArrImage
import app.bosunarr.feature.library.ArrQuality
import app.bosunarr.feature.library.ArrRatings
import app.bosunarr.feature.library.LibItem
import app.bosunarr.feature.library.LibKind
import app.bosunarr.feature.library.LibStatus
import app.bosunarr.feature.library.arrImage
import app.bosunarr.feature.library.sortTitleOf
import kotlinx.serialization.Serializable

/*
 * Lidarr v1 API shapes: only the fields the app uses, every field defaulted (AppJson ignores unknown keys,
 * coerces nulls and is lenient, so a number where a string is expected still decodes).
 */

@Serializable
data class LidarrStatistics(
    val albumCount: Int = 0,
    val trackFileCount: Int = 0,
    /** Tracks of monitored albums (what Lidarr wants on disk). */
    val trackCount: Int = 0,
    val totalTrackCount: Int = 0,
    val sizeOnDisk: Long = 0,
    val percentOfTracks: Double = 0.0,
)

@Serializable
data class LidarrLink(val url: String? = null, val name: String? = null)

@Serializable
data class LidarrArtist(
    val id: Int = 0,
    val artistName: String = "",
    val sortName: String? = null,
    val foreignArtistId: String? = null,
    val overview: String? = null,
    val artistType: String? = null,
    val disambiguation: String? = null,
    /** "continuing" or "ended". */
    val status: String? = null,
    val ended: Boolean = false,
    val images: List<ArrImage> = emptyList(),
    val path: String? = null,
    val qualityProfileId: Int? = null,
    val metadataProfileId: Int? = null,
    val monitored: Boolean = false,
    /** all, none or new. */
    val monitorNewItems: String? = null,
    val rootFolderPath: String? = null,
    val genres: List<String> = emptyList(),
    val added: String? = null,
    val ratings: ArrRatings? = null,
    val statistics: LidarrStatistics? = null,
    val links: List<LidarrLink> = emptyList(),
)

@Serializable
data class LidarrAlbum(
    val id: Int = 0,
    val title: String = "",
    val disambiguation: String? = null,
    val overview: String? = null,
    val artistId: Int = 0,
    val foreignAlbumId: String? = null,
    val monitored: Boolean = false,
    /** Album, EP, Single, Broadcast, Other. */
    val albumType: String? = null,
    /** Milliseconds. */
    val duration: Long = 0,
    val releaseDate: String? = null,
    val genres: List<String> = emptyList(),
    val images: List<ArrImage> = emptyList(),
    val statistics: LidarrStatistics? = null,
    val ratings: ArrRatings? = null,
    val mediumCount: Int = 0,
    val artist: LidarrArtist? = null,
)

@Serializable
data class LidarrTrack(
    val id: Int = 0,
    val artistId: Int = 0,
    val albumId: Int = 0,
    val trackFileId: Int = 0,
    /** A string in Lidarr ("1", "A1" for vinyl sides). */
    val trackNumber: String? = null,
    val absoluteTrackNumber: Int = 0,
    val title: String? = null,
    /** Milliseconds. */
    val duration: Long = 0,
    val mediumNumber: Int = 1,
    val hasFile: Boolean = false,
    val explicit: Boolean = false,
)

@Serializable
data class LidarrTrackFile(val id: Int = 0, val path: String? = null, val size: Long = 0, val dateAdded: String? = null, val quality: ArrQuality? = null)

@Serializable
data class LidarrMetadataProfile(val id: Int = 0, val name: String = "")

@Serializable
data class LidarrRootFolder(
    val id: Int = 0,
    val name: String? = null,
    val path: String = "",
    val freeSpace: Long? = null,
    val accessible: Boolean = true,
    val defaultQualityProfileId: Int? = null,
    val defaultMetadataProfileId: Int? = null,
    val defaultMonitorOption: String? = null,
    val defaultNewItemMonitorOption: String? = null,
)

/* ---------------------------------------------------------------- library item */

fun artistStatus(a: LidarrArtist): LibStatus {
    val have = a.statistics?.trackFileCount ?: 0
    val want = a.statistics?.trackCount ?: 0
    return when {
        want == 0 -> if (have > 0) LibStatus.Downloaded else if (a.monitored) LibStatus.Upcoming else LibStatus.Unmonitored
        have >= want -> LibStatus.Downloaded
        !a.monitored -> if (have > 0) LibStatus.Partial else LibStatus.Unmonitored
        have == 0 -> LibStatus.Missing
        else -> LibStatus.Partial
    }
}

fun artistItem(s: ServiceConfig, a: LidarrArtist): LibItem = LibItem(
    serviceId = s.id, serviceName = s.name, kind = LibKind.Music, id = a.id,
    title = a.artistName, sortTitle = sortTitleOf(a.artistName, a.sortName), year = null,
    poster = artistPoster(s, a), monitored = a.monitored, status = artistStatus(a),
    sizeOnDisk = a.statistics?.sizeOnDisk ?: 0, added = a.added,
    have = a.statistics?.trackFileCount ?: 0, total = a.statistics?.trackCount ?: 0,
    is4k = false, network = a.genres.firstOrNull(), tmdbId = 0, tvdbId = 0,
    albums = a.statistics?.albumCount ?: 0,
)

/** Downloaded / partly / missing / not released yet, for one album. */
fun albumStatus(a: LidarrAlbum, today: java.time.LocalDate = java.time.LocalDate.now()): LibStatus {
    val have = a.statistics?.trackFileCount ?: 0
    val want = a.statistics?.trackCount?.takeIf { it > 0 } ?: a.statistics?.totalTrackCount ?: 0
    val released = releaseDay(a.releaseDate)?.let { !it.isAfter(today) } ?: true
    return when {
        want > 0 && have >= want -> LibStatus.Downloaded
        !released -> LibStatus.Upcoming
        !a.monitored -> if (have > 0) LibStatus.Partial else LibStatus.Unmonitored
        have == 0 -> LibStatus.Missing
        else -> LibStatus.Partial
    }
}

fun releaseDay(iso: String?): java.time.LocalDate? =
    iso?.takeIf { it.length >= 10 }?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }

/* ---------------------------------------------------------------- images */

/** Artist poster on Lidarr (cached at `/MediaCover/<artistId>/poster…` like Sonarr/Radarr). */
fun artistPoster(s: ServiceConfig, a: LidarrArtist, size: Int? = 500): String? = arrImage(s, a.images, "poster", size, a.id)

fun artistFanart(s: ServiceConfig, a: LidarrArtist): String? = arrImage(s, a.images, "fanart", ownerId = a.id)

/**
 * Album cover on Lidarr (`/MediaCover/Albums/<albumId>/cover.<ext>`). The original is used (not a resized
 * variant): album covers keep the source file's extension, so a guessed `-500.jpg` name could miss.
 */
fun albumCover(s: ServiceConfig, images: List<ArrImage>, albumId: Int): String? {
    val url = images.firstOrNull { it.coverType == "cover" }?.url?.takeIf { it.isNotBlank() }
    if (url == null || url.startsWith("http://") || url.startsWith("https://")) {
        return if (albumId > 0) "/MediaCover/Albums/$albumId/cover.jpg" else null
    }
    return arrImage(s, images, "cover")
}
