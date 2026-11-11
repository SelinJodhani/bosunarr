package app.bosunarr.demo

import android.content.Context
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.URLDecoder

/** One request to a fake service, already parsed. */
internal class DemoRequest(
    val service: String,
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val body: String,
    val contentType: String?,
) {
    fun q(name: String): String? = query.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun qAll(name: String): List<String> = query.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    /** `application/x-www-form-urlencoded` fields (qBittorrent) or multipart text parts. */
    val form: Map<String, String> by lazy {
        if (contentType?.startsWith("multipart/") == true) {
            Regex("name=\"([^\"]+)\"(?:; filename=\"([^\"]*)\")?[^\\n]*\\r?\\n(?:[^\\r\\n]+\\r?\\n)*\\r?\\n([\\s\\S]*?)\\r?\\n--").findAll(body)
                .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }
        } else parseQuery(body).mapValues { it.value.firstOrNull().orEmpty() }
    }

    val json: JsonElement? by lazy { runCatching { app.bosunarr.net.AppJson.parseToJsonElement(body) }.getOrNull() }
}

/** What a fake service answers. */
internal class DemoResponse(
    val code: Int,
    val body: ByteArray,
    val type: String = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(e: JsonElement, code: Int = 200) = DemoResponse(code, e.toString().toByteArray())
        fun text(t: String, code: Int = 200) = DemoResponse(code, t.toByteArray(), "text/plain; charset=utf-8")
        fun empty(code: Int = 204) = DemoResponse(code, ByteArray(0), "text/plain")
        fun notFound(message: String = "Not found") = json(obj("message" to message), 404)
    }
}

internal fun parseQuery(q: String?): Map<String, List<String>> {
    if (q.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, MutableList<String>>()
    for (part in q.split('&')) {
        if (part.isEmpty()) continue
        val i = part.indexOf('=')
        val k = URLDecoder.decode(if (i >= 0) part.substring(0, i) else part, "UTF-8")
        val v = if (i >= 0) URLDecoder.decode(part.substring(i + 1), "UTF-8") else ""
        out.getOrPut(k) { mutableListOf() } += v
    }
    return out
}

/**
 * Answers every request to `*.bosunarr.invalid` (the demo services) locally, from bundled fixtures and an
 * in-memory simulation — nothing goes to the network. Installed on the base OkHttp client, so the API client and
 * the image loader both go through it; any other host passes straight through.
 */
class DemoInterceptor internal constructor() : Interceptor {
    constructor(context: Context) : this() {
        DemoAssets.use(context)
    }

    /** Simulated LAN latency for API calls (tests turn it off). */
    internal var latency = true

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!host.endsWith(Demo.DOMAIN_SUFFIX)) return chain.proceed(request)
        val res = try {
            handle(request, host.removeSuffix(Demo.DOMAIN_SUFFIX))
        } catch (e: Exception) {
            DemoResponse.json(obj("message" to "Demo: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()), 500)
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(res.code)
            .message(if (res.code in 200..299) "OK" else "Error")
            .header("Content-Type", res.type)
            .header("Content-Length", res.body.size.toString())
            .apply { res.headers.forEach { (k, v) -> header(k, v) } }
            .body(res.body.toResponseBody(res.type.toMediaType()))
            .build()
    }

    private fun handle(request: Request, name: String): DemoResponse {
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val r = DemoRequest(
            service = name.removePrefix("demo-"),
            method = request.method,
            path = request.url.encodedPath.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.trimEnd('/').ifEmpty { "/" },
            query = parseQuery(request.url.encodedQuery),
            body = body,
            contentType = request.body?.contentType()?.toString(),
        )
        // Images answer immediately; API calls take a moment, like a real server on the LAN.
        val image = DemoImages.serve(r)
        if (image != null) return image
        if (latency) Thread.sleep(40L + (Math.random() * 110).toLong())
        return DemoWorld.handle(r)
    }
}
