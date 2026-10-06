package org.fossify.phone.helpers

import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.commons.models.contacts.Contact

/**
 * Finds the first of [contacts] having a number, like `contacts.firstOrNull { it.doesHavePhoneNumber(number) }`,
 * without comparing the number with every number of every contact. The call history did that for each call, which
 * kept the CPU busy for many seconds with a big address book and a long history.
 *
 * Numbers that [Contact.doesHavePhoneNumber] considers equal end with the same digits once they have enough of them,
 * so only the contacts with a number ending like the searched one are compared, plus the ones with a short number.
 * The comparison itself is unchanged, and so is the result.
 */
class ContactsByNumberIndex(private val contacts: List<Contact>) {
    // positions in contacts, in ascending order
    private val positionsByNumberEnd = HashMap<String, MutableList<Int>>()
    private val positionsWithShortNumbers = ArrayList<Int>()

    init {
        contacts.forEachIndexed { position, contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                val numberEnd = getNumberEnd(phoneNumber.normalizedNumber.normalizePhoneNumber())
                val positions = if (numberEnd == null) {
                    positionsWithShortNumbers
                } else {
                    positionsByNumberEnd.getOrPut(numberEnd) { ArrayList() }
                }

                if (positions.lastOrNull() != position) {
                    positions.add(position)
                }
            }
        }
    }

    fun findContact(number: String): Contact? {
        val numberEnd = getNumberEnd(number.normalizePhoneNumber())
            ?: return contacts.firstOrNull { it.doesHavePhoneNumber(number) }

        val candidates = positionsByNumberEnd[numberEnd].orEmpty() + positionsWithShortNumbers
        return candidates.sorted().firstNotNullOfOrNull { position ->
            contacts[position].takeIf { it.doesHavePhoneNumber(number) }
        }
    }

    private fun getNumberEnd(normalizedNumber: String): String? {
        val digits = normalizedNumber.filter { it.isDigit() }
        return if (digits.length >= MATCHING_DIGITS) digits.takeLast(MATCHING_DIGITS) else null
    }

    companion object {
        // PhoneNumberUtils.compare needs at least this many matching digits at the end of two numbers
        private const val MATCHING_DIGITS = 7
    }
}
