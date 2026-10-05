package org.fossify.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * While there are calls, looks up the callers again when the contacts change, so a caller added to or renamed in the
 * contacts during a call is shown. [onCallContactsChanged] is called on the main thread when any caller changed.
 */
class CallContactsObserver(private val context: Context, private val onCallContactsChanged: () -> Unit) {
    companion object {
        // a contact edit or a sync changes the contacts several times in a row, look up the callers again only once
        private const val CONTACTS_CHANGE_DELAY_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isObserving = false

    private val refreshCallContactsTask = Runnable {
        ensureBackgroundThread {
            if (refreshCallContacts(context)) {
                mainHandler.post { onCallContactsChanged() }
            }
        }
    }

    private val contactsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(refreshCallContactsTask)
            mainHandler.postDelayed(refreshCallContactsTask, CONTACTS_CHANGE_DELAY_MS)
        }
    }

    fun start() {
        if (isObserving || !context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return
        }

        try {
            context.contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactsObserver)
            isObserving = true
        } catch (_: SecurityException) {
            // the callers are then only looked up once per call, as before
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(refreshCallContactsTask)
        if (isObserving) {
            context.contentResolver.unregisterContentObserver(contactsObserver)
            isObserving = false
        }
    }
}
