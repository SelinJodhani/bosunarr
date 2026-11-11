package app.bosunarr.feature.server

import app.bosunarr.data.ServiceConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64

/*
 * SSH for the Server service type, on JSch (mwiede fork) with BouncyCastle for the modern algorithms.
 *
 * Security rules this file keeps:
 *  - The host key is checked before authentication (so a password or key signature never reaches an unverified
 *    server): unknown -> [HostKeyUnknown] (the UI asks the user, trust-on-first-use), different from the pinned one
 *    -> [HostKeyMismatch] (refused; the user must explicitly trust the new key).
 *  - Nothing here logs: not keys, passwords, commands or their output. JSch's own logger stays off.
 *  - Scripts go to `sh -s` on stdin, so they are constant strings that never pass through the user's login shell
 *    (which may be fish or zsh); the only interpolated values are validated container ids ([containerRef]).
 */

/** User-facing SSH failure (the message is shown as is). */
open class SshException(message: String) : Exception(message)

/** The address couldn't be reached at all: the fallback address is worth trying. */
class SshUnreachable(message: String) : SshException(message)

/** The connection dropped (or was closed) while in use. */
class SshDisconnected(message: String = "The SSH connection was lost") : SshException(message)

/** A pinned host key: the key's algorithm (as in the key blob, e.g. `ssh-ed25519`) and its SHA256 fingerprint. */
data class HostKeyPin(val type: String, val fingerprint: String) {
    fun encode(): String = "$type $fingerprint"

    companion object {
        fun parse(raw: String?): HostKeyPin? {
            val parts = raw?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
            return when (parts.size) {
                2 -> HostKeyPin(parts[0], parts[1])
                1 -> HostKeyPin("", parts[0])
                else -> null
            }
        }
    }
}

/** The server presented a host key we haven't pinned yet: ask the user whether it's theirs. */
class HostKeyUnknown(val host: String, val presented: HostKeyPin) :
    SshException("$host's host key isn't trusted yet — check its fingerprint and trust it to connect")

/** The server presented a different key than the pinned one. Never connect silently. */
class HostKeyMismatch(val host: String, val pinned: HostKeyPin, val presented: HostKeyPin) :
    SshException("$host's host key has changed. This can mean someone is intercepting the connection — not connecting.")

/** `ssh://host:port` (IPv6 hosts in brackets). */
data class SshTarget(val host: String, val port: Int) {
    val url: String get() = "ssh://${display}"
    val display: String get() = (if (host.contains(':')) "[$host]" else host) + if (port == 22) "" else ":$port"
    val hostPort: String get() = (if (host.contains(':')) "[$host]" else host) + ":$port"

    companion object {
        private val HOST = Regex("^[A-Za-z0-9._%:-]+$")

        /** What the user typed or what's stored: `host`, `host:2222`, `ssh://user@host:22`, `[::1]:22`. */
        fun parse(raw: String?, defaultPort: Int = 22): SshTarget? {
            var s = raw?.trim() ?: return null
            if (s.contains("://")) s = s.substringAfter("://")
            s = s.substringAfter('@').trimEnd('/')
            if (s.isEmpty()) return null
            val host: String
            var port = defaultPort
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull() ?: return null
            } else if (s.count { it == ':' } == 1) {
                host = s.substringBefore(':')
                port = s.substringAfter(':').toIntOrNull() ?: return null
            } else host = s
            if (host.isEmpty() || !HOST.matches(host) || port !in 1..65535) return null
            return SshTarget(host, port)
        }
    }
}

data class ExecResult(val exit: Int, val out: String, val err: String, val truncated: Boolean = false) {
    val ok: Boolean get() = exit == 0
}

/** A key pair in OpenSSH formats; [publicKey] is the `authorized_keys` line. */
data class SshKey(val privateKey: String, val publicKey: String, val type: String, val fingerprint: String)

/* ================================================================ quoting */

private val CONTAINER_REF = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")

fun isValidContainerRef(id: String): Boolean = id.length <= 128 && CONTAINER_REF.matches(id)

/** A container id or name for a script, validated and single-quoted (belt and braces). */
fun containerRef(id: String): String {
    if (!isValidContainerRef(id)) throw SshException("Unexpected container id")
    return "'$id'"
}

/** POSIX single-quoting. */
fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** The exact line to append the key on the server (works in bash, zsh and fish). */
fun authorizedKeysCommand(publicKey: String): String =
    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo ${shQuote(publicKey.trim())} >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

/* ================================================================ client */

object Ssh {
    /** Host key algorithms we accept (no certificates, no SHA-1 RSA), in JSch's preference order. */
    private val HOST_KEY_ALGORITHMS = listOf(
        "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-512", "rsa-sha2-256",
    )

    @Volatile private var configured = false

    /**
     * Use the BouncyCastle implementations for Ed25519/Ed448/X25519. JSch's defaults are the JDK 15+ ones, which
     * on Android are the base-jar stubs that throw (no multi-release jars there): without this, `ssh-ed25519`
     * keys, curve25519 key exchange and Ed25519 key generation silently don't exist.
     */
    fun init() {
        if (configured) return
        synchronized(this) {
            if (configured) return
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            configured = true
        }
    }

    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** Algorithm name stored at the start of an SSH key blob. */
    fun keyType(blob: ByteArray): String {
        if (blob.size < 4) return ""
        val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        if (len <= 0 || len > blob.size - 4 || len > 64) return ""
        return String(blob, 4, len, Charsets.US_ASCII)
    }

    /** The pinned key's algorithms first, so a server with several host keys always shows the one we pinned. */
    private fun hostKeyAlgorithms(pinned: HostKeyPin?): String {
        val pinnedAlgos = when (pinned?.type) {
            null, "" -> emptyList()
            "ssh-rsa" -> listOf("rsa-sha2-512", "rsa-sha2-256")
            else -> HOST_KEY_ALGORITHMS.filter { it == pinned.type }
        }
        return (pinnedAlgos + HOST_KEY_ALGORITHMS).distinct().joinToString(",")
    }

    /** Opens a session to the service's address, falling back to `altUrl` when the first is unreachable. */
    suspend fun connect(cfg: ServiceConfig, timeoutMs: Int = 10_000): SshConn = withContext(Dispatchers.IO) {
        init()
        val targets = listOfNotNull(cfg.url, cfg.altUrl?.takeIf { it.isNotBlank() }).mapNotNull { SshTarget.parse(it) }.distinct()
        if (targets.isEmpty()) throw SshException("Add the server's address")
        if (cfg.username.isNullOrBlank()) throw SshException("Add the SSH username")
        var last: SshException? = null
        for (t in targets) {
            try {
                return@withContext connectTo(cfg, t, timeoutMs)
            } catch (e: SshUnreachable) {
                last = e
            }
        }
        throw last ?: SshUnreachable("The server is unreachable")
    }

    private fun connectTo(cfg: ServiceConfig, t: SshTarget, timeoutMs: Int): SshConn {
        val jsch = JSch()
        val pinned = HostKeyPin.parse(cfg.sshHostKey)
        val repo = PinnedHostKey(t.host, pinned)
        jsch.hostKeyRepository = repo
        val useKey = cfg.sshAuth != "password"
        if (useKey) {
            val key = cfg.sshPrivateKey?.takeIf { it.isNotBlank() }
                ?: throw SshException("No private key yet — generate or paste one in the server's settings")
            // Clear errors for a bad key or passphrase (JSch would only report "Auth fail" later).
            readKey(key, cfg.password, "")
            try {
                jsch.addIdentity("bosunarr", key.toByteArray(), null, cfg.password?.takeIf { it.isNotEmpty() }?.toByteArray())
            } catch (e: JSchException) {
                throw SshException(if (e.message.orEmpty().contains("passphrase", true)) "Wrong passphrase for the private key" else "The private key couldn't be read")
            }
        }
        val session: Session = try {
            jsch.getSession(cfg.username!!.trim(), t.host, t.port)
        } catch (e: JSchException) {
            throw SshException("Invalid SSH address or username")
        }
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("prefer_known_host_key_types", "no")
        session.setConfig("server_host_key", hostKeyAlgorithms(pinned))
        session.setConfig("PreferredAuthentications", if (useKey) "publickey" else "keyboard-interactive,password")
        if (!useKey) {
            val pw = cfg.password.orEmpty()
            session.setPassword(pw)
            session.userInfo = PasswordPrompt(pw)
        }
        // Also the socket read timeout: with no traffic for 15 s JSch sends a keepalive, and gives up after 3 misses.
        session.setServerAliveInterval(15_000)
        session.serverAliveCountMax = 3
        try {
            session.connect(timeoutMs)
        } catch (e: JSchException) {
            runCatching { session.disconnect() }
            repo.problem?.let { throw it }
            throw mapError(e, t, useKey, cfg.username!!.trim())
        }
        return SshConn(session, t)
    }

    private fun mapError(e: JSchException, t: SshTarget, useKey: Boolean, user: String): SshException {
        val msg = e.message.orEmpty()
        val cause = e.cause
        return when {
            cause is UnknownHostException -> SshUnreachable("Can't find ${t.host} — check the address")
            cause is ConnectException || msg.contains("Connection refused", true) ->
                SshUnreachable("${t.hostPort} refused the connection — is SSH running on that port?")
            cause is SocketTimeoutException || msg.contains("timeout", true) -> SshUnreachable("${t.host} timed out")
            cause is NoRouteToHostException -> SshUnreachable("No route to ${t.host}")
            cause is IOException -> SshUnreachable("${t.host} is unreachable")
            msg.startsWith("Auth fail", true) || msg.startsWith("Auth cancel", true) || msg.contains("USERAUTH fail", true) ->
                SshException(
                    if (useKey) "The server didn't accept the key for \"$user\" — add the public key to ~/.ssh/authorized_keys"
                    else "Login failed — check the username and password (the server may also only allow keys)",
                )
            msg.contains("Algorithm negotiation fail", true) -> SshException("The server and Bosunarr have no encryption algorithms in common")
            msg.contains("invalid privatekey", true) -> SshException("The private key couldn't be read")
            else -> SshException("SSH failed: ${msg.take(160).ifBlank { "unknown error" }}")
        }
    }

    /* ---------------------------------------------------------------- keys */

    /** A fresh key pair: Ed25519, or RSA-3072 if Ed25519 isn't available. */
    fun generateKey(comment: String): SshKey {
        init()
        val jsch = JSch()
        val kp = try {
            KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        } catch (e: Exception) {
            KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        }
        try {
            val prv = ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, null) }.toString(Charsets.UTF_8.name())
            return describe(kp, prv, comment)
        } finally {
            kp.dispose()
        }
    }

    /** Parse a pasted private key (OpenSSH, PEM or PuTTY); throws a user-facing error on a bad key or passphrase. */
    fun readKey(privateKey: String, passphrase: String?, comment: String): SshKey {
        init()
        val text = privateKey.trim() + "\n"
        val kp = try {
            KeyPair.load(JSch(), text.toByteArray(), null)
        } catch (e: Exception) {
            throw SshException("That doesn't look like a private key. Paste the whole file, including the BEGIN/END lines.")
        }
        try {
            if (kp.isEncrypted) {
                if (passphrase.isNullOrEmpty()) throw SshException("This key is protected — enter its passphrase")
                if (!kp.decrypt(passphrase)) throw SshException("Wrong passphrase")
            }
            return describe(kp, text, comment)
        } finally {
            kp.dispose()
        }
    }

    private fun describe(kp: KeyPair, privateKey: String, comment: String): SshKey {
        val blob = kp.publicKeyBlob ?: throw SshException("Couldn't derive the public key from this private key")
        val type = keyType(blob)
        val pub = "$type ${Base64.getEncoder().encodeToString(blob)} $comment".trim()
        return SshKey(privateKey, pub, type, fingerprint(blob))
    }
}

/** Accepts only the pinned key; remembers what the server presented so the caller can explain. */
private class PinnedHostKey(private val host: String, private val pinned: HostKeyPin?) : HostKeyRepository {
    @Volatile var problem: SshException? = null

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val presented = HostKeyPin(Ssh.keyType(key), Ssh.fingerprint(key))
        return when {
            pinned == null -> {
                problem = HostKeyUnknown(this.host, presented); HostKeyRepository.NOT_INCLUDED
            }
            pinned.fingerprint == presented.fingerprint -> HostKeyRepository.OK
            else -> {
                problem = HostKeyMismatch(this.host, pinned, presented); HostKeyRepository.CHANGED
            }
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "bosunarr-pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Answers password and keyboard-interactive (PAM) prompts with the configured password; never accepts host keys. */
private class PasswordPrompt(private val password: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?): Boolean = true
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
    override fun promptKeyboardInteractive(
        destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
    ): Array<String>? = when {
        prompt == null || prompt.isEmpty() -> emptyArray()
        prompt.size == 1 && echo?.getOrNull(0) != true -> arrayOf(password)
        else -> null // a second factor or something else we can't answer
    }
}

/* ================================================================ connection */

/** One authenticated SSH session; runs any number of commands (each on its own channel). */
class SshConn internal constructor(private val session: Session, val target: SshTarget) : Closeable {
    val isConnected: Boolean get() = session.isConnected

    /** Server banner, e.g. `SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13`. */
    val serverVersion: String? get() = runCatching { session.serverVersion }.getOrNull()

    private fun open(): ChannelExec = try {
        session.openChannel("exec") as ChannelExec
    } catch (e: JSchException) {
        throw SshDisconnected()
    }

    /**
     * Runs [command] (through the user's login shell, as `ssh host command` would) with [stdin] as its input, and
     * collects stdout/stderr. Output past [maxBytes] is dropped and the command closed.
     */
    suspend fun exec(command: String, stdin: String? = null, timeoutMs: Long = 30_000, maxBytes: Int = 4 shl 20): ExecResult =
        withContext(Dispatchers.IO) {
            val ch = open()
            try {
                withTimeout(timeoutMs) {
                    runInterruptible {
                        ch.setCommand(command)
                        // Always an input stream, so the remote side sees EOF on stdin instead of waiting for input.
                        ch.setInputStream(ByteArrayInputStream((stdin ?: "").toByteArray()))
                        val err = BoundedOutput(maxBytes / 4)
                        ch.setErrStream(err, true)
                        val input = ch.inputStream
                        try {
                            ch.connect(10_000)
                        } catch (e: JSchException) {
                            throw if (!session.isConnected) SshDisconnected() else SshException("Couldn't start the command on the server")
                        }
                        val (bytes, truncated) = readBounded(input, maxBytes)
                        if (!truncated) {
                            while (!ch.isClosed) Thread.sleep(15)
                        }
                        ExecResult(
                            exit = if (truncated) -1 else ch.exitStatus,
                            out = bytes.toString(Charsets.UTF_8.name()),
                            err = err.text(),
                            truncated = truncated,
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw SshException("The command timed out")
            } catch (e: java.io.InterruptedIOException) {
                // The read was interrupted: by the timeout above (or the caller leaving).
                throw if (session.isConnected) SshException("The command timed out") else SshDisconnected()
            } catch (e: IOException) {
                throw if (!session.isConnected) SshDisconnected() else SshException("Reading the command's output failed")
            } finally {
                ch.disconnect()
            }
        }

    /** Runs a POSIX shell [script] via `sh -s`, independent of the user's login shell. */
    suspend fun script(script: String, timeoutMs: Long = 30_000, maxBytes: Int = 4 shl 20): ExecResult =
        exec("sh -s", stdin = script, timeoutMs = timeoutMs, maxBytes = maxBytes)

    /**
     * Streams [command]'s output line by line until it ends or the collector cancels. With [pty] the remote process
     * gets a terminal, so closing the channel hangs it up (for `docker logs -f`); stderr is merged into stdout then.
     */
    fun lines(command: String, pty: Boolean = false): Flow<String> = flow {
        val ch = open()
        try {
            ch.setCommand(command)
            if (pty) ch.setPty(true)
            ch.setInputStream(null)
            if (!pty) ch.setErrStream(DiscardOutput, true)
            val reader = ch.inputStream.bufferedReader(Charsets.UTF_8)
            runInterruptible {
                try {
                    ch.connect(10_000)
                } catch (e: JSchException) {
                    throw if (!session.isConnected) SshDisconnected() else SshException("Couldn't start the command on the server")
                }
            }
            while (true) {
                val line = try {
                    runInterruptible { reader.readLine() }
                } catch (e: IOException) {
                    if (!session.isConnected) throw SshDisconnected()
                    null
                } ?: break
                emit(line.trimEnd('\r'))
            }
        } finally {
            ch.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        runCatching { session.disconnect() }
    }
}

private object DiscardOutput : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/** Keeps the first [limit] bytes written. */
private class BoundedOutput(private val limit: Int) : OutputStream() {
    private val buf = ByteArrayOutputStream()
    @Synchronized override fun write(b: Int) {
        if (buf.size() < limit) buf.write(b)
    }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        val n = minOf(len, limit - buf.size())
        if (n > 0) buf.write(b, off, n)
    }
    @Synchronized fun text(): String = buf.toString(Charsets.UTF_8.name())
}

private fun readBounded(input: InputStream, max: Int): Pair<ByteArrayOutputStream, Boolean> {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out to false
        val room = max - out.size()
        if (n > room) {
            out.write(buf, 0, room.coerceAtLeast(0))
            return out to true
        }
        out.write(buf, 0, n)
    }
}
