package app.bosunarr.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which background-check notifications the user wants. Plain preferences: nothing secret lives here. */
data class NotifySettings(
    val enabled: Boolean = false,
    /** Minutes between checks: 15 (Android's minimum), 30 or 60. */
    val intervalMin: Int = 15,
    val downloaded: Boolean = true,
    val failed: Boolean = true,
    val requests: Boolean = true,
    val available: Boolean = false,
    val health: Boolean = true,
    val torrents: Boolean = false,
    /** Whisparr is skipped entirely unless this is on; its notifications never show on the lock screen. */
    val whisparr: Boolean = false,
)

/** What the last background check did, for the settings screen. */
data class NotifyStatus(val lastRun: Long = 0, val summary: String? = null)

/** Settings + last-run status, backed by SharedPreferences and exposed as flows. */
class NotifyPrefs private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("notify_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<NotifySettings> = _settings

    private val _status = MutableStateFlow(NotifyStatus(prefs.getLong("lastRun", 0), prefs.getString("lastSummary", null)))
    val status: StateFlow<NotifyStatus> = _status

    private fun read() = NotifySettings(
        enabled = prefs.getBoolean("enabled", false),
        intervalMin = prefs.getInt("intervalMin", 15).takeIf { it in INTERVALS } ?: 15,
        downloaded = prefs.getBoolean("downloaded", true),
        failed = prefs.getBoolean("failed", true),
        requests = prefs.getBoolean("requests", true),
        available = prefs.getBoolean("available", false),
        health = prefs.getBoolean("health", true),
        torrents = prefs.getBoolean("torrents", false),
        whisparr = prefs.getBoolean("whisparr", false),
    )

    @Synchronized
    fun update(transform: (NotifySettings) -> NotifySettings): NotifySettings {
        val s = transform(_settings.value)
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putInt("intervalMin", s.intervalMin)
            .putBoolean("downloaded", s.downloaded)
            .putBoolean("failed", s.failed)
            .putBoolean("requests", s.requests)
            .putBoolean("available", s.available)
            .putBoolean("health", s.health)
            .putBoolean("torrents", s.torrents)
            .putBoolean("whisparr", s.whisparr)
            .apply()
        _settings.value = s
        return s
    }

    fun recordRun(summary: String) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("lastRun", now).putString("lastSummary", summary).apply()
        _status.value = NotifyStatus(now, summary)
    }

    companion object {
        val INTERVALS = listOf(15, 30, 60)

        @Volatile private var instance: NotifyPrefs? = null
        fun get(context: Context): NotifyPrefs =
            instance ?: synchronized(this) { instance ?: NotifyPrefs(context.applicationContext).also { instance = it } }
    }
}
