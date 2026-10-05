package org.fossify.phone.services

import android.annotation.SuppressLint
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getStateCompat
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.extensions.resolveCustomRingtoneUri
import org.fossify.phone.extensions.updateMissedCallReceiverState
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.FlipToSilenceDetector
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.helpers.RingtoneHelper
import org.fossify.phone.helpers.SingleCall
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private val ringtoneHelper by lazy { RingtoneHelper(this) }
    private val flipToSilenceDetector by lazy { FlipToSilenceDetector(this) { silenceRinging() } }

    // ringing calls the user already silenced, so flipping the phone again doesn't re-arm the detector
    private val silencedCalls = mutableSetOf<Call>()

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state != Call.STATE_RINGING) {
                ringtoneHelper.stop()
            }
            updateFlipToSilence()
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    // When the call screening service silenced the system ringer for a SIM with a custom ringtone,
    // play that ringtone ourselves. Only for the first incoming, ringing call - a second call while
    // one is active is call-waiting and the system plays a short waiting tone instead.
    private fun maybeStartCustomRingtone(call: Call) {
        if (call.isOutgoing() || call.getStateCompat() != Call.STATE_RINGING) {
            return
        }
        if (CallManager.getPhoneState() !is SingleCall) {
            return
        }

        val number = call.details?.handle?.schemeSpecificPart
        val uri = resolveCustomRingtoneUri(number, call.details?.accountHandle) ?: return
        ringtoneHelper.start(uri)
    }

    // Listen to the accelerometer only while an incoming call is actually ringing and not silenced yet
    private fun updateFlipToSilence() {
        val hasRingingCall = config.flipToSilence && calls.any {
            !it.isOutgoing() && it.getStateCompat() == Call.STATE_RINGING && it !in silencedCalls
        }

        if (hasRingingCall) {
            flipToSilenceDetector.start()
        } else {
            flipToSilenceDetector.stop()
        }
    }

    // Silences the ringtone and vibration on this phone only, the call keeps ringing for the caller
    @SuppressLint("MissingPermission")
    private fun silenceRinging() {
        markRingingCallsSilenced()
        try {
            // allowed for the default dialer, which this InCallService being bound implies
            telecomManager.silenceRinger()
        } catch (_: SecurityException) {
            // not the default dialer anymore, nothing else we can silence
        }
    }

    private fun markRingingCallsSilenced() {
        silencedCalls.addAll(calls.filter { it.getStateCompat() == Call.STATE_RINGING })
        ringtoneHelper.stop()
        updateFlipToSilence()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        maybeStartCustomRingtone(call)
        updateFlipToSilence()

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority
            || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
            || !canUseFullScreenIntent()
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }

        // before this call can be missed, decide whether the app or Telecom will show the notification
        updateMissedCallReceiverState()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        ringtoneHelper.stop()
        call.unregisterCallback(callListener)
        silencedCalls.remove(call)
        updateFlipToSilence()
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    // The ringer was silenced from outside the app (power or volume button, or our own flip gesture going
    // through TelecomManager): the system ringer is already quiet, stop our per-SIM ringtone too.
    override fun onSilenceRinger() {
        super.onSilenceRinger()
        markRingingCallsSilenced()
    }

    override fun onDestroy() {
        super.onDestroy()
        flipToSilenceDetector.stop()
        silencedCalls.clear()
        ringtoneHelper.stop()
        callNotificationManager.cancelNotification()
    }
}
