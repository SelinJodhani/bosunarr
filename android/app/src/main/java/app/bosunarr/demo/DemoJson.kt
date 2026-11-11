package app.bosunarr.demo

import android.content.Context
import app.bosunarr.net.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/* Small helpers shared by the fake services: JSON building, time and asset loading. */

/** Any Kotlin value (maps, lists, primitives, JSON) as JSON. */
internal fun j(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Instant -> JsonPrimitive(iso(v))
    is Map<*, *> -> JsonObject(LinkedHashMap<String, JsonElement>().apply { v.forEach { (k, x) -> put(k.toString(), j(x)) } })
    is Iterable<*> -> JsonArray(v.map { j(it) })
    is Array<*> -> JsonArray(v.map { j(it) })
    else -> JsonPrimitive(v.toString())
}

/** A JSON object keeping the given key order. */
internal fun obj(vararg p: Pair<String, Any?>): JsonObject =
    JsonObject(LinkedHashMap<String, JsonElement>().apply { p.forEach { (k, v) -> put(k, j(v)) } })

internal fun arr(items: Iterable<Any?>): JsonArray = JsonArray(items.map { j(it) })

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** "2026-11-11T19:00:00Z" (second precision, like the arrs). */
internal fun iso(i: Instant): String = i.truncatedTo(ChronoUnit.SECONDS).toString()

/** Wizarr's naive UTC "2026-11-11T19:00:00". */
internal fun naive(i: Instant): String = LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC).toString()

internal fun hours(h: Double): Duration = Duration.ofSeconds((h * 3600).toLong())
internal fun days(d: Double): Duration = Duration.ofSeconds((d * 86400).toLong())

/** Stable 32-hex id (Jellyfin style) for a string. */
internal fun hexId(seed: String): String =
    MessageDigest.getInstance("MD5").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

/** Stable 40-hex info hash for a string. */
internal fun infoHash(seed: String): String =
    MessageDigest.getInstance("SHA-1").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

/** Deterministic pseudo-random in [0, 1) for a seed (no state, so responses are stable between polls). */
internal fun rand(seed: String): Double {
    val h = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
    val v = ((h[0].toLong() and 0xFF) shl 16) or ((h[1].toLong() and 0xFF) shl 8) or (h[2].toLong() and 0xFF)
    return v / 16_777_216.0
}

/** "Sprite Fright" → "Sprite.Fright" for release names. */
internal fun dotted(title: String): String =
    title.replace("&", "and").replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(Regex("\\s+"), ".")

/**
 * Loads `assets/demo/<path>` as JSON. String values starting with `@now` are times relative to now:
 * `@now-3h`, `@now+2d`, `@now-90m`; `@naive…` gives Wizarr's zone-less format.
 */
internal object DemoAssets {
    /** Reads `assets/demo/<path>` (set from the app context; tests point it at the source tree). */
    @Volatile var loader: (String) -> ByteArray? = { null }

    fun use(context: Context) {
        val assets = context.applicationContext.assets
        loader = { path -> runCatching { assets.open("demo/$path").use { it.readBytes() } }.getOrNull() }
    }

    fun text(path: String): String = (loader(path) ?: error("Missing demo asset $path")).decodeToString()

    fun bytes(path: String): ByteArray? = loader(path)

    fun json(path: String, now: Instant = Instant.now()): JsonElement = resolve(AppJson.parseToJsonElement(text(path)), now)

    private val REL = Regex("^@(now|naive)([+-]\\d+(?:\\.\\d+)?)([mhd])$")

    private fun resolve(e: JsonElement, now: Instant): JsonElement = when (e) {
        is JsonObject -> JsonObject(LinkedHashMap<String, JsonElement>().apply { e.forEach { (k, v) -> put(k, resolve(v, now)) } })
        is JsonArray -> JsonArray(e.map { resolve(it, now) })
        is JsonPrimitive -> {
            val m = if (e.isString) REL.find(e.content) else null
            if (m == null) e else {
                val n = m.groupValues[2].toDouble()
                val secs = (n * when (m.groupValues[3]) { "m" -> 60.0; "h" -> 3600.0; else -> 86400.0 }).toLong()
                val t = now.plusSeconds(secs)
                JsonPrimitive(if (m.groupValues[1] == "naive") naive(t) else iso(t))
            }
        }
        else -> e
    }
}
