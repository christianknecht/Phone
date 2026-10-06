package org.fossify.phone.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.views.MyAppCompatCheckbox
import org.fossify.phone.R
import org.fossify.phone.databinding.DialogManageCallLogFiltersBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.models.CallLogFilter

/** Chooses the filters shown above the call history. None hides the filters. */
class ManageCallLogFiltersDialog(val activity: BaseSimpleActivity, val callback: () -> Unit) {
    private val binding by activity.viewBinding(DialogManageCallLogFiltersBinding::inflate)
    private val checkboxes = LinkedHashMap<CallLogFilter, MyAppCompatCheckbox>()

    init {
        val shownFilters = activity.config.callLogFilters
        val padding = activity.resources.getDimensionPixelSize(R.dimen.activity_margin)
        CallLogFilter.entries.filter { it != CallLogFilter.ALL }.forEach { filter ->
            checkboxes[filter] = MyAppCompatCheckbox(activity).apply {
                text = activity.getString(filter.labelResId)
                isChecked = filter in shownFilters
                setPadding(paddingLeft, padding, paddingRight, padding)
                binding.manageCallLogFiltersHolder.addView(this)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok) { _, _ -> dialogConfirmed() }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.call_log_filters)
            }
    }

    private fun dialogConfirmed() {
        activity.config.callLogFilters = checkboxes.filterValues { it.isChecked }.keys
        callback()
    }
}
