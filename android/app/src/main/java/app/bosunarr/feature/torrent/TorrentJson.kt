package app.bosunarr.feature.torrent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Lenient readers for the Transmission and Deluge RPC answers (numbers may come as floats, fields may be missing).

internal fun JsonElement?.jObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.jArr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.jText(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.jDbl(): Double? = jText()?.trim()?.toDoubleOrNull()
internal fun JsonElement?.jLng(): Long? = jText()?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
internal fun JsonElement?.jInt(): Int? = jLng()?.toInt()
internal fun JsonElement?.jBool(): Boolean? = when (jText()?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

/** qBittorrent's "no ETA" value, which the shared torrent UI treats as unknown. */
internal const val NO_ETA = 8_640_000L
