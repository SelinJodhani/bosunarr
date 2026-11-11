package app.bosunarr.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Whole-run cap: services are checked concurrently with their own (shorter) budget. */
private const val RUN_BUDGET_MS = 60_000L

/** The periodic background check. Always succeeds: a failed check just waits for the next period. */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            withTimeoutOrNull(RUN_BUDGET_MS) { Poller.run(applicationContext) }
                ?: NotifyPrefs.get(applicationContext).recordRun("Check took too long and was stopped")
        } catch (e: CancellationException) {
            throw e // stopped by WorkManager (constraints changed, cancelled)
        } catch (e: Exception) {
            runCatching { NotifyPrefs.get(applicationContext).recordRun("Check failed: ${e.javaClass.simpleName}") }
        }
        return Result.success()
    }
}

object NotifyScheduler {
    private const val PERIODIC = "bosunarr-notify"
    private const val ONE_OFF = "bosunarr-notify-now"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Make the scheduled work match the settings (call at app start and whenever settings change). */
    fun sync(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            val s = NotifyPrefs.get(context).settings.value
            if (!s.enabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            Notifier.ensureChannels(context)
            val req = PeriodicWorkRequestBuilder<NotifyWorker>(s.intervalMin.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            // UPDATE keeps the current schedule when nothing changed, and applies a new interval in place.
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    /** Run one check as soon as the network allows ("Check now", and right after turning notifications on). */
    fun runNow(context: Context) {
        runCatching {
            val req = OneTimeWorkRequestBuilder<NotifyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
