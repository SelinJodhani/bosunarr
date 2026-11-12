package app.bosunarr.backup

import kotlin.math.log2

/** A rough passphrase-strength hint for the export dialog (not a guarantee; Argon2id does the heavy lifting). */
enum class Strength(val label: String) { TooShort("Too short"), Weak("Weak"), Fair("Fair"), Strong("Strong"), VeryStrong("Very strong") }

object PassphraseStrength {
    const val MIN_LENGTH = 8

    private val COMMON = setOf(
        "password", "passw0rd", "12345678", "123456789", "1234567890", "qwertyuiop", "qwerty123", "iloveyou",
        "11111111", "00000000", "abcdefgh", "abc12345", "letmein1", "welcome1", "bosunarr", "sonarr", "radarr",
        "jellyfin", "plex", "admin123", "changeme",
    )

    /**
     * Estimate from length × character-pool size, discounted for repeats, sequences and common passwords.
     * Long multi-word passphrases score well even in lowercase, which is the advice the hint nudges towards.
     */
    fun of(p: String): Strength {
        if (p.length < MIN_LENGTH) return Strength.TooShort
        val lower = p.lowercase()
        if (lower in COMMON || COMMON.any { lower.startsWith(it) && lower.length - it.length <= 2 }) return Strength.Weak
        val distinct = p.toSet().size
        if (distinct <= 2) return Strength.Weak
        var pool = 0
        if (p.any { it.isLowerCase() }) pool += 26
        if (p.any { it.isUpperCase() }) pool += 26
        if (p.any { it.isDigit() }) pool += 10
        if (p.any { it == ' ' }) pool += 1
        if (p.any { !it.isLetterOrDigit() && it != ' ' }) pool += 33
        if (p.any { it.code > 127 }) pool += 50
        // Each character contributes less when the string is repetitive or a straight run (abcd, 1234).
        val runs = p.zipWithNext().count { (a, b) -> b.code - a.code == 1 || b == a }
        val effective = (p.length - runs / 2.0).coerceAtLeast(1.0) * (distinct.toDouble() / p.length).coerceIn(0.5, 1.0)
        val bits = effective * log2(pool.coerceAtLeast(2).toDouble())
        return when {
            bits < 36 -> Strength.Weak
            bits < 50 -> Strength.Fair
            bits < 70 -> Strength.Strong
            else -> Strength.VeryStrong
        }
    }
}
