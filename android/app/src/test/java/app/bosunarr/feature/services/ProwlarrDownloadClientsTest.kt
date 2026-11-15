package app.bosunarr.feature.services

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProwlarrDownloadClientsTest {
    private fun arrList(json: String) = AppJson.parseToJsonElement(json) as JsonArray

    private val radarr = arrList("""[{"implementation":"QBittorrent","enable":true,"fields":[
        {"name":"host","value":"qbittorrent"},{"name":"port","value":8080},{"name":"useSsl","value":false},
        {"name":"urlBase","value":null},{"name":"movieCategory","value":"radarr"}]}]""")
    private val radarr4k = arrList("""[{"implementation":"QBittorrent","enable":true,"fields":[
        {"name":"host","value":"qbittorrent"},{"name":"port","value":8080},{"name":"movieCategory","value":"radarr-4k"}]}]""")
    private val sonarr = arrList("""[{"implementation":"QBittorrent","enable":true,"fields":[
        {"name":"host","value":"qbittorrent"},{"name":"port","value":8080},{"name":"tvCategory","value":"sonarr"}]},
        {"implementation":"Sabnzbd","enable":true,"fields":[{"name":"host","value":"sabnzbd"},{"name":"tvCategory","value":"tv"}]}]""")

    @Test fun mappingSendsUhdToThe4kCategory() {
        val m = categoryMappings("radarr", "radarr-4k", "sonarr")
        assertEquals(listOf(MOVIE_UHD), m.first { it.clientCategory == "radarr-4k" }.categories)
        assertFalse(MOVIE_UHD in m.first { it.clientCategory == "radarr" }.categories)
        assertEquals(TV_CATS, m.first { it.clientCategory == "sonarr" }.categories)
        assertEquals("4K movies → radarr-4k · Movies → radarr · TV → sonarr", mappingSummary(m))
    }

    @Test fun withoutA4kCategoryUhdStaysWithMovies() {
        val m = categoryMappings("radarr", " ", null)
        assertEquals(1, m.size)
        assertTrue(MOVIE_UHD in m[0].categories)
        assertNull(mappingSummary(categoryMappings(null, null, "")))
    }

    @Test fun defaultsComeFromTheArrsServerSideConnection() {
        val d = clientDefaults(
            "QBittorrent", "https://qbit.tailnet.example",
            listOf(ArrClients(ServiceType.Radarr, "Radarr", radarr), ArrClients(ServiceType.Radarr, "Radarr 4K", radarr4k), ArrClients(ServiceType.Sonarr, "Sonarr", sonarr)),
        )
        assertEquals("qbittorrent", d.host)
        assertEquals(8080, d.port)
        assertFalse(d.useSsl)
        assertEquals("radarr", d.movies)
        assertEquals("radarr-4k", d.movies4k)
        assertEquals("sonarr", d.tv)
    }

    @Test fun withoutArrsTheBosunarrAddressIsUsed() {
        val d = clientDefaults("Deluge", "https://deluge.example.com/web/", emptyList())
        assertEquals("deluge.example.com", d.host)
        assertEquals(443, d.port)
        assertTrue(d.useSsl)
        assertEquals("/web", d.urlBase)
        assertEquals("", d.movies)
    }

    @Test fun otherImplementationsDontLeakIn() {
        val d = clientDefaults("Sabnzbd", "http://sab:8080", listOf(ArrClients(ServiceType.Sonarr, "Sonarr", sonarr)))
        assertEquals("sabnzbd", d.host)
        assertEquals("tv", d.tv)
    }

    @Test fun payloadFillsOnlyTheSchemasFields() {
        val schema = arrList("""[{"implementation":"Deluge","configContract":"DelugeSettings","enable":false,"fields":[
            {"name":"host","value":"localhost"},{"name":"port","value":8112},{"name":"password","value":null},{"name":"category","value":"prowlarr"}]}]""")
        val src = ServiceConfig(id = "d", type = "deluge", name = "Deluge", url = "http://d:8112", username = "ignored", password = "pw")
        val values = mapOf("host" to JsonPrimitive("deluge"), "port" to JsonPrimitive(8112)) + credentialValues(src)
        val p = buildClientPayload(schema, "Deluge", " Deluge ", values, categoryMappings("radarr", null, "sonarr"))!!
        val fields = p["fields"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"] }
        assertEquals("deluge", fields["host"]!!.jsonPrimitive.content)
        assertEquals("pw", fields["password"]!!.jsonPrimitive.content)
        assertFalse("username" in fields) // Deluge has no username field: not added
        assertEquals("Deluge", p["name"]!!.jsonPrimitive.content)
        assertEquals("true", p["enable"]!!.jsonPrimitive.content)
        assertEquals("DelugeSettings", p["configContract"]!!.jsonPrimitive.content)
        val cats = p["categories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("radarr", "sonarr"), cats.map { it["clientCategory"]!!.jsonPrimitive.content })
        assertEquals(5000, cats[1]["categories"]!!.jsonArray.first().jsonPrimitive.int)
        assertNull(buildClientPayload(schema, "QBittorrent", "x", values, emptyList()))
    }

    @Test fun onlySabnzbdSendsItsApiKey() {
        val sab = ServiceConfig(id = "s", type = "sabnzbd", name = "SAB", url = "http://s", apiKey = "k")
        val qb = ServiceConfig(id = "q", type = "qbittorrent", name = "qB", url = "http://q", apiKey = "k", username = "u", password = "p")
        assertEquals("k", credentialValues(sab)["apiKey"]!!.jsonPrimitive.content)
        assertEquals(setOf("username", "password"), credentialValues(qb).keys)
    }

    @Test fun importCheckLooksAtMovieOrTvMappings() {
        val mapped = PDownloadClient(id = 1, name = "q", enable = true, categories = categoryMappings("radarr", null, null))
        val unmapped = PDownloadClient(id = 2, name = "q2", enable = true, categories = emptyList())
        assertTrue(mapsMoviesOrTv(listOf(mapped)))
        assertFalse(mapsMoviesOrTv(listOf(unmapped)))
        assertFalse(mapsMoviesOrTv(listOf(mapped.copy(enable = false))))
    }
}
