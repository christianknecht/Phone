package org.fossify.phone.adapters

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.models.contacts.ContactSource
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ItemFilterContactSourceBinding

class FilterContactSourcesAdapter(
    val activity: SimpleActivity,
    private val contactSources: List<ContactSource>,
    ignoredContactSources: Set<String>
) : RecyclerView.Adapter<FilterContactSourcesAdapter.ViewHolder>() {

    private val selectedKeys = HashSet<Int>()

    init {
        // by full identifier, two accounts with the same name but a different type are filtered separately
        contactSources.forEach { contactSource ->
            if (contactSource.getFullIdentifier() !in ignoredContactSources) {
                selectedKeys.add(contactSource.hashCode())
            }
        }
    }

    private fun toggleItemSelection(select: Boolean, contactSource: ContactSource, position: Int) {
        if (select) {
            selectedKeys.add(contactSource.hashCode())
        } else {
            selectedKeys.remove(contactSource.hashCode())
        }

        notifyItemChanged(position)
    }

    fun getSelectedContactSources() = contactSources.filter { selectedKeys.contains(it.hashCode()) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFilterContactSourceBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contactSource = contactSources[position]
        holder.bindView(contactSource)
    }

    override fun getItemCount() = contactSources.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(contactSource: ContactSource): View {
            val isSelected = selectedKeys.contains(contactSource.hashCode())
            ItemFilterContactSourceBinding.bind(itemView).apply {
                filterContactSourceCheckbox.isChecked = isSelected
                filterContactSourceCheckbox.setColors(activity.getProperTextColor(), activity.getProperPrimaryColor(), activity.getProperBackgroundColor())
                val countText = if (contactSource.count >= 0) " (${contactSource.count})" else ""
                val displayName = "${contactSource.publicName}$countText"
                filterContactSourceCheckbox.text = displayName
                filterContactSourceHolder.setOnClickListener { viewClicked(!isSelected, contactSource) }

                return root
            }
        }

        private fun viewClicked(select: Boolean, contactSource: ContactSource) {
            toggleItemSelection(select, contactSource, adapterPosition)
        }
    }
}
