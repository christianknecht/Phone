package org.fossify.phone.extensions

import android.content.Context
import android.provider.ContactsContract
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.Contact

/**
 * Device contacts are loaded per raw contact, so a single aggregated contact shows up several times when its
 * raw contacts (Google, WhatsApp, Signal...) carry slightly different names. Keep one entry per aggregated contact,
 * preferring the raw contact Android uses for the contact's display name, then one carrying that name.
 */
fun Context.distinctByAggregatedContact(contacts: List<Contact>): ArrayList<Contact> {
    val isAggregated = { contact: Contact -> contact.source != SMT_PRIVATE && contact.contactId != 0 }
    val duplicates = contacts.filter(isAggregated).groupBy { it.contactId }.filterValues { it.size > 1 }
    if (duplicates.isEmpty()) {
        return ArrayList(contacts)
    }

    val aggregatedNames = getAggregatedNames(duplicates.keys)
    val kept = duplicates.mapValues { (contactId, group) ->
        val aggregatedName = aggregatedNames[contactId]
        group.firstOrNull { it.id == aggregatedName?.rawContactId }
            ?: group.firstOrNull { it.name.equals(aggregatedName?.displayName, ignoreCase = true) }
            ?: group.first()
    }

    return contacts.filterTo(ArrayList()) { contact ->
        !isAggregated(contact) || kept[contact.contactId].let { it == null || it === contact }
    }
}

private class AggregatedName(val rawContactId: Int, val displayName: String?)

private fun Context.getAggregatedNames(contactIds: Collection<Int>): Map<Int, AggregatedName> {
    val aggregatedNames = HashMap<Int, AggregatedName>()
    val projection = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.NAME_RAW_CONTACT_ID,
        ContactsContract.Contacts.DISPLAY_NAME
    )
    val selection = "${ContactsContract.Contacts._ID} IN (${contactIds.joinToString(",") { "?" }})"
    val selectionArgs = contactIds.map { it.toString() }.toTypedArray()
    try {
        contentResolver.query(ContactsContract.Contacts.CONTENT_URI, projection, selection, selectionArgs, null)?.use {
            while (it.moveToNext()) {
                val contactId = it.getInt(0)
                aggregatedNames[contactId] = AggregatedName(rawContactId = it.getInt(1), displayName = it.getString(2))
            }
        }
    } catch (ignored: Exception) {
    }
    return aggregatedNames
}
