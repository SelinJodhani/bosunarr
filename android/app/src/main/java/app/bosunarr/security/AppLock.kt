package app.bosunarr.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App lock: when on, MainActivity shows [LockGate]'s lock screen instead of the app after it has been in the
 * background for longer than [Settings.timeoutMs] (and on every cold start), until the user passes a
 * BiometricPrompt (strong biometric or the device PIN/pattern/password).
 *
 * Only MainActivity is gated. Quick Settings tiles, widgets and the notification worker have no UI and keep
 * working; they never call into this.
 *
 * Settings are plain SharedPreferences (nothing secret; read synchronously so the very first frame is already
 * the lock screen). Time is measured with the monotonic clock, so changing the phone's clock can't skip it.
 */
object AppLock {
    data class Settings(val enabled: Boolean = false, val timeoutMs: Long = 0, val hideInRecents: Boolean = true)

    /** "Lock after" choices: immediately, 1, 5, 15 minutes. */
    val TIMEOUTS = listOf(0L, 60_000L, 5 * 60_000L, 15 * 60_000L)

    fun timeoutLabel(ms: Long): String = when (ms) {
        0L -> "Immediately"
        else -> "After ${ms / 60_000} min"
    }

    private const val PREFS = "security_prefs"
    /** Leaving for the system file picker (backup) gets at least this long before it counts as "backgrounded". */
    private const val EXTERNAL_GRACE_MS = 5 * 60_000L

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    @Volatile private var initialised = false
    @Volatile private var backgroundedAt: Long? = null
    /**
     * A BiometricPrompt is showing: its device-credential screen may stop the activity, which mustn't re-lock.
     * Time-boxed, so a prompt torn down without a callback (e.g. by a rotation) can't disable the lock for good.
     */
    var authenticating: Boolean
        get() = authenticatingSince.let { it != 0L && SystemClock.elapsedRealtime() - it < 2 * 60_000L }
        set(v) { authenticatingSince = if (v) SystemClock.elapsedRealtime() else 0L }
    @Volatile private var authenticatingSince = 0L
    @Volatile private var expectingReturn = false

    /** Read the settings once per process; a cold start with the lock on starts locked. */
    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = Settings(
            enabled = p.getBoolean("enabled", false),
            timeoutMs = p.getLong("timeoutMs", 0L).takeIf { it in TIMEOUTS } ?: 0L,
            hideInRecents = p.getBoolean("hideInRecents", true),
        )
        _settings.value = s
        _locked.value = s.enabled
        initialised = true
    }

    fun update(context: Context, transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putLong("timeoutMs", s.timeoutMs)
            .putBoolean("hideInRecents", s.hideInRecents)
            .apply()
        _settings.value = s
        if (!s.enabled) _locked.value = false
    }

    /** The next trip out of the app is the user's own action (e.g. the backup file picker), not leaving it. */
    fun expectReturn() { expectingReturn = true }

    /** MainActivity.onStop. */
    fun onStop(changingConfigurations: Boolean) {
        if (changingConfigurations || authenticating) return
        backgroundedAt = SystemClock.elapsedRealtime()
        // "Immediately": cover the content now, so it's never drawn again on the way back.
        val s = _settings.value
        if (s.enabled && s.timeoutMs == 0L && !expectingReturn) _locked.value = true
    }

    /** MainActivity.onStart. */
    fun onStart() {
        val at = backgroundedAt ?: return
        backgroundedAt = null
        val grace = if (expectingReturn) EXTERNAL_GRACE_MS else 0L
        expectingReturn = false
        val s = _settings.value
        if (shouldLock(s.enabled, at, SystemClock.elapsedRealtime(), maxOf(s.timeoutMs, grace))) _locked.value = true
    }

    /** True when an app backgrounded at [backgroundedAt] must be locked at [now]. */
    fun shouldLock(enabled: Boolean, backgroundedAt: Long, now: Long, timeoutMs: Long): Boolean =
        enabled && (timeoutMs == 0L || now - backgroundedAt >= timeoutMs || now < backgroundedAt)

    fun unlock() {
        _locked.value = false
        backgroundedAt = null
    }

    /** A PIN, pattern or password is set: required, since it's the fallback (and the only way in without biometrics). */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Keep the app's content out of the recents thumbnail when "Hide in recent apps" is on (with the lock).
     * Android 13+ has a dedicated switch that still allows screenshots; older versions need FLAG_SECURE, which
     * also blocks screenshots and screen recording.
     */
    fun applyWindowProtection(activity: Activity, s: Settings = _settings.value) {
        val hide = s.enabled && s.hideInRecents
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
