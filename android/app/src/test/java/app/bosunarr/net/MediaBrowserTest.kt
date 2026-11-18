package app.bosunarr.net

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.security.ProxyAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Emby and Jellyfin differ: auth, the /emby prefix and the endpoints Emby only serves as pages. */
class MediaBrowserTest {
    private fun svc(type: String, url: String = "http://h:8096", basicUser: String? = null, headers: Map<String, String> = emptyMap()) =
        ServiceConfig(id = "x", type = type, name = "X", url = url, apiKey = "k3y", basicUser = basicUser, basicPassword = "pw", headers = headers)

    @Test fun embyIsItsOwnType() {
        val t = ServiceType.of("emby")!!
        assertEquals(ServiceType.Emby, t)
        assertEquals("Emby", t.label)
        assertEquals(8096, t.defaultPort)
        assertTrue(ServiceType.Emby in ServiceType.offered)
        assertTrue(isEmby(svc("emby")))
        assertFalse(isEmby(svc("jellyfin")))
    }

    @Test fun embyPathsGetTheEmbyPrefix() {
        val s = svc("emby")
        assertEquals("/emby/System/Info", mediaBrowserPath(s, "/System/Info"))
        assertEquals("/emby/Users/Query", mediaBrowserUsersPath(s))
        assertEquals("/emby/Library/VirtualFolders/Query", mediaBrowserLibrariesPath(s))
        assertEquals("/emby/Sessions", mediaBrowserSessionsPath(s))
        assertEquals("/emby/Users/u1/Items/Latest?Limit=5", mediaBrowserLatestPath(s, "u1", "Limit=5"))
    }

    @Test fun embyUrlThatAlreadyEndsInEmbyIsNotPrefixedTwice() {
        assertEquals("/System/Info", mediaBrowserPath(svc("emby", url = "https://media.example.com/emby"), "/System/Info"))
        assertEquals("/System/Info", mediaBrowserPath(svc("emby", url = "https://media.example.com/Emby/"), "/System/Info"))
        // Only a whole path segment counts.
        assertEquals("/emby/System/Info", mediaBrowserPath(svc("emby", url = "https://myemby"), "/System/Info"))
    }

    @Test fun jellyfinPathsAreUnchanged() {
        val s = svc("jellyfin")
        assertEquals("/System/Info", mediaBrowserPath(s, "/System/Info"))
        assertEquals("/Users", mediaBrowserUsersPath(s))
        assertEquals("/Library/VirtualFolders", mediaBrowserLibrariesPath(s))
        assertEquals("/Sessions?activeWithinSeconds=960", mediaBrowserSessionsPath(s))
        assertEquals("/Items/Latest?userId=u1&Limit=5", mediaBrowserLatestPath(s, "u1", "Limit=5"))
        // Not a media server at all: untouched.
        assertEquals("/api/v3/queue", mediaBrowserPath(svc("sonarr"), "/api/v3/queue"))
    }

    @Test fun embyTokenHeaderLeavesAuthorizationToTheProxy() {
        val own = embyAuthHeaders("k3y")
        assertEquals(mapOf("X-Emby-Token" to "k3y"), own)
        val merged = ProxyAuth.merge(own, emptyMap(), "me", "pw")
        assertEquals("k3y", merged["X-Emby-Token"])
        assertTrue(merged["Authorization"]!!.startsWith("Basic "))
    }

    @Test fun embyBehindBasicAuthKeepsTheKeyOutOfTheUrl() {
        val s = svc("emby", basicUser = "me", headers = mapOf("Authorization" to "Bearer t"))
        assertFalse(jellyfinTokenInQuery(s))
        assertEquals("http://h:8096/emby/Items?Limit=5", withQueryAuth(s, "http://h:8096/emby/Items?Limit=5"))
    }

    @Test fun listsComeAsArraysOrPages() {
        assertEquals(2, mediaBrowserItems(AppJson.parseToJsonElement("""[{"Id":"a"},{"Id":"b"}]""")).size)
        assertEquals(1, mediaBrowserItems(AppJson.parseToJsonElement("""{"Items":[{"Id":"a"}],"TotalRecordCount":1}""")).size)
        assertEquals(0, mediaBrowserItems(AppJson.parseToJsonElement("""{"TotalRecordCount":0}""")).size)
    }

    @Test fun idsFromBothServersAreAccepted() {
        assertTrue(isMediaBrowserId("131261")) // Emby item
        assertTrue(isMediaBrowserId("5d9db6fe9d18455c891cf84f2ab5910b")) // Emby user, Jellyfin item
        assertTrue(isMediaBrowserId("f137a2dd-21bb-c1b9-9aa5-c0f6bf02a805")) // Jellyfin, dashed
        assertFalse(isMediaBrowserId(null))
        assertFalse(isMediaBrowserId(""))
        assertFalse(isMediaBrowserId("12/../System"))
        assertFalse(isMediaBrowserId("1&IsFolder=true"))
    }
}
