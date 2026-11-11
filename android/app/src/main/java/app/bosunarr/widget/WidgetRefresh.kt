package app.bosunarr.widget

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.startup.Initializer
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.bosunarr.BuildConfig
import app.bosunarr.MainActivity
import app.bosunarr.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Keeps the widgets fresh: a 30-minute periodic job while any widget is placed, a refresh when the app comes to
 * the foreground (throttled), and the refresh button on each widget. Only placed widget kinds are fetched.
 */
object WidgetRefresh {
    private const val PERIODIC = "bosunarr.widgets.periodic"
    private const val NOW = "bosunarr.widgets.now"
    private const val PERIOD_MIN = 30L
    /** provideGlance asks for a refresh when the last attempt is older than this (the periodic job may be late). */
    const val STALE_MS = 35 * 60_000L
    /** Coming back to the app refreshes widgets at most this often. */
    private const val APP_OPEN_MIN_MS = 2 * 60_000L

    /**
     * Widget kinds currently on the home screen. Asks AppWidgetManager by receiver (names kept stable by the
     * manifest) rather than Glance's id store, which is keyed by the (R8-renamed) widget class.
     */
    fun placed(context: Context): Set<WidgetKind> {
        val m = AppWidgetManager.getInstance(context) ?: return emptySet()
        fun has(cls: Class<*>) = runCatching { m.getAppWidgetIds(ComponentName(context, cls)).isNotEmpty() }.getOrDefault(false)
        return buildSet {
            if (has(DownloadsWidgetReceiver::class.java)) add(WidgetKind.Downloads)
            if (has(UpNextWidgetReceiver::class.java)) add(WidgetKind.UpNext)
            if (has(StatusWidgetReceiver::class.java)) add(WidgetKind.Status)
        }
    }

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(PERIOD_MIN, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** One refresh as soon as possible (no network constraint, so being offline shows up as an error state). */
    fun now(context: Context) {
        val b = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
        // Expedited work needs a foreground notification before Android 12; there a normal job is quick enough.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, b.build())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** Called when MainActivity starts: keep the schedule alive and refresh if the data is a few minutes old. */
    fun onAppOpened(context: Context) {
        app.scope.launch {
            runCatching {
                val kinds = placed(context)
                if (kinds.isEmpty()) {
                    cancel(context)
                    return@runCatching
                }
                schedule(context)
                val oldest = kinds.minOf { WidgetStore.attemptedAt(context, it) }
                if (System.currentTimeMillis() - oldest > APP_OPEN_MIN_MS) now(context)
            }
            runCatching { publishPreviews(context) }
        }
    }

    /** Android 15+: generated widget-picker previews (rate-limited by the system, so once per app version). */
    private suspend fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences("bosunarr_widgets", Context.MODE_PRIVATE)
        val key = "previews_v${BuildConfig.VERSION_CODE}_${BuildConfig.VERSION_NAME}"
        if (prefs.getBoolean(key, false)) return
        val m = GlanceAppWidgetManager(context)
        val results = listOf(
            m.setWidgetPreviews(DownloadsWidgetReceiver::class),
            m.setWidgetPreviews(UpNextWidgetReceiver::class),
            m.setWidgetPreviews(StatusWidgetReceiver::class),
        )
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) prefs.edit { putBoolean(key, true) }
    }

    /** Fetch everything the placed widgets show, store it, and redraw them. */
    suspend fun refresh(context: Context) {
        val kinds = placed(context)
        // The encrypted store loads asynchronously at process start.
        val ready = kinds.isNotEmpty() && withTimeoutOrNull(10_000) { app.state.filterNotNull().first() } != null
        if (ready) coroutineScope {
            if (WidgetKind.Downloads in kinds) launch {
                guarded { WidgetStore.save(context, WidgetData.downloads(WidgetStore.downloads(context).value)) }
            }
            if (WidgetKind.UpNext in kinds) launch {
                guarded { WidgetStore.save(context, WidgetData.upNext(context, WidgetStore.upNext(context).value)) }
            }
            if (WidgetKind.Status in kinds) launch {
                guarded { WidgetStore.save(context, WidgetData.status()) }
            }
        }
        WidgetStore.refreshingSince.value = 0
        if (WidgetKind.Downloads in kinds) DownloadsWidget().updateAll(context)
        if (WidgetKind.UpNext in kinds) UpNextWidget().updateAll(context)
        if (WidgetKind.Status in kinds) StatusWidget().updateAll(context)
    }

    /** One widget's load must never take the others down (or run past WorkManager's limit). */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(120_000) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Redraw every placed widget from the cache (no network). */
    suspend fun redrawAll(context: Context) {
        DownloadsWidget().updateAll(context)
        UpNextWidget().updateAll(context)
        StatusWidget().updateAll(context)
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefresh.refresh(applicationContext)
        return Result.success()
    }
}

/** The refresh button on every widget. */
class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStore.refreshingSince.value = System.currentTimeMillis()
        WidgetRefresh.redrawAll(context)
        WidgetRefresh.now(context)
    }
}

/**
 * Registered through androidx.startup (manifest), so "refresh widgets when the app is opened" needs no change to
 * MainActivity: it watches MainActivity starts.
 */
class WidgetsInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as? Application ?: return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) WidgetRefresh.onAppOpened(activity.applicationContext)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
