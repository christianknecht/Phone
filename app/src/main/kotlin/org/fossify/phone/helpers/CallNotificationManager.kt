package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.telecom.Call
import android.telecom.PhoneAccount
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.setText
import org.fossify.commons.extensions.setVisibleIf
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isSPlus
import org.fossify.phone.R
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.hasCapability
import org.fossify.phone.models.CallContact
import org.fossify.phone.receivers.CallActionReceiver

private const val TOGGLE_MUTE_CODE = 2
private const val TOGGLE_HOLD_CODE = 3

class CallNotificationManager(private val context: Context, private val service: Service? = null) {
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

        val content = NotificationContent(
            channelId = channelId,
            caller = callContact,
            status = context.getString(getCallStatusTextId(callState)),
            avatar = callContactAvatar?.let { callContactAvatarHelper.getCircularBitmap(it) },
            callState = callState,
            isHighPriority = isHighPriority,
            isUpdate = isUpdate,
        )

        // Android drops an ongoing call style notification that doesn't come from a foreground service
        val useCallStyle = isSPlus() && service != null
        val notification = if (useCallStyle) buildCallStyleNotification(content) else buildLegacyNotification(content)
        synchronized(lock) {
            // the call state changed or the call ended meanwhile, a newer setup or the cancellation wins
            if (setupGeneration == generation) {
                if (!useCallStyle) {
                    notificationManager.notify(CALL_NOTIFICATION_ID, notification)
                } else if (!startForeground(notification)) {
                    notificationManager.notify(CALL_NOTIFICATION_ID, buildLegacyNotification(content))
                }
            }
        }
    }

    private class NotificationContent(
        val channelId: String,
        val caller: CallContact,
        val status: String,
        val avatar: Bitmap?,
        val callState: Int?,
        val isHighPriority: Boolean,
        val isUpdate: Boolean,
    )

    private fun getBaseBuilder(content: NotificationContent): Notification.Builder {
        val openAppPendingIntent =
            PendingIntent.getActivity(context, 0, CallActivity.getStartIntent(context), PendingIntent.FLAG_MUTABLE)

        val builder = Notification.Builder(context, content.channelId)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentIntent(openAppPendingIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(content.callState == Call.STATE_ACTIVE)
            .setChannelId(content.channelId)
            // adding the caller's name must not ring, vibrate or open the call screen again
            .setOnlyAlertOnce(content.isUpdate)

        // the chronometer counts from the start of the call, not from the last update of the notification
        val connectTime = CallManager.getPrimaryCall()?.details?.connectTimeMillis ?: 0L
        if (content.callState == Call.STATE_ACTIVE && connectTime > 0) {
            builder.setWhen(connectTime)
        }

        if (content.isHighPriority) {
            builder.setFullScreenIntent(openAppPendingIntent, true)
        }
        return builder
    }

    private fun buildLegacyNotification(content: NotificationContent): Notification {
        val collapsedView = RemoteViews(context.packageName, R.layout.call_notification).apply {
            setText(R.id.notification_caller_name, context.getCallerName(content.caller))
            setText(R.id.notification_call_status, content.status)
            setVisibleIf(R.id.notification_accept_call, content.callState == Call.STATE_RINGING)

            setOnClickPendingIntent(
                R.id.notification_decline_call,
                context.getCallActionPendingIntent(DECLINE_CALL, DECLINE_CALL_CODE)
            )
            setOnClickPendingIntent(
                R.id.notification_accept_call,
                context.getCallActionPendingIntent(ACCEPT_CALL, ACCEPT_CALL_CODE)
            )

            if (content.avatar != null) {
                setImageViewBitmap(R.id.notification_thumbnail, content.avatar)
            }
        }

        return getBaseBuilder(content)
            .setCustomContentView(collapsedView)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
    }

    // The system call style: a call chip in the status bar, a bigger notification shown first, the usual answer and
    // hang up buttons
    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildCallStyleNotification(content: NotificationContent): Notification {
        val person = Person.Builder()
            .setName(context.getCallerName(content.caller))
            .setImportant(true)
            .apply {
                if (content.caller.number.isNotEmpty()) {
                    setUri(PhoneAccount.SCHEME_TEL + ":" + content.caller.number)
                }
                if (content.avatar != null) {
                    setIcon(Icon.createWithBitmap(content.avatar))
                }
            }
            .build()

        val declinePendingIntent = context.getCallActionPendingIntent(DECLINE_CALL, DECLINE_CALL_CODE)
        val isRinging = content.callState == Call.STATE_RINGING
        val style = if (isRinging) {
            val acceptPendingIntent = context.getCallActionPendingIntent(ACCEPT_CALL, ACCEPT_CALL_CODE)
            Notification.CallStyle.forIncomingCall(person, declinePendingIntent, acceptPendingIntent)
        } else {
            Notification.CallStyle.forOngoingCall(person, declinePendingIntent)
        }

        val builder = getBaseBuilder(content)
            .setStyle(style)
            // lets Do Not Disturb recognize the caller: a call from an allowed contact stays visible
            .addPerson(person)
            .setContentText(content.status)
        if (!isRinging) {
            context.addOngoingCallActions(builder, content.callState)
        }
        return builder.build()
    }

    // Started by Telecom's binding, the service is allowed to run in the foreground during a call
    private fun startForeground(notification: Notification): Boolean {
        val service = service ?: return false
        return try {
            if (isQPlus()) {
                service.startForeground(
                    CALL_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                service.startForeground(CALL_NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Exception) {
            false
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
            service?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(CALL_NOTIFICATION_ID)
        }
    }
}

private fun Context.getCallerName(caller: CallContact): String {
    val name = caller.name.ifEmpty { getString(R.string.unknown_caller) }
    return if (caller.numberLabel.isNotEmpty()) "$name - ${caller.numberLabel}" else name
}

private fun getCallStatusTextId(callState: Int?) = when (callState) {
    Call.STATE_RINGING -> R.string.is_calling
    Call.STATE_DIALING -> R.string.dialing
    Call.STATE_DISCONNECTED -> R.string.call_ended
    Call.STATE_DISCONNECTING -> R.string.call_ending
    Call.STATE_HOLDING -> R.string.call_on_hold
    else -> R.string.ongoing_call
}

// Android shows only a few buttons next to "Hang up": mute first, the most useful one during a call
private fun Context.addOngoingCallActions(builder: Notification.Builder, callState: Int?) {
    val muteLabel = if (CallManager.isMuted()) R.string.notification_unmute else R.string.notification_mute
    builder.addAction(
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_microphone_off_vector),
            getString(muteLabel),
            getCallActionPendingIntent(TOGGLE_MUTE, TOGGLE_MUTE_CODE)
        ).build()
    )

    val call = CallManager.getPrimaryCall()
    val isOnHold = callState == Call.STATE_HOLDING
    val canHold = call != null && call.hasCapability(Call.Details.CAPABILITY_HOLD)
    val isSingleCall = CallManager.getPhoneState() is SingleCall
    val canToggleHold = isOnHold || callState == Call.STATE_ACTIVE
    if (isSingleCall && canHold && canToggleHold) {
        val holdLabel = if (isOnHold) R.string.notification_resume else R.string.notification_hold
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_pause_vector),
                getString(holdLabel),
                getCallActionPendingIntent(TOGGLE_HOLD, TOGGLE_HOLD_CODE)
            ).build()
        )
    }
}

// FLAG_UPDATE_CURRENT, not FLAG_CANCEL_CURRENT: the buttons of a notification still shown while it is being
// updated with the caller's name must keep working
private fun Context.getCallActionPendingIntent(action: String, requestCode: Int): PendingIntent {
    val intent = Intent(this, CallActionReceiver::class.java).setAction(action)
    return PendingIntent.getBroadcast(
        this,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}
