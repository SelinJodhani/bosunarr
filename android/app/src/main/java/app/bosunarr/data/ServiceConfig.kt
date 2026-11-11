package app.bosunarr.data

import kotlinx.serialization.Serializable

/**
 * One configured service. `url` is how the phone reaches it (LAN, tailnet or public); `altUrl` is an optional
 * second address tried when the first is unreachable (e.g. home LAN vs. Tailscale). Secrets never leave the
 * device: the whole list is stored encrypted (see [SecureStore]).
 */
@Serializable
data class ServiceConfig(
    val id: String,
    val type: String,
    val name: String,
    val url: String,
    val altUrl: String? = null,
    val apiKey: String? = null,
    val username: String? = null,
    val password: String? = null,
    val sort: Int = 0,
) {
    val serviceType: ServiceType? get() = ServiceType.of(type)
}

@Serializable
data class AppState(
    val services: List<ServiceConfig> = emptyList(),
    val dashboard: List<DashboardWidget>? = null,
    val themeMode: String = "system",
    val seedColor: Long? = null,
)

@Serializable
data class DashboardWidget(val id: String, val kind: String, val size: String = "m")

/** Normalise what the user typed: trim, add http:// when there's no scheme, drop trailing slashes. */
fun normalizeUrl(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return s
    if (!s.contains("://")) s = "http://$s"
    return s.trimEnd('/')
}
