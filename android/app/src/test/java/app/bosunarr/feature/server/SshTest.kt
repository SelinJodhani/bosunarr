package app.bosunarr.feature.server

import app.bosunarr.data.ServiceConfig
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SSH layer against an in-process Apache MINA sshd on localhost (no real servers): key generation, TOFU and
 * pinning (refused before any authentication), key and password auth, exec plumbing, streaming. Also the parsers.
 */
class SshTest {
    companion object {
        private lateinit var server: SshServer
        private lateinit var key: SshKey
        private val authAttempts = AtomicInteger()
        private var port = 0

        @BeforeClass @JvmStatic
        fun start() {
            key = Ssh.generateKey("test@bosunarr")
            val allowed = AuthorizedKeyEntry.parseAuthorizedKeyEntry(key.publicKey).resolvePublicKey(null, PublicKeyEntryResolver.FAILING)
            server = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser").also { Files.delete(it) }).apply { algorithm = "EC" }
                publickeyAuthenticator = PublickeyAuthenticator { user, k, _ -> authAttempts.incrementAndGet(); user == "tester" && KeyUtils.compareKeys(k, allowed) }
                passwordAuthenticator = PasswordAuthenticator { user, pw, _ -> authAttempts.incrementAndGet(); user == "tester" && pw == "s3cret" }
                commandFactory = ProcessShellCommandFactory.INSTANCE
            }
            server.start()
            port = server.port
        }

        @AfterClass @JvmStatic
        fun stop() = server.stop(true)
    }

    private fun cfg(pin: String? = null, auth: String = "key", password: String? = null, privateKey: String? = key.privateKey, url: String = "ssh://127.0.0.1:$port") =
        ServiceConfig(id = "t", type = "server", name = "Server", url = url, username = "tester", password = password, sshAuth = auth, sshPrivateKey = privateKey, sshHostKey = pin)

    private fun presentedPin(): HostKeyPin = runBlocking {
        try {
            Ssh.connect(cfg()).close(); fail("expected HostKeyUnknown"); throw IllegalStateException()
        } catch (e: HostKeyUnknown) {
            e.presented
        }
    }

    @Test fun generatedKeyIsEd25519InOpenSshFormat() {
        assertTrue(key.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(key.publicKey.startsWith("ssh-ed25519 AAAA"))
        assertTrue(key.publicKey.endsWith(" test@bosunarr"))
        assertEquals("ssh-ed25519", key.type)
        val again = Ssh.readKey(key.privateKey, null, "test@bosunarr")
        assertEquals(key.fingerprint, again.fingerprint)
        assertEquals(key.publicKey, again.publicKey)
    }

    @Test fun encryptedKeyNeedsTheRightPassphrase() {
        Ssh.init()
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val pem = ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, "hunter2".toByteArray()) }.toString()
        assertThrowsMessage("passphrase") { Ssh.readKey(pem, null, "") }
        assertThrowsMessage("Wrong passphrase") { Ssh.readKey(pem, "nope", "") }
        assertEquals("ssh-ed25519", Ssh.readKey(pem, "hunter2", "").type)
        assertThrowsMessage("doesn't look like a private key") { Ssh.readKey("hello", null, "") }
    }

    @Test fun unknownHostKeyIsRefusedBeforeAuthentication() {
        val before = authAttempts.get()
        val pin = presentedPin()
        assertTrue(pin.type, pin.type.startsWith("ecdsa-sha2-nistp"))
        assertTrue(pin.fingerprint.startsWith("SHA256:"))
        assertEquals("no credentials may reach an unverified server", before, authAttempts.get())
    }

    @Test fun changedHostKeyIsRefusedBeforeAuthentication() = runBlocking {
        val before = authAttempts.get()
        try {
            Ssh.connect(cfg(pin = "ecdsa-sha2-nistp256 SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).close()
            fail("expected HostKeyMismatch")
        } catch (e: HostKeyMismatch) {
            assertEquals(presentedPin(), e.presented)
        }
        assertEquals(before, authAttempts.get())
    }

    @Test fun pinnedKeyAuthRunsUname() = runBlocking {
        val r = ServerProbe.test(cfg(pin = presentedPin().encode()))
        assertTrue(r.uname, r.uname.isNotBlank())
    }

    @Test fun passwordAuth() = runBlocking {
        val pin = presentedPin().encode()
        ServerProbe.test(cfg(pin = pin, auth = "password", password = "s3cret", privateKey = null))
        try {
            ServerProbe.test(cfg(pin = pin, auth = "password", password = "wrong", privateKey = null))
            fail("expected login failure")
        } catch (e: SshException) {
            assertTrue(e.message, e.message!!.startsWith("Login failed"))
        }
    }

    @Test fun rejectedKeyExplainsAuthorizedKeys() = runBlocking {
        val other = Ssh.generateKey("other")
        try {
            ServerProbe.test(cfg(pin = presentedPin().encode(), privateKey = other.privateKey))
            fail("expected rejection")
        } catch (e: SshException) {
            assertTrue(e.message, e.message!!.contains("authorized_keys"))
        }
    }

    @Test fun scriptsGetStdinStdoutStderrAndExitCode() = runBlocking {
        val c = Ssh.connect(cfg(pin = presentedPin().encode()))
        try {
            val r = c.script("echo hello\necho oops 1>&2\nexit 3\n")
            assertEquals("hello", r.out.trim())
            assertEquals("oops", r.err.trim())
            assertEquals(3, r.exit)
            // Several commands over the same connection, one after another.
            assertEquals("again", c.script("echo again\n").out.trim())
            val streamed = c.lines("seq 1 5").toList()
            assertEquals(listOf("1", "2", "3", "4", "5"), streamed)
            val big = c.script("i=0; while [ \$i -lt 2000 ]; do echo 0123456789012345678901234567890123456789; i=\$((i+1)); done\n", maxBytes = 10_000)
            assertTrue(big.truncated)
            assertEquals(10_000, big.out.length)
        } finally {
            c.close()
        }
    }

    @Test fun unreachableFallsBackToAltUrl() = runBlocking {
        val pin = presentedPin().encode()
        // Port 1 is closed: the fallback address answers.
        val r = ServerProbe.test(cfg(pin = pin, url = "ssh://127.0.0.1:1").copy(altUrl = "ssh://127.0.0.1:$port"))
        assertTrue(r.uname.isNotBlank())
        try {
            Ssh.connect(cfg(pin = pin, url = "ssh://127.0.0.1:1")).close()
            fail("expected unreachable")
        } catch (e: SshUnreachable) {
            assertTrue(e.message, e.message!!.contains("refused"))
        }
    }

    /* ---------------------------------------------------------------- pure helpers */

    @Test fun targets() {
        assertEquals(SshTarget("10.0.0.2", 22), SshTarget.parse("10.0.0.2"))
        assertEquals(SshTarget("vps.example.com", 2222), SshTarget.parse("ssh://root@vps.example.com:2222/"))
        assertEquals(SshTarget("::1", 22), SshTarget.parse("[::1]"))
        assertEquals(SshTarget("fe80::1", 2200), SshTarget.parse("[fe80::1]:2200"))
        assertEquals(SshTarget("host", 2022), SshTarget.parse("host", defaultPort = 2022))
        assertEquals(null, SshTarget.parse("bad host"))
        assertEquals(null, SshTarget.parse("host;rm -rf /"))
        assertEquals(null, SshTarget.parse("host:99999"))
        assertEquals("ssh://[::1]:22", "ssh://" + SshTarget("::1", 22).hostPort)
    }

    @Test fun containerRefsAreValidated() {
        assertEquals("'sonarr'", containerRef("sonarr"))
        assertEquals("'a1b2c3d4e5f6'", containerRef("a1b2c3d4e5f6"))
        listOf("", "-rm", "a b", "a;b", "a'b", "\$(id)", "a\nb", "`x`").forEach { bad ->
            assertFalse(bad, isValidContainerRef(bad))
            assertThrowsMessage("Unexpected") { containerRef(bad) }
        }
        assertEquals("'it'\\''s'", shQuote("it's"))
        val line = authorizedKeysCommand(key.publicKey)
        assertTrue(line.contains("'${key.publicKey}' >> ~/.ssh/authorized_keys"))
    }

    @Test fun parsesOverview() {
        val out = """
@@host
media-box
@@os
NAME="Ubuntu"
PRETTY_NAME="Ubuntu 24.04.1 LTS"
@@kernel
Linux 6.8.0-45-generic x86_64
@@load
0.52 0.48 0.40 2/345 12345
@@cores
4
@@mem
MemTotal:        8000000 kB
MemFree:          500000 kB
MemAvailable:    6000000 kB
SwapTotal:       2000000 kB
SwapFree:        1500000 kB
@@uptime1
1000.00 3000.00
@@stat1
cpu  100 0 100 800 0 0 0 0 0 0
@@net1
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 5000 10 0 0 0 0 0 0 5000 10 0 0 0 0 0 0
  eth0: 10000 10 0 0 0 0 0 0 20000 10 0 0 0 0 0 0
vethabc: 1 1 0 0 0 0 0 0 1 1 0 0 0 0 0 0
@@uptime2
1002.00 3000.00
@@stat2
cpu  150 0 150 900 0 0 0 0 0 0
@@net2
  eth0: 30000 10 0 0 0 0 0 0 60000 10 0 0 0 0 0 0
@@df
Filesystem     1024-blocks      Used Available Capacity Mounted on
/dev/sda1        100000000  40000000  60000000      40% /
tmpfs              1000000         0   1000000       0% /run
overlay          100000000  40000000  60000000      40% /var/lib/docker/overlay2/x/merged
/dev/sdb1       2000000000 1000000000 1000000000    50% /mnt/media files
/dev/sda1        100000000  40000000  60000000      40% /srv/bind
@@temp
x86_pkg_temp 45000
@@hwmon
coretemp Package_id_0 47000
nvme  38850
@@end
""".trimIndent()
        val o = parseOverview(out, null)
        assertEquals("media-box", o.hostname)
        assertEquals("Ubuntu 24.04.1 LTS", o.os)
        assertEquals(0.5, o.cpu!!, 1e-9) // busy 100 of 200 ticks
        assertEquals(listOf(0.52, 0.48, 0.40), o.load)
        assertEquals(4, o.cores)
        assertEquals(2_000_000L * 1024, o.memUsed)
        assertEquals(500_000L * 1024, o.swapUsed)
        assertEquals(listOf("/", "/mnt/media files"), o.disks.map { it.mount })
        assertEquals(listOf("eth0"), o.net.map { it.iface })
        assertEquals(10_000.0, o.net[0].rxPerSec, 1e-6)
        assertEquals(20_000.0, o.net[0].txPerSec, 1e-6)
        assertEquals(listOf("coretemp Package id 0", "nvme"), o.temps.map { it.label })
        assertEquals(47.0, o.temps[0].celsius, 1e-9)

        // A later poll with one sample uses the previous one for rates.
        val later = out.replace("@@uptime1\n1000.00", "@@uptime1\n1012.00").replace("cpu  100 0 100 800", "cpu  250 0 250 1300")
        val next = parseOverview(later.substringBefore("@@uptime2") + "@@df\n", o.sample)
        assertEquals(1.0 / 3, next.cpu!!, 1e-9) // (500-300) busy of (1800-1200) total since the previous poll
        assertEquals(0.0, next.net[0].rxPerSec, 1e-6) // counter went back (10000 < 30000): clamped, not negative
        assertNotNull(next.sample)
    }

    @Test fun parsesDocker() {
        val ps = """
{"Command":"\"/init\"","CreatedAt":"2026-01-01 10:00:00 +0000 UTC","ID":"aaaaaaaaaaaa1111","Image":"lscr.io/linuxserver/sonarr:latest","Labels":"com.docker.compose.project=media,com.docker.compose.service=sonarr","Names":"sonarr","Ports":"0.0.0.0:8989->8989/tcp","RunningFor":"2 days ago","State":"running","Status":"Up 2 days (healthy)"}
{"ID":"bbbbbbbbbbbb2222","Image":"busybox","Labels":"","Names":"oneoff","State":"exited","Status":"Exited (137) 3 hours ago"}
{"ID":"bad;id","Image":"x","Names":"evil","State":"running","Status":"Up"}
not json
""".trimIndent()
        val list = parseContainers(ps)
        assertEquals(listOf("sonarr", "oneoff"), list.map { it.name })
        assertEquals("media", list[0].project)
        assertEquals("sonarr", list[0].service)
        assertEquals("healthy", list[0].health)
        assertTrue(list[0].running)
        assertEquals(137, list[1].exitCode)
        assertEquals(null, list[1].project)

        val stats = parseStats("""{"BlockIO":"1MB / 2MB","CPUPerc":"1.25%","Container":"aaaa","ID":"aaaaaaaaaaaa1111","MemPerc":"3.00%","MemUsage":"120MiB / 3.8GiB","Name":"sonarr","NetIO":"1kB / 2kB","PIDs":"40"}""")
        assertEquals(1.25, stats["aaaaaaaaaaaa1111"]!!.cpuPercent!!, 1e-9)
        assertEquals("120MiB / 3.8GiB", stats["name:sonarr"]!!.mem)

        assertEquals(DockerAccess.Ok("docker", "27.3.1"), parseDockerAccess("@@docker\n27.3.1\n"))
        assertEquals(DockerAccess.Ok("sudo -n docker", "27.3.1"), parseDockerAccess("@@sudo\n27.3.1\n"))
        assertEquals(DockerAccess.Missing, parseDockerAccess("@@missing\n"))
        val denied = parseDockerAccess("@@denied\npermission denied while trying to connect to the Docker daemon socket\n@@groups\nselin sudo\n")
        assertTrue(denied is DockerAccess.Denied && denied.groups == "selin sudo")
        assertTrue(parseDockerAccess("@@denied\nCannot connect to the Docker daemon\n@@groups\nx\n") is DockerAccess.DaemonDown)

        val d = parseInspect("""{"state":{"Status":"running","StartedAt":"2026-01-01T10:00:00Z","FinishedAt":"0001-01-01T00:00:00Z","Health":{"Status":"healthy"}},"restart":{"Name":"unless-stopped"},"restarts":2,"mounts":[{"Source":"/srv/tv","Destination":"/tv","RW":true},{"Name":"cfg","Source":"/var/lib/docker/volumes/cfg","Destination":"/config","RW":false}],"networks":{"media_default":{}},"path":"/init"}""")!!
        assertEquals("unless-stopped", d.restartPolicy)
        assertEquals(2L, d.restartCount)
        assertEquals(null, d.finishedAt)
        assertEquals(listOf("/srv/tv → /tv", "cfg → /config (ro)"), d.mounts)
        assertEquals(listOf("media_default"), d.networks)
    }

    private fun assertThrowsMessage(part: String, block: () -> Unit) {
        try {
            block(); fail("expected an error containing \"$part\"")
        } catch (e: SshException) {
            assertTrue("${e.message} should contain $part", e.message!!.contains(part, true))
        }
    }
}
