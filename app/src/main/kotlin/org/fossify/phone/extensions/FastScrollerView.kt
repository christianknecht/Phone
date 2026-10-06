package org.fossify.phone.extensions

import androidx.recyclerview.widget.RecyclerView
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.SORT_BY_FULL_NAME
import org.fossify.commons.models.contacts.Contact
import java.util.Locale

fun FastScrollerView.setupWithContacts(
    recyclerView: RecyclerView,
    contacts: List<Contact>,
) = setupWithRecyclerView(recyclerView, { position ->
    val initialLetter = try {
        contacts[position].getIndexLetter()
    } catch (e: IndexOutOfBoundsException) {
        ""
    }

    FastScrollItemIndicator.Text(initialLetter)
})

/**
 * The letter of the contact in the fast scroller, taken from what the list is sorted by. Sorted by full name, the
 * default, the contacts are sorted by the name they show, prefix included, but [Contact.getFirstLetter] takes the first
 * name. So "Dr Henning Meyer", listed among the D, gave an H, the scroller showed letters out of order, and its H led
 * there instead of to the contacts starting with H.
 */
private fun Contact.getIndexLetter(): String {
    if (Contact.sorting and SORT_BY_FULL_NAME == 0) {
        return getFirstLetter()
    }

    val name = getNameToDisplay()
    val character = if (name.isNotEmpty()) name.substring(0, 1) else ""
    return character.uppercase(Locale.getDefault()).normalizeString()
}
