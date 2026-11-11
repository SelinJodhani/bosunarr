package app.bosunarr.feature.usenet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

// Lenient readers: both clients mix strings and numbers ("1277.65" vs 1277.65, "true" vs true).

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.dbl(): Double? = text()?.trim()?.toDoubleOrNull()
internal fun JsonElement?.lng(): Long? = text()?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
internal fun JsonElement?.bool(): Boolean? = when (text()?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

internal fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
