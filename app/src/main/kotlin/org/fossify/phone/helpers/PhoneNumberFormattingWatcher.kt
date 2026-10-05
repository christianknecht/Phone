package org.fossify.phone.helpers

import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.fossify.phone.extensions.isPhoneFormattingChar
import org.fossify.phone.extensions.stripPhoneFormatting

/**
 * Formats the phone number as it is typed, like the stock dialers do (e.g. `079 123 45 67` in Switzerland).
 *
 * Unlike the platform PhoneNumberFormattingTextWatcher, the number is always reformatted from its dialable
 * characters, so pasted text and numbers set programmatically get formatted too. Numbers that can't be
 * formatted (containing `*`, `#`, pauses, letters...) are shown without any formatting.
 * The cursor is kept after the same dialable character, so digits can be inserted in the middle.
 */
class PhoneNumberFormattingWatcher(countryIso: String) : TextWatcher {
    private val formatter = PhoneNumberUtil.getInstance().getAsYouTypeFormatter(countryIso)
    private val formattableRegex = Regex("^\\+?[0-9]+$")
    private var selfChange = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable) {
        if (selfChange) {
            return
        }

        val text = s.toString()
        val formatted = format(text.stripPhoneFormatting())
        if (formatted == text) {
            return
        }

        val selectionEnd = Selection.getSelectionEnd(s)
        val cursor = if (selectionEnd in 0..text.length) selectionEnd else text.length
        val dialableBeforeCursor = text.substring(0, cursor).stripPhoneFormatting().length

        selfChange = true
        try {
            s.replace(0, s.length, formatted)
            Selection.setSelection(s, getCursorPosition(formatted, dialableBeforeCursor))
        } finally {
            selfChange = false
        }
    }

    private fun format(number: String): String {
        if (!formattableRegex.matches(number)) {
            return number
        }

        formatter.clear()
        var result = number
        number.forEach { result = formatter.inputDigit(it) }
        // never show something else than the typed digits
        return if (result.stripPhoneFormatting() == number) result else number
    }

    private fun getCursorPosition(formatted: String, dialableBeforeCursor: Int): Int {
        if (dialableBeforeCursor <= 0) {
            return 0
        }

        var dialableCount = 0
        formatted.forEachIndexed { index, char ->
            if (!char.isPhoneFormattingChar()) {
                dialableCount++
                if (dialableCount == dialableBeforeCursor) {
                    return if (dialableCount == formatted.stripPhoneFormatting().length) {
                        formatted.length
                    } else {
                        index + 1
                    }
                }
            }
        }
        return formatted.length
    }
}
