// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType

/**
 * Open Keyboard (test 219, idea 7): what kind of thing was copied (phone number, email, link, address), so the paste
 * chip can say so with a small sign, and a phone, email or web address box gets just that part of the copied text
 * ("Call me on 0412 345 678 after 5" pastes 0412345678 into a phone number box).
 */
class SmartClip private constructor(val kind: Kind, private val part: String) {
    enum class Kind(val badge: String) { PHONE("📞 "), EMAIL("✉️ "), LINK("🔗 "), ADDRESS("📍 ") }

    /** The piece to paste into a box of this [inputType], or null to paste the copied text as it is. */
    fun forField(inputType: Int): String? {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when {
            kind == Kind.PHONE && cls == InputType.TYPE_CLASS_PHONE -> part.filter { it.isDigit() || it == '+' }
            kind == Kind.EMAIL && cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> part
            kind == Kind.LINK && cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> part
            else -> null
        }
    }

    companion object {
        private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val link = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+")
        // 8 to 15 digits, with spaces, dashes, dots or brackets between, optionally starting with +
        private val phone = Regex("(?<![\\w+])\\+?\\(?\\d[\\d ().-]{6,18}\\d(?!\\w)")
        private val date = Regex("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}")
        private val street = Regex("(?i)\\b\\d{1,5}[a-z]?\\s+[a-z][a-z' -]{1,40}\\s(?:street|st|road|rd|avenue|ave|drive|dr|lane|ln|court|ct|place|pl|crescent|cres|parade|pde|highway|hwy|boulevard|blvd|way|terrace|tce|close|grove)\\b")

        /** Looks at copied text (only short text: a long note isn't one of these). */
        fun of(text: CharSequence): SmartClip? {
            if (text.length > 300) return null
            val s = text.toString()
            email.find(s)?.let { return SmartClip(Kind.EMAIL, it.value) }
            link.find(s)?.let { return SmartClip(Kind.LINK, it.value.trimEnd('.', ',', ')', '!', '?')) }
            street.find(s)?.let { return SmartClip(Kind.ADDRESS, s.trim()) }
            phone.find(s)?.let { m ->
                val digits = m.value.count { it.isDigit() }
                if (digits in 8..15 && !date.matches(m.value.trim())) return SmartClip(Kind.PHONE, m.value.trim())
            }
            return null
        }
    }
}
