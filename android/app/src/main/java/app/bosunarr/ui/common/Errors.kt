package app.bosunarr.ui.common

import app.bosunarr.net.userMessage

/**
 * An error as shown to people: a plain-language [message], plus the original technical text as [details] (shown
 * behind a "Details" expander) when it was rewritten.
 */
data class FriendlyError(val message: String, val details: String? = null)

private val HTTP = Regex("""^(.*?) returned HTTP (\d{3})$""")

/**
 * Turns the network layer's messages into friendly sentences. Messages that are already friendly ("Sonarr timed
 * out", "Radarr rejected the credentials") pass through unchanged.
 */
fun friendlyError(raw: String): FriendlyError {
    val text = raw.trim()
    HTTP.find(text)?.let { m ->
        val (name, code) = m.destructured
        val c = code.toInt()
        val msg = when {
            c == 404 -> "$name couldn't find that. Check the address in Settings, including any base path."
            c == 429 -> "$name is busy. Try again in a moment."
            c in 500..599 -> "$name ran into a problem on its side. Try again, or check its logs."
            else -> "$name refused the request."
        }
        return FriendlyError(msg, "HTTP $code")
    }
    if (text.startsWith("Network error")) {
        val detail = text.substringAfter("—", "").trim().ifEmpty { null }
        val lower = text.lowercase()
        val msg = when {
            "certificate" in lower || "ssl" in lower || "trust anchor" in lower || "handshake" in lower ->
                "Couldn't make a secure connection. The server's certificate isn't trusted by this phone."
            "unable to resolve host" in lower || "no address associated" in lower ->
                "Couldn't find the server. Check the address, or that your VPN is connected."
            "timeout" in lower || "timed out" in lower -> "The connection timed out. Check your network or VPN."
            "refused" in lower -> "The server refused the connection. Is the service running?"
            else -> "Couldn't connect. Check your network or VPN."
        }
        return FriendlyError(msg, detail)
    }
    return FriendlyError(text)
}

/** Just the friendly sentence (snackbars, one-line banners). */
fun friendlyMessage(raw: String): String = friendlyError(raw).message

/** [userMessage], made friendly: for snackbars and inline errors. */
fun Throwable.friendlyMessage(): String = friendlyMessage(userMessage())
