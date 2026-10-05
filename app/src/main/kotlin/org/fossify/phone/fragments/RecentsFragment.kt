package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.databinding.FragmentRecentsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getFavoriteContacts
import org.fossify.phone.extensions.getNumberNameLookup
import org.fossify.phone.extensions.handleGenericContactClick
import org.fossify.phone.extensions.runAfterAnimations
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.SharedContactsLoader
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    // shown above the call log when enabled, empty otherwise
    @Volatile
    private var favoriteContacts = listOf<Contact>()
    private var showsOnlyFavorites = false

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }

        // with no calls, the favorites strip is the only item of the list and the placeholders go below it
        binding.recentsList.viewTreeObserver.addOnGlobalLayoutListener {
            val stripBottom = if (showsOnlyFavorites) binding.recentsList.getChildAt(0)?.bottom ?: 0 else 0
            val params = binding.recentsPlaceholder.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin != stripBottom) {
                params.topMargin = stripBottom
                binding.recentsPlaceholder.layoutParams = params
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(properPrimaryColor)

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
        }

        refreshCallLog(loadAll = false) {
            binding.recentsList.runAfterAnimations {
                refreshCallLog(loadAll = true)
            }
        }
    }

    override fun onSearchClosed() {
        searchQuery = null
        showCallLog(allRecentCalls)
    }

    /** Reloads only the favorites strip, after the favorites were reordered or deleted elsewhere in the app. */
    fun refreshFavorites() {
        if (!context.config.showFavoritesInCallHistory) {
            return
        }

        SharedContactsLoader.getContacts(context) { contacts ->
            ensureBackgroundThread {
                favoriteContacts = context.getFavoriteContacts(contacts)
                activity?.runOnUiThread {
                    if (searchQuery.isNullOrEmpty() && !binding.progressIndicator.isVisible()) {
                        showCallLog(allRecentCalls)
                    }
                }
            }
        }
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) || it.doesContainPhoneNumber(fixedText)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            prepareCallLog(recentCalls) {
                activity?.runOnUiThread {
                    showsOnlyFavorites = false
                    showOrHidePlaceholder(recentCalls.isEmpty())
                    recentsAdapter?.updateItems(it, fixedText)
                }
            }
        }
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show && !binding.progressIndicator.isVisible()) {
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
        binding.progressIndicator.hide()
        showCallLog(recents)
    }

    // the favorites strip stays visible above the placeholders when there are no calls, or no access to them
    private fun showCallLog(recents: List<CallLogItem>) {
        val items = withFavorites(recents)
        showsOnlyFavorites = recents.isEmpty() && items.isNotEmpty()
        binding.apply {
            showOrHidePlaceholder(recents.isEmpty())
            recentsPlaceholder2.beGoneIf(recents.isNotEmpty() || context.hasPermission(PERMISSION_READ_CALL_LOG))
            recentsList.beGoneIf(items.isEmpty())
        }

        if (items.isNotEmpty()) {
            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    itemDelete = { deleted ->
                        allRecentCalls = allRecentCalls.filter { it !in deleted }
                    },
                    itemClick = {
                        val recentCall = it as RecentCall
                        activity?.startCallWithConfirmationCheck(recentCall.phoneNumber, recentCall.name)
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            activity?.startContactDetailsIntent(contact)
                        } else {
                            val name = recentCall.name.takeIf { recentCall.hasNumberName }
                            activity?.startAddContactIntent(recentCall.phoneNumber, name)
                        }
                    },
                    favoriteClick = { activity?.handleGenericContactClick(it) },
                    favoriteLongClick = { activity?.startContactDetailsIntent(it) }
                )

                binding.recentsList.adapter = recentsAdapter
            }

            recentsAdapter?.updateItems(items)
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            allRecentCalls = it
            if (searchQuery.isNullOrEmpty()) {
                activity?.runOnUiThread { gotRecents(it) }
            } else {
                updateSearchResult()
            }

            callback?.invoke()
        }
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<CallLogItem>) -> Unit) {
        val queryCount = if (loadAll) Int.MAX_VALUE else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()
        // the favorites are loaded once per refresh, by its first and quicker pass
        val loadFavorites = !loadAll

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, loadFavorites, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, loadFavorites, callback)
                }
            }
        }
    }

    private fun prepareCallLog(
        calls: List<RecentCall>,
        loadFavorites: Boolean = false,
        callback: (List<CallLogItem>) -> Unit,
    ) {
        val showFavorites = context.config.showFavoritesInCallHistory
        if (loadFavorites && !showFavorites) {
            favoriteContacts = emptyList()
        }

        // with no calls, the favorites are still loaded to be shown above the placeholder
        val needsFavorites = loadFavorites && showFavorites
        if (calls.isEmpty() && !needsFavorites) {
            callback(emptyList())
            return
        }

        SharedContactsLoader.getContacts(context) { contacts ->
            ensureBackgroundThread {
                if (needsFavorites) {
                    favoriteContacts = context.getFavoriteContacts(contacts)
                }

                if (calls.isEmpty()) {
                    callback(emptyList())
                    return@ensureBackgroundThread
                }

                val privateContacts = getPrivateContacts()
                // names given to numbers are dropped first and applied last, so that they follow edits
                // and a number that became a contact in the meantime shows the contact instead
                val updatedCalls = updateNamesIfEmpty(
                    calls = maybeFilterPrivateCalls(calls.map { withNumberName(it, null) }, privateContacts),
                    contacts = contacts,
                    privateContacts = privateContacts
                ).let { applyNumberNames(it, contacts + privateContacts) }

                callback(
                    groupCallsByDate(updatedCalls)
                )
            }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(calls: List<RecentCall>, contacts: List<Contact>, privateContacts: List<Contact>): List<RecentCall> {
        if (calls.isEmpty()) return mutableListOf()

        val contactsWithNumbers = contacts.filter { it.phoneNumbers.isNotEmpty() }
        return calls.map { call ->
            if (call.phoneNumber == call.name) {
                val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }
                val contact = contactsWithNumbers.firstOrNull { it.phoneNumbers.first().normalizedNumber == call.phoneNumber }

                when {
                    privateContact != null -> withUpdatedName(call = call, name = privateContact.getNameToDisplay())
                    contact != null -> withUpdatedName(call = call, name = contact.getNameToDisplay())
                    else -> call
                }
            } else {
                call
            }
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private fun applyNumberNames(calls: List<RecentCall>, contacts: List<Contact>): List<RecentCall> {
        val getNumberName = context.getNumberNameLookup()
        return calls.map { call ->
            val isNotContact = call.name == call.phoneNumber && !call.isUnknownNumber
            val name = if (isNotContact) getNumberName(call.phoneNumber) else null
            // a contact holding the number in any position wins, like on the call screen. Only named numbers are
            // checked, as the full comparison is too slow for the whole call history
            val contact = if (name != null) contacts.firstOrNull { it.doesHavePhoneNumber(call.phoneNumber) } else null
            when {
                contact != null -> withUpdatedName(call, contact.getNameToDisplay())
                name != null -> withNumberName(call, name)
                else -> call
            }
        }
    }

    // a null name restores the number as the name
    private fun withNumberName(call: RecentCall, name: String?): RecentCall {
        val hasAnyNumberName = call.hasNumberName || call.groupedCalls?.any { it.hasNumberName } == true
        if (name == null && !hasAnyNumberName) return call

        val nameToShow = name ?: call.phoneNumber
        return call.copy(
            name = nameToShow,
            hasNumberName = name != null,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name ?: it.phoneNumber, hasNumberName = name != null) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        for (call in recentCalls) {
            val currentDayCode = call.dayCode
            if (currentDayCode != lastDayCode) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }

    // the favorites strip scrolls with the call log, as its first item, but is left out of search results
    private fun withFavorites(callLog: List<CallLogItem>): List<CallLogItem> {
        val favorites = favoriteContacts
        return if (favorites.isEmpty()) {
            callLog
        } else {
            listOf(CallLogItem.Favorites(favorites)) + callLog
        }
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return (activity as MainActivity).cachedContacts.find { it.name == recentCall.name && it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }
}
