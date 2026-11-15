// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.tts.Pronounce
import org.junit.Test
import java.io.File

class PronounceTest {
    private val lexFile = File(System.getProperty("kokoro.lexicon") ?: "/home/user/dl/hf/lexicon-us-en.txt")
    private val known: Set<String> = if (lexFile.isFile) lexFile.readLines().map { it.substringBefore(' ') }.toSet()
        else setOf("hi", "and", "jar", "rod", "sam", "sung", "board", "the", "of", "it", "costs")

    @Test fun samples() {
        for (t in listOf(
            "Hi Jarrod, it's the 15th of November and it costs \$4.50.",
            "Meet at 3:30pm on 15/11/2026, 25% off, call 0412 345 678.",
            "Samsung S24 has 1,234,567 pixels and 3.14 is pi. Jarrod's TV.",
            "I was born in 1986 and it's 2026 now. 101 dogs, 1000 cats, texted, running, tried, keyboards, Jarrod.s phone, \$1 coin, \$2.05.",
        )) println(t + "\n  -> " + Pronounce.prepare(t, known))
        System.getenv("KOKORO_OUT")?.let { out ->
            Pronounce.writeExtraLexicon(File(out, "extra.txt"))
            File(out, "lines.txt").writeText(listOf(
                "Hi Jarrod, it's the 15th of November and it costs \$4.50.",
                "Call 0412 345 678 at 3:30pm. Samsung's keyboards texted 101 dogs.",
                "Zoltrix and Nguyen from Telstra tried running to Woolworths.",
            ).joinToString("\n") { Pronounce.prepare(it, known) })
        }
        kotlin.test.assertEquals("one hundred and one", Pronounce.say(101))
        kotlin.test.assertEquals("twenty-first", Pronounce.ordinal(21))
        kotlin.test.assertEquals("one thousand two hundred and thirty-four", Pronounce.say(1234))
    }
}
