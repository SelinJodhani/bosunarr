package app.bosunarr.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.bosunarr.R

/** Event kinds; each has its own channel so the user can tune sound/importance per kind in system settings. */
enum class NotifyKind(val channelId: String, val channelName: String, val description: String, val importance: Int, val fallbackLink: String) {
    Downloaded("downloaded", "Downloaded", "A movie or episode finished downloading and was imported", NotificationManager.IMPORTANCE_DEFAULT, NotifyLinks.ACTIVITY),
    Failed("failed", "Download problems", "A download failed or its import is blocked", NotificationManager.IMPORTANCE_DEFAULT, NotifyLinks.ACTIVITY),
    Request("requests", "New requests", "Someone requested a movie or series (Seerr admins)", NotificationManager.IMPORTANCE_DEFAULT, NotifyLinks.REQUESTS),
    Available("available", "Request available", "Something you requested is ready to watch", NotificationManager.IMPORTANCE_DEFAULT, NotifyLinks.REQUESTS),
    Health("health", "Service health", "Health errors, and services going offline or coming back", NotificationManager.IMPORTANCE_DEFAULT, NotifyLinks.HOME),
    Torrent("torrents", "Torrent completed", "A qBittorrent download reached 100%", NotificationManager.IMPORTANCE_LOW, NotifyLinks.ACTIVITY),
}

private const val ADULT_CHANNEL = "whisparr"
private const val TEST_CHANNEL = "test"
/** More events than this of one kind in one check → a single summary notification instead. */
private const val MAX_INDIVIDUAL = 5

/** One thing worth telling the user. [key] identifies it (re-posting the same key replaces the notification). */
data class NotifyEvent(
    val kind: NotifyKind,
    val key: String,
    val title: String,
    val text: String,
    val link: String,
    /** Whisparr: own channel, hidden on the lock screen. */
    val adult: Boolean = false,
    /** "Review" action on new requests (opens the app; never approves from the shade). */
    val reviewLink: String? = null,
)

object Notifier {
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = NotifyKind.entries.map { k ->
            NotificationChannel(k.channelId, k.channelName, k.importance).apply { description = k.description }
        } + NotificationChannel(ADULT_CHANNEL, "Whisparr", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Whisparr events (never shown on the lock screen)"
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        } + NotificationChannel(TEST_CHANNEL, "Test notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Sent from Settings → Notifications → Send test notification"
        }
        runCatching { nm.createNotificationChannels(channels) }
    }

    /** Notifications can actually be shown (runtime permission on 13+, and not blocked in system settings). */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun post(context: Context, events: List<NotifyEvent>) {
        if (events.isEmpty() || !canPost(context)) return
        ensureChannels(context)
        events.groupBy { it.kind to it.adult }.forEach { (k, list) -> postKind(context, k.first, k.second, list) }
    }

    private fun postKind(context: Context, kind: NotifyKind, adult: Boolean, events: List<NotifyEvent>) {
        val channel = if (adult) ADULT_CHANNEL else kind.channelId
        val group = "bosunarr." + (if (adult) "adult." else "") + kind.channelId
        val summaryId = group.hashCode()
        if (events.size > MAX_INDIVIDUAL) {
            // One roll-up for a burst (e.g. a season pack), tapping through to the kind's list screen.
            val title = rollupTitle(kind, events.size)
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            events.take(6).forEach { style.addLine(it.title) }
            if (events.size > 6) style.setSummaryText("+${events.size - 6} more")
            notify(context, summaryId, base(context, channel, adult, kind.fallbackLink, summaryId)
                .setContentTitle(title)
                .setContentText(events.take(3).joinToString(", ") { it.title })
                .setStyle(style)
                .setNumber(events.size))
            return
        }
        events.forEach { e ->
            val id = e.key.hashCode()
            val b = base(context, channel, adult, e.link, id)
                .setContentTitle(e.title)
                .setContentText(e.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(e.text))
                .setGroup(group)
            e.reviewLink?.let { b.addAction(0, "Review", NotifyLinks.pendingIntent(context, it, id + 1)) }
            notify(context, id, b)
        }
        // Group summary: hidden while there's a single child, collapses them once there are more.
        val style = NotificationCompat.InboxStyle()
        events.forEach { style.addLine(it.title) }
        notify(context, summaryId, base(context, channel, adult, kind.fallbackLink, summaryId)
            .setContentTitle(kind.channelName)
            .setContentText(events.joinToString(", ") { it.title })
            .setStyle(style)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN))
    }

    private fun rollupTitle(kind: NotifyKind, n: Int) = when (kind) {
        NotifyKind.Downloaded -> "$n downloads imported"
        NotifyKind.Failed -> "$n download problems"
        NotifyKind.Request -> "$n new requests"
        NotifyKind.Available -> "$n requests available"
        NotifyKind.Health -> "$n service alerts"
        NotifyKind.Torrent -> "$n torrents completed"
    }

    private fun base(context: Context, channel: String, adult: Boolean, link: String, requestCode: Int) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bosunarr)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(NotifyLinks.pendingIntent(context, link, requestCode))
            .setVisibility(if (adult) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)

    /** A sample notification so the user can check sound/appearance; opens the notification settings. */
    fun sendTest(context: Context): Boolean {
        if (!canPost(context)) return false
        ensureChannels(context)
        notify(context, "test".hashCode(), base(context, TEST_CHANNEL, false, NotifyLinks.SETTINGS, "test".hashCode())
            .setContentTitle("Notifications are working")
            .setContentText("Bosunarr will tell you about downloads, requests and service problems like this."))
        return true
    }

    @SuppressLint("MissingPermission") // checked in canPost(); a revoke between the check and here is caught
    private fun notify(context: Context, id: Int, b: NotificationCompat.Builder) {
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }
}
