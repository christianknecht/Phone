package org.fossify.phone.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.value
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.DialogNameNumberBinding
import org.fossify.phone.extensions.getNumberName
import org.fossify.phone.extensions.setNumberName

/**
 * Names a number that isn't a contact. The name is stored only in the app, not in the system contacts.
 * Saving an empty name, or pressing Remove, forgets it.
 */
class NameNumberDialog(
    val activity: SimpleActivity,
    private val number: String,
    callback: () -> Unit,
) {
    init {
        val currentName = activity.getNumberName(number).orEmpty()
        val binding = DialogNameNumberBinding.inflate(activity.layoutInflater).apply {
            nameNumberValue.setText(currentName)
            nameNumberValue.setSelection(currentName.length)
        }

        val title = if (currentName.isEmpty()) R.string.name_this_number else R.string.edit_number_name
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (currentName.isNotEmpty()) {
                    setNeutralButton(R.string.remove) { _, _ ->
                        activity.setNumberName(number, null)
                        callback()
                    }
                }

                activity.setupDialogStuff(binding.root, this, title) { alertDialog ->
                    alertDialog.showKeyboard(binding.nameNumberValue)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        activity.setNumberName(number, binding.nameNumberValue.value)
                        callback()
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
