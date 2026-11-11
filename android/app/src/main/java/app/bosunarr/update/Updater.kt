package app.bosunarr.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.bosunarr.BuildConfig
import app.bosunarr.app
import app.bosunarr.net.AppJson
import app.bosunarr.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub Releases (the APK is distributed there, not through a store).
 *
 * Safety: the APK's SHA-256 must match the release's SHA256SUMS.txt, and Android itself only installs it over the
 * current app when it's signed with the same key — a tampered or foreign APK can't replace Bosunarr.
 */
object Updater {
    const val REPO = "SelinJodhani/bosunarr"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val AUTO_INTERVAL_MS = 12 * 60 * 60 * 1000L

    @Serializable
    private data class GhAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "", val size: Long = 0)

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val page: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val published: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    data class Update(
        val version: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?,
        val apkName: String?,
        val apkSize: Long,
        val sumsUrl: String?,
        val published: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val update: Update) : State
        data class Downloading(val update: Update, val progress: Float) : State
        data class Ready(val update: Update, val file: File) : State
        data class Failed(val message: String, val update: Update? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** An update the user should be asked about (auto check found one, or a manual check). Null = no prompt. */
    val prompt = MutableStateFlow<Update?>(null)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val http: OkHttpClient by lazy {
        // Plain client: GitHub downloads redirect to its CDN, and no service credentials are ever attached here.
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Background check on app start, at most every 12 h, never for debug builds; prompts unless skipped. */
    suspend fun autoCheck() {
        if (BuildConfig.DEBUG) return
        val st = app.state.value ?: return
        if (System.currentTimeMillis() - st.lastUpdateCheck < AUTO_INTERVAL_MS) return
        app.updateState { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        val u = runCatching { fetchNewer() }.getOrNull() ?: return
        _state.value = State.Available(u)
        if (u.version != st.skippedVersion) prompt.value = u
    }

    /** "Check for updates" in Settings: always asks GitHub, prompts when there's something newer. */
    suspend fun manualCheck() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        _state.value = try {
            val u = fetchNewer()
            app.updateState { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            if (u == null) State.UpToDate else State.Available(u).also { prompt.value = u }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            State.Failed("Couldn't check for updates — ${e.message ?: "network error"}")
        }
    }

    fun skip(u: Update) {
        app.updateState { it.copy(skippedVersion = u.version) }
        prompt.value = null
    }

    private suspend fun fetchNewer(): Update? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "Bosunarr-Android").build()
        val text = http.newCall(req).await().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("GitHub answered HTTP ${res.code}")
            res.body?.string().orEmpty()
        }
        val releases = AppJson.decodeFromString(ListSerializer(GhRelease.serializer()), text)
        // Newest stable release that ships an APK.
        val r = releases.filter { !it.draft && !it.prerelease && it.assets.any { a -> a.name.endsWith(".apk") } }
            .maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) } ?: return@withContext null
        if (!isNewer(r.tag, currentVersion)) return@withContext null
        val apk = r.assets.first { it.name.endsWith(".apk") }
        Update(
            version = r.tag.removePrefix("v"),
            notes = cleanNotes(r.body.orEmpty()),
            pageUrl = r.page.ifBlank { RELEASES_URL },
            apkUrl = apk.url,
            apkName = apk.name,
            apkSize = apk.size,
            sumsUrl = r.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", true) }?.url,
            published = r.published,
        )
    }

    /** Download the APK (with progress), verify its checksum, then hand it to the system installer. */
    suspend fun downloadAndInstall(ctx: Context, u: Update) {
        val url = u.apkUrl ?: return openPage(ctx, u.pageUrl)
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(u, 0f)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "bosunarr-${u.version}.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(url).header("User-Agent", "Bosunarr-Android").build()).await().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("Download failed (HTTP ${res.code})")
                    val body = res.body ?: throw IllegalStateException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: u.apkSize
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (total > 0 && done - lastEmit > 256 * 1024) {
                                    lastEmit = done
                                    _state.value = State.Downloading(u, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                u.sumsUrl?.let { sumsUrl ->
                    val sums = http.newCall(Request.Builder().url(sumsUrl).header("User-Agent", "Bosunarr-Android").build()).await()
                        .use { it.body?.string().orEmpty() }
                    val expected = sums.lineSequence()
                        .map { it.trim().split(Regex("\\s+"), limit = 2) }
                        .firstOrNull { it.size == 2 && it[1].trimStart('*').substringAfterLast('/') == u.apkName }
                        ?.get(0)?.lowercase()
                    if (expected == null || expected != actual) {
                        out.delete()
                        throw SecurityException("The download didn't match the release checksum, so it wasn't installed.")
                    }
                }
                out
            }
            _state.value = State.Ready(u, file)
            install(ctx, file)
        } catch (e: CancellationException) {
            _state.value = State.Available(u)
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Download failed", u)
        }
    }

    /** Hand the verified APK to Android's installer (asks for "install unknown apps" the first time). */
    fun install(ctx: Context, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openPage(ctx, RELEASES_URL)
        }
    }

    fun openPage(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---------------------------------------------------------------- versions

    private fun parts(v: String): Pair<List<Int>, Boolean> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val core = clean.takeWhile { it.isDigit() || it == '.' }
        val nums = core.split('.').mapNotNull { it.toIntOrNull() }
        val pre = clean.length > core.length // "-dev", "-beta.1", "-debug"…
        return nums to pre
    }

    /** Semver-ish: numbers compared field by field; a pre-release sorts before the same numbers without one. */
    fun compareVersions(a: String, b: String): Int {
        val (na, pa) = parts(a)
        val (nb, pb) = parts(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val c = (na.getOrElse(i) { 0 }).compareTo(nb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pa == pb -> 0
            pa -> -1
            else -> 1
        }
    }

    fun isNewer(remote: String, current: String) = compareVersions(remote, current) > 0

    /** GitHub's generated notes are Markdown; show them as plain text. */
    private fun cleanNotes(md: String): String = md.lineSequence()
        .map { it.trimEnd() }
        .filterNot { it.startsWith("**Full Changelog**") || it.startsWith("<!--") }
        .map { line ->
            line.replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^\\s*[*-]\\s+"), "•  ")
                .replace("**", "")
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex(" by @\\S+ in https://\\S+"), "")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
