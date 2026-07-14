package com.bilal.meetingsremote

/**
 * Tracks one accepted join/start request without tying the policy to Android's
 * Handler. A generation token prevents a delayed callback from an older
 * attempt from timing out a newer one.
 */
internal class MeetingStartGuard(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private var generation = 0L
    private var deadlineMs = Long.MIN_VALUE

    fun begin(nowMs: Long): Long {
        generation += 1
        deadlineMs = nowMs + timeoutMs
        return generation
    }

    fun complete() {
        deadlineMs = Long.MIN_VALUE
    }

    fun expired(attempt: Long, nowMs: Long): Boolean =
        attempt == generation && deadlineMs != Long.MIN_VALUE && nowMs >= deadlineMs

    companion object {
        // Zoom documents error 5003 as no server response within 30 seconds.
        // Five seconds of grace keeps our UI bounded without racing that error.
        const val DEFAULT_TIMEOUT_MS = 35_000L
    }
}
