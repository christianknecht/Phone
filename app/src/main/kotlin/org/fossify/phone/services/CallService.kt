package org.fossify.phone.services

import android.annotation.SuppressLint
import android.media.AudioManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.audioManager
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getStateCompat
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.extensions.resolveCustomRingtoneUri
import org.fossify.phone.extensions.updateMissedCallReceiverState
import org.fossify.phone.helpers.CallContactsObserver
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.FlipToSilenceDetector
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.helpers.RingerTakeoverDecisions
import org.fossify.phone.helpers.RingtoneHelper
import org.fossify.phone.helpers.SingleCall
import org.fossify.phone.helpers.clearCallContacts
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private val ringtoneHelper by lazy { RingtoneHelper(this) }
    private val flipToSilenceDetector by lazy { FlipToSilenceDetector(this) { silenceRinging() } }

    // ringing calls the user already silenced, so flipping the phone again doesn't re-arm the detector
    private val silencedCalls = mutableSetOf<Call>()

    // a caller added to or renamed in the contacts during a call is shown on the call screen and notification
    private val callContactsObserver by lazy {
        CallContactsObserver(applicationContext) {
            // the notification of a call ending is already cancelled, don't post it again
            val state = CallManager.getState()
            val isEnding = state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING
            if (CallManager.getPhoneState() != NoCall && !isEnding) {
                callNotificationManager.setupNotification(isRefresh = true)
                CallManager.onCallContactsChanged()
            }
        }
    }

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

    // When the call screening service silenced the system ringer for this call, play the SIM ringtone
    // ourselves. The screening service's decision is reused rather than recomputed, so the two can't
    // disagree; no decision means it didn't run (or timed out) and the system ringer is still ringing,
    // so we stay quiet. Only for the first incoming, ringing call - a second call while one is active
    // is call-waiting and the system plays a short waiting tone instead.
    private fun maybeStartCustomRingtone(call: Call) {
        if (call.isOutgoing()) {
            return
        }

        val systemRingerSilenced = RingerTakeoverDecisions.take(call.callerNumber()) == true
        if (!systemRingerSilenced || call.getStateCompat() != Call.STATE_RINGING) {
            return
        }
        if (CallManager.getPhoneState() !is SingleCall) {
            return
        }

        val uri = resolveCustomRingtoneUri(call.details?.accountHandle) ?: return
        ringtoneHelper.start(uri)
    }

    // same key as the screening service: the handle's scheme specific part
    private fun Call.callerNumber() = details?.handle?.schemeSpecificPart

    // Listen to the accelerometer only while an incoming call is actually ringing and not silenced yet,
    // and not at all when the phone is in silent mode (nothing to silence)
    private fun updateFlipToSilence() {
        val isRingerSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        val hasRingingCall = config.flipToSilence && !isRingerSilent && calls.any {
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
        if (calls.size == 1) {
            // a lookup finishing after the previous call ended could have kept its caller
            clearCallContacts()
        }
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)
        callContactsObserver.start()

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
        if (!call.isOutgoing()) {
            RingerTakeoverDecisions.clear(call.callerNumber())
        }
        updateFlipToSilence()
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
            callContactsObserver.stop()
            clearCallContacts()
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
        callContactsObserver.stop()
        clearCallContacts()
    }
}
