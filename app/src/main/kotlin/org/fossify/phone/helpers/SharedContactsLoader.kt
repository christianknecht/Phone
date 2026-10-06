package org.fossify.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.models.contacts.Contact
import java.util.Locale

/**
 * Loading every contact through [ContactsHelper] is expensive, and the main screen used to start several identical
 * loads at once (one per tab, plus the contacts cache and the call log). Requests made while an identical load is
 * still running now share its result instead of starting a new one.
 *
 * The result of a load is also kept for [CACHE_DURATION_MS], as opening the app still loaded the same contacts four
 * times within a few seconds (the tabs, the contacts cache, then the call history twice). It's dropped as soon as
 * the system contacts change, and a load that was running when they changed isn't kept.
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
    private const val CACHE_DURATION_MS = 30_000L

    private data class Request(
        val getAll: Boolean,
        val ignoredContactSources: Set<String>,
        val sorting: Int,
        val startNameWithSurname: Boolean,
        val mergeDuplicateContacts: Boolean,
    )

    private class CachedContacts(val contacts: List<Contact>, val loadedAt: Long)

    private val lock = Any()
    private val pendingCallbacks = HashMap<Request, MutableList<(ArrayList<Contact>) -> Unit>>()
    private val deferredLoads = ArrayList<() -> Unit>()
    private val cache = HashMap<Request, CachedContacts>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // increased whenever the system contacts change, so that a load started before a change isn't cached
    private var contactsVersion = 0
    private var isObservingContacts = false
    private val contactsObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            synchronized(lock) {
                contactsVersion++
                cache.clear()
            }
        }
    }

    fun getContacts(context: Context, getAll: Boolean = false, callback: (ArrayList<Contact>) -> Unit) {
        val baseConfig = context.baseConfig
        // a load of all sources doesn't depend on the hidden ones
        val ignoredSources = if (getAll) emptySet() else HashSet(baseConfig.ignoredContactSources)
        val request = Request(
            getAll = getAll,
            ignoredContactSources = ignoredSources,
            sorting = baseConfig.sorting,
            startNameWithSurname = baseConfig.startNameWithSurname,
            mergeDuplicateContacts = baseConfig.mergeDuplicateContacts
        )
        observeContacts(context)
        val startLoad = { load(context, request) }
        val startNow = synchronized(lock) {
            val cached = cache[request]
            if (cached != null && SystemClock.elapsedRealtime() - cached.loadedAt < CACHE_DURATION_MS) {
                // asynchronous like a load, as the callers expect
                mainHandler.post { callback(ArrayList(cached.contacts)) }
                return
            }

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
        val versionAtStart = synchronized(lock) { contactsVersion }
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
                if (isObservingContacts && contactsVersion == versionAtStart) {
                    cache[request] = CachedContacts(contacts, SystemClock.elapsedRealtime())
                }

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

    private fun observeContacts(context: Context) {
        synchronized(lock) {
            if (isObservingContacts) {
                return
            }
            isObservingContacts = true
        }

        try {
            context.applicationContext.contentResolver.registerContentObserver(
                ContactsContract.AUTHORITY_URI,
                true,
                contactsObserver
            )
        } catch (ignored: Exception) {
            // without the observer, a cached result could be outdated, so nothing is cached
            synchronized(lock) {
                isObservingContacts = false
            }
        }
    }

    private fun isFilteredLoadPending() = pendingCallbacks.keys.any { !it.getAll }
}
