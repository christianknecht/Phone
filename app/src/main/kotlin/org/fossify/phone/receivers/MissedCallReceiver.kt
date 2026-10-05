package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.helpers.MissedCallNotifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Telecom sends this broadcast to the default dialer instead of posting its own missed call notification
 * whenever the missed call count changes (a new missed call, after a reboot, or 0 once they have been cleared).
 * Only the count is used, the calls themselves are read from the call log.
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) {
            return
        }

        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val notifier = MissedCallNotifier(context.applicationContext)
        if (count <= 0) {
            notifier.cancelNotification()
            return
        }

        val pendingResult = goAsync()
        val isFinished = AtomicBoolean(false)
        val finish = {
            if (isFinished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }

        ensureBackgroundThread {
            try {
                notifier.showNotification(count, finish)
            } catch (ignored: Exception) {
                finish()
            }
        }
    }
}
