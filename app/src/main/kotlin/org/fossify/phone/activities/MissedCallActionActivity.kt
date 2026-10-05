package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.IntentCompat
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.MISSED_CALL_BACK
import org.fossify.phone.helpers.MISSED_CALL_MESSAGE
import org.fossify.phone.helpers.MISSED_CALL_NUMBER
import org.fossify.phone.helpers.MISSED_CALL_SIM_HANDLE

/**
 * Handles the missed call notification actions: marks the missed calls as read, removes the notification, then
 * calls back or opens the messaging app. Calling back goes through [DialerActivity] like any other call, with the
 * SIM that received the missed call, or with the SIM set as default for the number if the user prefers that.
 * Without a SIM set for the number, the receiving SIM is used even when asking for the SIM before every call is on.
 */
class MissedCallActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(MISSED_CALL_NUMBER)
        if (!number.isNullOrEmpty()) {
            clearMissedCalls()
            when (intent.action) {
                MISSED_CALL_BACK -> callBack(number)
                MISSED_CALL_MESSAGE -> launchSendSMSIntent(number)
            }
        }

        finish()
    }

    private fun callBack(number: String) {
        val numberSim = if (config.callBackWithNumberSim) config.getCustomSIM(number) else null
        val handle = numberSim
            ?: IntentCompat.getParcelableExtra(intent, MISSED_CALL_SIM_HANDLE, PhoneAccountHandle::class.java)
        Intent(this, DialerActivity::class.java).apply {
            action = Intent.ACTION_CALL
            data = Uri.fromParts("tel", number, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (handle != null) {
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            startActivity(this)
        }
    }
}
