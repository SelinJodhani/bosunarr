package app.bosunarr.backup

import app.bosunarr.data.AppState
import app.bosunarr.data.DashboardWidget
import app.bosunarr.data.ServiceConfig
import app.bosunarr.demo.Demo
import app.bosunarr.notify.NotifySettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a backup holds (encrypted by [BackupCrypto]): the user's own services with their secrets and saved SSH
 * commands, the dashboard layout, appearance and notification choices. Not included: demo services, update-check
 * bookkeeping, app-lock settings (device-specific) and anything cached.
 *
 * [version] is bumped when the meaning of a field changes; new optional fields don't need a bump (unknown keys
 * are ignored on read, missing ones take their defaults).
 */
@Serializable
data class BackupPayload(
    val version: Int = VERSION,
    val createdAt: Long = 0,
    val appVersion: String = "",
    val services: List<ServiceConfig> = emptyList(),
    val dashboard: List<DashboardWidget>? = null,
    val appearance: Appearance? = null,
    val notifications: NotificationBackup? = null,
) {
    @Serializable
    data class Appearance(val themeMode: String = "system", val seedColor: Long? = null)

    /** Mirror of [NotifySettings] (kept separate so renaming that class can't break old backups). */
    @Serializable
    data class NotificationBackup(
        val enabled: Boolean = false,
        val intervalMin: Int = 15,
        val downloaded: Boolean = true,
        val failed: Boolean = true,
        val requests: Boolean = true,
        val available: Boolean = false,
        val health: Boolean = true,
        val torrents: Boolean = false,
        val whisparr: Boolean = false,
    ) {
        fun toSettings() = NotifySettings(enabled, intervalMin, downloaded, failed, requests, available, health, torrents, whisparr)

        companion object {
            fun of(s: NotifySettings) = NotificationBackup(s.enabled, s.intervalMin, s.downloaded, s.failed, s.requests, s.available, s.health, s.torrents, s.whisparr)
        }
    }

    /** Which parts the user chose to restore. */
    enum class Part { Services, Dashboard, Appearance, Notifications }

    /** Parts this backup actually contains. */
    val parts: List<Part>
        get() = buildList {
            if (services.isNotEmpty()) add(Part.Services)
            if (dashboard != null) add(Part.Dashboard)
            if (appearance != null) add(Part.Appearance)
            if (notifications != null) add(Part.Notifications)
        }

    /** "12 services, dashboard, appearance, notifications". */
    fun summary(): String = parts.joinToString(", ") {
        when (it) {
            Part.Services -> if (services.size == 1) "1 service" else "${services.size} services"
            Part.Dashboard -> "dashboard"
            Part.Appearance -> "appearance"
            Part.Notifications -> "notifications"
        }
    }.ifEmpty { "nothing" }

    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun decode(bytes: ByteArray): BackupPayload = try {
            json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException.Corrupt("unreadable contents")
        }

        /** Snapshot of [st] for export. In demo mode the user's own services (parked in realServices) are exported. */
        fun from(st: AppState, notify: NotifySettings?, appVersion: String, now: Long): BackupPayload {
            val own = (if (st.demo) st.realServices.orEmpty() else st.services).filterNot(Demo::isDemo)
            return BackupPayload(
                createdAt = now,
                appVersion = appVersion,
                services = own,
                dashboard = st.dashboard,
                appearance = Appearance(st.themeMode, st.seedColor),
                notifications = notify?.let(NotificationBackup::of),
            )
        }
    }
}

enum class RestoreMode { Merge, Replace }

/**
 * [st] with [payload] applied. Services: [RestoreMode.Merge] adds new ones and replaces those with the same id;
 * [RestoreMode.Replace] makes the list exactly the backup's. Dashboard and appearance are taken from the backup
 * when selected. Demo mode is left first (the restored services are the user's own). Notification settings live
 * outside [AppState]; the caller applies them.
 */
fun restoreState(st: AppState, payload: BackupPayload, mode: RestoreMode, parts: Set<BackupPayload.Part>): AppState {
    val base = Demo.exitState(st)
    val incoming = payload.services.filterNot(Demo::isDemo)
    val services = if (BackupPayload.Part.Services !in parts) base.services else when (mode) {
        RestoreMode.Replace -> incoming
        RestoreMode.Merge -> {
            val byId = incoming.associateBy { it.id }
            base.services.map { byId[it.id] ?: it } + incoming.filter { s -> base.services.none { it.id == s.id } }
        }
    }
    var out = base.copy(services = services, onboarded = base.onboarded || services.isNotEmpty())
    if (BackupPayload.Part.Dashboard in parts && payload.dashboard != null) out = out.copy(dashboard = payload.dashboard)
    if (BackupPayload.Part.Appearance in parts) payload.appearance?.let {
        // A theme value from a newer version that this one doesn't know keeps the current theme.
        out = out.copy(themeMode = it.themeMode.takeIf { m -> m in THEMES } ?: out.themeMode, seedColor = it.seedColor)
    }
    return out
}

private val THEMES = setOf("system", "light", "dark")
