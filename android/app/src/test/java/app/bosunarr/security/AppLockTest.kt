package app.bosunarr.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    @Test
    fun lockTiming() {
        // Off: never.
        assertFalse(AppLock.shouldLock(false, 0, 10_000_000, 0))
        // Immediately: any trip to the background.
        assertTrue(AppLock.shouldLock(true, 1_000, 1_001, 0))
        // 1 min: not before, yes at/after.
        assertFalse(AppLock.shouldLock(true, 1_000, 60_999, 60_000))
        assertTrue(AppLock.shouldLock(true, 1_000, 61_000, 60_000))
        // Monotonic clock going backwards (shouldn't happen; reboot resets the process anyway): lock to be safe.
        assertTrue(AppLock.shouldLock(true, 5_000, 1_000, 60_000))
    }
}
