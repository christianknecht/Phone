package org.fossify.phone.models

import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.models.contacts.Contact

sealed class CallLogItem {
    data class Date(
        val timestamp: Long,
        val dayCode: String,
    ) : CallLogItem()

    /** The favorite contacts optionally shown above the call log, see [org.fossify.phone.helpers.Config]. */
    data class Favorites(
        val contacts: List<Contact>,
    ) : CallLogItem()

    fun getItemId(): Int {
        return when (this) {
            is Date -> -(timestamp / (DAY_SECONDS * 1000L)).toInt()
            is Favorites -> FAVORITES_ITEM_ID
            is RecentCall -> id
        }
    }

    companion object {
        // day codes give small negative ids and calls positive ones, so this one cannot clash
        private const val FAVORITES_ITEM_ID = Int.MIN_VALUE
    }
}
