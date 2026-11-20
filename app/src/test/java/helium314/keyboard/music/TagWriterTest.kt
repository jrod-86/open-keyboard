// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Test 256: writing name, artist, album, year and cover into each music format. Needs sample files in TAG_SAMPLES. */
class TagWriterTest {
    @Test fun writesAllFormats() {
        val dir = System.getenv("TAG_SAMPLES")?.let(::File) ?: return
        val cover = File(dir, "cover.jpg")
        for (ext in listOf("mp3", "m4a", "flac", "ogg")) {
            val f = File(dir, "out.$ext").also { File(dir, "t.$ext").copyTo(it, overwrite = true) }
            TagWriter.write(f, "Symphony 2808", "Nero", "Welcome Reality", 2011, cover)
            val tag = AudioFileIO.read(f).tag
            assertEquals(ext, "Symphony 2808", tag.getFirst(FieldKey.TITLE))
            assertEquals(ext, "Nero", tag.getFirst(FieldKey.ARTIST))
            assertEquals(ext, "Welcome Reality", tag.getFirst(FieldKey.ALBUM))
            assertTrue(ext, tag.getFirst(FieldKey.YEAR).startsWith("2011"))
            assertTrue(ext, (tag.firstArtwork?.binaryData?.size ?: 0) > 100)
            println("$ext ok")
        }
    }
}
