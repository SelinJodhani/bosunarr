package app.bosunarr.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup file couldn't be opened; [message] is user-facing. */
sealed class BackupException(message: String) : Exception(message) {
    class NotABackup : BackupException("This isn't a Bosunarr backup file")
    class NewerVersion : BackupException("This backup was made by a newer version of Bosunarr — update the app to restore it")
    class WrongPassphrase : BackupException("Wrong passphrase, or the file is damaged")
    class Corrupt(detail: String) : BackupException("The backup file is damaged ($detail)")
}

/**
 * Passphrase-encrypted container for Bosunarr backups. No Android dependencies (unit-tested on the JVM).
 *
 * Key derivation: **Argon2id** (RFC 9106, v1.3, BouncyCastle) with a random 16-byte salt — memory-hard, so
 * guessing passphrases offline needs 64 MiB per attempt. Encryption: **AES-256-GCM** with a random 96-bit nonce
 * (one key per file, since the salt is fresh). The whole header is the GCM associated data, so tampering with
 * the version or KDF parameters fails authentication just like tampering with the ciphertext.
 *
 * File layout (big-endian):
 * ```
 *  0  8  magic "BOSUNARR"
 *  8  1  format version (1)
 *  9  1  KDF id (1 = Argon2id v1.3)
 * 10  4  memory, KiB
 * 14  4  iterations
 * 18  1  parallelism
 * 19  1  salt length (16)    20 .. salt
 *     1  nonce length (12)   .. nonce
 *     .. AES-256-GCM ciphertext followed by its 16-byte tag
 * ```
 * Readers reject a higher format version or unknown KDF id with [BackupException.NewerVersion], and KDF
 * parameters outside sane bounds (a crafted file could otherwise ask for gigabytes of memory).
 */
object BackupCrypto {
    private val MAGIC = "BOSUNARR".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION = 1
    private const val KDF_ARGON2ID = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    /** Argon2id cost. Defaults: 64 MiB, 3 passes, 1 lane (BouncyCastle doesn't use threads for lanes). */
    data class KdfParams(val memoryKiB: Int = 64 * 1024, val iterations: Int = 3, val parallelism: Int = 1) {
        init {
            require(memoryKiB in MIN_MEMORY..MAX_MEMORY && iterations in 1..MAX_ITERATIONS && parallelism in 1..MAX_PARALLELISM && memoryKiB >= 8 * parallelism)
        }
    }

    private const val MIN_MEMORY = 8
    private const val MAX_MEMORY = 512 * 1024
    private const val MAX_ITERATIONS = 16
    private const val MAX_PARALLELISM = 16

    private val random = SecureRandom()

    /** Encrypt [plaintext] under [passphrase]. The caller should wipe [plaintext] afterwards. */
    fun encrypt(plaintext: ByteArray, passphrase: String, params: KdfParams = KdfParams()): ByteArray {
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val header = ByteArrayOutputStream().apply {
            write(MAGIC)
            write(FORMAT_VERSION)
            write(KDF_ARGON2ID)
            write(ByteBuffer.allocate(4).putInt(params.memoryKiB).array())
            write(ByteBuffer.allocate(4).putInt(params.iterations).array())
            write(params.parallelism)
            write(salt.size); write(salt)
            write(nonce.size); write(nonce)
        }.toByteArray()
        val key = deriveKey(passphrase, salt, params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** Parsed header; [checkHeader] lets the UI reject a non-backup file before asking for a passphrase. */
    class Header internal constructor(val params: KdfParams, internal val salt: ByteArray, internal val nonce: ByteArray, internal val length: Int)

    fun checkHeader(file: ByteArray): Header {
        if (file.size < MAGIC.size || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BackupException.NotABackup()
        val b = ByteBuffer.wrap(file)
        try {
            b.position(MAGIC.size)
            val version = b.get().toInt() and 0xff
            if (version == 0) throw BackupException.Corrupt("bad version")
            if (version > FORMAT_VERSION) throw BackupException.NewerVersion()
            val kdf = b.get().toInt() and 0xff
            if (kdf != KDF_ARGON2ID) throw BackupException.NewerVersion()
            val memory = b.int
            val iterations = b.int
            val parallelism = b.get().toInt() and 0xff
            val params = runCatching { KdfParams(memory, iterations, parallelism) }.getOrElse { throw BackupException.Corrupt("unsupported key settings") }
            val saltLen = b.get().toInt() and 0xff
            if (saltLen !in 16..64) throw BackupException.Corrupt("bad salt")
            val salt = ByteArray(saltLen).also { b.get(it) }
            val nonceLen = b.get().toInt() and 0xff
            if (nonceLen != NONCE_LEN) throw BackupException.Corrupt("bad nonce")
            val nonce = ByteArray(nonceLen).also { b.get(it) }
            if (b.remaining() < TAG_BITS / 8) throw BackupException.Corrupt("truncated")
            return Header(params, salt, nonce, b.position())
        } catch (e: java.nio.BufferUnderflowException) {
            throw BackupException.Corrupt("truncated")
        }
    }

    /** Decrypt a backup file; throws [BackupException] (wrong passphrase, newer format, damage). */
    fun decrypt(file: ByteArray, passphrase: String): ByteArray {
        val h = checkHeader(file)
        val key = deriveKey(passphrase, h.salt, h.params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, h.nonce))
            cipher.updateAAD(file, 0, h.length)
            return cipher.doFinal(file, h.length, file.size - h.length)
        } catch (e: AEADBadTagException) {
            throw BackupException.WrongPassphrase()
        } finally {
            key.fill(0)
        }
    }

    /** Argon2id → 32-byte AES key. The passphrase is NFC-normalised so the same text typed on another keyboard matches. */
    internal fun deriveKey(passphrase: String, salt: ByteArray, p: KdfParams): ByteArray {
        val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toCharArray()
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(p.memoryKiB)
                .withIterations(p.iterations)
                .withParallelism(p.parallelism)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(32)
        try {
            gen.generateBytes(chars, out)
        } finally {
            chars.fill('\u0000')
        }
        return out
    }
}
