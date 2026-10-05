package org.fossify.phone.extensions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.net.toUri
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.phone.helpers.Config
import org.fossify.phone.models.SIMAccount

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager
    get() = getSystemService(Context.POWER_SERVICE) as PowerManager

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val simAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                label += " ($address)"
            }

            simAccounts.add(
                SIMAccount(
                    id = index + 1,
                    handle = phoneAccount.accountHandle,
                    label = label,
                    phoneNumber = address.substringAfter("tel:"),
                    color = phoneAccount.highlightColor
                )
            )
        }
    } catch (ignored: Exception) {
    }

    return simAccounts
}

/**
 * Whether we should take over ringing for this call: the per-SIM ringtones feature is on (Q+, since
 * suppressing the system ringer needs setSilenceCall, API 29+) and the caller has no contact-specific
 * ringtone (contact ringtones win).
 *
 * Deliberately independent of the SIM: a CallScreeningService is NOT given the PhoneAccountHandle
 * (getAccountHandle() is null there) - only the InCallService is - yet both must reach the same
 * "take over the ringer?" decision, otherwise the screening service fails to silence and the system
 * ringtone plays on top of ours. Which ringtone to play (per SIM) is decided later, in the
 * InCallService, where the handle is available.
 *
 * Do Not Disturb: once we silence it, the system ringer no longer applies the DND rules for us, so we
 * only take over when DND is off or lets this caller through. In every other case the system ringer
 * is left alone and applies DND (priority contacts, repeat callers...) exactly as without this feature.
 */
fun Context.shouldPlayCustomRingtone(number: String?): Boolean {
    if (!isQPlus() || !config.perSimRingtonesEnabled) {
        return false
    }

    if (!notificationManager.isCallAllowedByDnd(number, hasPermission(PERMISSION_READ_CONTACTS))) {
        return false
    }

    return number == null || !contactHasCustomRingtone(number)
}

/**
 * The ringtone the InCallService should play once the system ringer has been silenced: the calling
 * SIM's configured ringtone, or the system default ringtone as a fallback (we already silenced the
 * system, so we must play something). Null when we should not take over ringing at all.
 */
fun Context.resolveCustomRingtoneUri(number: String?, handle: PhoneAccountHandle?): Uri? {
    if (!shouldPlayCustomRingtone(number)) {
        return null
    }

    val simRingtone = handle?.let { config.getSimRingtone(it) }
    if (!simRingtone.isNullOrEmpty()) {
        return simRingtone.toUri()
    }

    return RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
}

private fun Context.contactHasCustomRingtone(number: String): Boolean {
    if (!hasPermission(PERMISSION_READ_CONTACTS)) {
        return false
    }

    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return try {
        contentResolver.query(uri, arrayOf(PhoneLookup.CUSTOM_RINGTONE), null, null, null)
            ?.use { cursor -> cursor.hasNonEmptyRingtone() } ?: false
    } catch (ignored: Exception) {
        false
    }
}

private fun Cursor.hasNonEmptyRingtone(): Boolean {
    while (moveToNext()) {
        if (!getString(0).isNullOrEmpty()) {
            return true
        }
    }
    return false
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (ignored: Exception) {
        false
    }
}

fun Context.clearMissedCalls() {
    ensureBackgroundThread {
        try {
            // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
            // should update the database and reset the cached missed call count in MissedCallNotifier.java
            // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
            telecomManager.cancelMissedCallsNotification()
        } catch (ignored: Exception) {
        }
    }
}

fun Context.canLaunchAccountsConfiguration(): Boolean {
    return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        .resolveActivity(packageManager) != null
}

fun Context.launchAccountsConfiguration() {
    startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
}

fun Activity.startAddContactIntent(phoneNumber: String, name: String? = null) {
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, phoneNumber)
        if (!name.isNullOrEmpty()) {
            putExtra(ContactsContract.Intents.Insert.NAME, name)
        }
        launchActivityIntent(this)
    }
}
