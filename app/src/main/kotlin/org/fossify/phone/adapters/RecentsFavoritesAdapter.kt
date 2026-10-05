package org.fossify.phone.adapters

import android.annotation.SuppressLint
import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.databinding.ItemRecentsFavoriteBinding

/**
 * The horizontal strip of favorite contacts optionally shown at the top of the call history.
 */
class RecentsFavoritesAdapter(
    private val activity: Activity,
    private val itemClick: (Contact) -> Unit,
    private val itemLongClick: (Contact) -> Unit,
) : RecyclerView.Adapter<RecentsFavoritesAdapter.ViewHolder>() {

    private var contacts: List<Contact> = emptyList()
    private var textColor = 0
    private var fontSize = 0f

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(contacts: List<Contact>, textColor: Int, fontSize: Float) {
        if (this.contacts == contacts && this.textColor == textColor && this.fontSize == fontSize) {
            return
        }

        this.contacts = contacts
        this.textColor = textColor
        this.fontSize = fontSize
        notifyDataSetChanged()
    }

    override fun getItemCount() = contacts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemRecentsFavoriteBinding.inflate(activity.layoutInflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        val name = contact.getNameToDisplay()
        holder.binding.apply {
            root.contentDescription = name
            root.setOnClickListener { itemClick(contact) }
            root.setOnLongClickListener {
                itemLongClick(contact)
                true
            }

            itemRecentsFavoriteName.apply {
                text = name
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * NAME_TEXT_SIZE_RATIO)
            }

            if (!activity.isDestroyed) {
                SimpleContactsHelper(activity).loadContactImage(contact.photoUri, itemRecentsFavoriteImage, name)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            Glide.with(activity).clear(holder.binding.itemRecentsFavoriteImage)
        }
    }

    class ViewHolder(val binding: ItemRecentsFavoriteBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val NAME_TEXT_SIZE_RATIO = 0.8f
    }
}
