package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.view.Menu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.commons.activities.ManageBlockedNumbersActivity
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.addLockedLabelIfNeeded
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getFontSizeText
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_MEDIUM
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ON_CLICK_CALL_CONTACT
import org.fossify.commons.helpers.ON_CLICK_VIEW_CONTACT
import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES
import org.fossify.commons.helpers.TAB_LAST_USED
import org.fossify.commons.helpers.isNougatPlus
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivitySettingsBinding
import org.fossify.phone.dialogs.ExportCallHistoryDialog
import org.fossify.phone.dialogs.ManageVisibleTabsDialog
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.canLaunchAccountsConfiguration
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.extensions.launchAccountsConfiguration
import org.fossify.phone.helpers.FlipToSilenceDetector
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.models.RecentCall
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    companion object {
        private const val CALL_HISTORY_FILE_TYPE = "application/json"
        private val IMPORT_CALL_HISTORY_FILE_TYPES = buildList {
            add("application/json")
            if (!isQPlus()) {
                // Workaround for https://github.com/FossifyOrg/Messages/issues/88
                add("application/octet-stream")
            }
        }
    }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)
    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                toast(R.string.importing)
                importCallHistory(uri)
            }
        }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(CALL_HISTORY_FILE_TYPE)) { uri ->
        if (uri != null) {
            toast(R.string.exporting)
            RecentsHelper(this).getRecentCalls(queryLimit = Int.MAX_VALUE) { recents ->
                exportCallHistory(recents, uri)
            }
        }
    }

    // which SIM the system ringtone picker is currently choosing a ringtone for
    private var ringtonePickerSimHandle: PhoneAccountHandle? = null
    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val handle = ringtonePickerSimHandle
        ringtonePickerSimHandle = null
        if (handle != null && result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            config.setSimRingtone(handle, uri?.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(settingsNestedScrollview))
            setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageSpeedDial()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupManageShownTabs()
        setupDefaultTab()
        setupOnContactClick()
        setupDialPadOpen()
        setupGroupSubsequentCalls()
        setupShowFavoritesInCallHistory()
        setupStartNameWithSurname()
        setupShowContactAccounts()
        setupFormatPhoneNumbers()
        setupDialpadVibrations()
        setupDialpadNumbers()
        setupDialpadBeeps()
        setupShowCallConfirmation()
        setupDisableProximitySensor()
        setupDisableSwipeToAnswer()
        setupAlwaysShowFullscreen()
        setupFlipToSilence()
        setupAskSimBeforeCall()
        setupCallBackWithNumberSim()
        setupPerSimRingtones()
        setupManageSimRingtones()
        setupCallsExport()
        setupCallsImport()
        updateTextColors(binding.settingsHolder)

        binding.apply {
            arrayOf(
                settingsColorCustomizationSectionLabel,
                settingsGeneralSettingsLabel,
                settingsStartupLabel,
                settingsCallsLabel,
                settingsDialpadSectionLabel,
                settingsMigrationSectionLabel
            ).forEach {
                it.setTextColor(getProperPrimaryColor())
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.calling_accounts -> launchAccountsConfiguration()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.calling_accounts).isVisible = canLaunchAccountsConfiguration()
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() {
        binding.apply {
            settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
            settingsUseEnglish.isChecked = config.useEnglish
            settingsUseEnglishHolder.setOnClickListener {
                settingsUseEnglish.toggle()
                config.useEnglish = settingsUseEnglish.isChecked
                exitProcess(0)
            }
        }
    }

    private fun setupLanguage() {
        binding.apply {
            settingsLanguage.text = Locale.getDefault().displayLanguage
            settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        }
    }

    private fun setupManageBlockedNumbers() {
        binding.apply {
            settingsManageBlockedNumbersLabel.text = addLockedLabelIfNeeded(R.string.manage_blocked_numbers)
            settingsManageBlockedNumbersHolder.beVisibleIf(isNougatPlus())
            settingsManageBlockedNumbersHolder.setOnClickListener {
                if (isOrWasThankYouInstalled()) {
                    Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                        startActivity(this)
                    }
                } else {
                    FeatureLockedDialog(this@SettingsActivity) { }
                }
            }
        }
    }

    private fun setupManageSpeedDial() {
        binding.settingsManageSpeedDialHolder.setOnClickListener {
            Intent(this, ManageSpeedDialActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupFontSize() {
        binding.settingsFontSize.text = getFontSizeText()
        binding.settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(R.string.extra_large))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                binding.settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupManageShownTabs() {
        binding.settingsManageTabsHolder.setOnClickListener {
            ManageVisibleTabsDialog(this)
        }
    }

    private fun setupDefaultTab() {
        binding.settingsDefaultTab.text = getDefaultTabText()
        binding.settingsDefaultTabHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TAB_CONTACTS, getString(R.string.contacts_tab)),
                RadioItem(TAB_FAVORITES, getString(R.string.favorites_tab)),
                RadioItem(TAB_CALL_HISTORY, getString(R.string.call_history_tab)),
                RadioItem(TAB_LAST_USED, getString(R.string.last_used_tab))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.defaultTab) {
                config.defaultTab = it as Int
                binding.settingsDefaultTab.text = getDefaultTabText()
            }
        }
    }

    private fun getDefaultTabText() = getString(
        when (baseConfig.defaultTab) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.call_history_tab
            else -> R.string.last_used_tab
        }
    )

    private fun setupOnContactClick() {
        binding.settingsOnContactClick.text = getOnContactClickText()
        binding.settingsOnContactClickHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(ON_CLICK_CALL_CONTACT, getString(org.fossify.commons.R.string.call_contact)),
                RadioItem(ON_CLICK_VIEW_CONTACT, getString(org.fossify.commons.R.string.view_contact))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.onContactClick) {
                config.onContactClick = it as Int
                binding.settingsOnContactClick.text = getOnContactClickText()
            }
        }
    }

    private fun getOnContactClickText() = getString(
        when (config.onContactClick) {
            ON_CLICK_CALL_CONTACT -> org.fossify.commons.R.string.call_contact
            else -> org.fossify.commons.R.string.view_contact
        }
    )

    private fun setupDialPadOpen() {
        binding.apply {
            settingsOpenDialpadAtLaunch.isChecked = config.openDialPadAtLaunch
            settingsOpenDialpadAtLaunchHolder.setOnClickListener {
                settingsOpenDialpadAtLaunch.toggle()
                config.openDialPadAtLaunch = settingsOpenDialpadAtLaunch.isChecked
            }
        }
    }

    private fun setupGroupSubsequentCalls() {
        binding.apply {
            settingsGroupSubsequentCalls.isChecked = config.groupSubsequentCalls
            settingsGroupSubsequentCallsHolder.setOnClickListener {
                settingsGroupSubsequentCalls.toggle()
                config.groupSubsequentCalls = settingsGroupSubsequentCalls.isChecked
            }
        }
    }

    private fun setupShowFavoritesInCallHistory() {
        binding.apply {
            settingsShowFavoritesInCallHistory.isChecked = config.showFavoritesInCallHistory
            settingsShowFavoritesInCallHistoryHolder.setOnClickListener {
                settingsShowFavoritesInCallHistory.toggle()
                config.showFavoritesInCallHistory = settingsShowFavoritesInCallHistory.isChecked
            }
        }
    }

    private fun setupStartNameWithSurname() {
        binding.apply {
            settingsStartNameWithSurname.isChecked = config.startNameWithSurname
            settingsStartNameWithSurnameHolder.setOnClickListener {
                settingsStartNameWithSurname.toggle()
                config.startNameWithSurname = settingsStartNameWithSurname.isChecked
            }
        }
    }

    private fun setupShowContactAccounts() {
        binding.settingsShowContactAccounts.isChecked = config.showContactAccounts
        binding.settingsShowContactAccountsHolder.setOnClickListener {
            binding.settingsShowContactAccounts.toggle()
            config.showContactAccounts = binding.settingsShowContactAccounts.isChecked
        }
    }

    private fun setupFormatPhoneNumbers() {
        binding.settingsFormatPhoneNumbers.isChecked = config.formatPhoneNumbers
        binding.settingsFormatPhoneNumbersHolder.setOnClickListener {
            binding.settingsFormatPhoneNumbers.toggle()
            config.formatPhoneNumbers = binding.settingsFormatPhoneNumbers.isChecked
        }
    }

    private fun setupDialpadVibrations() {
        binding.apply {
            settingsDialpadVibration.isChecked = config.dialpadVibration
            settingsDialpadVibrationHolder.setOnClickListener {
                settingsDialpadVibration.toggle()
                config.dialpadVibration = settingsDialpadVibration.isChecked
            }
        }
    }

    private fun setupDialpadNumbers() {
        binding.apply {
            settingsHideDialpadNumbers.isChecked = config.hideDialpadNumbers
            settingsHideDialpadNumbersHolder.setOnClickListener {
                settingsHideDialpadNumbers.toggle()
                config.hideDialpadNumbers = settingsHideDialpadNumbers.isChecked
            }
        }
    }

    private fun setupDialpadBeeps() {
        binding.apply {
            settingsDialpadBeeps.isChecked = config.dialpadBeeps
            settingsDialpadBeepsHolder.setOnClickListener {
                settingsDialpadBeeps.toggle()
                config.dialpadBeeps = settingsDialpadBeeps.isChecked
            }
        }
    }

    private fun setupShowCallConfirmation() {
        binding.apply {
            settingsShowCallConfirmation.isChecked = config.showCallConfirmation
            settingsShowCallConfirmationHolder.setOnClickListener {
                settingsShowCallConfirmation.toggle()
                config.showCallConfirmation = settingsShowCallConfirmation.isChecked
            }
        }
    }

    private fun setupDisableProximitySensor() {
        binding.apply {
            settingsDisableProximitySensor.isChecked = config.disableProximitySensor
            settingsDisableProximitySensorHolder.setOnClickListener {
                settingsDisableProximitySensor.toggle()
                config.disableProximitySensor = settingsDisableProximitySensor.isChecked
            }
        }
    }

    private fun setupDisableSwipeToAnswer() {
        binding.apply {
            settingsDisableSwipeToAnswer.isChecked = config.disableSwipeToAnswer
            settingsDisableSwipeToAnswerHolder.setOnClickListener {
                settingsDisableSwipeToAnswer.toggle()
                config.disableSwipeToAnswer = settingsDisableSwipeToAnswer.isChecked
            }
        }
    }

    private fun setupAlwaysShowFullscreen() {
        binding.apply {
            settingsAlwaysShowFullscreen.isChecked = config.alwaysShowFullscreen
            settingsAlwaysShowFullscreenHolder.setOnClickListener {
                settingsAlwaysShowFullscreen.toggle()
                config.alwaysShowFullscreen = settingsAlwaysShowFullscreen.isChecked
            }
        }
    }

    private fun setupFlipToSilence() {
        binding.apply {
            settingsFlipToSilenceHolder.beVisibleIf(FlipToSilenceDetector.isSupported(this@SettingsActivity))
            settingsFlipToSilence.isChecked = config.flipToSilence
            settingsFlipToSilenceHolder.setOnClickListener {
                settingsFlipToSilence.toggle()
                config.flipToSilence = settingsFlipToSilence.isChecked
            }
        }
    }

    private fun setupAskSimBeforeCall() {
        binding.apply {
            settingsAskSimBeforeCallHolder.beVisibleIf(areMultipleSIMsAvailable())
            settingsAskSimBeforeCall.isChecked = config.askSimBeforeCall
            settingsAskSimBeforeCallHolder.setOnClickListener {
                settingsAskSimBeforeCall.toggle()
                config.askSimBeforeCall = settingsAskSimBeforeCall.isChecked
            }
        }
    }

    private fun setupCallBackWithNumberSim() {
        binding.apply {
            settingsCallBackWithNumberSimHolder.beVisibleIf(areMultipleSIMsAvailable())
            settingsCallBackWithNumberSim.isChecked = config.callBackWithNumberSim
            settingsCallBackWithNumberSimHolder.setOnClickListener {
                settingsCallBackWithNumberSim.toggle()
                config.callBackWithNumberSim = settingsCallBackWithNumberSim.isChecked
            }
        }
    }

    private fun setupPerSimRingtones() {
        // setSilenceCall, used to suppress the system ringer, is API 29+, so gate on Q+ and dual SIM
        val isSupported = isQPlus() && areMultipleSIMsAvailable()
        binding.apply {
            settingsPerSimRingtonesHolder.beVisibleIf(isSupported)
            settingsPerSimRingtones.isChecked = config.perSimRingtonesEnabled
            settingsPerSimRingtonesHolder.setOnClickListener {
                settingsPerSimRingtones.toggle()
                config.perSimRingtonesEnabled = settingsPerSimRingtones.isChecked
                updateManageSimRingtonesVisibility()
            }
        }
    }

    private fun setupManageSimRingtones() {
        updateManageSimRingtonesVisibility()
        binding.settingsManageSimRingtonesHolder.setOnClickListener {
            val sims = getAvailableSIMCardLabels()
            if (sims.isEmpty()) {
                return@setOnClickListener
            }

            val items = sims.mapIndexed { index, sim -> RadioItem(index, sim.label) } as ArrayList<RadioItem>
            RadioGroupDialog(this, items) { selected ->
                launchRingtonePicker(sims[selected as Int].handle)
            }
        }
    }

    private fun updateManageSimRingtonesVisibility() {
        val isVisible = isQPlus() && areMultipleSIMsAvailable() && config.perSimRingtonesEnabled
        binding.settingsManageSimRingtonesHolder.beVisibleIf(isVisible)
    }

    private fun launchRingtonePicker(handle: PhoneAccountHandle) {
        ringtonePickerSimHandle = handle
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, config.getSimRingtone(handle)?.toUri())
        }

        try {
            pickRingtone.launch(intent)
        } catch (e: Exception) {
            ringtonePickerSimHandle = null
            showErrorToast(e)
        }
    }

    private fun setupCallsExport() {
        binding.settingsExportCallsHolder.setOnClickListener {
            ExportCallHistoryDialog(this) { filename ->
                saveDocument.launch("$filename.json")
            }
        }
    }

    private fun setupCallsImport() {
        binding.settingsImportCallsHolder.setOnClickListener {
            getContent.launch(IMPORT_CALL_HISTORY_FILE_TYPES.toTypedArray())
        }
    }

    private fun importCallHistory(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)!!.use { inputStream ->
                inputStream.bufferedReader().readText()
            }

            val objects = Json.decodeFromString<List<RecentCall>>(jsonString)

            if (objects.isEmpty()) {
                toast(R.string.no_entries_for_importing)
                return
            }

            RecentsHelper(this).restoreRecentCalls(this, objects) {
                toast(R.string.importing_successful)
            }
        } catch (_: SerializationException) {
            toast(R.string.invalid_file_format)
        } catch (_: IllegalArgumentException) {
            toast(R.string.invalid_file_format)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun exportCallHistory(recents: List<RecentCall>, uri: Uri) {
        if (recents.isEmpty()) {
            toast(R.string.no_entries_for_exporting)
        } else {
            try {
                val outputStream = contentResolver.openOutputStream(uri)!!

                val jsonString = Json.encodeToString(recents)
                outputStream.use {
                    it.write(jsonString.toByteArray())
                }
                toast(R.string.exporting_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}
