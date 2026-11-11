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
    /*
     * Server (SSH) only. The user is [username]; host and port are in [url] as `ssh://host:port`. With key auth,
     * [password] holds the key's passphrase (if any); with password auth, the login password.
     */
    /** "key" (default) or "password". */
    val sshAuth: String? = null,
    /** Private key, OpenSSH or PEM text. */
    val sshPrivateKey: String? = null,
    /** Pinned host key, "<type> SHA256:<base64>" (trust on first use). */
    val sshHostKey: String? = null,
    /** Saved commands for the Server screen's Commands tab. */
    val sshCommands: List<SshCommand> = emptyList(),
) {
    val serviceType: ServiceType? get() = ServiceType.of(type)
}

/** A user-defined command run over SSH (after a confirmation), e.g. `docker compose pull` in a stack folder. */
@Serializable
data class SshCommand(val id: String, val name: String, val command: String)

@Serializable
data class AppState(
    val services: List<ServiceConfig> = emptyList(),
    val dashboard: List<DashboardWidget>? = null,
    val themeMode: String = "system",
    /** Set once the first-run setup has been finished or skipped. */
    val onboarded: Boolean = false,
    /** Last automatic update check (epoch ms) and a release the user chose to skip. */
    val lastUpdateCheck: Long = 0,
    val skippedVersion: String? = null,
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
