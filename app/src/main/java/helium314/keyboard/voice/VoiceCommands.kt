// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/** Gboard-style spoken punctuation and commands for voice typing. */
object VoiceCommands {
    enum class Action { DELETE_THAT, SEND, STOP, CLEAR_ALL }

    /**
     * Open Keyboard (test 220, Jarrod): every voice command has its own switch under Settings, Plugins, Voice commands,
     * so someone can keep just the ones they like. The main "Voice commands" switch turns them all off.
     */
    class Command(val id: String, val name: String, val example: String)
    val commands = listOf(
        Command("vc_punctuation", "Say punctuation", "\"comma\", \"full stop\", \"question mark\", \"new line\""),
        Command("vc_delete", "Delete that", "\"delete that\" or \"scratch that\" removes what you just said"),
        Command("vc_clear", "Clear all", "\"clear all\" empties the text box"),
        Command("vc_send", "Send", "\"send\" sends the message (Chat's own box always listens for send)"),
        Command("vc_stop", "Stop listening", "\"stop listening\" turns the mic off"),
        Command("vc_change", "Change a word", "\"change dinner to lunch\" fixes a word you said"),
        Command("vc_rewrite", "Make that…", "\"make that shorter / friendlier / more professional\" (uses Writing help)"),
        Command("vc_write", "Write a…", "\"write a birthday message for my wife\" (uses the AI writer)"),
    )

    /** Whether one voice command is on: the main switch and its own switch. */
    fun isOn(id: String) = helium314.keyboard.latin.Plugins.isOn("voice_commands") && helium314.keyboard.latin.Plugins.isOn(id)

    private fun idOf(a: Action) = when (a) {
        Action.DELETE_THAT -> "vc_delete"; Action.SEND -> "vc_send"; Action.STOP -> "vc_stop"; Action.CLEAR_ALL -> "vc_clear"
    }

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
        return actions[plain]?.takeIf { isOn(idOf(it)) }
    }

    /** Voice editing (test 219, idea 8): "change dinner to lunch", or "make that shorter" (Writing help on the text). */
    sealed class Edit {
        class Replace(val old: String, val new: String) : Edit()
        class Rewrite(val label: String) : Edit()
    }

    private val replaceCmd = Regex("(?i)^(?:change|replace)\\s+(.{1,40}?)\\s+(?:to|with)\\s+(.{1,60}?)[.!]?$")
    private val rewrites = mapOf(
        "shorter" to "Shorter", "longer" to "Longer", "friendlier" to "Friendlier", "more friendly" to "Friendlier",
        "more professional" to "Professional", "professional" to "Professional", "funnier" to "Funnier",
        "more loving" to "More loving", "more casual" to "Casual", "casual" to "Casual", "clearer" to "Clearer",
    )
    private val rewriteCmd = Regex("(?i)^make (?:that|it|this)( a bit| a little)? (.+?)[.!]?$")

    /** If the whole sentence is an editing command, returns it ("change X to Y" only when X has at most 4 words). */
    fun edit(sentence: String): Edit? {
        if (!helium314.keyboard.latin.Plugins.isOn("voice_commands")) return null
        val t = sentence.trim()
        if (isOn("vc_rewrite")) rewriteCmd.matchEntire(t)?.let { m -> rewrites[m.groupValues[2].lowercase().trim()]?.let { return Edit.Rewrite(it) } }
        if (isOn("vc_change")) replaceCmd.matchEntire(t)?.let { m ->
            val old = m.groupValues[1].trim().trim(',', '"', '“', '”'); val new = m.groupValues[2].trim().trim(',', '"', '“', '”')
            if (old.isNotEmpty() && new.isNotEmpty() && old.split(' ').size <= 4) return Edit.Replace(old, new)
        }
        return null
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
        if (isOn("vc_punctuation")) for ((regex, symbol) in markRegex) {
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
