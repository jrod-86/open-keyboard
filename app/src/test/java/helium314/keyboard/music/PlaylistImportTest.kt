// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistImportTest {
    private fun song(id: Long, title: String, artist: String, path: String, dur: Long = 200_000) =
        Song(id, title, artist, "Album", 1, "", dur, 1, 0, 0, 0, path)

    private val lib = Library(listOf(
        song(1, "Bohemian Rhapsody", "Queen", "/storage/emulated/0/Music/Queen/01 Bohemian Rhapsody.mp3"),
        song(2, "Hello", "Adele", "/storage/emulated/0/Music/Adele/Hello.flac"),
        song(3, "Hello", "Lionel Richie", "/storage/emulated/0/Music/Lionel/Hello.mp3"),
        song(4, "Don't Stop Me Now", "Queen", "/storage/emulated/0/Music/Queen/dont_stop.mp3"),
    ))

    private fun run(name: String, text: String) = PlaylistImport.resolve(lib, PlaylistImport.parse(name, text))

    @Test fun m3uSamsung() {
        val r = run("Road trip.m3u", "#EXTM3U\n#EXTINF:354,Queen - Bohemian Rhapsody\n/storage/emulated/0/Music/Queen/01 Bohemian Rhapsody.mp3\n../Music/Adele/Hello.flac\n/sdcard/x/missing.mp3\n")
        assertEquals("Road trip", r[0].list.name)
        assertEquals(listOf(1L, 2L), r[0].found)
        assertEquals(1, r[0].missing.size)
    }

    @Test fun itunesText() {
        val t = "Name\tArtist\tComposer\tAlbum\tTime\tLocation\nHello\tAdele\t\t25\t295\tC:\\Users\\me\\Music\\Adele\\25\\Hello.m4a\nDon't Stop Me Now (Remastered 2011)\tQueen\t\tJazz\t209\t\n"
        val r = run("Favs.txt", t)
        assertEquals(listOf(2L, 4L), r[0].found)
    }

    @Test fun csvSpotify() {
        val t = "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n\"spotify:1\",\"Hello\",\"Lionel Richie\",\"Can't Slow Down\",\"250000\"\n\"spotify:2\",\"Bohemian Rhapsody - Remastered 2011\",\"Queen\",\"A Night\",\"354000\"\n"
        val r = run("liked.csv", t)
        assertEquals(listOf(3L, 1L), r[0].found)
    }

    @Test fun itunesXml() {
        val t = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Major Version</key><integer>1</integer>
<key>Tracks</key><dict>
<key>100</key><dict><key>Track ID</key><integer>100</integer><key>Name</key><string>Hello</string><key>Artist</key><string>Adele</string><key>Total Time</key><integer>295000</integer><key>Location</key><string>file:///C:/Music/Adele/Hello.m4a</string></dict>
<key>101</key><dict><key>Track ID</key><integer>101</integer><key>Name</key><string>Bohemian Rhapsody</string><key>Artist</key><string>Queen</string><key>Explicit</key><false/></dict>
</dict>
<key>Playlists</key><array>
<dict><key>Name</key><string>Library</string><key>Master</key><true/><key>Playlist Items</key><array><dict><key>Track ID</key><integer>100</integer></dict></array></dict>
<dict><key>Name</key><string>Gym &amp; Run</string><key>Playlist Items</key><array><dict><key>Track ID</key><integer>101</integer></dict><dict><key>Track ID</key><integer>100</integer></dict></array></dict>
</array></dict></plist>"""
        val r = run("Library.xml", t)
        assertEquals(1, r.size)
        assertEquals("Gym & Run", r[0].list.name)
        assertEquals(listOf(1L, 2L), r[0].found)
    }

    @Test fun xspfAndPls() {
        val x = run("v.xspf", """<?xml version="1.0"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><title>VLC list</title><trackList><track><location>file:///storage/emulated/0/Music/Queen/dont_stop.mp3</location></track></trackList></playlist>""")
        assertEquals("VLC list", x[0].list.name); assertEquals(listOf(4L), x[0].found)
        val p = run("a.pls", "[playlist]\nFile1=/sdcard/Music/Adele/Hello.flac\nTitle1=Adele - Hello\nNumberOfEntries=1\n")
        assertEquals(listOf(2L), p[0].found)
    }

    @Test fun lrc() {
        val l = Lyrics.parse("[ar:x]\n[00:01.50]First line\n[00:03.20]<00:03.20>Second <00:03.80>line\n", "t")!!
        assertEquals(true, l.synced); assertEquals(1500L, l.lines[0].timeMs); assertEquals("Second line", l.lines[1].text)
        assertEquals(1, l.lineAt(3000))
    }
}
