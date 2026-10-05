package org.fossify.phone.extensions

import android.app.NotificationManager
import android.net.Uri
import org.fossify.commons.helpers.isTiramisuPlus

/**
 * True when Do Not Disturb is off, or (Android 13+, where matchesCallFilter is public) when the
 * current DND policy lets a call from this number ring - the same check Telecom runs for its own
 * ringer (priority contacts, repeat callers...). False when DND blocks the call, and also whenever we
 * cannot tell (older Android, hidden number, no contacts permission, which matchesCallFilter needs).
 * Only INTERRUPTION_FILTER_ALL counts as "off": UNKNOWN (state not available) is treated as "can't tell".
 */
fun NotificationManager.isCallAllowedByDnd(number: String?, canReadContacts: Boolean): Boolean {
    if (currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
        return true
    }

    if (!isTiramisuPlus() || number.isNullOrEmpty() || !canReadContacts) {
        return false
    }

    return try {
        matchesCallFilter(Uri.fromParts("tel", number, null))
    } catch (ignored: Exception) {
        false
    }
}
