package org.fossify.phone.helpers

import android.content.Context
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.SORT_BY_FULL_NAME
import org.fossify.commons.helpers.SORT_DESCENDING
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.extensions.config
import java.util.IdentityHashMap

/**
 * Sorts contacts like commons' [Contact.compareTo] does, except with the full name sorting when
 * [Config.ignoreNamePrefixesInSorting] is on: the name prefix ("Dr", "M."...) is then left out, so "Dr Henning Meyer"
 * is listed among the H instead of the D. The shown names keep their prefix.
 *
 * Commons sorts the contacts it loads, so this sorts them again, with each name normalized once instead of at every
 * comparison.
 */
object ContactSorting {
    fun ignoresNamePrefixes(context: Context): Boolean {
        return Contact.sorting and SORT_BY_FULL_NAME != 0 && context.config.ignoreNamePrefixesInSorting
    }

    fun sort(context: Context, contacts: MutableList<Contact>) {
        if (!ignoresNamePrefixes(context)) {
            contacts.sort()
            return
        }

        val sortNames = IdentityHashMap<Contact, String>()
        val getSortName = { contact: Contact ->
            sortNames.getOrPut(contact) { contact.getNameWithoutPrefix().normalizeString() }
        }
        val isDescending = Contact.sorting and SORT_DESCENDING != 0
        contacts.sortWith { first, second ->
            val result = compareNames(getSortName(first), getSortName(second), first, second)
            if (isDescending) -result else result
        }
    }

    // the same rules as commons' Contact.compareUsingStrings
    private fun compareNames(firstName: String, secondName: String, first: Contact, second: Contact): Int {
        val isFirstLetter = firstName.firstOrNull()?.isLetter()
        val isSecondLetter = secondName.firstOrNull()?.isLetter()
        return when {
            isFirstLetter == true && isSecondLetter == false -> -1
            isFirstLetter == false && isSecondLetter == true -> 1
            firstName.isEmpty() && secondName.isNotEmpty() -> 1
            firstName.isNotEmpty() && secondName.isEmpty() -> -1
            firstName.equals(secondName, ignoreCase = true) -> {
                first.getNameToDisplay().compareTo(second.getNameToDisplay(), ignoreCase = true)
            }

            else -> firstName.compareTo(secondName, ignoreCase = true)
        }
    }
}

/** The shown name without its prefix, used to sort and index the contacts when the prefixes are ignored. */
fun Contact.getNameWithoutPrefix(): String {
    val name = getNameToDisplay()
    if (prefix.isBlank() || !name.startsWith(prefix)) {
        return name
    }

    return name.removePrefix(prefix).trim().ifEmpty { name }
}
