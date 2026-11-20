// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Test 256 (Jarrod): writes fixed song info and the cover into the music file itself (MP3, M4A, FLAC, OGG...),
 * so every app sees the fix. Uses jaudiotagger (LGPL-2.1). Works on a plain File; Android access is handled by
 * [FileFixer], which copies the song out, edits it here and copies it back.
 */
object TagWriter {
    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** Throws if the file can't be read or written, so the caller can say what went wrong. */
    fun write(file: File, title: String, artist: String, album: String, year: Int, cover: File?) {
        val af = AudioFileIO.read(file)
        val tag = af.tagOrCreateAndSetDefault
        if (title.isNotBlank()) tag.setField(FieldKey.TITLE, title)
        if (artist.isNotBlank()) tag.setField(FieldKey.ARTIST, artist)
        if (album.isNotBlank()) tag.setField(FieldKey.ALBUM, album)
        if (year > 0) tag.setField(FieldKey.YEAR, year.toString())
        if (cover != null && cover.isFile) {
            val bytes = cover.readBytes()
            val (w, h) = imageSize(bytes)
            val mime = if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
            tag.deleteArtworkField()
            if (tag is org.jaudiotagger.tag.flac.FlacTag) {
                // FLAC needs the picture's size, which jaudiotagger can't measure on Android
                tag.setField(tag.createArtworkField(bytes, 3, mime, "", w, h, 24, 0))
            } else if (tag is org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag) {
                // Ogg: a FLAC picture block, base64'd into METADATA_BLOCK_PICTURE
                val block = org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture(bytes, 3, mime, "", w, h, 24, 0)
                tag.setField(tag.createField(org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey.METADATA_BLOCK_PICTURE,
                    java.util.Base64.getEncoder().encodeToString(block.rawContent)))
            } else {
                val art = ArtworkFactory.getNew()
                art.binaryData = bytes; art.mimeType = mime; art.pictureType = 3; art.width = w; art.height = h
                tag.setField(art)
            }
        }
        af.commit()
    }

    /** Width and height of a JPEG or PNG without decoding it (pure Kotlin, works on Android and in tests). */
    internal fun imageSize(b: ByteArray): Pair<Int, Int> {
        fun u16(i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
        if (b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte())
            return (u16(16) shl 16 or u16(18)) to (u16(20) shl 16 or u16(22))
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val m = b[i + 1].toInt() and 255
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return u16(i + 7) to u16(i + 5)
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            i += 2 + u16(i + 2)
        }
        return 500 to 500
    }
}
