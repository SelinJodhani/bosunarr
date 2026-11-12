package app.bosunarr.security

/**
 * Custom headers and HTTP basic auth for services behind a reverse proxy (Cloudflare Access, Authelia/basic auth,
 * a custom API-gateway header…). Pure functions, no Android: unit-tested on the JVM.
 *
 * How they combine with a service's own auth headers ([merge]):
 *  1. The service's own headers (API key, Jellyfin token, NZBGet basic auth) come first.
 *  2. Proxy basic auth is added as `Authorization: Basic …` **only when the service's own auth doesn't already use
 *     `Authorization`** (NZBGet sends its own login as basic auth, so its proxy credentials must be the same; for
 *     Jellyfin the caller moves the token to `X-Emby-Authorization` first, see ServiceClient).
 *  3. Custom headers come last. A custom header replaces a service header **only when it has exactly the same
 *     name** (case-insensitive) — e.g. a custom `X-Api-Key` deliberately overrides the configured API key. Names
 *     that merely look similar never touch the API-key header.
 *
 * Values are secrets: never log them, never put them in exception messages.
 */
object ProxyAuth {
    /** Cloudflare Access service-token headers, offered as a one-tap preset in the form. */
    val CLOUDFLARE_ACCESS = listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")

    /** Headers the HTTP stack owns; letting users set them breaks requests in confusing ways. */
    private val RESERVED = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "keep-alive", "proxy-connection")

    /** RFC 7230 §3.2.6 `tchar`. */
    private fun isTchar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

    /** Why [name] can't be used as a header name, or null when it's fine. */
    fun nameError(name: String): String? = when {
        name.isEmpty() -> "Enter a header name"
        name.any { !isTchar(it) } -> "Letters, digits and - _ . only (no spaces or colons)"
        name.lowercase() in RESERVED -> "$name is set automatically"
        else -> null
    }

    /**
     * Why [value] can't be sent, or null when it's fine. CR/LF would allow header injection; other control and
     * non-ASCII characters are rejected by OkHttp at request time, so they are caught here instead.
     */
    fun valueError(value: String): String? = when {
        value.any { it == '\r' || it == '\n' } -> "Line breaks aren't allowed"
        value.any { it != '\t' && (it < ' ' || it > '~') } -> "Plain ASCII characters only"
        else -> null
    }

    /** RFC 7617: the user-id can't contain a colon; neither part may contain control characters. */
    fun basicUserError(user: String): String? = when {
        user.contains(':') -> "The username can't contain a colon"
        user.any { it < ' ' || it == '\u007f' } -> "Control characters aren't allowed"
        else -> null
    }

    fun basicPasswordError(password: String): String? =
        if (password.any { it < ' ' || it == '\u007f' }) "Control characters aren't allowed" else null

    /** Every problem with a set of custom headers (duplicate names included), or empty when all are valid. */
    fun headerErrors(headers: List<Pair<String, String>>): List<String> {
        val errors = mutableListOf<String>()
        val seen = HashSet<String>()
        headers.forEach { (n, v) ->
            val name = n.trim()
            nameError(name)?.let { errors += it }
            valueError(v)?.let { errors += "$name: $it" }
            if (name.isNotEmpty() && !seen.add(name.lowercase())) errors += "$name is listed twice"
        }
        return errors
    }

    /** `Basic base64(user:password)` (UTF-8), or null when no proxy user is set. */
    fun basicHeader(user: String?, password: String?): String? {
        if (user.isNullOrEmpty()) return null
        val raw = "$user:${password.orEmpty()}".toByteArray(Charsets.UTF_8)
        return "Basic " + java.util.Base64.getEncoder().encodeToString(raw)
    }

    /**
     * The final header set for one request: [own] (the service's auth headers), then proxy basic auth, then
     * [custom] headers, following the rules in the class doc. Invalid custom headers are skipped (the form
     * refuses to save them; this guards against hand-edited or restored data reaching OkHttp, which would throw).
     */
    fun merge(own: Map<String, String>, custom: Map<String, String>, basicUser: String?, basicPassword: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(k: String, v: String) {
            out.keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { out.remove(it) }
            out[k] = v
        }
        own.forEach { (k, v) -> put(k, v) }
        val basic = basicHeader(basicUser, basicPassword)
        if (basic != null && out.keys.none { it.equals("Authorization", ignoreCase = true) }) put("Authorization", basic)
        custom.forEach { (k, v) ->
            val name = k.trim()
            if (nameError(name) == null && valueError(v) == null) put(name, v)
        }
        return out
    }
}
