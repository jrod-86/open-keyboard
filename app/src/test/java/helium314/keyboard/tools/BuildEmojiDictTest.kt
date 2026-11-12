package helium314.keyboard.tools

import helium314.keyboard.latin.makedict.CombinedInputOutput
import helium314.keyboard.latin.makedict.DictIO
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Tool, not a test: builds assets/dicts/emoji_en.dict from a combined text file. Runs only when asked. */
@RunWith(RobolectricTestRunner::class)
class BuildEmojiDictTest {
    @Test
    fun build() {
        val input = System.getenv("EMOJI_COMBINED") ?: return Assume.assumeTrue(false)
        val output = System.getenv("EMOJI_DICT_OUT")!!
        val dict = File(input).bufferedReader().use { CombinedInputOutput.readDictionaryCombined(it) }
        DictIO.writeBinaryDictionary(output, dict)
    }
}
