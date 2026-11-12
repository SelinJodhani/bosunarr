package app.bosunarr.security

import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.ServiceClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyAuthTest {
    @Test
    fun headerNameValidation() {
        assertNull(ProxyAuth.nameError("CF-Access-Client-Id"))
        assertNull(ProxyAuth.nameError("X_Custom.Header~1"))
        assertNotNull(ProxyAuth.nameError(""))
        assertNotNull(ProxyAuth.nameError("Bad Header"))
        assertNotNull(ProxyAuth.nameError("Bad:Header"))
        assertNotNull(ProxyAuth.nameError("X-Ünicode"))
        assertNotNull(ProxyAuth.nameError("Host"))
        assertNotNull(ProxyAuth.nameError("content-length"))
    }

    @Test
    fun headerValueValidation() {
        assertNull(ProxyAuth.valueError("abc.def-123=="))
        assertNull(ProxyAuth.valueError("with space\tand tab"))
        assertNotNull(ProxyAuth.valueError("a\r\nX-Injected: 1"))
        assertNotNull(ProxyAuth.valueError("line\nbreak"))
        assertNotNull(ProxyAuth.valueError("nul\u0000"))
        assertNotNull(ProxyAuth.valueError("café"))
    }

    @Test
    fun basicAuthValidationAndEncoding() {
        assertNotNull(ProxyAuth.basicUserError("a:b"))
        assertNull(ProxyAuth.basicUserError("alice"))
        assertNotNull(ProxyAuth.basicPasswordError("pw\n"))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:pä:ss".toByteArray()), ProxyAuth.basicHeader("alice", "pä:ss"))
        assertNull(ProxyAuth.basicHeader(null, "x"))
        assertNull(ProxyAuth.basicHeader("", "x"))
    }

    @Test
    fun duplicateNamesAreReported() {
        val errors = ProxyAuth.headerErrors(listOf("X-A" to "1", "x-a" to "2"))
        assertEquals(1, errors.size)
    }

    @Test
    fun mergePrecedence() {
        val own = mapOf("X-Api-Key" to "service-key")
        // Proxy basic auth is added; a custom header with a different name never touches the API key.
        val m = ProxyAuth.merge(own, mapOf("X-Api-Key-Proxy" to "p", "CF-Access-Client-Id" to "cid"), "u", "pw")
        assertEquals("service-key", m["X-Api-Key"])
        assertEquals("p", m["X-Api-Key-Proxy"])
        assertEquals(ProxyAuth.basicHeader("u", "pw"), m["Authorization"])
        // Exactly the same name (any case) is an explicit override.
        val o = ProxyAuth.merge(own, mapOf("x-api-key" to "override"), null, null)
        assertEquals(1, o.size)
        assertEquals("override", o["x-api-key"])
        // Basic auth never replaces the service's own Authorization (NZBGet's login).
        val n = ProxyAuth.merge(mapOf("Authorization" to "Basic own"), emptyMap(), "u", "pw")
        assertEquals("Basic own", n["Authorization"])
        // Invalid stored headers (e.g. a hand-edited restore) are skipped instead of crashing OkHttp.
        val bad = ProxyAuth.merge(own, mapOf("Bad Name" to "x", "X-Ok" to "a\nb"), null, null)
        assertEquals(own, bad)
    }

    /** End to end through ServiceClient: what actually goes on the wire. */
    private class Capture : Interceptor {
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            requests += req
            val body = if (req.url.encodedPath.endsWith("/auth/login")) "Ok." else """{"version":"4.0.0"}"""
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .apply { if (req.url.encodedPath.endsWith("/auth/login")) header("Set-Cookie", "SID=abc; HttpOnly") }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun clientWith(capture: Capture) = ServiceClient(OkHttpClient.Builder().addInterceptor(capture).build())

    @Test
    fun serviceClientSendsProxyHeadersOnEveryRequest() = runBlocking {
        val cap = Capture()
        val client = clientWith(cap)
        val s = ServiceConfig(
            id = "s", type = "sonarr", name = "Sonarr", url = "https://sonarr.example.com", apiKey = "KEY",
            headers = mapOf("CF-Access-Client-Id" to "cid.access", "CF-Access-Client-Secret" to "csecret"),
            basicUser = "proxy", basicPassword = "pw",
        )
        client.text(s, "/api/v3/system/status")
        val r = cap.requests.single()
        assertEquals("KEY", r.header("X-Api-Key"))
        assertEquals("cid.access", r.header("CF-Access-Client-Id"))
        assertEquals("csecret", r.header("CF-Access-Client-Secret"))
        assertEquals(ProxyAuth.basicHeader("proxy", "pw"), r.header("Authorization"))
        // Images use the same map.
        assertEquals("csecret", client.authHeaders(s)["CF-Access-Client-Secret"])
    }

    @Test
    fun withoutExtrasNothingChanges() = runBlocking {
        val cap = Capture()
        val client = clientWith(cap)
        val s = ServiceConfig(id = "s", type = "radarr", name = "Radarr", url = "http://radarr", apiKey = "KEY")
        client.text(s, "/api/v3/system/status")
        val r = cap.requests.single()
        assertEquals("KEY", r.header("X-Api-Key"))
        assertNull(r.header("Authorization"))
        assertEquals(mapOf("X-Api-Key" to "KEY"), client.authHeaders(s))
    }

    @Test
    fun jellyfinTokenMovesAsideForProxyBasicAuth() {
        val client = clientWith(Capture())
        val plain = ServiceConfig(id = "j", type = "jellyfin", name = "J", url = "http://j", apiKey = "TOKEN")
        assertTrue(client.authHeaders(plain)["Authorization"]!!.startsWith("MediaBrowser"))
        val proxied = plain.copy(basicUser = "u", basicPassword = "p")
        val h = client.authHeaders(proxied)
        assertTrue(h["X-Emby-Authorization"]!!.contains("Token=\"TOKEN\""))
        assertEquals(ProxyAuth.basicHeader("u", "p"), h["Authorization"])
    }

    @Test
    fun qbittorrentLoginCarriesProxyHeaders() = runBlocking {
        val cap = Capture()
        val client = clientWith(cap)
        val s = ServiceConfig(
            id = "q", type = "qbittorrent", name = "qBit", url = "https://qbit.example.com", username = "admin", password = "pw",
            headers = mapOf("CF-Access-Client-Id" to "cid"), basicUser = "proxy", basicPassword = "ppw",
        )
        client.text(s, "/api/v2/app/version")
        assertEquals(2, cap.requests.size)
        cap.requests.forEach { r ->
            assertEquals("cid", r.header("CF-Access-Client-Id"))
            assertEquals(ProxyAuth.basicHeader("proxy", "ppw"), r.header("Authorization"))
        }
        assertFalse(cap.requests[1].header("Cookie").isNullOrEmpty())
    }
}
