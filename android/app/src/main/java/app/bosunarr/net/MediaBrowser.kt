package app.bosunarr.net

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/*
 * Emby and Jellyfin share one API (Jellyfin forked Emby 3.5), so Bosunarr drives both with the Jellyfin screen and
 * dashboard loader. These are the places where they differ. The Emby side is checked against Emby's OpenAPI spec
 * (MediaBrowser/Emby.SDK, Resources/OpenApi/openapi_v3.json, versions 4.7.5 to 4.10.2). Pure: unit-tested.
 */

fun isEmby(s: ServiceConfig): Boolean = s.serviceType == ServiceType.Emby

/**
 * Emby reads its API key from `X-Emby-Token` (or `api_key` in the query). It's not `Authorization`, so a reverse
 * proxy's basic auth or custom `Authorization` header can sit next to it: Emby never needs the query-string
 * fallback Jellyfin 12 does (see `jellyfinTokenInQuery`), which keeps the key out of URLs and proxy logs.
 */
fun embyAuthHeaders(key: String): Map<String, String> = mapOf("X-Emby-Token" to key)

/**
 * [path] as [s] serves it. Emby documents its API under `/emby` (the spec's server URL is `…/emby`, and every Emby
 * client uses it); Jellyfin dropped the prefix. A URL the user entered with `/emby` already on the end isn't
 * prefixed twice. Other services get [path] unchanged.
 */
fun mediaBrowserPath(s: ServiceConfig, path: String): String =
    if (!isEmby(s) || s.url.trimEnd('/').endsWith("/emby", ignoreCase = true)) path else "/emby$path"

/** Users: Emby only has the paged `/Users/Query` (no `GET /Users`); Jellyfin answers `/Users` with a bare array. */
fun mediaBrowserUsersPath(s: ServiceConfig): String = mediaBrowserPath(s, if (isEmby(s)) "/Users/Query" else "/Users")

/** Libraries: Emby only has the paged `/Library/VirtualFolders/Query`; Jellyfin answers the plain path with an array. */
fun mediaBrowserLibrariesPath(s: ServiceConfig): String =
    mediaBrowserPath(s, if (isEmby(s)) "/Library/VirtualFolders/Query" else "/Library/VirtualFolders")

/**
 * Sessions. Emby has no `activeWithinSeconds` filter and lists every connected client; idle ones carry no
 * `NowPlayingItem`, so callers drop them either way.
 */
fun mediaBrowserSessionsPath(s: ServiceConfig): String =
    mediaBrowserPath(s, if (isEmby(s)) "/Sessions" else "/Sessions?activeWithinSeconds=960")

/**
 * Latest media as user [userId] sees it ([query] without a leading `?`). Emby only has the per-user route;
 * Jellyfin 10.9+ moved it to `/Items/Latest?userId=`.
 */
fun mediaBrowserLatestPath(s: ServiceConfig, userId: String, query: String): String =
    mediaBrowserPath(s, if (isEmby(s)) "/Users/$userId/Items/Latest?$query" else "/Items/Latest?userId=$userId&$query")

/** The list in [el]: a bare array (Jellyfin) or a page's `Items` (Emby's `{"Items": […], "TotalRecordCount": n}`). */
fun mediaBrowserItems(el: JsonElement): JsonArray = when (el) {
    is JsonArray -> el
    is JsonObject -> el["Items"] as? JsonArray ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

/** Decodes a list endpoint whichever shape it comes in (see [mediaBrowserItems]). */
fun <T> mediaBrowserList(item: KSerializer<T>): KSerializer<List<T>> =
    object : JsonTransformingSerializer<List<T>>(ListSerializer(item)) {
        override fun transformDeserialize(element: JsonElement): JsonElement = mediaBrowserItems(element)
    }

private val MB_ID = Regex("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$|^[0-9]{1,19}$")

/**
 * An id that is safe to put in a path or query: Jellyfin's GUIDs (with or without dashes) and Emby's user GUIDs,
 * or Emby's item ids, which are plain numbers ("131261").
 */
fun isMediaBrowserId(id: String?): Boolean = id != null && MB_ID.matches(id)
