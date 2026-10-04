package org.fossify.phone.helpers

import android.content.Context
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.models.contacts.Contact

/**
 * Loading every contact through [ContactsHelper] is expensive, and the main screen used to start several identical
 * loads at once (one per tab, plus the contacts cache and the call log). Requests made while an identical load is
 * still running now share its result instead of starting a new one. Nothing is cached once a load has finished.
 *
 * Loads of all contact sources ([getAll]) feed the background cache and the call log, so they wait for the filtered
 * loads that the visible tabs need instead of competing with them.
 */
object SharedContactsLoader {
    private data class Request(val getAll: Boolean, val ignoredContactSources: Set<String>, val sorting: Int)

    private val lock = Any()
    private val pendingCallbacks = HashMap<Request, MutableList<(ArrayList<Contact>) -> Unit>>()
    private val deferredLoads = ArrayList<() -> Unit>()

    fun getContacts(context: Context, getAll: Boolean = false, callback: (ArrayList<Contact>) -> Unit) {
        val baseConfig = context.baseConfig
        val request = Request(getAll, HashSet(baseConfig.ignoredContactSources), baseConfig.sorting)
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
        ContactsHelper(context).getContacts(getAll = request.getAll, showOnlyContactsWithNumbers = true) { contacts ->
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

    private fun isFilteredLoadPending() = pendingCallbacks.keys.any { !it.getAll }
}
