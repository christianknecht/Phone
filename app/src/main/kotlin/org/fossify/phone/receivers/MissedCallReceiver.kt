package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.IntentCompat
import org.fossify.phone.helpers.MissedCallNotifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Telecom sends this broadcast to the default dialer instead of posting its own missed call notification
 * whenever the missed call count changes (a new missed call, after a reboot, or 0 once they have been cleared).
 * The calls themselves are read from the call log. The receiver is protected by MODIFY_PHONE_STATE, which Telecom
 * (system uid) holds, so other apps can't send it.
 */
class MissedCallReceiver : BroadcastReceiver() {
    companion object {
        // well below the 10 seconds a foreground broadcast may take
        private const val WATCHDOG_DELAY_MS = 8000L
    }

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

        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val handle = IntentCompat.getParcelableExtra(
            intent, TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle::class.java
        )

        val pendingResult = goAsync()
        val isFinished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val finish = Runnable {
            if (isFinished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }

        // never keep the broadcast longer than allowed, even if the work gets stuck
        handler.postDelayed(finish, WATCHDOG_DELAY_MS)
        MissedCallNotifier.execute {
            try {
                notifier.showNotification(count, number, handle)
            } catch (ignored: Exception) {
            } finally {
                handler.removeCallbacks(finish)
                finish.run()
            }
        }
    }
}
