package app.bosunarr.data

import androidx.compose.ui.graphics.Color

enum class Credentials { ApiKey, UserPass, None }

enum class ServiceCategory(val label: String) {
    Media("Media managers"), Requests("Requests"), Indexers("Indexers"), Subtitles("Subtitles"),
    Downloads("Downloads"), Server("Media server"), Stats("Statistics"), Users("Users & invites")
}

/** Every service Bosunarr can talk to. `id` is the stable key stored on disk. */
enum class ServiceType(
    val id: String,
    val label: String,
    val category: ServiceCategory,
    val credentials: Credentials,
    val defaultPort: Int,
    val keyHint: String,
    val color: Color,
    /** What it does, in a word or two ("TV shows", "Downloads"). */
    val role: String,
) {
    Sonarr("sonarr", "Sonarr", ServiceCategory.Media, Credentials.ApiKey, 8989, "Sonarr → Settings → General → API Key", Color(0xFF35C5F4), "TV shows"),
    Radarr("radarr", "Radarr", ServiceCategory.Media, Credentials.ApiKey, 7878, "Radarr → Settings → General → API Key", Color(0xFFFFC230), "Movies"),
    Whisparr("whisparr", "Whisparr", ServiceCategory.Media, Credentials.ApiKey, 6969, "Whisparr → Settings → General → API Key", Color(0xFFE85A9B), "Adult"),
    Prowlarr("prowlarr", "Prowlarr", ServiceCategory.Indexers, Credentials.ApiKey, 9696, "Prowlarr → Settings → General → API Key", Color(0xFFE66001), "Indexers"),
    Bazarr("bazarr", "Bazarr", ServiceCategory.Subtitles, Credentials.ApiKey, 6767, "Bazarr → Settings → General → API Key", Color(0xFF14B8A6), "Subtitles"),
    Jellyseerr("jellyseerr", "Jellyseerr", ServiceCategory.Requests, Credentials.ApiKey, 5055, "Jellyseerr → Settings → General → API Key", Color(0xFF7C3AED), "Requests"),
    Overseerr("overseerr", "Overseerr", ServiceCategory.Requests, Credentials.ApiKey, 5055, "Overseerr → Settings → General → API Key", Color(0xFF6366F1), "Requests"),
    QBittorrent("qbittorrent", "qBittorrent", ServiceCategory.Downloads, Credentials.UserPass, 8080, "Your qBittorrent Web UI username and password", Color(0xFF2F67BA), "Downloads"),
    Jellyfin("jellyfin", "Jellyfin", ServiceCategory.Server, Credentials.ApiKey, 8096, "Jellyfin → Dashboard → API Keys", Color(0xFFAA5CC3), "Media server"),
    Jellystat("jellystat", "Jellystat", ServiceCategory.Stats, Credentials.ApiKey, 3000, "Jellystat → Settings → API Keys", Color(0xFFF472B6), "Watch stats"),
    Wizarr("wizarr", "Wizarr", ServiceCategory.Users, Credentials.ApiKey, 5690, "Wizarr → Settings → API Keys", Color(0xFFF59E0B), "Invites");

    companion object {
        fun of(id: String): ServiceType? = entries.firstOrNull { it.id == id }
    }
}
