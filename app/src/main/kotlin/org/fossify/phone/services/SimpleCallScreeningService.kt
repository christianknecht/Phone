package org.fossify.phone.services

import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.phone.extensions.shouldPlayCustomRingtone
import org.fossify.phone.helpers.RingerTakeoverDecisions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SimpleCallScreeningService : CallScreeningService() {
    companion object {
        // Telecom waits about 5 s for a screening response before letting the call ring as is. The
        // ringer decision (DND check, contact lookup) must be done well before that, otherwise we would
        // store a "take over" decision Telecom never applied and two ringtones would play.
        private const val RINGER_DECISION_BUDGET_MS = 2_000L
    }

    // Lookups hit content providers, so they run off the main thread; respondToCall may be called
    // asynchronously as long as it happens within Telecom's timeout.
    override fun onScreenCall(callDetails: Call.Details) {
        val deadline = SystemClock.elapsedRealtime() + RINGER_DECISION_BUDGET_MS
        ensureBackgroundThread {
            val number = callDetails.handle?.schemeSpecificPart
            val isBlocked = isCallBlocked(number)
            val silenceSystemRinger = !isBlocked && shouldSilenceSystemRinger(number, deadline)

            // stored before responding, so it is there when Telecom hands the call to CallService
            RingerTakeoverDecisions.put(number, silenceSystemRinger)
            respondToCall(callDetails, isBlocked, silenceSystemRinger)
        }
    }

    private fun isCallBlocked(number: String?): Boolean {
        return when {
            number != null && isNumberBlocked(number) -> true
            number != null && baseConfig.blockUnknownNumbers -> {
                val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                val result = SimpleContactsHelper(this).existsSync(number, privateCursor)
                result == ContactLookupResult.NotFound
            }

            number == null && baseConfig.blockHiddenNumbers -> true
            else -> false
        }
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean, silenceSystemRinger: Boolean) {
        // silence the system ringer so CallService can play our per-SIM ringtone instead.
        // setSilenceCall is API 29+, shouldSilenceSystemRinger is only true on Q+ anyway.
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

    // If the decision takes too long, leave the system ringer in charge (default ringtone, DND applied)
    private fun shouldSilenceSystemRinger(number: String?, deadline: Long): Boolean {
        if (!isQPlus()) {
            return false
        }

        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            return false
        }

        return try {
            CompletableFuture.supplyAsync { shouldPlayCustomRingtone(number) }
                .get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (_: ExecutionException) {
            false
        } catch (_: InterruptedException) {
            false
        }
    }
}
