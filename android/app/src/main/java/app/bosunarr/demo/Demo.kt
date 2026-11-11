package app.bosunarr.demo

import app.bosunarr.app
import app.bosunarr.data.AppState
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import java.net.URI

/**
 * Demo mode: a complete, fake media stack so someone without a server can see what the app does.
 *
 * The demo services are ordinary [ServiceConfig]s whose addresses use the reserved `.invalid` TLD
 * (`http://demo-sonarr.bosunarr.invalid`); [DemoInterceptor] answers those requests from bundled fixtures, so every
 * feature runs its real code path. Entering the demo parks the user's own services in [AppState.realServices];
 * leaving puts them back. Demo services are never written into that list.
 */
object Demo {
    const val DOMAIN = "bosunarr.invalid"
    const val DOMAIN_SUFFIX = ".$DOMAIN"
    /** Dummy credential for every demo service. */
    private const val KEY = "demo"

    private fun svc(type: ServiceType, name: String = type.label, user: Boolean = false) = ServiceConfig(
        id = "demo-${type.id}",
        type = type.id,
        name = name,
        url = "http://demo-${type.id}$DOMAIN_SUFFIX",
        apiKey = if (user) null else KEY,
        username = if (user) KEY else null,
        password = if (user) KEY else null,
    )

    /** The demo stack (no Whisparr, no Overseerr). */
    val services: List<ServiceConfig> = listOf(
        svc(ServiceType.Sonarr),
        svc(ServiceType.Radarr),
        svc(ServiceType.Prowlarr),
        svc(ServiceType.Bazarr),
        svc(ServiceType.Jellyseerr),
        svc(ServiceType.QBittorrent, user = true),
        svc(ServiceType.Jellyfin),
        svc(ServiceType.Jellystat),
        svc(ServiceType.Wizarr),
    )

    fun isDemoUrl(url: String?): Boolean =
        url != null && runCatching { URI(url).host }.getOrNull()?.endsWith(DOMAIN_SUFFIX) == true

    fun isDemo(s: ServiceConfig): Boolean = isDemoUrl(s.url)

    /** State with the demo stack in place of the user's services (a no-op if already in the demo). */
    fun enterState(st: AppState): AppState =
        if (st.demo) st else st.copy(demo = true, realServices = st.services, services = services, onboarded = true)

    /** State with the user's own services back. Any demo service that slipped into the list is dropped. */
    fun exitState(st: AppState): AppState {
        if (!st.demo) return st
        val real = st.realServices.orEmpty().filterNot(::isDemo)
        // Nothing of their own to go back to: the welcome screen comes back (now, and on the next launch).
        return st.copy(demo = false, services = real, realServices = null, onboarded = st.onboarded && real.isNotEmpty())
    }

    /** Start the demo with fresh fake data. */
    suspend fun enter() {
        DemoWorld.reset()
        services.forEach { app.client.reset(it.id) }
        app.updateStateNow(::enterState)
    }

    /** Leave the demo; true when the user had services of their own to go back to. */
    suspend fun exit(): Boolean {
        var hadReal = false
        app.updateStateNow { st -> exitState(st).also { hadReal = it.services.isNotEmpty() } }
        services.forEach { app.client.reset(it.id) }
        return hadReal
    }
}
