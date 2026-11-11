package app.bosunarr.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.bosunarr.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.File

// What each widget shows, cached on disk so widgets render instantly (and offline) without touching the network.
// `at` = last refresh attempt, `okAt` = last successful one (0 = never). Nothing here is ever sent anywhere.

@Serializable
data class DownloadItem(
    val title: String,
    val subtitle: String? = null,
    val progress: Float = 0f,
    /** Bytes/s; 0 when idle or unknown (arr queue items without a matching torrent). */
    val speed: Long = 0,
    val state: String = "",
    val link: String = DeepLink.ACTIVITY,
)

@Serializable
data class DownloadsSnapshot(
    val at: Long = 0,
    val okAt: Long = 0,
    /** At least one qBittorrent, Sonarr or Radarr is configured. */
    val configured: Boolean = true,
    val hasQbit: Boolean = false,
    val down: Long = 0,
    val up: Long = 0,
    val active: Int = 0,
    val items: List<DownloadItem> = emptyList(),
    /** Every source failed: the user-facing reason. Older data (if any) is still shown. */
    val error: String? = null,
    /** Some sources failed ("1 service unreachable"). */
    val partial: String? = null,
)

@Serializable
data class UpNextItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** LocalDate.toEpochDay() of the (local) release day. */
    val day: Long,
    /** Air time (epoch ms) for episodes; null for all-day movie releases. */
    val time: Long? = null,
    /** File name in the poster cache, null when there's no poster. */
    val poster: String? = null,
    val link: String = DeepLink.CALENDAR,
)

@Serializable
data class UpNextSnapshot(
    val at: Long = 0,
    val okAt: Long = 0,
    val configured: Boolean = true,
    val items: List<UpNextItem> = emptyList(),
    val error: String? = null,
    val partial: String? = null,
)

@Serializable
data class ServiceHealth(val id: String, val name: String, val type: String, val ok: Boolean, val error: String? = null)

@Serializable
data class StatusSnapshot(
    val at: Long = 0,
    val okAt: Long = 0,
    val services: List<ServiceHealth> = emptyList(),
    val error: String? = null,
)

enum class WidgetKind(val file: String) { Downloads("downloads"), UpNext("upnext"), Status("status") }

/**
 * Disk-backed snapshots exposed as StateFlows: a widget composition collects its flow, so a refresh that lands
 * while the Glance session is alive recomposes it directly (and `updateAll` covers sessions that aren't).
 */
object WidgetStore {
    private lateinit var dir: File
    private val lock = Any()

    private val downloads = MutableStateFlow<DownloadsSnapshot?>(null)
    private val upNext = MutableStateFlow<UpNextSnapshot?>(null)
    private val status = MutableStateFlow<StatusSnapshot?>(null)
    private val loaded = HashSet<WidgetKind>()

    /** When the user tapped refresh (0 = not refreshing); in memory only, so a dead process never spins forever. */
    val refreshingSince = MutableStateFlow(0L)

    private fun init(context: Context) {
        if (!::dir.isInitialized) synchronized(lock) {
            if (!::dir.isInitialized) dir = File(context.applicationContext.noBackupFilesDir, "widgets").apply { mkdirs() }
        }
    }

    private fun <T> load(context: Context, kind: WidgetKind, flow: MutableStateFlow<T?>, ser: KSerializer<T>): StateFlow<T?> {
        init(context)
        synchronized(lock) {
            if (kind !in loaded) {
                loaded += kind
                if (flow.value == null) {
                    flow.value = runCatching { AppJson.decodeFromString(ser, File(dir, "${kind.file}.json").readText()) }.getOrNull()
                }
            }
        }
        return flow
    }

    private fun <T> save(context: Context, kind: WidgetKind, flow: MutableStateFlow<T?>, ser: KSerializer<T>, value: T) {
        init(context)
        synchronized(lock) {
            loaded += kind
            flow.value = value
            runCatching {
                val tmp = File(dir, "${kind.file}.json.tmp")
                tmp.writeText(AppJson.encodeToString(ser, value))
                tmp.renameTo(File(dir, "${kind.file}.json"))
            }
        }
    }

    fun downloads(context: Context) = load(context, WidgetKind.Downloads, downloads, DownloadsSnapshot.serializer())
    fun upNext(context: Context) = load(context, WidgetKind.UpNext, upNext, UpNextSnapshot.serializer())
    fun status(context: Context) = load(context, WidgetKind.Status, status, StatusSnapshot.serializer())

    fun save(context: Context, v: DownloadsSnapshot) = save(context, WidgetKind.Downloads, downloads, DownloadsSnapshot.serializer(), v)
    fun save(context: Context, v: UpNextSnapshot) = save(context, WidgetKind.UpNext, upNext, UpNextSnapshot.serializer(), v)
    fun save(context: Context, v: StatusSnapshot) = save(context, WidgetKind.Status, status, StatusSnapshot.serializer(), v)

    /** Last refresh attempt for [kind] (0 = never). */
    fun attemptedAt(context: Context, kind: WidgetKind): Long = when (kind) {
        WidgetKind.Downloads -> downloads(context).value?.at
        WidgetKind.UpNext -> upNext(context).value?.at
        WidgetKind.Status -> status(context).value?.at
    } ?: 0L

    // ------------------------------------------------------------------ poster thumbnails

    /** Small (RGB_565) posters; the same Bitmap instance is reused so RemoteViews can de-duplicate it. */
    private val posters = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun posterDir(context: Context): File {
        init(context)
        return File(dir, "posters").apply { mkdirs() }
    }

    fun poster(context: Context, name: String): Bitmap? {
        posters.get(name)?.let { return it }
        val f = File(posterDir(context), name)
        if (!f.exists()) return null
        val bmp = runCatching {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull() ?: return null
        posters.put(name, bmp)
        return bmp
    }

    fun putPoster(name: String, bmp: Bitmap) { posters.put(name, bmp) }

    /** Delete poster files no longer referenced by the Up next snapshot. */
    fun prunePosters(context: Context, keep: Set<String>) {
        posterDir(context).listFiles()?.forEach { f -> if (f.name !in keep) { f.delete(); posters.remove(f.name) } }
    }
}
