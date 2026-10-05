package org.fossify.phone.helpers

import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val SHOW_FAVORITES_IN_CALL_HISTORY = "show_favorites_in_call_history"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"
const val ASK_SIM_BEFORE_CALL = "ask_sim_before_call"
const val PER_SIM_RINGTONES_ENABLED = "per_sim_ringtones_enabled"
const val FLIP_TO_SILENCE = "flip_to_silence"
const val SIM_RINGTONE_PREFIX = "sim_ringtone_"
const val NUMBER_NAMES = "number_names"

const val ALL_TABS_MASK = TAB_CONTACTS or TAB_FAVORITES or TAB_CALL_HISTORY

val tabsList = arrayListOf(TAB_CONTACTS, TAB_FAVORITES, TAB_CALL_HISTORY)

private const val PATH = "org.fossify.phone.action."
const val ACCEPT_CALL = PATH + "ACCEPT_CALL"
const val DECLINE_CALL = PATH + "DECLINE_CALL"
const val MISSED_CALLS_DISMISSED = PATH + "MISSED_CALLS_DISMISSED"
const val MISSED_CALL_BACK = PATH + "MISSED_CALL_BACK"
const val MISSED_CALL_MESSAGE = PATH + "MISSED_CALL_MESSAGE"

// missed call notification action extras
const val MISSED_CALL_NUMBER = "missed_call_number"
const val MISSED_CALL_SIM_HANDLE = "missed_call_sim_handle"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds
