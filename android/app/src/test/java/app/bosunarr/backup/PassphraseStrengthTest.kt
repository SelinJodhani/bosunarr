package app.bosunarr.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseStrengthTest {
    @Test
    fun ratings() {
        assertEquals(Strength.TooShort, PassphraseStrength.of("abc123"))
        assertEquals(Strength.Weak, PassphraseStrength.of("password"))
        assertEquals(Strength.Weak, PassphraseStrength.of("password1"))
        assertEquals(Strength.Weak, PassphraseStrength.of("aaaaaaaaaaaa"))
        assertEquals(Strength.Weak, PassphraseStrength.of("12345678"))
        assertTrue(PassphraseStrength.of("Tr0ub4dor&3") >= Strength.Strong)
        assertTrue(PassphraseStrength.of("correct horse battery staple") >= Strength.Strong)
        assertEquals(Strength.VeryStrong, PassphraseStrength.of("vK8#qP2!zR7\$mW4@"))
    }
}
