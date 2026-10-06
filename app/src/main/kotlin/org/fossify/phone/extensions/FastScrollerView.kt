package org.fossify.phone.extensions

import androidx.recyclerview.widget.RecyclerView
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.SORT_BY_FULL_NAME
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.helpers.ContactSorting
import org.fossify.phone.helpers.getNameWithoutPrefix
import java.util.Locale

fun FastScrollerView.setupWithContacts(
    recyclerView: RecyclerView,
    contacts: List<Contact>,
) {
    val ignoreNamePrefixes = ContactSorting.ignoresNamePrefixes(context)
    setupWithRecyclerView(recyclerView, { position ->
        val initialLetter = try {
            contacts[position].getIndexLetter(ignoreNamePrefixes)
        } catch (e: IndexOutOfBoundsException) {
            ""
        }

        FastScrollItemIndicator.Text(initialLetter)
    })
}

/**
 * The letter of the contact in the fast scroller, taken from what the list is sorted by. Sorted by full name, the
 * default, the contacts are sorted by the name they show, prefix included, but [Contact.getFirstLetter] takes the first
 * name. So "Dr Henning Meyer", listed among the D, gave an H, the scroller showed letters out of order, and its H led
 * there instead of to the contacts starting with H. When the prefixes are ignored in the sorting (see
 * [ContactSorting]), the letter is the one of the name without its prefix, where the contact is then listed.
 */
private fun Contact.getIndexLetter(ignoreNamePrefixes: Boolean): String {
    if (Contact.sorting and SORT_BY_FULL_NAME == 0) {
        return getFirstLetter()
    }

    val name = if (ignoreNamePrefixes) getNameWithoutPrefix() else getNameToDisplay()
    val character = if (name.isNotEmpty()) name.substring(0, 1) else ""
    return character.uppercase(Locale.getDefault()).normalizeString()
}
