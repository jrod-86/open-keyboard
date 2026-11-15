// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import java.io.File

/**
 * Gets text ready for Kokoro. Kokoro (through sherpa-onnx) reads only words in its word list and silently skips the
 * rest, so: numbers, money, times and dates become words, and names it doesn't know ("Jarrod", "Samsung") are built
 * from words it does know plus simple letter sounds, joined with "-" (sherpa reads the pieces as one word).
 */
object Pronounce {
    // letter sounds, in Kokoro's phoneme symbols (misaki: A = "ay", I = "eye", O = "oh", W = "ow", Y = "oy")
    private val CONSONANTS = linkedMapOf(
        "b" to "b", "c" to "k", "ck" to "k", "ch" to "ʧ", "d" to "d", "f" to "f", "ff" to "f", "g" to "ɡ", "gh" to "ɡ",
        "h" to "h", "j" to "ʤ", "k" to "k", "kh" to "k", "l" to "l", "ll" to "l", "m" to "m", "mm" to "m", "n" to "n",
        "nn" to "n", "ng" to "ŋ", "p" to "p", "pp" to "p", "ph" to "f", "qu" to "k w", "q" to "k", "r" to "ɹ", "rr" to "ɹ",
        "s" to "s", "ss" to "s", "sh" to "ʃ", "t" to "t", "th" to "θ", "tt" to "t", "v" to "v", "w" to "w", "wh" to "w",
        "x" to "k s", "z" to "z", "zz" to "z", "zh" to "ʒ", "tch" to "ʧ", "dg" to "ʤ", "y" to "j",
    )
    private val VOWELS = linkedMapOf(
        "a" to "æ", "e" to "ɛ", "i" to "ɪ", "o" to "ɑ", "u" to "ʌ", "y" to "i", "ai" to "A", "ay" to "A", "ee" to "i",
        "ea" to "i", "ie" to "i", "oa" to "O", "oo" to "u", "ou" to "W", "ow" to "O", "oi" to "Y", "oy" to "Y",
        "au" to "ɔ", "aw" to "ɔ", "ar" to "ɑ ɹ", "er" to "ɜ ɹ", "ir" to "ɜ ɹ", "ur" to "ɜ ɹ", "or" to "ɔ ɹ",
        "eu" to "u", "ei" to "A", "ue" to "u",
    )
    // our own made-up words for the sounds: "xqu" + letters (plain), "xqs" + letters (stressed vowel)
    // word endings added to a known word ("dogs", "keyboards", "texted"), "xqf" + name
    private val ENDINGS = linkedMapOf("z" to "z", "s" to "s", "iz" to "ᵻ z", "d" to "d", "t" to "t", "id" to "ᵻ d",
        "ing" to "ɪ ŋ", "er" to "ɚ", "ers" to "ɚ z", "ly" to "l i")
    private fun plainKey(g: String) = "xqu$g"
    private fun stressKey(g: String) = "xqs$g"

    /** Writes the letter-sound words as an extra word list for sherpa-onnx. */
    fun writeExtraLexicon(f: File): File {
        val sb = StringBuilder()
        for ((g, p) in CONSONANTS) sb.append(plainKey(g)).append(' ').append(p).append('\n')
        for ((g, p) in VOWELS) {
            sb.append(plainKey(g)).append(' ').append(p).append('\n')
            sb.append(stressKey(g)).append(' ').append("ˈ ").append(p).append('\n')
        }
        for ((g, p) in ENDINGS) sb.append("xqf").append(g).append(' ').append(p).append('\n')
        if (!f.isFile || f.readText() != sb.toString()) f.writeText(sb.toString())
        return f
    }

    /** Text ready to speak: numbers as words, unknown names spelled out by sound. */
    fun prepare(text: String, known: Set<String>): String = fixWords(numbers(cleanup(text)), known)

    private fun cleanup(t: String): String {
        var s = t.replace('’', '\'').replace('‘', '\'').replace("&", " and ").replace("@", " at ")
            .replace(Regex("https?://\\S+"), " link ")
            .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), " ") // emojis
        // "S24" -> "S 24", "4pm" -> "4 pm"
        s = s.replace(Regex("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])(?!(st|nd|rd|th)\\b)"), " ")
        return s
    }

    // ---- numbers ----

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    fun say(n: Long): String {
        if (n < 0) return "minus " + say(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + (if (n % 10 != 0L) "-" + ONES[(n % 10).toInt()] else "")
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + (if (n % 100 != 0L) " and " + say(n % 100) else "")
        for ((size, word) in listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")) {
            if (n >= size) {
                val rest = n % size
                return say(n / size) + " " + word + when {
                    rest == 0L -> ""
                    rest < 100 -> " and " + say(rest)
                    else -> " " + say(rest)
                }
            }
        }
        return n.toString()
    }

    fun ordinal(n: Long): String {
        val w = say(n)
        val irregular = mapOf("one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
            "nine" to "ninth", "twelve" to "twelfth")
        val m = Regex("([a-z]+)$").find(w) ?: return w
        val last = m.value
        val ord = irregular[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return w.substring(0, m.range.first) + ord
    }

    private fun digits(s: String) = s.map { if (it.isDigit()) ONES[it - '0'] else "" }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun year(n: Int): String = when {
        n in 2000..2009 -> say(n.toLong())
        n % 100 == 0 -> say((n / 100).toLong()) + " hundred"
        n % 100 < 10 -> say((n / 100).toLong()) + " oh " + ONES[n % 100]
        else -> say((n / 100).toLong()) + " " + say((n % 100).toLong())
    }

    private fun plain(numStr: String, yearOk: Boolean): String {
        val s = numStr.replace(",", "")
        if (s.length > 1 && s.startsWith("0")) return digits(s) // codes and phone numbers
        if (s.length > 12) return digits(s)
        val n = s.toLong()
        if (yearOk && s.length == 4 && n in 1100..2099) return year(n.toInt())
        return say(n)
    }

    private fun numbers(t: String): String {
        var s = t
        // money: $4.50, $1,200
        s = s.replace(Regex("([$€£])\\s?(\\d[\\d,]*)(?:\\.(\\d{1,2}))?(\\s?[kKmM]\\b)?")) { m ->
            val unit = when (m.groupValues[1]) { "€" -> "euro" to "euros"; "£" -> "pound" to "pounds"; else -> "dollar" to "dollars" }
            val whole = m.groupValues[2].replace(",", "").toLongOrNull() ?: return@replace m.value
            val big = when (m.groupValues[4].trim().lowercase()) { "k" -> " thousand"; "m" -> " million"; else -> "" }
            val cents = m.groupValues[3].let { if (it.isEmpty()) 0 else (if (it.length == 1) it + "0" else it).toInt() }
            buildString {
                append(say(whole)).append(big).append(' ').append(if (whole == 1L && big.isEmpty()) unit.first else unit.second)
                if (cents > 0) append(" and ").append(say(cents.toLong())).append(if (unit.first == "pound") " pence" else if (cents == 1) " cent" else " cents")
            }
        }
        // percent
        s = s.replace(Regex("(\\d+(?:\\.\\d+)?)\\s?%")) { m -> decimal(m.groupValues[1]) + " percent" }
        // times: 3:30, 10:05, 3:00
        s = s.replace(Regex("\\b(\\d{1,2}):(\\d{2})\\b")) { m ->
            val h = m.groupValues[1].toLong(); val mi = m.groupValues[2].toInt()
            say(h) + when { mi == 0 -> " o'clock"; mi < 10 -> " oh " + ONES[mi]; else -> " " + say(mi.toLong()) }
        }
        s = s.replace(Regex("\\b([aApP])\\.?[mM]\\.?\\b")) { m -> if (m.groupValues[1].lowercase() == "a") "A M" else "P M" }
        // dates like 15/11/2026 (day first, as in Australia)
        s = s.replace(Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\b")) { m ->
            val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (d !in 1..31 || mo !in 1..12) m.value
            else "the " + ordinal(d.toLong()) + " of " + months[mo - 1] + ", " + m.groupValues[3].toInt().let { y -> year(if (y < 100) 2000 + y else y) }
        }
        // ordinals: 1st, 22nd, 15th
        s = s.replace(Regex("\\b(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)) { m -> ordinal(m.groupValues[1].toLong()) }
        // phone numbers and codes: groups of digits with spaces or dashes, read digit by digit
        s = s.replace(Regex("(?<![\\d.,])\\+?\\d{2,5}(?:[ -]\\d{2,5}){1,4}(?!\\d|[.,]\\d)")) { m ->
            val ds = m.value.filter { it.isDigit() }
            if (ds.length >= 8 || ds.startsWith("0")) m.value.split(Regex("[ -]")).joinToString(", ") { digits(it) } else m.value
        }
        // decimals, then whole numbers (with thousands commas)
        s = s.replace(Regex("\\b\\d+\\.\\d+\\b")) { m -> decimal(m.value) }
        s = s.replace(Regex("\\b\\d{1,3}(?:,\\d{3})+\\b|\\b\\d+\\b")) { m -> plain(m.value, true) }
        // a few signs
        s = s.replace("+", " plus ").replace(Regex("(?<=\\s)=(?=\\s)"), " equals ").replace("#", " number ")
        return s
    }

    private fun decimal(v: String): String {
        val parts = v.split(".")
        val whole = plain(parts[0], false)
        return if (parts.size < 2) whole else whole + " point " + digits(parts[1])
    }

    // ---- unknown words ----

    private fun fixWords(t: String, known: Set<String>): String = Regex("[A-Za-z][A-Za-z']*").replace(t) { m ->
        val w = m.value
        val lower = w.lowercase()
        when {
            lower in known -> w
            lower.endsWith("'s") && lower.dropLast(2) in known -> w
            lower.endsWith("'s") -> withEnding(lower.dropLast(2), "'s", known) ?: (soundOut(lower.dropLast(2), known) + "-" + plainKey("z"))
            w.length in 2..5 && w.all { it.isUpperCase() } -> w.toList().joinToString(" ") // TV, ABC: say the letters
            else -> withEnding(lower, null, known) ?: soundOut(lower.replace("'", ""), known)
        }
    }

    /** A known word plus an ending: "dogs" = "dog" + z, "texted" = "text" + id, "running" = "run" + ing. */
    private fun withEnding(w: String, forced: String?, known: Set<String>): String? {
        fun sKey(base: String) = when {
            Regex("(s|x|z|ch|sh|ge|ce|se|ze)$").containsMatchIn(base) -> "iz"
            Regex("([ptkf]|th)$").containsMatchIn(base) -> "s"
            else -> "z"
        }
        fun dKey(base: String) = when {
            Regex("[td]e?$").containsMatchIn(base) -> "id"
            Regex("([pkfsx]|ch|sh)e?$").containsMatchIn(base) -> "t"
            else -> "d"
        }
        fun base(stem: String, eToo: Boolean): String? = when {
            stem.length >= 2 && stem in known -> stem
            eToo && stem + "e" in known -> stem + "e"
            stem.length >= 3 && stem.last() == stem[stem.length - 2] && stem.dropLast(1) in known -> stem.dropLast(1) // runn-ing
            stem.endsWith("i") && stem.dropLast(1) + "y" in known -> stem.dropLast(1) + "y" // tri-es, cri-ed
            else -> null
        }
        if (forced == "'s") return if (w in known) w + "-xqf" + sKey(w) else null
        val rules = listOf("ers" to "ers", "ing" to "ing", "ly" to "ly", "er" to "er", "es" to "s", "ed" to "d", "s" to "s", "d" to "d")
        for ((end, kind) in rules) {
            if (!w.endsWith(end) || w.length - end.length < 2) continue
            val stem = w.dropLast(end.length)
            val b = base(stem, end != "s" && end != "ly") ?: continue
            if (end == "d" && !b.endsWith("e")) continue
            val key = when (kind) { "s" -> sKey(b); "d" -> dKey(b); else -> kind }
            return b + "-xqf" + key
        }
        return null
    }

    /**
     * Builds an unknown word from known words of 3+ letters and letter sounds, using as few pieces as possible
     * (known words count as cheaper). "jarrod" -> "jar-rod", "zoltrix" -> z-o-l-t-r-i-x sounds.
     */
    fun soundOut(w: String, known: Set<String>): String {
        if (w.isEmpty()) return w
        val n = w.length
        val cost = IntArray(n + 1) { Int.MAX_VALUE }
        val from = IntArray(n + 1)
        val kind = CharArray(n + 1)
        cost[0] = 0
        for (i in 0 until n) {
            if (cost[i] == Int.MAX_VALUE) continue
            for (j in i + 1..minOf(n, i + 14)) {
                val p = w.substring(i, j)
                val options = buildList {
                    if (p.length >= 3 && p in known) add(2 to 'L')
                    if (p in CONSONANTS) add(3 to 'C')
                    if (p in VOWELS) add(3 to 'V')
                }
                for ((c, k) in options) if (cost[i] + c < cost[j]) { cost[j] = cost[i] + c; from[j] = i; kind[j] = k }
            }
        }
        if (cost[n] == Int.MAX_VALUE) return w
        val parts = mutableListOf<Pair<Char, String>>()
        var j = n
        while (j > 0) { val i = from[j]; parts.add(0, kind[j] to w.substring(i, j)); j = i }
        // a word needs one stressed sound: known words bring their own, otherwise stress the first vowel
        var stressed = parts.any { it.first == 'L' }
        return parts.joinToString("-") { (k, p) ->
            when (k) {
                'L' -> p
                'V' -> if (!stressed) { stressed = true; stressKey(p) } else plainKey(p)
                else -> plainKey(p)
            }
        }
    }
}
