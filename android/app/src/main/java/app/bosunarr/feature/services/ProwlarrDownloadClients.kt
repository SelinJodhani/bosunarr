package app.bosunarr.feature.services

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.library.UHD
import app.bosunarr.net.fanOut
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Prowlarr's own download clients: where releases grabbed from Prowlarr's search go. Separate from the arrs'
 * clients, and empty by default, so a grab from Prowlarr fails until one is added. Bosunarr sets one up from a
 * download client the user already added to Bosunarr, with Prowlarr's category mapping pointed at the categories
 * Radarr and Sonarr watch — so a release grabbed from Prowlarr is still imported into the library.
 *
 * The pure parts (mapping, defaults, payload) are unit-tested; the network wrappers are at the end.
 */

@Serializable
internal data class PCatMap(val clientCategory: String = "", val categories: List<Int> = emptyList())

@Serializable
internal data class PDownloadClient(
    val id: Int = 0,
    val name: String = "",
    val implementation: String = "",
    val implementationName: String? = null,
    val protocol: String? = null,
    val enable: Boolean = true,
    val priority: Int = 1,
    val categories: List<PCatMap> = emptyList(),
)

/** Prowlarr's implementation id for a Bosunarr download service, or null when Prowlarr has no such client. */
internal fun prowlarrImplementation(type: ServiceType?): String? = when (type) {
    ServiceType.QBittorrent -> "QBittorrent"
    ServiceType.Transmission -> "Transmission"
    ServiceType.Deluge -> "Deluge"
    ServiceType.Sabnzbd -> "Sabnzbd"
    ServiceType.Nzbget -> "Nzbget"
    else -> null
}

/** Newznab movie categories (2045 = Movies/UHD) and TV categories, as Prowlarr maps them. */
internal val MOVIE_CATS = listOf(2000, 2010, 2020, 2030, 2040, 2045, 2050, 2060, 2070, 2080, 2090)
internal const val MOVIE_UHD = 2045
internal val TV_CATS = listOf(5000, 5010, 5020, 5030, 5040, 5045, 5050, 5060, 5070, 5080, 5090)

/** Prowlarr category → client category rows. UHD movies go to [movies4k] when set, else with the other movies. */
internal fun categoryMappings(movies: String?, movies4k: String?, tv: String?): List<PCatMap> {
    val m = movies?.trim().orEmpty()
    val u = movies4k?.trim().orEmpty()
    val t = tv?.trim().orEmpty()
    return buildList {
        if (u.isNotEmpty()) add(PCatMap(u, listOf(MOVIE_UHD)))
        if (m.isNotEmpty()) add(PCatMap(m, if (u.isNotEmpty()) MOVIE_CATS - MOVIE_UHD else MOVIE_CATS))
        if (t.isNotEmpty()) add(PCatMap(t, TV_CATS))
    }
}

/** "Movies → radarr · 4K movies → radarr-4k · TV → sonarr", or null when nothing is mapped. */
internal fun mappingSummary(maps: List<PCatMap>): String? = maps.filter { it.clientCategory.isNotBlank() && it.categories.isNotEmpty() }
    .map { m ->
        val label = when {
            m.categories.all { it == MOVIE_UHD } -> "4K movies"
            m.categories.all { it / 1000 == 2 } -> "Movies"
            m.categories.all { it / 1000 == 5 } -> "TV"
            else -> "Mixed"
        }
        "$label → ${m.clientCategory}"
    }
    .joinToString(" · ").ifEmpty { null }

/** Whether a grab of a movie or TV release from Prowlarr lands in a category some arr imports from. */
internal fun mapsMoviesOrTv(clients: List<PDownloadClient>): Boolean =
    clients.any { c -> c.enable && c.categories.any { m -> m.clientCategory.isNotBlank() && m.categories.any { it / 1000 == 2 || it / 1000 == 5 } } }

/**
 * Starting values for a new Prowlarr client: the connection the arrs already use for this client (they run on the
 * server like Prowlarr, so their address — often a container name like `qbittorrent` — is the one Prowlarr needs)
 * and the categories they import from. Falls back to the address Bosunarr uses.
 */
internal data class ClientDefaults(
    val host: String = "",
    val port: Int? = null,
    val useSsl: Boolean = false,
    val urlBase: String = "",
    val movies: String = "",
    val movies4k: String = "",
    val tv: String = "",
)

/** One arr's download-client list (`/api/v3/downloadclient`), with the arr's type and name. */
internal data class ArrClients(val type: ServiceType, val name: String, val clients: JsonArray)

private fun JsonObject.fields(): Map<String, JsonElement?> =
    (this["fields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { f ->
        (f["name"] as? JsonPrimitive)?.content?.let { it to f["value"] }
    }.toMap()

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun clientDefaults(implementation: String, fallbackUrl: String, arrs: List<ArrClients>): ClientDefaults {
    var d = ClientDefaults()
    val fallback = fallbackUrl.toHttpUrlOrNull()
    if (fallback != null) {
        d = d.copy(
            host = fallback.host, port = fallback.port, useSsl = fallback.isHttps,
            urlBase = fallback.encodedPath.trimEnd('/').takeIf { it.isNotEmpty() }.orEmpty(),
        )
    }
    var connectionFromArr = false
    for (arr in arrs) {
        val match = arr.clients.mapNotNull { it as? JsonObject }.firstOrNull { c ->
            (c["implementation"] as? JsonPrimitive)?.content.equals(implementation, ignoreCase = true) &&
                (c["enable"] as? JsonPrimitive)?.content != "false"
        } ?: continue
        val f = match.fields()
        if (!connectionFromArr) {
            f["host"].text()?.let { host ->
                connectionFromArr = true
                d = d.copy(
                    host = host,
                    port = (f["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: d.port,
                    useSsl = (f["useSsl"] as? JsonPrimitive)?.content == "true",
                    urlBase = f["urlBase"].text().orEmpty(),
                )
            }
        }
        when (arr.type) {
            ServiceType.Radarr -> f["movieCategory"].text()?.let { cat ->
                if (UHD.containsMatchIn(arr.name) || UHD.containsMatchIn(cat)) { if (d.movies4k.isEmpty()) d = d.copy(movies4k = cat) }
                else if (d.movies.isEmpty()) d = d.copy(movies = cat)
            }
            ServiceType.Sonarr -> f["tvCategory"].text()?.let { cat -> if (d.tv.isEmpty()) d = d.copy(tv = cat) }
            else -> {}
        }
    }
    return d
}

/**
 * The body for `POST /api/v1/downloadclient` (and `/test`): Prowlarr's schema entry for [implementation] with
 * [values] filled into the fields that exist for it (a field the client doesn't have is skipped, e.g. a username
 * for Deluge). Null when the schema has no such implementation.
 */
internal fun buildClientPayload(
    schema: JsonArray,
    implementation: String,
    name: String,
    values: Map<String, JsonElement>,
    mappings: List<PCatMap>,
): JsonObject? {
    val entry = schema.mapNotNull { it as? JsonObject }.firstOrNull {
        (it["implementation"] as? JsonPrimitive)?.content.equals(implementation, ignoreCase = true)
    } ?: return null
    val fields = buildJsonArray {
        (entry["fields"] as? JsonArray).orEmpty().forEach { f ->
            val o = f as? JsonObject ?: return@forEach
            val key = (o["name"] as? JsonPrimitive)?.content
            val v = key?.let { values[it] }
            add(if (v == null) o else JsonObject(o + ("value" to v)))
        }
    }
    return JsonObject(
        entry + mapOf(
            "name" to JsonPrimitive(name.trim()),
            "enable" to JsonPrimitive(true),
            "priority" to JsonPrimitive(1),
            "fields" to fields,
            "categories" to buildJsonArray {
                mappings.forEach { m ->
                    add(buildJsonObject {
                        put("clientCategory", m.clientCategory)
                        putJsonArray("categories") { m.categories.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            },
        ),
    )
}

/** What Bosunarr sends from its own copy of the client's login; only fields Prowlarr has for it are used. */
internal fun credentialValues(source: ServiceConfig): Map<String, JsonElement> = buildMap {
    source.username?.takeIf { it.isNotEmpty() }?.let { put("username", JsonPrimitive(it)) }
    source.password?.takeIf { it.isNotEmpty() }?.let { put("password", JsonPrimitive(it)) }
    if (source.serviceType == ServiceType.Sabnzbd) source.apiKey?.takeIf { it.isNotEmpty() }?.let { put("apiKey", JsonPrimitive(it)) }
}

/* ================================================================ network */

internal suspend fun prowlarrClients(s: ServiceConfig): List<PDownloadClient> = app.client.get(s, "/api/v1/downloadclient")

/** Defaults for [source] from every configured Radarr/Sonarr (ones that don't answer are skipped). */
internal suspend fun loadClientDefaults(source: ServiceConfig): ClientDefaults {
    val impl = prowlarrImplementation(source.serviceType) ?: return ClientDefaults()
    val arrs = app.services.value.filter { it.serviceType == ServiceType.Radarr || it.serviceType == ServiceType.Sonarr }
    val lists = fanOut(arrs) { a -> app.client.json(a, "/api/v3/downloadclient") as? JsonArray }
        .mapNotNull { (a, r) -> r.getOrNull()?.let { ArrClients(a.serviceType!!, a.name, it) } }
    return clientDefaults(impl, source.url, lists)
}

internal suspend fun clientSchema(s: ServiceConfig): JsonArray =
    app.client.json(s, "/api/v1/downloadclient/schema") as? JsonArray ?: JsonArray(emptyList())

/** Prowlarr connects to the client (and checks the categories) before answering; 400 carries the reason. */
internal suspend fun testProwlarrClient(s: ServiceConfig, payload: JsonObject) {
    longCall(s, "/api/v1/downloadclient/test", "POST", payload.toString(), timeoutSec = 60)
}

internal suspend fun saveProwlarrClient(s: ServiceConfig, payload: JsonObject) {
    longCall(s, "/api/v1/downloadclient", "POST", payload.toString(), timeoutSec = 60)
}

internal suspend fun deleteProwlarrClient(s: ServiceConfig, id: Int) {
    app.client.send(s, "/api/v1/downloadclient/$id", "DELETE")
}
