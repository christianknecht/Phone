package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.telecom.Call
import android.widget.RemoteViews
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.setText
import org.fossify.commons.extensions.setVisibleIf
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.models.CallContact
import org.fossify.phone.receivers.CallActionReceiver

class CallNotificationManager(private val context: Context) {
    companion object {
        private const val CALL_NOTIFICATION_ID = 42
        private const val ACCEPT_CALL_CODE = 0
        private const val DECLINE_CALL_CODE = 1
    }

    private val notificationManager = context.notificationManager
    private val callContactAvatarHelper = CallContactAvatarHelper(context)

    // Each setupNotification() and cancelNotification() makes the previous setups outdated, so a contact lookup that
    // finishes late, after a newer call state or after the call ended, can't post an old notification again.
    private val lock = Any()
    private var generation = 0

    // the avatar of the last caller with a photo, decoded only once per call instead of at each state change
    @Volatile
    private var lastAvatar: Pair<String, Bitmap?>? = null

    /**
     * Posts the notification right away with what is already known about the caller, at first just the number, since
     * on a locked phone its full screen intent is what opens the call screen. Then updates it with the contact's name
     * and photo once they are found, without alerting again. [isRefresh] updates it without alerting at all, as when
     * the caller's contact changed.
     */
    fun setupNotification(lowPriority: Boolean = false, isRefresh: Boolean = false) {
        val call = CallManager.getPrimaryCall()
        val callState = CallManager.getState()
        val setupGeneration = synchronized(lock) { ++generation }

        val isContactKnown = isCallContactKnown(call)
        val shownContact = getCallContactNow(context, call)
        val shownAvatar = getKnownAvatar(shownContact.photoUri)
        postNotification(shownContact, shownAvatar, callState, lowPriority, setupGeneration, isUpdate = isRefresh)

        val isAvatarKnown = shownContact.photoUri.isEmpty() || lastAvatar?.first == shownContact.photoUri
        if (isContactKnown && isAvatarKnown) {
            return
        }

        getCallContact(context, call) { callContact ->
            ensureBackgroundThread {
                val avatar = getAvatar(callContact)
                if (callContact != shownContact || avatar !== shownAvatar) {
                    postNotification(callContact, avatar, callState, lowPriority, setupGeneration, isUpdate = true)
                }
            }
        }
    }

    private fun getKnownAvatar(photoUri: String): Bitmap? {
        val avatar = lastAvatar ?: return null
        return if (photoUri.isNotEmpty() && avatar.first == photoUri) avatar.second else null
    }

    private fun getAvatar(callContact: CallContact): Bitmap? {
        if (callContact.photoUri.isEmpty()) {
            return null
        }

        val known = lastAvatar
        if (known?.first == callContact.photoUri) {
            return known.second
        }

        return callContactAvatarHelper.getCallContactAvatar(callContact).also {
            lastAvatar = callContact.photoUri to it
        }
    }

    @SuppressLint("NewApi")
    private fun postNotification(
        callContact: CallContact,
        callContactAvatar: Bitmap?,
        callState: Int?,
        lowPriority: Boolean,
        setupGeneration: Int,
        isUpdate: Boolean,
    ) {
        val isHighPriority = callState == Call.STATE_RINGING && !lowPriority
        val channelId =
            if (isHighPriority) "simple_dialer_call_high_priority" else "simple_dialer_call"
        createNotificationChannel(isHighPriority, channelId)

        val openAppIntent = CallActivity.getStartIntent(context)
        val openAppPendingIntent =
            PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_MUTABLE)

        // FLAG_UPDATE_CURRENT, not FLAG_CANCEL_CURRENT: the buttons of a notification still shown while it is being
        // updated with the caller's name must keep working
        val acceptCallIntent = Intent(context, CallActionReceiver::class.java)
        acceptCallIntent.action = ACCEPT_CALL
        val acceptPendingIntent =
            PendingIntent.getBroadcast(
                context,
                ACCEPT_CALL_CODE,
                acceptCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        val declineCallIntent = Intent(context, CallActionReceiver::class.java)
        declineCallIntent.action = DECLINE_CALL
        val declinePendingIntent =
            PendingIntent.getBroadcast(
                context,
                DECLINE_CALL_CODE,
                declineCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        var callerName = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
        if (callContact.numberLabel.isNotEmpty()) {
            callerName += " - ${callContact.numberLabel}"
        }

        val contentTextId = when (callState) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_DIALING -> R.string.dialing
            Call.STATE_DISCONNECTED -> R.string.call_ended
            Call.STATE_DISCONNECTING -> R.string.call_ending
            else -> R.string.ongoing_call
        }

        val collapsedView = RemoteViews(context.packageName, R.layout.call_notification).apply {
            setText(R.id.notification_caller_name, callerName)
            setText(R.id.notification_call_status, context.getString(contentTextId))
            setVisibleIf(R.id.notification_accept_call, callState == Call.STATE_RINGING)

            setOnClickPendingIntent(R.id.notification_decline_call, declinePendingIntent)
            setOnClickPendingIntent(R.id.notification_accept_call, acceptPendingIntent)

            if (callContactAvatar != null) {
                setImageViewBitmap(
                    R.id.notification_thumbnail,
                    callContactAvatarHelper.getCircularBitmap(callContactAvatar)
                )
            }
        }

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentIntent(openAppPendingIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setCustomContentView(collapsedView)
            .setOngoing(true)
            .setUsesChronometer(callState == Call.STATE_ACTIVE)
            .setChannelId(channelId)
            .setStyle(Notification.DecoratedCustomViewStyle())
            // adding the caller's name must not ring, vibrate or open the call screen again
            .setOnlyAlertOnce(isUpdate)

        if (isHighPriority) {
            builder.setFullScreenIntent(openAppPendingIntent, true)
        }

        val notification = builder.build()
        synchronized(lock) {
            // the call state changed or the call ended meanwhile, a newer setup or the cancellation wins
            if (setupGeneration == generation) {
                notificationManager.notify(CALL_NOTIFICATION_ID, notification)
            }
        }
    }

    fun createNotificationChannel(isHighPriority: Boolean, channelId: String) {
        val name = if (isHighPriority) {
            context.getString(R.string.call_notification_channel_high_priority)
        } else {
            context.getString(R.string.call_notification_channel)
        }

        val importance = if (isHighPriority) IMPORTANCE_HIGH else IMPORTANCE_DEFAULT
        NotificationChannel(channelId, name, importance).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }
    }

    fun cancelNotification() {
        synchronized(lock) {
            generation++
            notificationManager.cancel(CALL_NOTIFICATION_ID)
        }
    }
}
