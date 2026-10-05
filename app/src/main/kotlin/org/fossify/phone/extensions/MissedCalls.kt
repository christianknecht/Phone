package org.fossify.phone.extensions

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog.Calls
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getIntValueOrNull
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValueOrNull
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.phone.receivers.MissedCallReceiver

// The missed calls the user hasn't seen yet, as the system dialers define them
private const val NEW_MISSED_CALLS_SELECTION = "${Calls.TYPE} = ${Calls.MISSED_TYPE} AND ${Calls.NEW} = 1" +
    " AND (${Calls.IS_READ} IS NULL OR ${Calls.IS_READ} = 0)"

class MissedCall(val number: String, val isHidden: Boolean, val date: Long, val accountId: String?)

/** The missed calls not seen yet, newest first, without the ones from blocked numbers. */
fun Context.getNewMissedCalls(): List<MissedCall> {
    val calls = ArrayList<MissedCall>()
    val projection = arrayOf(Calls.NUMBER, Calls.NUMBER_PRESENTATION, Calls.DATE, Calls.PHONE_ACCOUNT_ID)
    try {
        contentResolver.query(
            Calls.CONTENT_URI, projection, NEW_MISSED_CALLS_SELECTION, null, "${Calls.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getStringValueOrNull(Calls.NUMBER).orEmpty()
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val isHidden = presentation != Calls.PRESENTATION_ALLOWED || number.isBlank() || number == "-1"
                calls.add(
                    MissedCall(
                        number = number,
                        isHidden = isHidden,
                        date = cursor.getLongValue(Calls.DATE),
                        accountId = cursor.getStringValueOrNull(Calls.PHONE_ACCOUNT_ID)
                    )
                )
            }
        }
    } catch (ignored: Exception) {
    }

    // blocked calls are logged as BLOCKED_TYPE, this only catches numbers blocked after the call was logged
    val blockedNumbers = getBlockedNumbers()
    return calls.filter { it.isHidden || !isNumberBlocked(it.number, blockedNumbers) }
}

/** Marks the missed calls as seen in the call log, like the system does when it handles the notification itself. */
fun Context.markMissedCallsAsRead() {
    if (!hasPermission(PERMISSION_WRITE_CALL_LOG)) {
        return
    }

    try {
        val values = ContentValues().apply {
            put(Calls.NEW, 0)
            put(Calls.IS_READ, 1)
        }
        val selection = "${Calls.TYPE} = ${Calls.MISSED_TYPE} AND (${Calls.NEW} = 1" +
            " OR ${Calls.IS_READ} IS NULL OR ${Calls.IS_READ} = 0)"
        contentResolver.update(Calls.CONTENT_URI, values, selection, null)
    } catch (ignored: Exception) {
    }
}

/**
 * Telecom gives the missed call notification to the default dialer only if a receiver for it is enabled when the
 * call is missed (MissedCallNotifierImpl.shouldManageNotificationThroughDefaultDialer queries the receivers each time),
 * and then no longer posts its own. So keep the receiver enabled only while the app can read the call log and post
 * notifications, otherwise the system notification is used.
 */
fun Context.updateMissedCallReceiverState() {
    try {
        val canNotify = hasPermission(PERMISSION_READ_CALL_LOG) && notificationManager.areNotificationsEnabled()
        val state = if (canNotify) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val component = ComponentName(this, MissedCallReceiver::class.java)
        if (packageManager.getComponentEnabledSetting(component) != state) {
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    } catch (ignored: Exception) {
    }
}
