package org.fossify.phone.services

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.phone.extensions.shouldPlayCustomRingtone

class SimpleCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        when {
            number != null && isNumberBlocked(number) -> {
                respondToCall(callDetails, isBlocked = true)
            }

            number != null && baseConfig.blockUnknownNumbers -> {
                val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                val result = SimpleContactsHelper(this).existsSync(number, privateCursor)
                respondToCall(callDetails, isBlocked = result == ContactLookupResult.NotFound)
            }

            number == null && baseConfig.blockHiddenNumbers -> {
                respondToCall(callDetails, isBlocked = true)
            }

            else -> {
                respondToCall(callDetails, isBlocked = false)
            }
        }
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        // silence the system ringer so CallService can play our per-SIM ringtone instead.
        // resolvePerSimRingtoneUri is non-null only on Q+, so setSilenceCall (API 29+) is safe here.
        val silenceSystemRinger = !isBlocked && shouldSilenceSystemRinger(callDetails)
        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSkipCallLog(isBlocked)
            .setSkipNotification(isBlocked)
            .apply {
                if (silenceSystemRinger && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setSilenceCall(true)
                }
            }
            .build()

        respondToCall(callDetails, response)
    }

    private fun shouldSilenceSystemRinger(callDetails: Call.Details): Boolean {
        val number = callDetails.handle?.schemeSpecificPart
        return shouldPlayCustomRingtone(number)
    }
}
