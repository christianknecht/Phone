package org.fossify.phone.helpers

import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import androidx.core.content.res.ResourcesCompat
import org.fossify.commons.R as CommonsR
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/**
 * The account type of every raw contact linked to each aggregated contact, so the lists can show where a contact is
 * stored. Raw contacts are not merged: two raw contacts in the same account give the same type twice, which hints at
 * duplicates. Private contacts (stored by Fossify Contacts) are never aggregated, so they get [SMT_PRIVATE] once.
 */
data class ContactAccounts(private val accountTypesByContactId: Map<Int, List<String>>) {
    fun getAccountTypes(contact: Contact): List<String> {
        return if (contact.source == SMT_PRIVATE) {
            listOf(SMT_PRIVATE)
        } else {
            accountTypesByContactId[contact.contactId].orEmpty()
        }
    }
}

/**
 * Loads the raw contacts' account types and the icon and label of each account type. Icons are cached per account
 * type for the whole process, see [loadAccountIcons].
 */
object ContactAccountsHelper {
    private const val FOSSIFY_CONTACTS_PACKAGE = "org.fossify.contacts"
    private const val FOSSIFY_CONTACTS_DEBUG_PACKAGE = "org.fossify.contacts.debug"
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val SYNC_ADAPTER_ACTION = "android.content.SyncAdapter"
    private const val SYNC_ADAPTER_META_DATA = "android.content.SyncAdapter"

    /**
     * [drawable] is shared, so views must use [newDrawable]. [isGeneric] icons are monochrome and get tinted with the
     * text color, the account apps' own icons are shown as they are.
     */
    class AccountIcon(private val drawable: Drawable?, val label: String, val isGeneric: Boolean) {
        fun newDrawable(): Drawable? = drawable?.constantState?.newDrawable()?.mutate() ?: drawable
    }

    private val accountIcons = ConcurrentHashMap<String, AccountIcon>()

    /** Queries the contacts provider, call it from a background thread. Returns null if the setting is off. */
    fun getContactAccounts(context: Context): ContactAccounts? {
        if (!context.config.showContactAccounts) {
            return null
        }

        val accountTypes = HashMap<Int, MutableList<String>>()
        val projection = arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE)
        val selection = "${RawContacts.DELETED} = 0"
        try {
            context.queryCursor(
                uri = RawContacts.CONTENT_URI,
                projection = projection,
                selection = selection,
                showErrors = false
            ) { cursor ->
                val contactId = cursor.getInt(0)
                val accountType = cursor.getString(1).orEmpty()
                accountTypes.getOrPut(contactId) { ArrayList() }.add(accountType)
            }
        } catch (ignored: Exception) {
        }

        // a stable order, so the same contact always shows its icons the same way
        accountTypes.values.forEach { it.sort() }
        loadAccountIcons(context, accountTypes.values.flatten().toSet() + SMT_PRIVATE)
        return ContactAccounts(accountTypes)
    }

    /** Returns the cached icon of [accountType], or a generic one if it hasn't been loaded. Safe on the main thread. */
    fun getAccountIcon(context: Context, accountType: String): AccountIcon {
        return accountIcons[accountType] ?: getGenericIcon(context)
    }

    /** Loads the icons of the [accountTypes] not cached yet. It reads other apps' resources, so off the UI thread. */
    private fun loadAccountIcons(context: Context, accountTypes: Set<String>) {
        val missingTypes = accountTypes.filter { !accountIcons.containsKey(it) }
        if (missingTypes.isEmpty()) {
            return
        }

        val authenticators = try {
            AccountManager.get(context).authenticatorTypes.associateBy { it.type }
        } catch (ignored: Exception) {
            emptyMap()
        }

        val syncAdapterPackages by lazy { getContactsSyncAdapterPackages(context) }
        missingTypes.forEach { accountType ->
            val icon = if (accountType == SMT_PRIVATE) {
                getPrivateContactsIcon(context)
            } else {
                authenticators[accountType]?.let { getAuthenticatorIcon(context, it) }
                    ?: syncAdapterPackages[accountType]?.let { getApplicationIcon(context, it) }
            }
            accountIcons[accountType] = icon ?: getGenericIcon(context)
        }
    }

    /**
     * AccountManager doesn't list every authenticator: WhatsApp's for example isn't visible to other apps, so its
     * contacts got the phone icon. The apps' contacts sync adapters are visible (see the manifest's <queries>), so
     * the app syncing an account type into the contacts is found there and its icon used instead.
     */
    private fun getContactsSyncAdapterPackages(context: Context): Map<String, String> {
        val packageManager = context.packageManager
        val services = try {
            packageManager.queryIntentServices(Intent(SYNC_ADAPTER_ACTION), PackageManager.GET_META_DATA)
        } catch (ignored: Exception) {
            emptyList()
        }

        val packages = HashMap<String, String>()
        services.forEach { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo
            getContactsSyncAdapterAccountType(packageManager, serviceInfo)?.let {
                packages.putIfAbsent(it, serviceInfo.packageName)
            }
        }
        return packages
    }

    // the account type a sync adapter syncs into the contacts provider, null if it syncs something else
    private fun getContactsSyncAdapterAccountType(packageManager: PackageManager, serviceInfo: ServiceInfo): String? {
        return try {
            val parser = serviceInfo.loadXmlMetaData(packageManager, SYNC_ADAPTER_META_DATA) ?: return null
            parser.use {
                var eventType = parser.eventType
                while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
                    eventType = parser.next()
                }

                if (eventType != XmlPullParser.START_TAG) {
                    return null
                }

                val resources = packageManager.getResourcesForApplication(serviceInfo.packageName)
                val getAttribute = { name: String ->
                    val resId = parser.getAttributeResourceValue(ANDROID_NAMESPACE, name, 0)
                    if (resId != 0) resources.getString(resId) else parser.getAttributeValue(ANDROID_NAMESPACE, name)
                }

                getAttribute("accountType")?.takeIf {
                    it.isNotEmpty() && getAttribute("contentAuthority") == ContactsContract.AUTHORITY
                }
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun getApplicationIcon(context: Context, packageName: String): AccountIcon? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            AccountIcon(drawable, getApplicationLabel(context, packageName) ?: packageName, isGeneric = false)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun getAuthenticatorIcon(context: Context, authenticator: AuthenticatorDescription): AccountIcon? {
        val packageManager = context.packageManager
        return try {
            val resources = packageManager.getResourcesForApplication(authenticator.packageName)
            val iconId = authenticator.iconId.takeIf { it != 0 } ?: authenticator.smallIconId
            val drawable = iconId.takeIf { it != 0 }?.let { ResourcesCompat.getDrawable(resources, it, null) }
                ?: packageManager.getApplicationIcon(authenticator.packageName)
            val label = authenticator.labelId.takeIf { it != 0 }?.let {
                packageManager.getText(authenticator.packageName, it, null)?.toString()
            } ?: getApplicationLabel(context, authenticator.packageName) ?: authenticator.type
            AccountIcon(drawable, label, isGeneric = false)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun getPrivateContactsIcon(context: Context): AccountIcon {
        val packageManager = context.packageManager
        listOf(FOSSIFY_CONTACTS_PACKAGE, FOSSIFY_CONTACTS_DEBUG_PACKAGE).forEach { packageName ->
            try {
                val drawable = packageManager.getApplicationIcon(packageName)
                val label = getApplicationLabel(context, packageName) ?: packageName
                return AccountIcon(drawable, label, isGeneric = false)
            } catch (ignored: Exception) {
            }
        }

        val drawable = ResourcesCompat.getDrawable(context.resources, CommonsR.drawable.ic_lock_vector, null)
        return AccountIcon(drawable, context.getString(CommonsR.string.phone_storage_hidden), isGeneric = true)
    }

    // contacts stored on the device only, or in an account whose app doesn't tell its icon
    private fun getGenericIcon(context: Context): AccountIcon {
        val drawable = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_smartphone_vector, null)
        return AccountIcon(drawable, context.getString(CommonsR.string.phone_storage), isGeneric = true)
    }

    private fun getApplicationLabel(context: Context, packageName: String): String? {
        return try {
            val packageManager = context.packageManager
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (ignored: Exception) {
            null
        }
    }
}
