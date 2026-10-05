package org.fossify.phone.extensions

private const val PHONE_FORMATTING_CHARS = "-().\\/"

/**
 * True for the characters used only to make a phone number readable (spaces, dashes, parentheses, dots, slashes).
 * Digits, `+`, `*`, `#` and the pause/wait characters `,` and `;` are not formatting characters.
 */
fun Char.isPhoneFormattingChar() = isWhitespace() || this in PHONE_FORMATTING_CHARS

/** Removes the formatting characters from a phone number, keeping everything that can be dialed. */
fun String.stripPhoneFormatting() = filterNot { it.isPhoneFormattingChar() }

/**
 * Drops a label in front of a phone number, like the "Tel: " in "Tel: 079 123 45 67", i.e. everything before
 * the first dialable character when that part contains a letter. Anything after it is kept as is.
 */
fun String.dropLeadingPhoneLabel(): String {
    val start = indexOfFirst { it.isDigit() || it == '+' || it == '*' || it == '#' }
    return if (start > 0 && substring(0, start).any { it.isLetter() }) substring(start) else this
}
