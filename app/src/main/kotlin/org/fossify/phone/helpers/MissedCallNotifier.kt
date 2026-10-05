package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.CallLog.Calls
import android.telecom.PhoneAccountHandle
import android.text.format.DateUtils
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.isSPlus
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.MissedCallActionActivity
import org.fossify.phone.extensions.MissedCall
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.extensions.getNewMissedCalls
import org.fossify.phone.extensions.getNumberName
import org.fossify.phone.extensions.updateMissedCallReceiverState
import org.fossify.phone.models.CallContact
import org.fossify.phone.models.SIMAccount
import org.fossify.phone.receivers.CallActionReceiver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts the app's own missed call notification. As the default dialer declares a receiver for
 * TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION, Telecom no longer posts its own notification, it only
 * tells us when the missed call count changes. The new missed calls are read back from the call log, which also
 * gives the time and the SIM that received each call. Telecom sends the broadcast only once the call is written in
 * the call log (CallLogManager's LogCallCompletedListener), so the call log is read only once.
 *
 * The receiver is enabled only while the app can actually post this notification, see
 * [updateMissedCallReceiverState], otherwise Telecom posts its own.
 */
class MissedCallNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "missed_calls"
        private const val NOTIFICATION_ID = 43 // the ongoing call notification uses 42
        private const val OPEN_CALL_HISTORY_CODE = 10
        private const val DISMISS_CODE = 11
        private const val CALL_BACK_CODE = 12
        private const val MESSAGE_CODE = 13
        private const val MAX_LISTED_CALLS = 5

        // a notification of a group that alerts only through its summary, which is never posted, is silent
        private const val SILENT_GROUP = "missed_calls_silent"

        // the notification work runs one at a time, in the order Telecom sent the counts
        private val executor = Executors.newSingleThreadExecutor()

        // bumped whenever the notification is cancelled, so work started before that doesn't post it again
        private val generation = AtomicInteger()
        private val lock = Any()

        fun execute(work: () -> Unit) {
            executor.execute(work)
        }
    }

    private class MissedCallInfo(val call: MissedCall, val name: String, val photoUri: String, val sim: SIMAccount?)

    /**
     * Must be called off the main thread. [count], [number] and [handle] come from the Telecom broadcast and are only
     * used when the call log can't be read.
     */
    fun showNotification(count: Int, number: String?, handle: PhoneAccountHandle?) {
        val startGeneration = generation.get()
        val canReadCallLog = context.hasPermission(PERMISSION_READ_CALL_LOG)
        if (!canReadCallLog || !context.notificationManager.areNotificationsEnabled()) {
            // give the next missed calls back to Telecom, which can always show them
            context.updateMissedCallReceiverState()
            if (context.notificationManager.areNotificationsEnabled()) {
                showWithoutCallLog(count, number, handle, startGeneration)
            }
            return
        }

        val calls = context.getNewMissedCalls()
        if (calls.isEmpty()) {
            cancelNotification()
            return
        }

        val sims = context.getAvailableSIMCardLabels()
        val infos = getInfos(calls.take(MAX_LISTED_CALLS), sims)
        synchronized(lock) {
            if (generation.get() != startGeneration) {
                return
            }

            // the calls may have been marked as read meanwhile, show only the ones that are still new
            val currentCalls = context.getNewMissedCalls()
            val shown = infos.filter { info ->
                currentCalls.any { it.date == info.call.date && it.number == info.call.number }
            }

            if (currentCalls.isEmpty() || shown.isEmpty()) {
                if (currentCalls.isEmpty()) {
                    cancelNotification() // the lock is reentrant
                }
                return
            }

            val newest = currentCalls.first().date
            val isNew = newest > context.config.lastNotifiedMissedCallDate
            postNotification(shown, currentCalls.size, showSim = sims.size > 1, alert = isNew)
            if (isNew) {
                context.config.lastNotifiedMissedCallDate = newest
            }
        }
    }

    fun cancelNotification() {
        synchronized(lock) {
            generation.incrementAndGet()
            context.notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    // without access to the call log, only the latest call given by Telecom is known
    private fun showWithoutCallLog(count: Int, number: String?, handle: PhoneAccountHandle?, startGeneration: Int) {
        val isHidden = number.isNullOrBlank() || number == "-1"
        val call = MissedCall(number.orEmpty(), isHidden, System.currentTimeMillis(), handle?.id)
        val sims = context.getAvailableSIMCardLabels()
        val info = getInfos(listOf(call), sims).first()
        synchronized(lock) {
            if (generation.get() == startGeneration) {
                postNotification(listOf(info), count, showSim = sims.size > 1, alert = true)
            }
        }
    }

    private fun getInfos(calls: List<MissedCall>, sims: List<SIMAccount>): List<MissedCallInfo> {
        val contacts = MissedCallContactLookup(context)
        return calls.map { call ->
            val sim = sims.firstOrNull { it.handle.id == call.accountId }
            if (call.isHidden) {
                MissedCallInfo(call, context.getString(R.string.unknown_caller), "", sim)
            } else {
                val contact = contacts.find(call.number)
                val name = contact?.name
                    ?: context.getNumberName(call.number)
                    ?: if (context.config.formatPhoneNumbers) call.number.formatPhoneNumber() else call.number
                MissedCallInfo(call, name, contact?.photoUri.orEmpty(), sim)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun postNotification(calls: List<MissedCallInfo>, total: Int, showSim: Boolean, alert: Boolean) {
        createNotificationChannel()
        val latest = calls.first()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_missed_vector)
            .setWhen(latest.call.date)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(getOpenCallHistoryIntent())
            .setDeleteIntent(getDismissIntent())
            .setNumber(total)

        if (isSPlus()) {
            builder.setCategory(Notification.CATEGORY_MISSED_CALL)
        }

        // already notified calls, after a reboot or when one of them is cleared, are shown again without a sound
        if (!alert) {
            builder.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }

        if (total == 1) {
            builder.setContentTitle(latest.name)
                .setContentText(context.getString(R.string.missed_call))

            if (showSim && latest.sim != null) {
                builder.setSubText("${latest.sim.id} - ${latest.sim.label}")
            }

            val avatar = CallContactAvatarHelper(context)
                .getCallContactAvatar(CallContact(latest.name, latest.photoUri, latest.call.number, ""))
            if (avatar != null) {
                builder.setLargeIcon(avatar)
            }

            if (!latest.call.isHidden) {
                val callBack = context.getString(R.string.call_back)
                val sendSms = context.getString(org.fossify.commons.R.string.send_sms)
                builder.addAction(getAction(MISSED_CALL_BACK, CALL_BACK_CODE, callBack, latest))
                builder.addAction(getAction(MISSED_CALL_MESSAGE, MESSAGE_CODE, sendSms, latest))
            }
        } else {
            val title = context.resources.getQuantityString(R.plurals.missed_calls_count, total, total)
            val style = Notification.InboxStyle().setBigContentTitle(title)
            calls.take(MAX_LISTED_CALLS).forEach { style.addLine(getCallLine(it, showSim)) }
            if (total > calls.size) {
                style.setSummaryText("+${total - calls.size}")
            }

            builder.setContentTitle(title)
                .setContentText(calls.map { it.name }.distinct().joinToString(", "))
                .setStyle(style)
        }

        context.notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        val name = context.getString(R.string.missed_calls_channel)
        NotificationChannel(CHANNEL_ID, name, IMPORTANCE_DEFAULT).apply {
            context.notificationManager.createNotificationChannel(this)
        }
    }

    private fun getCallLine(info: MissedCallInfo, showSim: Boolean): String {
        val time = DateUtils.formatDateTime(context, info.call.date, DateUtils.FORMAT_SHOW_TIME)
        var line = "$time  ${info.name}"
        if (showSim && info.sim != null) {
            line += " (SIM ${info.sim.id})"
        }
        return line
    }

    private fun getOpenCallHistoryIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            type = Calls.CONTENT_TYPE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, OPEN_CALL_HISTORY_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getDismissIntent(): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).setAction(MISSED_CALLS_DISMISSED)
        return PendingIntent.getBroadcast(
            context, DISMISS_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getAction(action: String, requestCode: Int, title: String, info: MissedCallInfo): Notification.Action {
        val intent = Intent(context, MissedCallActionActivity::class.java).apply {
            this.action = action
            putExtra(MISSED_CALL_NUMBER, info.call.number)
            info.sim?.let { putExtra(MISSED_CALL_SIM_HANDLE, it.handle) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_phone_vector)
        return Notification.Action.Builder(icon, title, pendingIntent).build()
    }
}
