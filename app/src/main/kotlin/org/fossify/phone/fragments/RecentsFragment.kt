package org.fossify.phone.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.chip.Chip
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
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
import org.fossify.phone.databinding.ItemCallLogFilterBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.extensions.getFavoriteContacts
import org.fossify.phone.extensions.getNumberNameLookup
import org.fossify.phone.extensions.runAfterAnimations
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.ContactsByNumberIndex
import org.fossify.phone.helpers.FavoriteNumbers
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.SharedContactsLoader
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogFilter
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.SelectedCallLogFilter
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
    private var selectedFilter = SelectedCallLogFilter(CallLogFilter.ALL)

    // the filters and labels the chips were made for, to remake them after a change in the settings
    private var shownFilters = listOf<Pair<SelectedCallLogFilter, String>>()

    // the numbers of the favorites, when the favorites filter is shown
    @Volatile
    private var favoriteNumbers: FavoriteNumbers? = null
    private var recentsHelper = RecentsHelper(context)

    // a refresh asked while one is running is done once it ends, instead of both reading the whole call history at the
    // same time. Main thread only
    private var isRefreshing = false
    private var isRefreshPending = false
    private var isPendingRefreshInvalidating = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)

        binding.recentsFilters.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) }
            val filter = chip?.tag as? SelectedCallLogFilter ?: return@setOnCheckedStateChangeListener
            if (filter != selectedFilter) {
                selectedFilter = filter
                if (searchQuery.isNullOrEmpty()) {
                    showCallLog(allRecentCalls)
                } else {
                    updateSearchResult()
                }
            }
        }
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
        setupFilterColors()

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }
    }

    // "all calls" first, then the filters chosen in the settings, with one filter per SIM
    private fun getShownFilters(): List<Pair<SelectedCallLogFilter, String>> {
        val filters = context.config.callLogFilters.flatMap { filter ->
            if (filter == CallLogFilter.SIM) {
                val sims = context.getAvailableSIMCardLabels()
                if (sims.size > 1) {
                    sims.map { SelectedCallLogFilter(filter, it.id) to it.label }
                } else {
                    emptyList()
                }
            } else {
                listOf(SelectedCallLogFilter(filter) to context.getString(filter.labelResId))
            }
        }

        return if (filters.isEmpty()) {
            emptyList()
        } else {
            listOf(SelectedCallLogFilter(CallLogFilter.ALL) to context.getString(R.string.all_calls)) + filters
        }
    }

    private fun updateFilters() {
        val filters = getShownFilters()
        if (filters == shownFilters) {
            return
        }

        shownFilters = filters
        if (filters.none { it.first == selectedFilter }) {
            selectedFilter = SelectedCallLogFilter(CallLogFilter.ALL)
        }

        val inflater = LayoutInflater.from(context)
        binding.recentsFilters.removeAllViews()
        filters.forEach { (filter, label) ->
            // the group keeps track of its chips by id, so the id is set before adding them
            val chip = ItemCallLogFilterBinding.inflate(inflater, binding.recentsFilters, false).root
            chip.id = generateViewId()
            chip.tag = filter
            chip.text = label
            binding.recentsFilters.addView(chip)
            if (filter == selectedFilter) {
                binding.recentsFilters.check(chip.id)
            }
        }

        setupFilterColors()
    }

    private fun setupFilterColors() {
        val textColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val backgroundColors = ColorStateList(states, intArrayOf(properPrimaryColor, Color.TRANSPARENT))
        val textColors = ColorStateList(states, intArrayOf(properPrimaryColor.getContrastColor(), textColor))
        val strokeColors = ColorStateList(
            states,
            intArrayOf(properPrimaryColor, textColor.adjustAlpha(FILTER_STROKE_ALPHA))
        )

        for (i in 0 until binding.recentsFilters.childCount) {
            (binding.recentsFilters.getChildAt(i) as? Chip)?.apply {
                chipBackgroundColor = backgroundColors
                chipStrokeColor = strokeColors
                setTextColor(textColors)
            }
        }
    }

    private fun matchesSelectedFilter(call: RecentCall): Boolean {
        return selectedFilter.matches(call) { favoriteNumbers?.contains(it.phoneNumber) == true }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (isRefreshing) {
            isRefreshPending = true
            isPendingRefreshInvalidating = isPendingRefreshInvalidating || invalidate
            return
        }

        if (invalidate) {
            allRecentCalls = emptyList()
        }

        isRefreshing = true
        refreshCallLog(loadAll = false) {
            binding.recentsList.runAfterAnimations {
                refreshCallLog(loadAll = true) {
                    post { onRefreshDone() }
                }
            }
        }
    }

    private fun onRefreshDone() {
        isRefreshing = false
        if (isRefreshPending) {
            val invalidate = isPendingRefreshInvalidating
            isRefreshPending = false
            isPendingRefreshInvalidating = false
            refreshItems(invalidate)
        }
    }

    override fun onSearchClosed() {
        searchQuery = null
        showCallLog(allRecentCalls)
    }

    /** Reloads only the favorites strip, after the favorites were reordered or deleted elsewhere in the app. */
    fun refreshFavorites() {
        val showFavorites = context.config.showFavoritesInCallHistory
        val filtersByFavorites = CallLogFilter.FAVORITES in context.config.callLogFilters
        if (!showFavorites && !filtersByFavorites) {
            return
        }

        SharedContactsLoader.getContacts(context) { contacts ->
            ensureBackgroundThread {
                updateFavorites(contacts, showFavorites, filtersByFavorites)
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
            val recentCalls = keepCalls(allRecentCalls, ::matchesSelectedFilter)
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
    private fun showCallLog(allRecents: List<CallLogItem>) {
        val recents = filterCallLog(allRecents)
        val items = withFavorites(recents)
        showsOnlyFavorites = recents.isEmpty() && items.isNotEmpty()
        val hasPermission = context.hasPermission(PERMISSION_READ_CALL_LOG)
        val isFilteredOut = recents.isEmpty() && allRecents.isNotEmpty()
        updateFilters()
        binding.apply {
            recentsPlaceholder.text = context.getString(
                when {
                    isFilteredOut -> R.string.no_calls_match_filter
                    hasPermission -> R.string.no_previous_calls
                    else -> R.string.could_not_access_the_call_history
                }
            )
            showOrHidePlaceholder(recents.isEmpty())
            recentsPlaceholder2.beGoneIf(recents.isNotEmpty() || hasPermission)
            recentsFiltersHolder.beVisibleIf(hasPermission && shownFilters.isNotEmpty())
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
                        // a filtered group holds only some of the calls it groups, so the calls are dropped one by one
                        val deletedIds = deleted.flatMap { it.groupedCalls.orEmpty() + it }.mapTo(HashSet()) { it.id }
                        allRecentCalls = groupCallsByDate(keepCalls(allRecentCalls) { it.id !in deletedIds })
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
                    // the strip is a shortcut for calling, so ignore the "on contact click" setting here
                    favoriteClick = { activity?.startCallWithConfirmationCheck(it) },
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
        val filtersByFavorites = CallLogFilter.FAVORITES in context.config.callLogFilters
        if (loadFavorites && !showFavorites) {
            favoriteContacts = emptyList()
        }
        if (loadFavorites && !filtersByFavorites) {
            favoriteNumbers = null
        }

        // with no calls, the favorites are still loaded to be shown above the placeholder
        val needsFavorites = loadFavorites && (showFavorites || filtersByFavorites)
        if (calls.isEmpty() && !needsFavorites) {
            callback(emptyList())
            return
        }

        SharedContactsLoader.getContacts(context) { contacts ->
            ensureBackgroundThread {
                if (needsFavorites) {
                    updateFavorites(contacts, showFavorites, filtersByFavorites)
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

    // the strip and the filter are both optional, what isn't used is emptied
    private fun updateFavorites(contacts: List<Contact>, showFavorites: Boolean, filtersByFavorites: Boolean) {
        val favorites = context.getFavoriteContacts(contacts)
        favoriteContacts = if (showFavorites) favorites else emptyList()
        favoriteNumbers = if (filtersByFavorites) FavoriteNumbers.load(context, favorites) else null
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

        // built once instead of going through every contact for each call
        val contactsByFirstNumber = HashMap<String, Contact>()
        contacts.forEach { contact ->
            val firstNumber = contact.phoneNumbers.firstOrNull() ?: return@forEach
            contactsByFirstNumber.putIfAbsent(firstNumber.normalizedNumber, contact)
        }

        return calls.map { call ->
            if (call.phoneNumber == call.name) {
                val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }
                val contact = contactsByFirstNumber[call.phoneNumber]

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
        val contactsByNumber by lazy { ContactsByNumberIndex(contacts) }
        return calls.map { call ->
            val isNotContact = call.name == call.phoneNumber && !call.isUnknownNumber
            val name = if (isNotContact) getNumberName(call.phoneNumber) else null
            // a contact holding the number in any position wins, like on the call screen. Only named numbers are
            // checked, as the full comparison is too slow for the whole call history
            val contact = if (name != null) contactsByNumber.findContact(call.phoneNumber) else null
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

    private fun filterCallLog(callLog: List<CallLogItem>): List<CallLogItem> {
        return if (selectedFilter.filter == CallLogFilter.ALL) {
            callLog
        } else {
            groupCallsByDate(keepCalls(callLog, ::matchesSelectedFilter))
        }
    }

    // grouped calls are kept one by one, a group shows only those that are kept
    private fun keepCalls(callLog: List<CallLogItem>, predicate: (RecentCall) -> Boolean): List<RecentCall> {
        return callLog.filterIsInstance<RecentCall>().mapNotNull { call ->
            val groupedCalls = call.groupedCalls
            if (groupedCalls.isNullOrEmpty()) {
                return@mapNotNull call.takeIf(predicate)
            }

            val kept = groupedCalls.filter(predicate)
            when (kept.size) {
                0 -> null
                groupedCalls.size -> call
                1 -> kept.first().copy(groupedCalls = null)
                else -> kept.first().copy(groupedCalls = kept.toMutableList())
            }
        }
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

    companion object {
        private const val FILTER_STROKE_ALPHA = 0.4f
    }
}
