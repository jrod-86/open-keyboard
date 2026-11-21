// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;

import java.io.File;

/**
 * Test 307 (Jarrod): "add a tag to the file so that the player never recognises it as music again". A small extra tag,
 * OPEN_WAVES = "not music", that other players ignore: ID3 TXXX (MP3), an iTunes-style freeform atom (M4A) or a Vorbis
 * comment (FLAC, OGG, Opus). Nothing else in the file changes. Plain Java so it can be tested off the phone.
 */
public final class NotMusicMark {
    public static final String KEY = "OPEN_WAVES";
    public static final String VALUE = "not music";
    private static final String MP4_ID = "----:com.apple.iTunes:" + KEY;

    private NotMusicMark() {}

    private static Tag inner(Tag t) { return t instanceof FlacTag ? ((FlacTag) t).getVorbisCommentTag() : t; }

    /** True if the file carries the mark. Never throws. */
    public static boolean has(File f) {
        try {
            Tag t = AudioFileIO.read(f).getTag();
            if (t == null) return false;
            t = inner(t);
            if (t instanceof AbstractID3v2Tag) {
                for (TagField tf : ((AbstractID3v2Tag) t).getFields("TXXX")) {
                    if (tf instanceof AbstractID3v2Frame && ((AbstractID3v2Frame) tf).getBody() instanceof FrameBodyTXXX
                        && KEY.equalsIgnoreCase(((FrameBodyTXXX) ((AbstractID3v2Frame) tf).getBody()).getDescription())) return true;
                }
                return false;
            }
            if (t instanceof Mp4Tag) return !((Mp4Tag) t).getFirst(MP4_ID).isEmpty();
            if (t instanceof VorbisCommentTag) return !((VorbisCommentTag) t).getFirst(KEY).isEmpty();
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Adds (true) or removes (false) the mark. Throws when the file type can't hold it or can't be written. */
    public static void set(File f, boolean on) throws Exception {
        AudioFile af = AudioFileIO.read(f);
        Tag outer = af.getTagOrCreateAndSetDefault();
        Tag t = inner(outer);
        if (t instanceof AbstractID3v2Tag) {
            AbstractID3v2Tag id3 = (AbstractID3v2Tag) t;
            java.util.List<TagField> keep = new java.util.ArrayList<>();
            boolean had = false;
            for (TagField tf : id3.getFields("TXXX")) {
                boolean ours = tf instanceof AbstractID3v2Frame && ((AbstractID3v2Frame) tf).getBody() instanceof FrameBodyTXXX
                    && KEY.equalsIgnoreCase(((FrameBodyTXXX) ((AbstractID3v2Frame) tf).getBody()).getDescription());
                if (ours) had = true; else keep.add(tf);
            }
            if (had == on) return;
            // put back the other TXXX frames exactly as they were, plus ours when marking
            id3.removeFrame("TXXX");
            for (TagField tf : keep) id3.addField(tf);
            if (on) {
                AbstractID3v2Frame fr = id3.createFrame("TXXX");
                FrameBodyTXXX body = (FrameBodyTXXX) fr.getBody();
                body.setDescription(KEY);
                body.setText(VALUE);
                id3.addField(fr);
            }
        } else if (t instanceof Mp4Tag) {
            Mp4Tag mp4 = (Mp4Tag) t;
            boolean had = !mp4.getFirst(MP4_ID).isEmpty();
            if (had == on) return;
            if (on) mp4.addField(new Mp4TagReverseDnsField(MP4_ID, "com.apple.iTunes", KEY, VALUE));
            else mp4.deleteField(MP4_ID);
        } else if (t instanceof VorbisCommentTag) {
            VorbisCommentTag v = (VorbisCommentTag) t;
            boolean had = !v.getFirst(KEY).isEmpty();
            if (had == on) return;
            if (on) v.setField(v.createField(KEY, VALUE)); else v.deleteField(KEY);
        } else {
            throw new UnsupportedOperationException("this kind of file can't hold the mark");
        }
        af.commit();
    }
}
