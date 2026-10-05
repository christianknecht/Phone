package org.fossify.phone.dialogs

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.ContactSource
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.FilterContactSourcesAdapter
import org.fossify.phone.databinding.DialogFilterContactSourcesBinding
import org.fossify.phone.extensions.config

class FilterContactSourcesDialog(val activity: SimpleActivity, private val callback: () -> Unit) {
    private val binding by activity.viewBinding(DialogFilterContactSourcesBinding::inflate)

    private var dialog: AlertDialog? = null
    private var contactSources = ArrayList<ContactSource>()

    // identifiers of the sources left out of the list that were already ignored, kept as they are on save
    private var hiddenIgnoredSources = HashSet<String>()

    init {
        // the callback runs on a background thread
        ContactsHelper(activity).getContactSources { sources ->
            prepareContactSources(sources)
            activity.runOnUiThread { showDialog() }
        }
    }

    /**
     * Commons lists every account whose ContentResolver.getIsSyncable() is >= 0 for the contacts authority, which
     * includes 0 (not syncable). So an account of an app that has nothing to do with contacts but uses the same
     * name as a real contacts account (e.g. a Gmail address used to sign in elsewhere) shows up as a second entry.
     * Counting by name only then gave both entries the same count.
     */
    private fun prepareContactSources(sources: ArrayList<ContactSource>) {
        val accountsWithContacts = getAccountsWithContacts()
        val phoneContactCounts = getPhoneContactCounts()
        val privateCount = getPrivateContactsCount()

        val ignoredSources = activity.config.ignoredContactSources
        val shownSources = ArrayList<ContactSource>()
        sources.distinctBy { it.getFullIdentifier() }.forEach { source ->
            val identifier = source.getFullIdentifier()
            if (isUnusedAccount(source, accountsWithContacts)) {
                if (identifier in ignoredSources) {
                    hiddenIgnoredSources.add(identifier)
                }
            } else {
                val count = if (source.type == SMT_PRIVATE) privateCount else phoneContactCounts[identifier] ?: 0
                shownSources.add(source.copy(count = count))
            }
        }

        contactSources = addAccountTypeToSameNames(shownSources)
    }

    private fun showDialog() {
        val ignoredSources = activity.config.ignoredContactSources
        binding.filterContactSourcesList.adapter = FilterContactSourcesAdapter(activity, contactSources, ignoredSources)

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok) { _, _ -> confirmContactSources() }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    // an account that can't sync contacts and has none stored can't add anything to the list, so it isn't offered
    private fun isUnusedAccount(source: ContactSource, accountsWithContacts: Set<String>): Boolean {
        if (source.type.isEmpty() || source.name.isEmpty() || source.type == SMT_PRIVATE) {
            return false
        }

        if (source.getFullIdentifier() in accountsWithContacts) {
            return false
        }

        val isSyncable = try {
            ContentResolver.getIsSyncable(Account(source.name, source.type), ContactsContract.AUTHORITY) > 0
        } catch (ignored: Exception) {
            true
        }
        return !isSyncable
    }

    // two different accounts can share a name (e.g. the same address in two apps), show which app each one is from
    private fun addAccountTypeToSameNames(sources: List<ContactSource>): ArrayList<ContactSource> {
        val sharedNames = sources.groupBy { it.publicName }.filterValues { it.size > 1 }.keys
        if (sharedNames.isEmpty()) {
            return ArrayList(sources)
        }

        val typeLabels = getAccountTypeLabels()
        return sources.mapTo(ArrayList()) { source ->
            if (source.publicName in sharedNames && source.type.isNotEmpty()) {
                val label = typeLabels[source.type] ?: source.type
                source.copy(publicName = "${source.publicName} · $label")
            } else {
                source
            }
        }
    }

    private fun getAccountTypeLabels(): Map<String, String> {
        return try {
            AccountManager.get(activity).authenticatorTypes.mapNotNull { description ->
                val label = try {
                    activity.packageManager.getText(description.packageName, description.labelId, null)?.toString()
                } catch (ignored: Exception) {
                    null
                }
                label?.let { description.type to it }
            }.toMap()
        } catch (ignored: Exception) {
            emptyMap()
        }
    }

    // full identifiers (name:type) of the accounts that have at least one contact stored on the device
    private fun getAccountsWithContacts(): Set<String> {
        val accounts = HashSet<String>()
        val projection = arrayOf(RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE)
        val selection = "${RawContacts.DELETED} = 0"
        activity.queryCursor(RawContacts.CONTENT_URI, projection, selection = selection) { cursor ->
            accounts.add(getIdentifier(cursor))
        }
        return accounts
    }

    // how many contacts with a phone number each account has, by full identifier, like the Contacts tab lists them
    private fun getPhoneContactCounts(): Map<String, Int> {
        val rawContactsPerAccount = HashMap<String, HashSet<Long>>()
        val projection = arrayOf(Data.RAW_CONTACT_ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE)
        val selection = "${Data.MIMETYPE} = ?"
        val args = arrayOf(Phone.CONTENT_ITEM_TYPE)
        activity.queryCursor(Data.CONTENT_URI, projection, selection = selection, selectionArgs = args) { cursor ->
            val rawContactIds = rawContactsPerAccount.getOrPut(getIdentifier(cursor)) { HashSet() }
            rawContactIds.add(cursor.getLongValue(Data.RAW_CONTACT_ID))
        }
        return rawContactsPerAccount.mapValues { it.value.size }
    }

    private fun getPrivateContactsCount(): Int {
        val privateCursor = activity.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(activity, privateCursor).size
    }

    // matches ContactSource.getFullIdentifier(), the local phone storage has an empty name and type
    private fun getIdentifier(cursor: Cursor): String {
        val name = cursor.getStringValueOrNull(RawContacts.ACCOUNT_NAME).orEmpty()
        val type = cursor.getStringValueOrNull(RawContacts.ACCOUNT_TYPE).orEmpty()
        return ContactSource(name, type, "").getFullIdentifier()
    }

    private fun confirmContactSources() {
        val adapter = binding.filterContactSourcesList.adapter as FilterContactSourcesAdapter
        val selectedContactSources = adapter.getSelectedContactSources()
        val ignoredContactSources = contactSources.filter { !selectedContactSources.contains(it) }.map {
            if (it.type == SMT_PRIVATE) SMT_PRIVATE else it.getFullIdentifier()
        }.toHashSet()
        ignoredContactSources.addAll(hiddenIgnoredSources)

        if (activity.config.ignoredContactSources != ignoredContactSources) {
            activity.config.ignoredContactSources = ignoredContactSources
            callback()
        }
        dialog?.dismiss()
    }
}
