package org.fossify.phone.helpers

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.Contact
import java.util.concurrent.ConcurrentHashMap

/**
 * The numbers of the favorite contacts, for the favorites filter of the call history. They are read from the
 * contacts provider rather than taken from the loaded contacts, which keep only one of the raw contacts of a contact
 * and so lose the numbers of the others.
 */
class FavoriteNumbers(private val numbers: List<String>) {
    private val matches = ConcurrentHashMap<String, Boolean>()

    fun contains(number: String): Boolean {
        return matches.getOrPut(number) {
            @Suppress("DEPRECATION")
            numbers.any { PhoneNumberUtils.compare(it, number) }
        }
    }

    companion object {
        /** Call from a background thread. [favorites] adds the numbers of the starred private contacts. */
        fun load(context: Context, favorites: List<Contact>): FavoriteNumbers {
            val numbers = ArrayList<String>()
            favorites.filter { it.source == SMT_PRIVATE }.flatMapTo(numbers) { contact ->
                contact.phoneNumbers.map { it.value }
            }

            numbers.addAll(queryStarredNumbers(context))
            return FavoriteNumbers(numbers.distinct())
        }

        private fun queryStarredNumbers(context: Context): List<String> {
            val numbers = ArrayList<String>()
            val projection = arrayOf(Phone.NUMBER)
            val selection = "${Phone.STARRED} = 1"
            try {
                context.contentResolver.query(Phone.CONTENT_URI, projection, selection, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(numbers::add)
                    }
                }
            } catch (ignored: Exception) {
            }

            return numbers
        }
    }
}
