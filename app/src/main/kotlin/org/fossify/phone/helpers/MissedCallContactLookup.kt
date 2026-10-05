package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValueOrNull
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.extensions.config

/**
 * Finds the contact of the few numbers shown in the missed call notification, one lookup per number, which is much
 * faster than loading every contact while Telecom waits for the broadcast to finish.
 */
class MissedCallContactLookup(private val context: Context) {
    class FoundContact(val name: String, val photoUri: String)

    private val canReadContacts = context.hasPermission(PERMISSION_READ_CONTACTS)

    // the contacts stored only in Fossify Contacts, unknown to the system, loaded only if needed
    private val privateContacts by lazy { loadPrivateContacts() }

    init {
        if (canReadContacts) {
            Contact.startWithSurname = context.config.startNameWithSurname
        }
    }

    fun find(number: String): FoundContact? {
        if (!canReadContacts) {
            return null
        }

        return findSystemContact(number) ?: findPrivateContact(number)
    }

    private fun findSystemContact(number: String): FoundContact? {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }

                val lookupKey = cursor.getStringValueOrNull(PhoneLookup.LOOKUP_KEY)
                val displayName = cursor.getStringValueOrNull(PhoneLookup.DISPLAY_NAME)
                val photoUri = cursor.getStringValueOrNull(PhoneLookup.PHOTO_URI).orEmpty()
                // the system display name ignores the app's name format, use the app's own when possible
                val name = lookupKey?.let { getFormattedName(it) } ?: displayName
                name?.takeIf { it.isNotBlank() }?.let { FoundContact(it, photoUri) }
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun getFormattedName(lookupKey: String): String? {
        return try {
            ContactsHelper(context).getContactWithLookupKey(lookupKey)?.getNameToDisplay()?.takeIf { it.isNotBlank() }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun findPrivateContact(number: String): FoundContact? {
        val contact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) } ?: return null
        val name = contact.getNameToDisplay().takeIf { it.isNotBlank() } ?: return null
        return FoundContact(name, contact.photoUri)
    }

    private fun loadPrivateContacts(): List<Contact> {
        if (SMT_PRIVATE in context.config.ignoredContactSources) {
            return emptyList()
        }

        return try {
            val cursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            MyContactsContentProvider.getContacts(context, cursor)
        } catch (ignored: Exception) {
            emptyList()
        }
    }
}
