package app.bosunarr.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    /** Cheap Argon2 settings so the suite stays fast; [defaultParamsRoundTrip] covers the real ones. */
    private val fast = BackupCrypto.KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val plain = """{"services":[{"id":"a","apiKey":"secret-key-123"}]}""".toByteArray()

    private inline fun <reified T : BackupException> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: BackupException) {
            assertTrue("got ${e::class.simpleName}: ${e.message}", e is T)
        }
    }

    @Test
    fun roundTrip() {
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        assertArrayEquals(plain, BackupCrypto.decrypt(file, "correct horse battery"))
    }

    @Test
    fun defaultParamsRoundTrip() {
        val started = System.nanoTime()
        val file = BackupCrypto.encrypt(plain, "correct horse battery")
        val h = BackupCrypto.checkHeader(file)
        assertEquals(64 * 1024, h.params.memoryKiB)
        assertEquals(3, h.params.iterations)
        assertArrayEquals(plain, BackupCrypto.decrypt(file, "correct horse battery"))
        println("Argon2id 64 MiB/t=3 encrypt+decrypt on this JVM: ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test
    fun ciphertextHasNoPlaintext() {
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        val text = String(file, Charsets.ISO_8859_1)
        assertFalse(text.contains("secret-key-123"))
        assertFalse(text.contains("services"))
        assertTrue(text.startsWith("BOSUNARR"))
    }

    @Test
    fun freshSaltAndNonceEveryTime() {
        val a = BackupCrypto.encrypt(plain, "pass phrase", fast)
        val b = BackupCrypto.encrypt(plain, "pass phrase", fast)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrongPassphrase() {
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        assertThrows<BackupException.WrongPassphrase> { BackupCrypto.decrypt(file, "correct horse batterY") }
    }

    @Test
    fun unicodePassphraseIsNormalised() {
        // "é" precomposed vs. e + combining acute: the same passphrase typed on different keyboards.
        val file = BackupCrypto.encrypt(plain, "café au lait", fast)
        assertArrayEquals(plain, BackupCrypto.decrypt(file, "café au lait"))
    }

    @Test
    fun tamperedCiphertextFails() {
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        file[file.size - 20] = (file[file.size - 20].toInt() xor 1).toByte()
        assertThrows<BackupException.WrongPassphrase> { BackupCrypto.decrypt(file, "correct horse battery") }
    }

    @Test
    fun tamperedHeaderFails() {
        // Header is authenticated: flipping a salt byte (offset 20) must not decrypt.
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        file[20] = (file[20].toInt() xor 1).toByte()
        assertThrows<BackupException.WrongPassphrase> { BackupCrypto.decrypt(file, "correct horse battery") }
    }

    @Test
    fun notABackup() {
        assertThrows<BackupException.NotABackup> { BackupCrypto.checkHeader("{\"services\":[]}".toByteArray()) }
        assertThrows<BackupException.NotABackup> { BackupCrypto.checkHeader(ByteArray(0)) }
    }

    @Test
    fun newerFormatVersionIsRejectedClearly() {
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        file[8] = 2
        assertThrows<BackupException.NewerVersion> { BackupCrypto.checkHeader(file) }
        val file2 = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        file2[9] = 7 // unknown KDF
        assertThrows<BackupException.NewerVersion> { BackupCrypto.checkHeader(file2) }
    }

    @Test
    fun truncatedFile() {
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        assertThrows<BackupException.Corrupt> { BackupCrypto.checkHeader(file.copyOf(30)) }
        assertThrows<BackupException.Corrupt> { BackupCrypto.checkHeader(file.copyOf(12)) }
    }

    @Test
    fun absurdKdfParamsAreRefused() {
        // A crafted file asking for 4 GiB of memory must be refused before any key derivation.
        val file = BackupCrypto.encrypt(plain, "correct horse battery", fast)
        file[10] = 0x7f
        assertThrows<BackupException.Corrupt> { BackupCrypto.checkHeader(file) }
    }

    private fun hex(s: String) = s.split(':').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun argon2idMatchesIndependentImplementation() {
        // Expected keys computed with OpenSSL 4.1's ARGON2ID KDF (a separate implementation from BouncyCastle):
        //   openssl kdf -keylen 32 -kdfopt pass:<p> -kdfopt hexsalt:000102…0f -kdfopt iter:3 -kdfopt memcost:<m> -kdfopt lanes:<l> ARGON2ID
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            hex("80:3C:58:2B:3C:99:A5:67:E0:1B:2D:F8:3C:F5:5C:4B:32:04:0C:D8:51:1A:ED:25:F0:51:50:2D:81:EE:43:B2"),
            BackupCrypto.deriveKey("password", salt, BackupCrypto.KdfParams(memoryKiB = 32, iterations = 3, parallelism = 4)),
        )
        // The app's default cost (64 MiB, t=3, p=1).
        assertArrayEquals(
            hex("90:4B:42:49:2D:A0:40:31:A4:68:A6:D1:D5:16:89:84:8C:15:E5:C8:8D:85:87:B0:18:6D:CE:57:4B:8C:4B:CC"),
            BackupCrypto.deriveKey("correct horse battery", salt, BackupCrypto.KdfParams()),
        )
    }
}
