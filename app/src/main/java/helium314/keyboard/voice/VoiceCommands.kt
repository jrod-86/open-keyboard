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
        val plain = sentence.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        return actions[plain]
    }

    // longest phrases first so "exclamation mark" wins over a shorter match
    private val marks = listOf(
        "new paragraph" to "\n\n", "next paragraph" to "\n\n", "new line" to "\n", "next line" to "\n",
        "exclamation mark" to "!", "exclamation point" to "!", "question mark" to "?",
        "full stop" to ".", "comma" to ",", "semicolon" to ";", "semi colon" to ";",
        "colon" to ":", "ellipsis" to "…", "dot dot dot" to "…",
        "open bracket" to "(", "close bracket" to ")", "open quote" to "\"", "close quote" to "\"",
        "hyphen" to "-", "dash" to " –", "ampersand" to "&", "at sign" to "@", "hashtag" to "#",
    )
    private val markRegex = marks.map { (spoken, symbol) ->
        Regex("[ ,.]*\\b${spoken.replace(" ", "[ -]")}\\b[,.]?", RegexOption.IGNORE_CASE) to symbol
    }

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
        t = t.replace(Regex(" *\n *"), "\n").replace(Regex("\\( +"), "(").replace(Regex("([@#]) +"), "$1")
            .replace(Regex(" {2,}"), " ").replace(Regex(" +([,.!?;:…)])"), "$1")
        // capital letter after a sentence end or a new line
        t = Regex("([.!?…]\\s+|\n)([a-z])").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t.trimStart(' ')
    }
}
