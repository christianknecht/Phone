package org.fossify.phone.models

import android.provider.CallLog.Calls
import androidx.annotation.StringRes
import org.fossify.phone.R

/**
 * The filters that can be shown above the call history, chosen in the settings. All calls are shown first, and the
 * filter tapped shows only the calls matching it.
 */
enum class CallLogFilter(@StringRes val labelResId: Int) {
    ALL(R.string.all_calls),
    MISSED(R.string.missed_calls),
    INCOMING(R.string.incoming_calls),
    OUTGOING(R.string.outgoing_calls),
    DECLINED(R.string.declined_calls),
    CONTACTS(R.string.contacts_tab),
    NOT_CONTACTS(R.string.not_contacts),
    FAVORITES(R.string.favorites),
    HIDDEN_NUMBERS(R.string.hidden_numbers),

    // one filter per SIM, shown only with several SIMs
    SIM(R.string.filter_by_sim);

    companion object {
        val DEFAULT_SHOWN = setOf(MISSED, CONTACTS, NOT_CONTACTS, SIM)

        // hidden numbers and numbers named only in the app aren't contacts
        fun isContact(call: RecentCall) = !call.isUnknownNumber && !call.hasNumberName && call.name != call.phoneNumber
    }
}

/** The filter selected above the call history, [simId] being the one of the SIM for [CallLogFilter.SIM]. */
data class SelectedCallLogFilter(val filter: CallLogFilter, val simId: Int = -1) {
    fun matches(call: RecentCall, isFavorite: (RecentCall) -> Boolean): Boolean {
        return when (filter) {
            CallLogFilter.ALL -> true
            CallLogFilter.MISSED -> call.type == Calls.MISSED_TYPE
            CallLogFilter.INCOMING -> call.type == Calls.INCOMING_TYPE || call.type == Calls.ANSWERED_EXTERNALLY_TYPE
            CallLogFilter.OUTGOING -> call.type == Calls.OUTGOING_TYPE
            CallLogFilter.DECLINED -> call.type == Calls.REJECTED_TYPE
            CallLogFilter.CONTACTS -> CallLogFilter.isContact(call)
            CallLogFilter.NOT_CONTACTS -> !CallLogFilter.isContact(call)
            CallLogFilter.FAVORITES -> !call.isUnknownNumber && isFavorite(call)
            CallLogFilter.HIDDEN_NUMBERS -> call.isUnknownNumber
            CallLogFilter.SIM -> call.simID == simId
        }
    }
}
