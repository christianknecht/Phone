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
import android.text.format.DateUtils
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isSPlus
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.MissedCallActionActivity
import org.fossify.phone.extensions.MissedCall
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.extensions.getNewMissedCalls
import org.fossify.phone.extensions.getNumberName
import org.fossify.phone.models.CallContact
import org.fossify.phone.models.SIMAccount
import org.fossify.phone.receivers.CallActionReceiver

/**
 * Posts the app's own missed call notification. As the default dialer declares a receiver for
 * TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION, Telecom no longer posts its own notification, it only
 * tells us when the missed call count changes. The new missed calls are read back from the call log, which also
 * gives the time and the SIM that received each call.
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

        // Telecom normally reports the missed call once it is written in the call log, retry briefly in case not
        private const val QUERY_ATTEMPTS = 3
        private const val QUERY_RETRY_DELAY_MS = 1000L
    }

    private class MissedCallInfo(val call: MissedCall, val name: String, val photoUri: String, val sim: SIMAccount?)

    /** Must be called off the main thread, calls [onDone] once the notification is posted or cancelled. */
    fun showNotification(expectedCount: Int, onDone: () -> Unit) {
        val notificationManager = context.notificationManager
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || !notificationManager.areNotificationsEnabled()) {
            onDone()
            return
        }

        var calls = context.getNewMissedCalls()
        var attempt = 1
        while (calls.size < expectedCount && attempt < QUERY_ATTEMPTS) {
            Thread.sleep(QUERY_RETRY_DELAY_MS)
            calls = context.getNewMissedCalls()
            attempt++
        }

        if (calls.isEmpty()) {
            cancelNotification()
            onDone()
            return
        }

        getContacts { contacts ->
            ensureBackgroundThread {
                try {
                    val sims = context.getAvailableSIMCardLabels()
                    postNotification(calls.map { getInfo(it, contacts, sims) }, showSim = sims.size > 1)
                } finally {
                    onDone()
                }
            }
        }
    }

    fun cancelNotification() {
        context.notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun getContacts(callback: (List<Contact>) -> Unit) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            callback(emptyList())
            return
        }

        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        SharedContactsLoader.getContacts(context, getAll = true) { contacts ->
            ensureBackgroundThread {
                if (SMT_PRIVATE !in context.config.ignoredContactSources) {
                    try {
                        contacts.addAll(MyContactsContentProvider.getContacts(context, privateCursor))
                    } catch (ignored: Exception) {
                    }
                }
                callback(contacts)
            }
        }
    }

    private fun getInfo(call: MissedCall, contacts: List<Contact>, sims: List<SIMAccount>): MissedCallInfo {
        val sim = sims.firstOrNull { it.handle.id == call.accountId }
        if (call.isHidden) {
            return MissedCallInfo(call, context.getString(R.string.unknown_caller), "", sim)
        }

        val contact = contacts.firstOrNull { it.doesHavePhoneNumber(call.number) }
        val name = contact?.getNameToDisplay()?.takeIf { it.isNotBlank() }
            ?: context.getNumberName(call.number)
            ?: if (context.config.formatPhoneNumbers) call.number.formatPhoneNumber() else call.number
        return MissedCallInfo(call, name, contact?.photoUri.orEmpty(), sim)
    }

    @SuppressLint("NewApi")
    private fun postNotification(calls: List<MissedCallInfo>, showSim: Boolean) {
        createNotificationChannel()
        val latest = calls.first()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_missed_vector)
            .setWhen(latest.call.date)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(getOpenCallHistoryIntent())
            .setDeleteIntent(getDismissIntent())
            .setNumber(calls.size)

        if (isSPlus()) {
            builder.setCategory(Notification.CATEGORY_MISSED_CALL)
        }

        if (calls.size == 1) {
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
            val title = context.resources.getQuantityString(R.plurals.missed_calls_count, calls.size, calls.size)
            val style = Notification.InboxStyle().setBigContentTitle(title)
            calls.take(MAX_LISTED_CALLS).forEach { style.addLine(getCallLine(it, showSim)) }
            if (calls.size > MAX_LISTED_CALLS) {
                style.setSummaryText("+${calls.size - MAX_LISTED_CALLS}")
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
