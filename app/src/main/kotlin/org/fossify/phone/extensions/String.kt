package org.fossify.phone.extensions

private const val PHONE_FORMATTING_CHARS = "-().\\/"

/**
 * True for the characters used only to make a phone number readable (spaces, dashes, parentheses, dots, slashes).
 * Digits, `+`, `*`, `#` and the pause/wait characters `,` and `;` are not formatting characters.
 */
fun Char.isPhoneFormattingChar() = isWhitespace() || this in PHONE_FORMATTING_CHARS

/** Removes the formatting characters from a phone number, keeping everything that can be dialed. */
fun String.stripPhoneFormatting() = filterNot { it.isPhoneFormattingChar() }
