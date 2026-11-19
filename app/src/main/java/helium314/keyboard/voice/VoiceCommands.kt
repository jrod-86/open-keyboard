// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/** Gboard-style spoken punctuation and commands for voice typing. */
object VoiceCommands {
    enum class Action { DELETE_THAT, SEND, STOP, CLEAR_ALL }

    private val actions = mapOf(
        "delete that" to Action.DELETE_THAT, "undo that" to Action.DELETE_THAT, "scratch that" to Action.DELETE_THAT,
        "delete" to Action.DELETE_THAT,
        "send" to Action.SEND, "send it" to Action.SEND, "send message" to Action.SEND, "send that" to Action.SEND,
        "stop listening" to Action.STOP, "stop voice typing" to Action.STOP,
        "clear all" to Action.CLEAR_ALL, "delete all" to Action.CLEAR_ALL, "clear everything" to Action.CLEAR_ALL,
    )

    /** If the whole sentence is a command, returns it. */
    fun action(sentence: String): Action? {
        if (!helium314.keyboard.latin.Plugins.isOn("voice_commands")) return null
        val plain = sentence.lowercase().replace(notLetters, " ").trim().replace(spaces, " ")
        return actions[plain]
    }

    /** "…, send." / "… send it" at the very end of what was said (Chat's Hands-free: say send to send). */
    private val trailingSend = Regex("(?i)[\\s,.!?;:-]*\\b(?:and |now )?send(?: it| that| message| the message| now)?[\\s.!?]*$")

    fun endsWithSend(sentence: String) = trailingSend.containsMatchIn(sentence)

    /** What was said, without the "send" on the end. */
    fun withoutSend(sentence: String) = trailingSend.replace(sentence, "").trim()

    // longest phrases first so "exclamation mark" wins over a shorter match
    private val marks = listOf(
        "new paragraph" to "\n\n", "next paragraph" to "\n\n", "new lines" to "\n", "new line" to "\n", "next line" to "\n",
        // what the speech models sometimes hear instead of "new line"
        "near line" to "\n", "knew line" to "\n", "newline" to "\n",
        "exclamation mark" to "!", "exclamation point" to "!", "question mark" to "?",
        "full stop" to ".", "comma" to ",", "semicolon" to ";", "semi colon" to ";",
        "colon" to ":", "ellipsis" to "…", "dot dot dot" to "…",
        "open bracket" to "(", "close bracket" to ")", "open quote" to "\"", "close quote" to "\"",
        "hyphen" to "-", "dash" to " –", "ampersand" to "&", "at sign" to "@", "hashtag" to "#",
    )
    private val markRegex = marks.map { (spoken, symbol) ->
        Regex("[ ,.]*\\b${spoken.replace(" ", "[ -]")}\\b[,.]?", RegexOption.IGNORE_CASE) to symbol
    }

    // Open Keyboard (test 217): made once, not on every live word update (10 times a second while talking)
    private val notLetters = Regex("[^a-z ]")
    private val spaces = Regex("\\s+")
    private val spacedNewLine = Regex(" *\n *")
    private val openBracketSpace = Regex("\\( +")
    private val tagSpace = Regex("([@#]) +")
    private val doubleSpace = Regex(" {2,}")
    private val spaceBeforeMark = Regex(" +([,.!?;:…)])")
    private val sentenceStart = Regex("([.!?…]\\s+|\n)([a-z])")

    /** Turns spoken punctuation ("comma", "new line") into symbols and fixes capitals after them. */
    fun punctuate(text: String): String {
        var t = text
        for ((regex, symbol) in markRegex) {
            t = regex.replace(t) { m ->
                val opening = symbol == "(" || (symbol == "\"" && m.value.contains("open", true))
                when {
                    opening -> " $symbol"
                    symbol.startsWith("\n") -> symbol
                    symbol == " –" || symbol == "&" || symbol == "@" || symbol == "#" -> " ${symbol.trim()} "
                    else -> symbol
                }
            }
        }
        // tidy spaces
        t = t.replace(spacedNewLine, "\n").replace(openBracketSpace, "(").replace(tagSpace, "$1")
            .replace(doubleSpace, " ").replace(spaceBeforeMark, "$1")
        // capital letter after a sentence end or a new line
        t = sentenceStart.replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t.trimStart(' ')
    }
}
