package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import androidx.annotation.WorkerThread
import org.fossify.commons.extensions.getIntValueOrNull
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.extensions.getStringValueOrNull
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.extensions.config

/**
 * Finds the contact of a few numbers, such as the caller or the numbers of the missed call notification, one lookup
 * per number. This is much faster than loading every contact, which takes seconds with a big address book.
 *
 * The system contacts are searched first, then the contacts stored only in Fossify Contacts. The private contacts are
 * skipped when they are hidden in the contact sources filter, unless [includeHiddenPrivateContacts] is set.
 */
class ContactNumberLookup(
    private val context: Context,
    private val includeHiddenPrivateContacts: Boolean = false,
) {
    // numberLabel is the type of the matching number, like "Mobile", given only when the contact has several numbers
    class FoundContact(val name: String, val photoUri: String, val numberLabel: String)

    private val canReadContacts = context.hasPermission(PERMISSION_READ_CONTACTS)

    // the contacts stored only in Fossify Contacts, unknown to the system, loaded only if needed
    private val privateContacts by lazy { loadPrivateContacts() }

    init {
        if (canReadContacts) {
            Contact.startWithSurname = context.config.startNameWithSurname
        }
    }

    @WorkerThread
    fun find(number: String): FoundContact? {
        if (!canReadContacts || number.isBlank()) {
            return null
        }

        return findSystemContact(number) ?: findPrivateContact(number)
    }

    private fun findSystemContact(number: String): FoundContact? {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            PhoneLookup.LOOKUP_KEY,
            PhoneLookup.DISPLAY_NAME,
            PhoneLookup.PHOTO_URI,
            PhoneLookup.TYPE,
            PhoneLookup.LABEL
        )

        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }

                val lookupKey = cursor.getStringValueOrNull(PhoneLookup.LOOKUP_KEY)
                val displayName = cursor.getStringValueOrNull(PhoneLookup.DISPLAY_NAME)
                val photoUri = cursor.getStringValueOrNull(PhoneLookup.PHOTO_URI).orEmpty()
                val type = cursor.getIntValueOrNull(PhoneLookup.TYPE)
                val label = cursor.getStringValueOrNull(PhoneLookup.LABEL).orEmpty()

                // the system display name ignores the app's name format, use the app's own when possible
                val contact = lookupKey?.let { getContact(it) }
                val name = contact?.getNameToDisplay()?.takeIf { it.isNotBlank() } ?: displayName
                val numberLabel = if (type != null && (contact?.phoneNumbers?.size ?: 0) > 1) {
                    context.getPhoneNumberTypeText(type, label)
                } else {
                    ""
                }

                name?.takeIf { it.isNotBlank() }?.let { FoundContact(it, photoUri, numberLabel) }
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun getContact(lookupKey: String): Contact? {
        return try {
            ContactsHelper(context).getContactWithLookupKey(lookupKey)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun findPrivateContact(number: String): FoundContact? {
        val contact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) } ?: return null
        val name = contact.getNameToDisplay().takeIf { it.isNotBlank() } ?: return null
        val numberLabel = if (contact.phoneNumbers.size > 1) {
            contact.phoneNumbers.firstOrNull { it.value == number }
                ?.let { context.getPhoneNumberTypeText(it.type, it.label) }
                .orEmpty()
        } else {
            ""
        }

        return FoundContact(name, contact.photoUri, numberLabel)
    }

    private fun loadPrivateContacts(): List<Contact> {
        if (!includeHiddenPrivateContacts && SMT_PRIVATE in context.config.ignoredContactSources) {
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
