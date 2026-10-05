package org.fossify.phone.helpers

import android.content.Context
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.models.contacts.Contact
import java.util.Locale

/**
 * Loading every contact through [ContactsHelper] is expensive, and the main screen used to start several identical
 * loads at once (one per tab, plus the contacts cache and the call log). Requests made while an identical load is
 * still running now share its result instead of starting a new one. Nothing is cached once a load has finished.
 *
 * Loads of all contact sources ([getAll]) feed the background cache and the call log, so they wait for the filtered
 * loads that the visible tabs need instead of competing with them.
 *
 * Filtered loads pass the hidden contact sources to [ContactsHelper] explicitly. Left out, commons only filters the
 * phone numbers by account name, so an account whose name is shared by a visible one (e.g. an Exchange or Google
 * address that another app also uses as its account name) kept showing its contacts. With the sources passed, commons
 * skips their raw contacts by name and type, but no longer drops the contacts without a number nor merges the ones with
 * the same name, so that's done here like commons does it when nothing is hidden.
 */
object SharedContactsLoader {
    private data class Request(val getAll: Boolean, val ignoredContactSources: Set<String>, val sorting: Int)

    private val lock = Any()
    private val pendingCallbacks = HashMap<Request, MutableList<(ArrayList<Contact>) -> Unit>>()
    private val deferredLoads = ArrayList<() -> Unit>()

    fun getContacts(context: Context, getAll: Boolean = false, callback: (ArrayList<Contact>) -> Unit) {
        val baseConfig = context.baseConfig
        // a load of all sources doesn't depend on the hidden ones
        val ignoredSources = if (getAll) emptySet() else HashSet(baseConfig.ignoredContactSources)
        val request = Request(getAll, ignoredSources, baseConfig.sorting)
        val startLoad = { load(context, request) }
        val startNow = synchronized(lock) {
            val callbacks = pendingCallbacks[request]
            if (callbacks != null) {
                callbacks.add(callback)
                return
            }

            pendingCallbacks[request] = mutableListOf(callback)
            if (getAll && isFilteredLoadPending()) {
                deferredLoads.add(startLoad)
                false
            } else {
                true
            }
        }

        if (startNow) {
            startLoad()
        }
    }

    private fun load(context: Context, request: Request) {
        ContactsHelper(context).getContacts(
            getAll = request.getAll,
            ignoredContactSources = HashSet(request.ignoredContactSources),
            showOnlyContactsWithNumbers = true
        ) { loadedContacts ->
            val contacts = if (request.ignoredContactSources.isEmpty()) {
                loadedContacts
            } else {
                val withNumbers = loadedContacts.filter { it.phoneNumbers.isNotEmpty() }
                if (context.baseConfig.mergeDuplicateContacts) {
                    mergeSameNames(withNumbers)
                } else {
                    withNumbers
                }
            }
            val (callbacks, loadsToStart) = synchronized(lock) {
                val callbacks = pendingCallbacks.remove(request).orEmpty()
                val loadsToStart = if (isFilteredLoadPending()) {
                    emptyList()
                } else {
                    deferredLoads.toList().also { deferredLoads.clear() }
                }
                callbacks to loadsToStart
            }

            loadsToStart.forEach { it() }

            // every caller gets its own list, as most of them add private contacts or re-sort it in place
            callbacks.forEach { it(ArrayList(contacts)) }
        }
    }

    // keeps one contact per name, the one with the most details, as ContactsHelper does
    private fun mergeSameNames(contacts: List<Contact>): List<Contact> {
        val merged = contacts.groupBy { it.getNameToDisplay().lowercase(Locale.getDefault()) }.values.map { sameName ->
            sameName.maxBy { it.getStringToCompare().length }
        }
        return ArrayList(merged).apply { sort() }
    }

    private fun isFilteredLoadPending() = pendingCallbacks.keys.any { !it.getAll }
}
