package org.fossify.phone.extensions

import android.content.Context

// Names given to numbers that aren't contacts, stored only in the app, never in the system contacts.
// Contacts always take precedence, so only use these when no contact matches the number.

/** The name the user gave to [number] in the app, or null. */
fun Context.getNumberName(number: String): String? {
    val config = config
    val names = config.numberNames
    return if (names.isEmpty()) null else names[config.normalizeNumberKey(number)]
}

/** Saves [name] for [number], a blank [name] removes it. */
fun Context.setNumberName(number: String, name: String?) {
    val config = config
    val names = config.numberNames.toMutableMap()
    val key = config.normalizeNumberKey(number)
    if (name.isNullOrBlank()) {
        names.remove(key)
    } else {
        names[key] = name.trim()
    }

    config.numberNames = names
}

/**
 * Like [getNumberName], for looking up many numbers at once such as the call history:
 * the names are read once and each distinct number is normalized only once.
 */
fun Context.getNumberNameLookup(): (String) -> String? {
    val config = config
    val names = config.numberNames
    if (names.isEmpty()) return { null }

    val cache = HashMap<String, String?>()
    return { number ->
        if (cache.containsKey(number)) {
            cache[number]
        } else {
            names[config.normalizeNumberKey(number)].also { cache[number] = it }
        }
    }
}
