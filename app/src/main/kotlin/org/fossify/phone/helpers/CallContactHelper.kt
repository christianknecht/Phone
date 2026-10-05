package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.Call
import androidx.annotation.WorkerThread
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getNumberName
import org.fossify.phone.extensions.isConference
import org.fossify.phone.models.CallContact
import java.util.concurrent.ConcurrentHashMap

// The caller of each ongoing call, looked up once by number and kept until there are no calls anymore, so the call
// screen, the call notification and each call state change don't query the contacts again.
private val callContacts = ConcurrentHashMap<String, Lazy<CallContact>>()

/** Forgets the callers of the previous calls, call it when there are no calls anymore and on the first call. */
fun clearCallContacts() {
    callContacts.clear()
}

/**
 * What can be shown about the other party of [call] right away, without any query: the caller if they were already
 * looked up, otherwise the number (or the name given to it in the app) until [getCallContact] finds the contact.
 */
fun getCallContactNow(context: Context, call: Call?): CallContact {
    if (call.isConference()) {
        return getConferenceContact(context)
    }

    val number = call.getNumber() ?: return CallContact("", "", "", "")
    getKnownCallContact(number)?.let { return it }

    val displayedNumber = context.getDisplayedNumber(number)
    return CallContact(context.getNumberName(number) ?: displayedNumber, "", displayedNumber, "")
}

/** True when [getCallContact] would give the same as [getCallContactNow], without having to look anything up. */
fun isCallContactKnown(call: Call?): Boolean {
    if (call.isConference()) {
        return true
    }

    val number = call.getNumber() ?: return true
    return getKnownCallContact(number) != null
}

/**
 * Finds the contact of the other party of [call], by number. [callback] is called right away on the calling thread
 * when nothing needs to be looked up, otherwise on a background thread.
 */
fun getCallContact(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call.isConference()) {
        callback(getConferenceContact(context))
        return
    }

    val number = call.getNumber()
    if (number == null) {
        callback(CallContact("", "", "", ""))
        return
    }

    val known = getKnownCallContact(number)
    if (known != null) {
        callback(known)
        return
    }

    val appContext = context.applicationContext
    ensureBackgroundThread {
        // concurrent lookups of the same number wait for the first one instead of querying again
        val contact = callContacts.getOrPut(number) { lazy { lookUpCallContact(appContext, number) } }.value
        callback(contact)
    }
}

/**
 * Looks up the callers of the ongoing calls again, after the contacts changed during a call. The previous callers stay
 * shown until then. Returns true when any of them changed.
 */
@WorkerThread
fun refreshCallContacts(context: Context): Boolean {
    var hasChanged = false
    for ((number, contact) in callContacts) {
        // a lookup still running may have read the contacts before they changed, wait for it and look up again
        val previous = contact.value
        val refreshed = lookUpCallContact(context, number)
        // replaced only if not cleared or replaced meanwhile
        if (refreshed != previous && callContacts.replace(number, contact, lazyOf(refreshed))) {
            hasChanged = true
        }
    }

    return hasChanged
}

private fun getKnownCallContact(number: String): CallContact? {
    val contact = callContacts[number] ?: return null
    return if (contact.isInitialized()) contact.value else null
}

@WorkerThread
private fun lookUpCallContact(context: Context, number: String): CallContact {
    val displayedNumber = context.getDisplayedNumber(number)
    // the private contacts were always used for the caller, even when hidden in the contacts tab
    val contact = ContactNumberLookup(context, includeHiddenPrivateContacts = true).find(number)
    return if (contact != null) {
        CallContact(contact.name, contact.photoUri, displayedNumber, contact.numberLabel)
    } else {
        CallContact(context.getNumberName(number) ?: displayedNumber, "", displayedNumber, "")
    }
}

private fun getConferenceContact(context: Context) = CallContact(context.getString(R.string.conference), "", "", "")

private fun Context.getDisplayedNumber(number: String): String {
    return if (config.formatPhoneNumbers) number.formatPhoneNumber() else number
}

// the number of a "tel:" handle, null when hidden or not a phone number
private fun Call?.getNumber(): String? {
    val handle = try {
        this?.details?.handle?.toString()
    } catch (e: NullPointerException) {
        null
    } ?: return null

    val uri = Uri.decode(handle)
    return if (uri.startsWith("tel:")) uri.substringAfter("tel:") else null
}
