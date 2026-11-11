package app.bosunarr.nav

import kotlinx.serialization.Serializable

// Top-level tabs (bottom bar).
@Serializable object DashboardRoute
@Serializable object LibraryRoute
@Serializable object DiscoverRoute
@Serializable object CalendarRoute
@Serializable object ActivityRoute

// Library (Sonarr/Radarr). serviceId = ServiceConfig.id, id = the arr's own series/movie id.
@Serializable data class SeriesRoute(val serviceId: String, val id: Int)
@Serializable data class MovieRoute(val serviceId: String, val id: Int)
/** Add a new series/movie found by search ("movie" or "series"); term pre-fills the search. */
@Serializable data class LibraryAddRoute(val kind: String, val term: String = "")

// Discover (Jellyseerr/Overseerr). mediaType = "movie" | "tv".
@Serializable data class MediaRoute(val mediaType: String, val tmdbId: Int)
@Serializable object RequestsRoute

// Settings
@Serializable object SettingsRoute
/** Edit a service (id) or add a new one of `type`. */
@Serializable data class ServiceEditRoute(val id: String? = null, val type: String? = null)

/** A service's own screen (dispatches by type: arr, prowlarr, bazarr, qbittorrent, jellyfin, jellystat, wizarr). */
@Serializable data class ServiceRoute(val id: String)
