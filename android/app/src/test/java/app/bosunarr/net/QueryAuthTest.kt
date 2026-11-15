package app.bosunarr.net

import app.bosunarr.data.ServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryAuthTest {
    private fun svc(type: String, basicUser: String? = null, headers: Map<String, String> = emptyMap()) =
        ServiceConfig(id = "x", type = type, name = "X", url = "http://h:8096", apiKey = "k3y", basicUser = basicUser, headers = headers)

    @Test fun jellyfinWithoutProxyAuthKeepsTheHeaderOnly() {
        val s = svc("jellyfin")
        assertFalse(jellyfinTokenInQuery(s))
        assertEquals("http://h:8096/System/Info", withQueryAuth(s, "http://h:8096/System/Info"))
    }

    @Test fun jellyfinBehindBasicAuthGetsApiKeyInTheQuery() {
        val s = svc("jellyfin", basicUser = "me")
        assertTrue(jellyfinTokenInQuery(s))
        assertEquals("http://h:8096/Items?Limit=5&ApiKey=k3y", withQueryAuth(s, "http://h:8096/Items?Limit=5"))
    }

    @Test fun customAuthorizationHeaderAlsoMovesTheToken() {
        assertTrue(jellyfinTokenInQuery(svc("jellyfin", headers = mapOf(" authorization " to "Bearer t"))))
        assertFalse(jellyfinTokenInQuery(svc("jellyfin", headers = mapOf("CF-Access-Client-Id" to "id"))))
    }

    @Test fun otherServicesBehindBasicAuthAreUnchanged() {
        val s = svc("sonarr", basicUser = "me")
        assertEquals("http://h:8989/api/v3/queue", withQueryAuth(s, "http://h:8989/api/v3/queue"))
    }

    @Test fun sabnzbdAndTautulliStillUseApikey() {
        assertEquals("http://h/api?mode=queue&apikey=k3y", withQueryAuth(svc("sabnzbd"), "http://h/api?mode=queue"))
        assertEquals("http://h/api/v2?cmd=status&apikey=k3y", withQueryAuth(svc("tautulli"), "http://h/api/v2?cmd=status"))
    }

    @Test fun existingKeyIsReplacedNotDuplicated() {
        assertEquals("http://h/x?ApiKey=k3y", withQueryAuth(svc("jellyfin", basicUser = "me"), "http://h/x?ApiKey=old"))
    }
}
