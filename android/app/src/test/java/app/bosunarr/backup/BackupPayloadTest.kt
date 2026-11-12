package app.bosunarr.backup

import app.bosunarr.data.AppState
import app.bosunarr.data.DashboardWidget
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.SshCommand
import app.bosunarr.demo.Demo
import app.bosunarr.notify.NotifySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadTest {
    private fun svc(id: String, name: String = id, key: String = "k-$id") =
        ServiceConfig(id = id, type = "sonarr", name = name, url = "http://$id.lan:8989", apiKey = key)

    private val server = ServiceConfig(
        id = "srv", type = "server", name = "Box", url = "ssh://box:22", username = "me", sshAuth = "key",
        sshPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n",
        sshHostKey = "ssh-ed25519 SHA256:xyz", sshCommands = listOf(SshCommand("c1", "Pull", "docker compose pull")),
    )
    private val proxied = svc("p").copy(headers = mapOf("CF-Access-Client-Id" to "id", "CF-Access-Client-Secret" to "sec"), basicUser = "u", basicPassword = "pw")

    @Test
    fun fullRoundTripThroughEncryption() {
        val st = AppState(services = listOf(svc("a"), server, proxied), dashboard = listOf(DashboardWidget("w", "queue", "l")), themeMode = "dark", seedColor = 0xFF112233)
        val payload = BackupPayload.from(st, NotifySettings(enabled = true, intervalMin = 30, torrents = true), "1.2.3", 1000)
        val file = BackupCrypto.encrypt(payload.encode(), "a long passphrase", BackupCrypto.KdfParams(1024, 1, 1))
        val back = BackupPayload.decode(BackupCrypto.decrypt(file, "a long passphrase"))
        assertEquals(payload, back)
        assertEquals(listOf("docker compose pull"), back.services.first { it.id == "srv" }.sshCommands.map { it.command })
        assertEquals("sec", back.services.first { it.id == "p" }.headers["CF-Access-Client-Secret"])
        assertEquals("3 services, dashboard, appearance, notifications", back.summary())
        assertEquals(30, back.notifications!!.toSettings().intervalMin)
    }

    @Test
    fun demoModeExportsTheUsersOwnServices() {
        val st = Demo.enterState(AppState(services = listOf(svc("mine"))))
        assertTrue(st.demo)
        val payload = BackupPayload.from(st, null, "x", 0)
        assertEquals(listOf("mine"), payload.services.map { it.id })
    }

    @Test
    fun mergeAddsAndReplacesById() {
        val st = AppState(services = listOf(svc("a", key = "old"), svc("b")), onboarded = true, themeMode = "light")
        val payload = BackupPayload(services = listOf(svc("a", key = "new"), svc("c")), appearance = BackupPayload.Appearance("dark"))
        val out = restoreState(st, payload, RestoreMode.Merge, BackupPayload.Part.entries.toSet())
        assertEquals(listOf("a", "b", "c"), out.services.map { it.id })
        assertEquals("new", out.services.first { it.id == "a" }.apiKey)
        assertEquals("dark", out.themeMode)
    }

    @Test
    fun replaceAllUsesExactlyTheBackup() {
        val st = AppState(services = listOf(svc("a"), svc("b")), onboarded = true)
        val payload = BackupPayload(services = listOf(svc("c")))
        val out = restoreState(st, payload, RestoreMode.Replace, setOf(BackupPayload.Part.Services))
        assertEquals(listOf("c"), out.services.map { it.id })
    }

    @Test
    fun unselectedPartsAreKept() {
        val st = AppState(services = listOf(svc("a")), dashboard = listOf(DashboardWidget("x", "queue")), themeMode = "light")
        val payload = BackupPayload(services = listOf(svc("c")), dashboard = emptyList(), appearance = BackupPayload.Appearance("dark"))
        val out = restoreState(st, payload, RestoreMode.Replace, setOf(BackupPayload.Part.Appearance))
        assertEquals(listOf("a"), out.services.map { it.id })
        assertEquals(1, out.dashboard!!.size)
        assertEquals("dark", out.themeMode)
    }

    @Test
    fun restoringInDemoLeavesTheDemoAndOnboards() {
        val st = Demo.enterState(AppState(services = emptyList()))
        val out = restoreState(st, BackupPayload(services = listOf(svc("a"))), RestoreMode.Merge, setOf(BackupPayload.Part.Services))
        assertFalse(out.demo)
        assertNull(out.realServices)
        assertEquals(listOf("a"), out.services.map { it.id })
        assertTrue(out.onboarded)
        assertTrue(out.services.none(Demo::isDemo))
    }

    @Test
    fun newerPayloadFieldsAreIgnoredAndUnknownThemeKept() {
        val json = """{"version":9,"createdAt":5,"futureThing":{"a":1},"services":[{"id":"a","type":"sonarr","name":"S","url":"http://s","newField":true}],
            |"appearance":{"themeMode":"amoled"}}""".trimMargin()
        val p = BackupPayload.decode(json.toByteArray())
        assertEquals(9, p.version)
        assertEquals(emptyMap<String, String>(), p.services.single().headers)
        val out = restoreState(AppState(themeMode = "light"), p, RestoreMode.Merge, p.parts.toSet())
        assertEquals("light", out.themeMode)
    }

    @Test
    fun oldStoredServiceJsonStillDecodes() {
        // A ServiceConfig saved before headers/basic auth existed.
        val p = BackupPayload.decode("""{"services":[{"id":"a","type":"sonarr","name":"S","url":"http://s","apiKey":"k"}]}""".toByteArray())
        val s = p.services.single()
        assertEquals(emptyMap<String, String>(), s.headers)
        assertNull(s.basicUser)
        assertNull(s.basicPassword)
    }

    @Test(expected = BackupException.Corrupt::class)
    fun garbageContents() {
        BackupPayload.decode("not json".toByteArray())
    }
}
