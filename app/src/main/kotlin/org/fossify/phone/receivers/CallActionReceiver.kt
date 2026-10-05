package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.helpers.ACCEPT_CALL
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.DECLINE_CALL
import org.fossify.phone.helpers.MISSED_CALLS_DISMISSED

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCEPT_CALL -> {
                context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
            }

            DECLINE_CALL -> CallManager.reject()
            MISSED_CALLS_DISMISSED -> context.clearMissedCalls()
        }
    }
}
