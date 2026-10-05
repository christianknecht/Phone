package org.fossify.phone.helpers

import android.os.SystemClock

/**
 * The "take over the ringer?" decision for incoming calls, made once by the call screening service and
 * reused by CallService, so the two can never disagree (e.g. DND changing in between): either the
 * system ringer was silenced and we play the SIM ringtone, or it wasn't and we stay quiet.
 *
 * Process-level and keyed by the caller's number (the handle's scheme specific part, empty for hidden
 * numbers), since the screening service and the InCallService get different Call objects. Entries are
 * consumed when read and expire quickly, so a decision can't leak into a later call from the same number.
 */
object RingerTakeoverDecisions {
    private const val ENTRY_TTL_MS = 30_000L

    private class Entry(val takeOver: Boolean, val createdAt: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun put(number: String?, takeOver: Boolean) {
        val now = SystemClock.elapsedRealtime()
        entries.values.removeAll { now - it.createdAt > ENTRY_TTL_MS }
        entries[number.orEmpty()] = Entry(takeOver, now)
    }

    /** Returns and removes the decision for this number, null when the screening service made none. */
    @Synchronized
    fun take(number: String?): Boolean? {
        val entry = entries.remove(number.orEmpty()) ?: return null
        val isExpired = SystemClock.elapsedRealtime() - entry.createdAt > ENTRY_TTL_MS
        return if (isExpired) null else entry.takeOver
    }

    @Synchronized
    fun clear(number: String?) {
        entries.remove(number.orEmpty())
    }
}
