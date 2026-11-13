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
    /*
     * Reverse-proxy extras (HTTP services only), sent with every request to this service and with its images.
     * See [app.bosunarr.security.ProxyAuth] for how they combine with the service's own auth headers.
     */
    /** Custom request headers, e.g. Cloudflare Access `CF-Access-Client-Id` / `CF-Access-Client-Secret`. */
    val headers: Map<String, String> = emptyMap(),
    /** HTTP basic auth in front of the service (the proxy's, not the service's own login). */
    val basicUser: String? = null,
    val basicPassword: String? = null,
    /**
     * Wizarr only: the public address invitees open (e.g. `https://invite.example.com`). Invite links are built on it,
     * since the address the app talks to is often private (LAN or Tailscale) and useless to the person invited.
     */
    val inviteUrl: String? = null,
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
    /** Demo mode: [services] holds the fake demo stack and the user's own services wait in [realServices]. */
    val demo: Boolean = false,
    val realServices: List<ServiceConfig>? = null,
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
