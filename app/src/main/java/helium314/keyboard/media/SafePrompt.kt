// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

/**
 * Image maker safeguard: prompts asking for sexual, nude or graphically violent pictures are refused before anything
 * is drawn. Whole words only (so "Essex" or "class" are fine), ignoring case, accents, spacing tricks like "n u d e"
 * and common letter swaps like "nud3". The finished picture is checked separately.
 */
object SafePrompt {
    // only the clear-cut words: creative things (horror, zombies, "a sexy sports car", swimwear) stay allowed,
    // and the picture check catches anything explicit that gets through
    private val blocked = setOf(
        "nude", "nudes", "nudity", "naked", "topless", "nsfw", "porn", "porno", "pornographic", "pornography", "xxx",
        "sex", "sexual", "erotic", "hentai", "orgasm", "intercourse", "masturbate", "masturbating",
        "nipple", "nipples", "genital", "genitals", "penis", "vagina", "vulva",
        "gore", "beheaded", "beheading", "decapitated", "dismembered", "disembowelled", "disemboweled", "self harm",
    )

    private val swaps = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')

    private fun normalise(text: String): String {
        val plain = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.map { swaps[it] ?: it }.joinToString("")
    }

    /** Null when the prompt is fine, otherwise a short, friendly reason to show. */
    fun check(prompt: String): String? {
        val text = normalise(prompt)
        val words = text.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        // "n u d e", "n.u.d.e": single letters run together
        val squashed = Regex("(?:\\b[a-z]\\b[^a-z]*){3,}").findAll(text).map { it.value.filter(Char::isLetter) }.toList()
        val all = words + squashed
        val pairs = words.zipWithNext { a, b -> "$a $b" }
        val hit = all.any { it in blocked } || pairs.any { it in blocked }
        return if (hit) "Local Image doesn't make nude, sexual or gory pictures. Try something else." else null
    }
}
