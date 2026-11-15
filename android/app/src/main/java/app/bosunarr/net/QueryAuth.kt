package app.bosunarr.net

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Service keys that travel in the URL instead of a header. Pure (OkHttp's HttpUrl only, no Android): unit-tested.
 * URLs built here carry a secret, so they are never logged.
 */

/**
 * Jellyfin reads its token from `Authorization`. When that header is taken by the reverse proxy (basic auth, or a
 * custom `Authorization` header), the token has to go elsewhere: Jellyfin 12 no longer accepts
 * `X-Emby-Authorization` or `api_key`, only the `ApiKey` query parameter.
 */
fun jellyfinTokenInQuery(s: ServiceConfig): Boolean =
    s.serviceType == ServiceType.Jellyfin &&
        (!s.basicUser.isNullOrEmpty() || s.headers.keys.any { it.trim().equals("Authorization", ignoreCase = true) })

/**
 * [url] with the service's key added as a query parameter where the service needs it: `apikey` for SABnzbd and
 * Tautulli (they read it nowhere else), `ApiKey` for Jellyfin behind a proxy that uses `Authorization` (see
 * [jellyfinTokenInQuery]). Every other URL is returned unchanged.
 */
fun withQueryAuth(s: ServiceConfig, url: String): String {
    val param = when {
        s.serviceType == ServiceType.Sabnzbd || s.serviceType == ServiceType.Tautulli -> "apikey"
        jellyfinTokenInQuery(s) -> "ApiKey"
        else -> return url
    }
    val parsed = url.toHttpUrlOrNull() ?: return url
    return parsed.newBuilder().setQueryParameter(param, s.apiKey.orEmpty()).build().toString()
}
