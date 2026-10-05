package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import org.fossify.phone.extensions.getPhoneAccountHandleModel
import org.fossify.phone.extensions.putPhoneAccountHandle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.phone.models.SpeedDial
import androidx.core.content.edit
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(
            key = getKeyForCustomSIM(number),
            parcelable = handle
        ).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeNumberKey(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit().remove(getKeyForCustomSIM(number)).apply()
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeNumberKey(number)
    }

    // a stable key for a number, so that the local and international forms of a number match
    fun normalizeNumberKey(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    // names given to numbers that aren't contacts, stored only in the app, keyed by normalizeNumberKey()
    var numberNames: Map<String, String>
        get() {
            val json = prefs.getString(NUMBER_NAMES, null)
            if (json.isNullOrEmpty()) return emptyMap()

            return try {
                Json.decodeFromString<Map<String, String>>(json)
            } catch (_: SerializationException) {
                emptyMap()
            } catch (_: IllegalArgumentException) {
                emptyMap()
            }
        }
        set(numberNames) = prefs.edit { putString(NUMBER_NAMES, Json.encodeToString(numberNames)) }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit().putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls).apply()

    var showFavoritesInCallHistory: Boolean
        get() = prefs.getBoolean(SHOW_FAVORITES_IN_CALL_HISTORY, false)
        set(showFavoritesInCallHistory) = prefs.edit()
            .putBoolean(SHOW_FAVORITES_IN_CALL_HISTORY, showFavoritesInCallHistory).apply()

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit().putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad).apply()

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit().putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor).apply()

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit().putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer).apply()

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit().putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed).apply()

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit().putBoolean(DIALPAD_VIBRATION, dialpadVibration).apply()

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit().putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers).apply()

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit().putBoolean(DIALPAD_BEEPS, dialpadBeeps).apply()

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit().putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen).apply()

    var askSimBeforeCall: Boolean
        get() = prefs.getBoolean(ASK_SIM_BEFORE_CALL, true)
        set(value) = prefs.edit { putBoolean(ASK_SIM_BEFORE_CALL, value) }

    // the date of the newest missed call the user has been alerted about, so it doesn't ring again
    var lastNotifiedMissedCallDate: Long
        get() = prefs.getLong(LAST_NOTIFIED_MISSED_CALL_DATE, 0L)
        set(value) = prefs.edit { putLong(LAST_NOTIFIED_MISSED_CALL_DATE, value) }

    var flipToSilence: Boolean
        get() = prefs.getBoolean(FLIP_TO_SILENCE, false)
        set(value) = prefs.edit { putBoolean(FLIP_TO_SILENCE, value) }

    var perSimRingtonesEnabled: Boolean
        get() = prefs.getBoolean(PER_SIM_RINGTONES_ENABLED, false)
        set(value) = prefs.edit { putBoolean(PER_SIM_RINGTONES_ENABLED, value) }

    fun getSimRingtone(handle: PhoneAccountHandle): String? {
        return prefs.getString(SIM_RINGTONE_PREFIX + getHandleKey(handle), null)
    }

    fun setSimRingtone(handle: PhoneAccountHandle, uri: String?) {
        val key = SIM_RINGTONE_PREFIX + getHandleKey(handle)
        prefs.edit {
            if (uri.isNullOrEmpty()) remove(key) else putString(key, uri)
        }
    }

    // stable string identity for a SIM, matching how PhoneAccountHandleModel is built
    private fun getHandleKey(handle: PhoneAccountHandle): String {
        val componentName = handle.componentName
        return "${componentName.packageName}/${componentName.className}/${handle.id}"
    }
}
