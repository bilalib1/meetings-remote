package com.bilal.meetingsremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingStartGuardTest {
    @Test fun expiresAtDeadline() {
        val guard = MeetingStartGuard(timeoutMs = 100)
        val attempt = guard.begin(nowMs = 1_000)

        assertFalse(guard.expired(attempt, 1_099))
        assertTrue(guard.expired(attempt, 1_100))
    }

    @Test fun completionDisarmsAttempt() {
        val guard = MeetingStartGuard(timeoutMs = 100)
        val attempt = guard.begin(nowMs = 1_000)

        guard.complete()

        assertFalse(guard.expired(attempt, 2_000))
    }

    @Test fun staleCallbackCannotTimeoutNewAttempt() {
        val guard = MeetingStartGuard(timeoutMs = 100)
        val first = guard.begin(nowMs = 1_000)
        guard.begin(nowMs = 1_020)

        assertFalse(guard.expired(first, 2_000))
    }
}
