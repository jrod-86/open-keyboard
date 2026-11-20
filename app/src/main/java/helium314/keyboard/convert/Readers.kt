// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.convert

import android.annotation.SuppressLint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser as MdParser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.DateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** Thrown with a message that is shown to the person as is. */
class ConvertProblem(msg: String) : Exception(msg)

// ---------------------------------------------------------------- small helpers

/** All files of a zip (docx, xlsx, odt, epub… are zips), name -> bytes. Limited so a "zip bomb" can't fill the phone. */
internal fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = HashMap<String, ByteArray>()
    var total = 0L
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val data = z.readBytes()
                total += data.size
                if (total > 600L * 1024 * 1024) throw ConvertProblem("This file is too big to open on the phone.")
                out[e.name.trimStart('/')] = data
            }
        }
    } catch (e: ConvertProblem) { throw e } catch (e: Exception) {
        if (out.isEmpty()) throw ConvertProblem("This file looks damaged (it couldn't be unpacked).")
    }
    return out
}

internal fun xml(bytes: ByteArray?): Element? = bytes?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) }

/** Tag name without its "w:" style prefix. */
internal val Element.ln get() = tagName().substringAfter(':')

/** Attribute by its name without prefix ("val" finds "w:val"). */
internal fun Element.a(name: String): String? {
    if (hasAttr(name)) return attr(name)
    for (at in attributes()) if (at.key.substringAfter(':') == name) return at.value
    return null
}

internal fun Element.kids(name: String) = children().filter { it.ln == name }
internal fun Element.kid(name: String) = children().firstOrNull { it.ln == name }
internal fun Element.all(name: String): List<Element> = getAllElements().filter { it.ln == name }

/** Resolve "../media/a.png" against the folder of "word/document.xml". */
internal fun resolvePath(base: String, target: String): String {
    if (target.startsWith("/")) return target.trimStart('/')
    val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    for (p in target.split('/')) when (p) {
        "", "." -> {}
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        else -> parts.add(java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8"))
    }
    return parts.joinToString("/")
}

/** Office relationship file: id -> target path (already resolved). */
internal fun rels(files: Map<String, ByteArray>, partPath: String): Map<String, String> {
    val relPath = partPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } +
        "_rels/" + partPath.substringAfterLast('/') + ".rels"
    val doc = xml(files[relPath]) ?: return emptyMap()
    return doc.all("Relationship").associate {
        val t = it.a("Target").orEmpty()
        it.a("Id").orEmpty() to (if (it.a("TargetMode") == "External") t else resolvePath(partPath, t))
    }
}

internal fun mimeOfImage(path: String, bytes: ByteArray): String {
    if (bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) return "image/png"
    if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
    return when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"; "emf", "wmf" -> "image/x-emf"
        else -> "image/jpeg"
    }
}

/** Only pictures Android can draw are kept (old Windows EMF/WMF and SVG drawings are skipped). */
internal fun imageBlock(path: String, bytes: ByteArray?): Block.Image? {
    if (bytes == null || bytes.isEmpty()) return null
    val m = mimeOfImage(path, bytes)
    if (m == "image/x-emf" || m == "image/svg+xml") return null
    return Block.Image(bytes, m)
}

/** Text in UTF-8, or the old Windows encoding if it isn't valid UTF-8. */
internal fun decodeText(bytes: ByteArray): String {
    var b = bytes
    if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
    if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
    if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        String(b, Charset.forName("windows-1252"))
    }
}

/** Merges neighbouring runs that look the same, and drops empty ones. */
internal fun tidy(runs: List<Run>): List<Run> {
    val out = ArrayList<Run>()
    for (r in runs) {
        if (r.text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == r.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + r.text)
        else out.add(r)
    }
    return out
}

// ---------------------------------------------------------------- Word (.docx)

object DocxReader {
    fun read(bytes: ByteArray, title: String): Doc {
        val files = unzip(bytes)
        val main = xml(files["[Content_Types].xml"])?.all("Override")
            ?.firstOrNull { it.a("ContentType").orEmpty().contains("document.main+xml") }?.a("PartName")?.trimStart('/')
            ?: "word/document.xml"
        val doc = xml(files[main]) ?: throw ConvertProblem("This doesn't look like a Word file.")
        val rel = rels(files, main)
        val styles = xml(files["word/styles.xml"])
        val headingOf = HashMap<String, Int>()
        val styleNum = HashMap<String, Pair<String, Int>>()
        styles?.all("style")?.forEach { st ->
            val id = st.a("styleId") ?: return@forEach
            val name = st.kid("name")?.a("val")?.lowercase().orEmpty()
            val lvl = Regex("^heading (\\d)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: st.kid("pPr")?.kid("outlineLvl")?.a("val")?.toIntOrNull()?.plus(1)?.takeIf { it in 1..6 }
                ?: if (name == "title") 1 else if (name == "subtitle") 2 else null
            if (lvl != null) headingOf[id] = lvl.coerceIn(1, 6)
            st.kid("pPr")?.kid("numPr")?.let { np ->
                val num = np.kid("numId")?.a("val"); if (num != null) styleNum[id] = num to (np.kid("ilvl")?.a("val")?.toIntOrNull() ?: 0)
            }
        }
        val numbering = Numbering(xml(files["word/numbering.xml"]))
        val blocks = ArrayList<Block>()
        val body = doc.all("body").firstOrNull() ?: doc

        fun toggle(e: Element?) = e != null && e.a("val").let { it == null || it !in setOf("0", "false", "off", "none") }

        fun paragraph(p: Element, into: MutableList<Block>) {
            val pPr = p.kid("pPr")
            val styleId = pPr?.kid("pStyle")?.a("val")
            val runs = ArrayList<Run>()
            val images = ArrayList<Block>()
            var pageBreakAfter = false

            fun run(r: Element, link: String?) {
                val rPr = r.kid("rPr")
                val base = Run("", b = toggle(rPr?.kid("b")), i = toggle(rPr?.kid("i")),
                    u = rPr?.kid("u")?.a("val").let { rPr?.kid("u") != null && it != "none" },
                    s = toggle(rPr?.kid("strike")) || toggle(rPr?.kid("dstrike")), link = link,
                    mono = rPr?.kid("rFonts")?.a("ascii")?.lowercase()?.let { "courier" in it || "consol" in it || "mono" in it } == true)
                for (c in r.children()) when (c.ln) {
                    "t", "delText" -> if (c.ln == "t") runs.add(base.copy(text = c.wholeText()))
                    "tab", "ptab" -> runs.add(base.copy(text = "\t"))
                    "br", "cr" -> if (c.a("type") == "page") pageBreakAfter = true else runs.add(base.copy(text = "\n"))
                    "noBreakHyphen" -> runs.add(base.copy(text = "-"))
                    "sym" -> c.a("char")?.toIntOrNull(16)?.let { if (it in 0x20..0xEFFF && it !in 0xE000..0xEFFF) runs.add(base.copy(text = String(Character.toChars(it)))) }
                    "drawing", "pict", "object" -> {
                        for (blip in c.all("blip")) {
                            val id = blip.a("embed") ?: blip.a("link") ?: continue
                            val path = rel[id] ?: continue
                            imageBlock(path, files[path])?.let { images.add(it) }
                        }
                        for (im in c.all("imagedata")) {
                            val path = rel[im.a("id") ?: continue] ?: continue
                            imageBlock(path, files[path])?.let { images.add(it) }
                        }
                        // text boxes: their paragraphs come after this one
                        c.all("txbxContent").forEach { tb -> tb.children().forEach { if (it.ln == "p") paragraph(it, images) } }
                    }
                }
            }

            fun walk(e: Element, link: String?) {
                for (c in e.children()) when (c.ln) {
                    "r" -> run(c, link)
                    "hyperlink" -> walk(c, c.a("id")?.let { rel[it] } ?: c.a("anchor")?.let { "#$it" } ?: link)
                    "del", "moveFrom", "pPr", "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> {}
                    "sdt" -> c.kid("sdtContent")?.let { walk(it, link) }
                    else -> walk(c, link) // ins, smartTag, fldSimple, customXml…
                }
            }
            walk(p, null)
            if (pPr?.kid("pageBreakBefore") != null && toggle(pPr.kid("pageBreakBefore"))) into.add(Block.PageBreak)
            val trimmed = tidy(runs)
            val level = (headingOf[styleId ?: ""] ?: pPr?.kid("outlineLvl")?.a("val")?.toIntOrNull()?.let { if (it < 6) it + 1 else null })
            val align = when (pPr?.kid("jc")?.a("val")) { "center" -> 1; "right", "end" -> 2; else -> 0 }
            val numPr = pPr?.kid("numPr")
            val num = numPr?.kid("numId")?.a("val")?.let { it to (numPr.kid("ilvl")?.a("val")?.toIntOrNull() ?: 0) }
                ?: styleId?.let { styleNum[it] }
            when {
                trimmed.isEmpty() && images.isEmpty() -> into.add(Block.Para(emptyList()))
                trimmed.isEmpty() -> {}
                level != null -> into.add(Block.Heading(level, trimmed))
                num != null && num.first != "0" -> {
                    val bullet = numbering.next(num.first, num.second)
                    into.add(Block.Para(trimmed, list = num.second + 1, bullet = bullet, align = align))
                }
                else -> into.add(Block.Para(trimmed, align = align, code = trimmed.all { it.mono }))
            }
            into.addAll(images)
            if (pageBreakAfter) into.add(Block.PageBreak)
        }

        fun table(t: Element, into: MutableList<Block>) {
            val rows = ArrayList<List<List<Run>>>()
            for (tr in t.children().filter { it.ln == "tr" }) {
                val cells = ArrayList<List<Run>>()
                for (tc in tr.children().filter { it.ln == "tc" || it.ln == "sdt" }.flatMap { if (it.ln == "sdt") it.all("tc") else listOf(it) }) {
                    val inner = ArrayList<Block>()
                    for (c in tc.children()) when (c.ln) { "p" -> paragraph(c, inner); "tbl" -> table(c, inner) }
                    val merged = tc.kid("tcPr")?.kid("vMerge")?.let { it.a("val") != "restart" } == true
                    val text = ArrayList<Run>()
                    if (!merged) for (b in inner) {
                        val r = when (b) { is Block.Para -> b.runs; is Block.Heading -> b.runs.map { it.copy(b = true) }; is Block.Table -> runsOf(b.rows.joinToString("\n") { row -> row.joinToString("  ") { it.plain() } }); else -> emptyList() }
                        if (r.isEmpty()) continue
                        if (text.isNotEmpty()) text.add(Run("\n"))
                        text.addAll(r)
                    }
                    cells.add(tidy(text))
                    repeat((tc.kid("tcPr")?.kid("gridSpan")?.a("val")?.toIntOrNull() ?: 1) - 1) { cells.add(emptyList()) }
                }
                rows.add(cells)
            }
            if (rows.isNotEmpty()) into.add(Block.Table(rows, header = t.all("tblHeader").isNotEmpty()))
        }

        fun container(e: Element) {
            for (c in e.children()) when (c.ln) {
                "p" -> paragraph(c, blocks)
                "tbl" -> table(c, blocks)
                "sdt" -> c.kid("sdtContent")?.let { container(it) }
                "sectPr" -> {}
                else -> container(c)
            }
        }
        container(body)
        // drop empty paragraphs at the end and runs of more than one empty paragraph
        val cleaned = ArrayList<Block>()
        for (b in blocks) {
            if (b is Block.Para && b.runs.isEmpty() && (cleaned.lastOrNull() as? Block.Para)?.runs?.isEmpty() != false) continue
            cleaned.add(b)
        }
        while ((cleaned.lastOrNull() as? Block.Para)?.runs?.isEmpty() == true) cleaned.removeAt(cleaned.size - 1)
        val docTitle = xml(files["docProps/core.xml"])?.all("title")?.firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        return Doc(docTitle ?: title, cleaned)
    }

    /** Word list numbering: works out "•", "1.", "a)" for each list paragraph. */
    private class Numbering(x: Element?) {
        private val abstractOf = HashMap<String, String>()
        private val formats = HashMap<String, MutableMap<Int, Pair<String, String>>>() // abstractId -> level -> (numFmt, lvlText)
        private val counters = HashMap<String, IntArray>()

        init {
            x?.all("abstractNum")?.forEach { an ->
                val id = an.a("abstractNumId") ?: return@forEach
                val m = HashMap<Int, Pair<String, String>>()
                an.kids("lvl").forEach { l -> m[l.a("ilvl")?.toIntOrNull() ?: 0] = (l.kid("numFmt")?.a("val") ?: "bullet") to (l.kid("lvlText")?.a("val") ?: "•") }
                formats[id] = m
            }
            x?.kids("num")?.ifEmpty { null }
            x?.all("num")?.forEach { n -> val id = n.a("numId") ?: return@forEach; n.kid("abstractNumId")?.a("val")?.let { abstractOf[id] = it } }
        }

        fun next(numId: String, level: Int): String {
            val abs = abstractOf[numId]
            val (fmt, text) = formats[abs]?.get(level) ?: ("bullet" to "•")
            if (fmt == "bullet" || fmt == "none") return "•"
            val c = counters.getOrPut(abs ?: numId) { IntArray(9) }
            val l = level.coerceIn(0, 8)
            c[l]++
            for (k in l + 1 until 9) c[k] = 0
            var out = text
            for (k in 0..l) out = out.replace("%${k + 1}", format(formats[abs]?.get(k)?.first ?: "decimal", c[k].coerceAtLeast(1)))
            return out.ifBlank { "${c[l]}." }
        }

        private fun format(fmt: String, n: Int) = when (fmt) {
            "lowerLetter" -> letters(n).lowercase(); "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase(); "upperRoman" -> roman(n)
            else -> n.toString()
        }
        private fun letters(n: Int): String { var x = n; val sb = StringBuilder(); while (x > 0) { x--; sb.insert(0, 'A' + x % 26); x /= 26 }; return sb.toString() }
        private fun roman(n: Int): String {
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1); val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n; val sb = StringBuilder(); for (k in v.indices) while (x >= v[k]) { sb.append(s[k]); x -= v[k] }; return sb.toString()
        }
    }
}

// ---------------------------------------------------------------- OpenDocument (.odt / .odp)

object OdfReader {
    private data class Look(val b: Boolean = false, val i: Boolean = false, val u: Boolean = false, val s: Boolean = false, val mono: Boolean = false, val heading: Int? = null, val align: Int = 0)

    fun read(bytes: ByteArray, title: String, slides: Boolean): Doc {
        val files = unzip(bytes)
        val content = xml(files["content.xml"]) ?: throw ConvertProblem("This doesn't look like an OpenDocument file.")
        val looks = HashMap<String, Look>()
        val parents = HashMap<String, String>()
        for (src in listOf(xml(files["styles.xml"]), content)) src?.all("style")?.forEach { st ->
            val name = st.a("name") ?: return@forEach
            val tp = st.kid("text-properties"); val pp = st.kid("paragraph-properties")
            st.a("parent-style-name")?.let { parents[name] = it }
            looks[name] = Look(
                b = tp?.a("font-weight") == "bold" || (tp?.a("font-weight")?.toIntOrNull() ?: 400) >= 600,
                i = tp?.a("font-style") == "italic",
                u = tp?.a("text-underline-style")?.let { it != "none" } == true,
                s = tp?.a("text-line-through-style")?.let { it != "none" } == true,
                mono = tp?.a("font-name")?.lowercase()?.let { "mono" in it || "courier" in it } == true,
                heading = st.a("default-outline-level")?.toIntOrNull() ?: when (name) { "Title" -> 1; "Subtitle" -> 2; else -> null },
                align = when (pp?.a("text-align")) { "center" -> 1; "end", "right" -> 2; else -> 0 },
            )
        }
        fun look(name: String?): Look {
            var n = name; var depth = 0
            var acc = Look()
            while (n != null && depth < 6) {
                val l = looks[n] ?: break
                acc = Look(acc.b || l.b, acc.i || l.i, acc.u || l.u, acc.s || l.s, acc.mono || l.mono, acc.heading ?: l.heading, if (acc.align != 0) acc.align else l.align)
                n = parents[n]; depth++
            }
            return acc
        }

        val numberedLists = HashSet<String>()
        for (src in listOf(xml(files["styles.xml"]), content)) src?.all("list-style")?.forEach { ls ->
            val first = ls.children().firstOrNull { it.a("level") == "1" } ?: ls.children().firstOrNull()
            if (first?.ln == "list-level-style-number" && first.a("num-format").orEmpty().isNotEmpty()) ls.a("name")?.let { numberedLists.add(it) }
        }
        val paraList = HashMap<String, String>()
        for (src in listOf(xml(files["styles.xml"]), content)) src?.all("style")?.forEach { st -> st.a("list-style-name")?.let { l -> st.a("name")?.let { paraList[it] = l } } }
        val blocks = ArrayList<Block>()
        var frame: (Element, MutableList<Block>) -> Unit = { _, _ -> }

        fun inline(e: Node, base: Run, out: MutableList<Run>, images: MutableList<Block>) {
            for (c in e.childNodes()) {
                if (c is TextNode) { out.add(base.copy(text = c.wholeText.replace(Regex("[\\r\\n\\t]+"), " "))); continue }
                if (c !is Element) continue
                when (c.ln) {
                    "span" -> { val l = look(c.a("style-name")); inline(c, base.copy(b = base.b || l.b, i = base.i || l.i, u = base.u || l.u, s = base.s || l.s, mono = base.mono || l.mono), out, images) }
                    "a" -> inline(c, base.copy(link = c.a("href")), out, images)
                    "s" -> out.add(base.copy(text = " ".repeat(c.a("c")?.toIntOrNull()?.coerceIn(1, 100) ?: 1)))
                    "tab" -> out.add(base.copy(text = "\t"))
                    "line-break" -> out.add(base.copy(text = "\n"))
                    "frame" -> frame(c, images)
                    "note", "annotation", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break" -> {}
                    else -> inline(c, base, out, images)
                }
            }
        }

        fun para(e: Element, into: MutableList<Block>, list: Int = 0, bullet: String? = null) {
            val l = look(e.a("style-name"))
            val runs = ArrayList<Run>(); val imgs = ArrayList<Block>()
            inline(e, Run("", b = l.b, i = l.i, u = l.u, s = l.s, mono = l.mono), runs, imgs)
            val r = tidy(runs).let { rs -> if (rs.isNotEmpty() && rs.first().text.isBlank() && rs.size > 1) rs.drop(1) else rs }
            val heading = if (e.ln == "h") (e.a("outline-level")?.toIntOrNull() ?: l.heading ?: 1) else l.heading
            when {
                r.isEmpty() -> if (imgs.isEmpty() && list == 0) into.add(Block.Para(emptyList()))
                heading != null && list == 0 -> into.add(Block.Heading(heading.coerceIn(1, 6), r))
                else -> into.add(Block.Para(r, list = list, bullet = bullet, align = l.align, code = l.mono))
            }
            into.addAll(imgs)
        }

        fun table(t: Element, into: MutableList<Block>) {
            val rows = ArrayList<List<List<Run>>>()
            for (tr in t.all("table-row")) {
                if (tr.parents().firstOrNull { it.ln == "table" } != t) continue
                val cells = ArrayList<List<Run>>()
                for (tc in tr.children().filter { it.ln == "table-cell" || it.ln == "covered-table-cell" }) {
                    val inner = ArrayList<Block>()
                    tc.children().forEach { if (it.ln == "p" || it.ln == "h") para(it, inner) }
                    val text = ArrayList<Run>()
                    inner.filterIsInstance<Block.Para>().filter { it.runs.isNotEmpty() }.forEach { if (text.isNotEmpty()) text.add(Run("\n")); text.addAll(it.runs) }
                    inner.filterIsInstance<Block.Heading>().forEach { if (text.isNotEmpty()) text.add(Run("\n")); text.addAll(it.runs.map { r -> r.copy(b = true) }) }
                    val rep = (tc.a("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 50)
                    repeat(rep) { cells.add(tidy(text)) }
                }
                while (cells.size > 1 && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
                rows.add(cells)
            }
            if (rows.isNotEmpty()) into.add(Block.Table(rows, name = t.a("name"), header = t.all("table-header-rows").isNotEmpty()))
        }

        fun list(e: Element, depth: Int, into: MutableList<Block>) {
            var n = 0
            val firstPara = e.all("p").firstOrNull()?.a("style-name")
            val numbered = (e.a("style-name") ?: firstPara?.let { paraList[it] ?: parents[it]?.let { p -> paraList[p] } })?.let { it in numberedLists } == true
            for (item in e.children().filter { it.ln == "list-item" || it.ln == "list-header" }) {
                n++
                var first = true
                for (c in item.children()) when (c.ln) {
                    "p", "h" -> { para(c, into, depth, if (first) (if (numbered) "$n." else "•") else " "); first = false }
                    "list" -> list(c, depth + 1, into)
                }
            }
        }

        fun container(e: Element, into: MutableList<Block>) {
            for (c in e.children()) when (c.ln) {
                "p", "h" -> para(c, into)
                "list" -> list(c, 1, into)
                "table" -> table(c, into)
                "frame" -> frame(c, into)
                "sequence-decls", "variable-decls", "user-field-decls", "forms", "tracked-changes", "notes", "office-annotation" -> {}
                else -> container(c, into)
            }
        }

        // frames can hold a picture or a text box
        fun frameImpl(f: Element, into: MutableList<Block>) {
            f.kid("image")?.a("href")?.let { href -> imageBlock(href, files[href.trimStart('.', '/')])?.let { into.add(it); return } }
            f.kid("text-box")?.let { container(it, into) }
        }
        frame = ::frameImpl

        val body = content.all("body").firstOrNull() ?: content
        if (slides) {
            body.all("page").forEachIndexed { idx, page ->
                if (idx > 0) blocks.add(Block.PageBreak)
                val inner = ArrayList<Block>()
                for (f in page.children().filter { it.ln == "frame" || it.ln == "custom-shape" || it.ln == "g" || it.ln == "rect" }) {
                    val cls = f.a("class")
                    if (cls == "title") f.all("p").forEach { p -> para(p, inner); (inner.lastOrNull() as? Block.Para)?.let { inner[inner.size - 1] = Block.Heading(2, it.runs) } }
                    else if (f.ln == "frame") frameImpl(f, inner) else container(f, inner)
                }
                if (inner.none { it is Block.Heading }) blocks.add(Block.Heading(2, runsOf(page.a("name")?.takeIf { !it.startsWith("page") } ?: "Slide ${idx + 1}")))
                blocks.addAll(inner.filter { !(it is Block.Para && it.runs.isEmpty()) })
            }
        } else container(body, blocks)
        val docTitle = xml(files["meta.xml"])?.all("title")?.firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        return Doc(docTitle ?: title, blocks)
    }
}

// ---------------------------------------------------------------- PowerPoint (.pptx)

object PptxReader {
    fun read(bytes: ByteArray, title: String): Doc {
        val files = unzip(bytes)
        val pres = xml(files["ppt/presentation.xml"]) ?: throw ConvertProblem("This doesn't look like a PowerPoint file.")
        val presRels = rels(files, "ppt/presentation.xml")
        val slides = pres.all("sldId").mapNotNull { presRels[it.attr("r:id").ifEmpty { it.attributes().firstOrNull { a -> a.key.endsWith(":id") }?.value.orEmpty() }] }
        val blocks = ArrayList<Block>()
        slides.forEachIndexed { idx, path ->
            val slide = xml(files[path]) ?: return@forEachIndexed
            val rel = rels(files, path)
            if (idx > 0) blocks.add(Block.PageBreak)
            val inner = ArrayList<Block>()
            var hasTitle = false

            fun paras(txBody: Element, kind: String?) {
                for (p in txBody.kids("p")) {
                    val runs = ArrayList<Run>()
                    for (c in p.children()) when (c.ln) {
                        "r", "fld" -> {
                            val pr = c.kid("rPr")
                            runs.add(Run(c.kid("t")?.wholeText().orEmpty(), b = pr?.a("b") == "1" || pr?.a("b") == "true", i = pr?.a("i") == "1" || pr?.a("i") == "true",
                                u = pr?.a("u")?.let { it != "none" } == true, s = pr?.a("strike")?.let { it != "noStrike" } == true,
                                link = pr?.kid("hlinkClick")?.a("id")?.let { rel[it] }))
                        }
                        "br" -> runs.add(Run("\n"))
                    }
                    val t = tidy(runs)
                    if (t.isEmpty() || t.plain().isBlank()) continue
                    val pPr = p.kid("pPr")
                    val lvl = pPr?.a("lvl")?.toIntOrNull() ?: 0
                    when {
                        kind == "title" || kind == "ctrTitle" -> { inner.add(Block.Heading(if (hasTitle) 3 else 2, t)); hasTitle = true }
                        kind == "subTitle" -> inner.add(Block.Heading(3, t))
                        (kind == "body" || kind == "obj" || kind == "") && pPr?.kid("buNone") == null -> inner.add(Block.Para(t, list = lvl + 1, bullet = "•"))
                        else -> inner.add(Block.Para(t))
                    }
                }
            }

            fun tree(e: Element) {
                for (c in e.children()) when (c.ln) {
                    "sp" -> {
                        val ph = c.all("ph").firstOrNull()
                        val kind = ph?.let { it.a("type") ?: "" }
                        c.kid("txBody")?.let { paras(it, kind) }
                    }
                    "pic" -> c.all("blip").firstOrNull()?.a("embed")?.let { rel[it] }?.let { p -> imageBlock(p, files[p])?.let { inner.add(it) } }
                    "graphicFrame" -> c.all("tbl").firstOrNull()?.let { tbl ->
                        val rows = tbl.kids("tr").map { tr -> tr.kids("tc").map { tc -> tidy(tc.all("t").map { Run(it.wholeText()) }.flatMapIndexed { i, r -> if (i > 0) listOf(Run(" "), r) else listOf(r) }) } }
                        if (rows.isNotEmpty()) inner.add(Block.Table(rows, header = true))
                    }
                    "grpSp" -> tree(c)
                }
            }
            slide.all("spTree").firstOrNull()?.let { tree(it) }
            if (!hasTitle) blocks.add(Block.Heading(2, runsOf("Slide ${idx + 1}")))
            blocks.addAll(inner)
        }
        if (blocks.isEmpty()) throw ConvertProblem("This presentation has no slides.")
        return Doc(title, blocks)
    }
}

// ---------------------------------------------------------------- Spreadsheets (.xlsx, .ods, .csv)

object XlsxReader {
    fun read(bytes: ByteArray, title: String): Doc {
        val files = unzip(bytes)
        val wb = xml(files["xl/workbook.xml"]) ?: throw ConvertProblem("This doesn't look like an Excel file.")
        val wbRels = rels(files, "xl/workbook.xml")
        val shared = xml(files["xl/sharedStrings.xml"])?.kids("sst")?.firstOrNull()?.kids("si")?.map { si -> si.all("t").filter { t -> t.parents().none { it.ln == "rPh" } }.joinToString("") { it.wholeText() } }
            ?: xml(files["xl/sharedStrings.xml"])?.all("si")?.map { it.all("t").joinToString("") { t -> t.wholeText() } } ?: emptyList()
        val date1904 = wb.all("workbookPr").firstOrNull()?.a("date1904").let { it == "1" || it == "true" }
        // which cell styles are dates
        val styles = xml(files["xl/styles.xml"])
        val custom = styles?.all("numFmt")?.associate { (it.a("numFmtId")?.toIntOrNull() ?: -1) to it.a("formatCode").orEmpty() } ?: emptyMap()
        val xfDate = styles?.all("cellXfs")?.firstOrNull()?.kids("xf")?.map { xf ->
            val id = xf.a("numFmtId")?.toIntOrNull() ?: 0
            id in 14..22 || id in 45..47 || custom[id]?.let { code -> code.replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "").lowercase().let { c -> ('y' in c || 'd' in c || "h:" in c || ":mm" in c) } } == true
        } ?: emptyList()
        val blocks = ArrayList<Block>()
        for (sh in wb.all("sheet")) {
            val path = wbRels[sh.a("id")] ?: continue
            val x = xml(files[path]) ?: continue
            val grid = ArrayList<MutableList<String>>()
            var rowIdx = -1
            for (row in x.all("row")) {
                rowIdx = (row.a("r")?.toIntOrNull()?.minus(1)) ?: (rowIdx + 1)
                if (rowIdx > 200_000) break
                while (grid.size <= rowIdx) grid.add(ArrayList())
                val cells = grid[rowIdx]
                var col = -1
                for (c in row.kids("c")) {
                    col = c.a("r")?.let { colIndex(it) } ?: (col + 1)
                    if (col > 16_384) continue
                    val t = c.a("t")
                    val v = c.kid("v")?.text()
                    val text = when (t) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> c.kid("is")?.all("t")?.joinToString("") { it.wholeText() }.orEmpty()
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        "str", "e" -> v.orEmpty()
                        else -> v?.let { num(it, xfDate.getOrNull(c.a("s")?.toIntOrNull() ?: 0) == true, date1904) }.orEmpty()
                    }
                    while (cells.size <= col) cells.add("")
                    cells[col] = text
                }
            }
            blocks.add(sheet(sh.a("name") ?: "Sheet", grid))
        }
        if (blocks.isEmpty()) throw ConvertProblem("This spreadsheet has no sheets.")
        return Doc(title, blocks)
    }

    private fun colIndex(ref: String): Int { var n = 0; for (ch in ref) { if (ch !in 'A'..'Z') break; n = n * 26 + (ch - 'A' + 1) }; return n - 1 }

    private fun num(v: String, isDate: Boolean, d1904: Boolean): String {
        val d = v.toDoubleOrNull() ?: return v
        if (isDate && d > 0 && d < 2_958_466) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear(); cal.set(if (d1904) 1904 else 1899, if (d1904) 0 else 11, if (d1904) 1 else 30)
            val days = d.toLong(); val secs = Math.round((d - days) * 86400)
            cal.add(Calendar.DAY_OF_MONTH, days.toInt()); cal.add(Calendar.SECOND, secs.toInt())
            val df = if (d < 1) DateFormat.getTimeInstance(DateFormat.SHORT) else if (secs == 0L) DateFormat.getDateInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            df.timeZone = TimeZone.getTimeZone("UTC")
            return df.format(cal.time)
        }
        return cleanNumber(d, v)
    }
}

internal fun cleanNumber(d: Double, raw: String): String {
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
    return java.math.BigDecimal(raw.trim()).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
}

/** A grid of strings into a table block, without empty rows and columns at the end. */
internal fun sheet(name: String, grid: List<List<String>>): Block.Table {
    val rows = grid.map { it.toMutableList() }.toMutableList()
    for (r in rows) while (r.isNotEmpty() && r.last().isEmpty()) r.removeAt(r.size - 1)
    while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.size - 1)
    val width = rows.maxOfOrNull { it.size } ?: 0
    return Block.Table(rows.map { r -> List(width) { c -> runsOf(r.getOrElse(c) { "" }) } }, name = name, header = true)
}

object OdsReader {
    fun read(bytes: ByteArray, title: String): Doc {
        val files = unzip(bytes)
        val content = xml(files["content.xml"]) ?: throw ConvertProblem("This doesn't look like an OpenDocument spreadsheet.")
        val blocks = ArrayList<Block>()
        for (t in content.all("table")) {
            if (t.parents().any { it.ln == "table" }) continue
            val grid = ArrayList<List<String>>()
            for (tr in t.all("table-row")) {
                if (tr.parents().firstOrNull { it.ln == "table" } != t) continue
                val cells = ArrayList<String>()
                for (tc in tr.children().filter { it.ln == "table-cell" || it.ln == "covered-table-cell" }) {
                    val text = when (tc.a("value-type")) {
                        "float", "percentage", "currency" -> tc.kids("p").joinToString("\n") { it.wholeText() }.ifEmpty { tc.a("value").orEmpty() }
                        else -> tc.kids("p").joinToString("\n") { p -> p.text() }
                    }
                    val rep = tc.a("number-columns-repeated")?.toIntOrNull() ?: 1
                    repeat(if (text.isEmpty()) rep.coerceAtMost(200) else rep.coerceAtMost(1000)) { cells.add(text) }
                }
                val rep = tr.a("number-rows-repeated")?.toIntOrNull() ?: 1
                val empty = cells.all { it.isEmpty() }
                repeat(if (empty) rep.coerceAtMost(50) else rep.coerceAtMost(1000)) { grid.add(cells) }
                if (grid.size > 200_000) break
            }
            blocks.add(sheet(t.a("name") ?: "Sheet", grid))
        }
        if (blocks.isEmpty()) throw ConvertProblem("This spreadsheet has no sheets.")
        return Doc(title, blocks)
    }
}

object CsvReader {
    fun read(bytes: ByteArray, title: String): Doc {
        val text = decodeText(bytes)
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t', '|').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = parse(text, delim)
        return Doc(title, listOf(sheet(title, rows)))
    }

    fun parse(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(c)
            } else when (c) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(c)
                delim -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}

// ---------------------------------------------------------------- RTF

object RtfReader {
    private class State(var b: Boolean = false, var i: Boolean = false, var u: Boolean = false, var s: Boolean = false, var skip: Boolean = false, var uc: Int = 1, var lt: Boolean = false) {
        fun copy() = State(b, i, u, s, skip, uc, lt)
    }

    private val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
        "footnote", "listtable", "listoverridetable", "rsidtbl", "generator", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl",
        "fldinst", "object", "shp", "shpinst", "pntext", "pntxta", "pntxtb", "revtbl", "mmathPr", "filetbl", "nonshppict", "bkmkstart", "bkmkend", "annotation", "atnid", "atnauthor")

    fun read(bytes: ByteArray, title: String): Doc {
        val cp1252 = Charset.forName("windows-1252")
        val src = String(bytes, cp1252)
        if (!src.trimStart().startsWith("{\\rtf")) throw ConvertProblem("This doesn't look like a rich text (RTF) file.")
        val blocks = ArrayList<Block>()
        var runs = ArrayList<Run>()
        var align = 0
        var outline: Int? = null
        var ilvl = 0
        val listText = StringBuilder()
        // heading styles from the style sheet: "{\s1 ... heading 1;}"
        val headingStyle = HashMap<Int, Int>()
        Regex("\\{(?:\\\\\\*)?\\\\s(\\d+)[^;{}]*?([A-Za-z][^\\\\;{}]*);").findAll(src.take(400_000)).forEach { m ->
            val name = m.groupValues[2].replace(Regex("^[A-Za-z]+-?\\d*\\s+"), "").trim().lowercase()
            val lvl = Regex("^heading (\\d)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: if (name == "title") 1 else if (name == "subtitle") 2 else null
            if (lvl != null) headingStyle[m.groupValues[1].toInt()] = lvl
        }
        var inTable = false
        var tableRows = ArrayList<List<List<Run>>>()
        var tableRow = ArrayList<List<Run>>()
        val stack = ArrayDeque<State>()
        var st = State()
        var ucSkip = 0
        val text = StringBuilder()
        val hex = java.io.ByteArrayOutputStream()

        fun flushText() {
            if (hex.size() > 0) { text.append(String(hex.toByteArray(), cp1252)); hex.reset() }
            if (text.isNotEmpty()) { if (st.lt) listText.append(text) else runs.add(Run(text.toString(), st.b, st.i, st.u, st.s)); text.clear() }
        }
        fun closeTable() {
            if (tableRow.isNotEmpty()) { tableRows.add(tableRow); tableRow = ArrayList() }
            if (tableRows.isNotEmpty()) blocks.add(Block.Table(tableRows))
            tableRows = ArrayList()
        }
        /** Text outside a table after a table: the table is finished. */
        fun beforeContent() { if (!inTable && (tableRows.isNotEmpty() || tableRow.isNotEmpty())) { flushText(); closeTable() } }
        fun endPara() {
            flushText()
            if (inTable) { runs.add(Run("\n")); return }
            beforeContent()
            var r = tidy(runs).toMutableList()
            while (r.isNotEmpty() && r.first().text.isBlank()) r.removeAt(0)
            if (r.isNotEmpty()) r[0] = r[0].copy(text = r[0].text.trimStart(' ', '\t'))
            val h = outline
            val bullet = listText.toString().filter { it.code !in 0xE000..0xF8FF && it != '\t' }.trim()
            blocks.add(when {
                h != null && r.isNotEmpty() -> Block.Heading(h, r.map { it.copy(b = false) })
                listText.isNotEmpty() && r.isNotEmpty() -> Block.Para(r, list = ilvl + 1, bullet = if (bullet.any { it.isLetterOrDigit() }) bullet else "•")
                else -> Block.Para(r, align = align)
            })
            listText.clear()
            runs = ArrayList()
        }

        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when (c) {
                '{' -> { flushText(); stack.addLast(st.copy()); i++ }
                '}' -> { flushText(); st = stack.removeLastOrNull() ?: st; i++ }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val nc = src[i]
                    if (nc.isLetter()) {
                        val start = i
                        while (i < n && src[i].isLetter()) i++
                        val word = src.substring(start, i)
                        var param: Int? = null
                        if (i < n && (src[i] == '-' || src[i].isDigit())) {
                            val ps = i; i++
                            while (i < n && src[i].isDigit()) i++
                            param = src.substring(ps, i).toIntOrNull()
                        }
                        if (i < n && src[i] == ' ') i++
                        if (word in skipDest) { st.skip = true; continue }
                        if (st.skip) continue
                        when (word) {
                            "par" -> endPara()
                            "line" -> { beforeContent(); text.append('\n') }
                            "tab" -> text.append('\t')
                            "b" -> { flushText(); st.b = param != 0 }
                            "i" -> { flushText(); st.i = param != 0 }
                            "ul" -> { flushText(); st.u = param != 0 }
                            "ulnone" -> { flushText(); st.u = false }
                            "strike" -> { flushText(); st.s = param != 0 }
                            "plain" -> { flushText(); st.b = false; st.i = false; st.u = false; st.s = false }
                            "pard" -> { flushText(); align = 0; inTable = false; outline = null; ilvl = 0 }
                            "s" -> outline = headingStyle[param ?: -1]
                            "ilvl" -> ilvl = (param ?: 0).coerceIn(0, 8)
                            "listtext" -> st.lt = true
                            "outlinelevel" -> outline = ((param ?: 0) + 1).takeIf { it in 1..6 }
                            "qc" -> align = 1
                            "qr" -> align = 2
                            "ql", "qj" -> align = 0
                            "uc" -> st.uc = param ?: 1
                            "u" -> { beforeContent(); var cp = param ?: 0; if (cp < 0) cp += 65536; text.append(cp.toChar()); ucSkip = st.uc }
                            "emdash" -> text.append('—'); "endash" -> text.append('–'); "bullet" -> text.append('•')
                            "lquote" -> text.append('‘'); "rquote" -> text.append('’'); "ldblquote" -> text.append('“'); "rdblquote" -> text.append('”')
                            "trowd", "intbl" -> {
                                if (!inTable) { flushText(); if (runs.isNotEmpty()) { blocks.add(Block.Para(tidy(runs), align = align)); runs = ArrayList() } }
                                inTable = true
                            }
                            "cell" -> { flushText(); tableRow.add(tidy(runs).let { r -> if (r.lastOrNull()?.text == "\n") r.dropLast(1) else r }); runs = ArrayList() }
                            "row" -> { flushText(); tableRows.add(tableRow); tableRow = ArrayList(); runs = ArrayList() }
                            "page" -> { endPara(); blocks.add(Block.PageBreak) }
                        }
                        continue
                    }
                    when (nc) {
                        '\'' -> {
                            if (!st.skip && i + 2 < n) src.substring(i + 1, i + 3).toIntOrNull(16)?.let { if (ucSkip > 0) ucSkip-- else { beforeContent(); hex.write(it) } }
                            i += 3; continue
                        }
                        '*' -> st.skip = true
                        '~' -> if (!st.skip) text.append('\u00A0')
                        '_' -> if (!st.skip) text.append('-')
                        '\\', '{', '}' -> if (!st.skip) text.append(nc)
                        '\n', '\r' -> if (!st.skip) endPara()
                    }
                    i++
                }
                '\r', '\n' -> i++
                else -> {
                    if (!st.skip) {
                        if (ucSkip > 0) ucSkip-- else {
                            beforeContent()
                            if (hex.size() > 0) { text.append(String(hex.toByteArray(), cp1252)); hex.reset() }
                            text.append(c)
                        }
                    }
                    i++
                }
            }
        }
        flushText()
        closeTable()
        if (runs.isNotEmpty()) blocks.add(Block.Para(tidy(runs), align = align))
        val cleaned = ArrayList<Block>()
        for (b in blocks) { if (b is Block.Para && b.runs.isEmpty() && (cleaned.lastOrNull() as? Block.Para)?.runs?.isEmpty() != false) continue; cleaned.add(b) }
        while ((cleaned.lastOrNull() as? Block.Para)?.runs?.isEmpty() == true) cleaned.removeAt(cleaned.size - 1)
        return Doc(title, cleaned)
    }
}

// ---------------------------------------------------------------- Plain text, Markdown, web pages, eBooks

object TextReader {
    fun read(bytes: ByteArray, title: String): Doc {
        val text = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("\n{2,}")).map { Block.Para(runsOf(it.trimEnd('\n'))) }.filter { it.runs.isNotEmpty() }
        return Doc(title, blocks)
    }
}

object HtmlReader {
    fun markdown(bytes: ByteArray, title: String): Doc {
        val ext = listOf(TablesExtension.create())
        val md = MdParser.builder().extensions(ext).build().parse(decodeText(bytes))
        val html = HtmlRenderer.builder().extensions(ext).build().render(md)
        return fromHtml(html, title) { null }
    }

    fun read(bytes: ByteArray, title: String) = fromHtml(decodeText(bytes), title) { null }

    /** [resource] finds pictures that the page points to (used for eBooks). */
    fun fromHtml(html: String, title: String, resource: (String) -> ByteArray?): Doc {
        val doc = Jsoup.parse(html)
        val blocks = ArrayList<Block>()
        HtmlWalker(blocks, resource).block(doc.body(), 0, false)
        return Doc(doc.title().takeIf { it.isNotBlank() } ?: title, blocks)
    }

    private const val BR = '\u2028'

    private class HtmlWalker(val out: MutableList<Block>, val resource: (String) -> ByteArray?) {
        private val skip = setOf("script", "style", "noscript", "template", "head", "svg", "button", "input", "select", "textarea", "iframe", "object", "video", "audio", "canvas", "map")
        private val blockTags = setOf("p", "div", "section", "article", "header", "footer", "main", "aside", "nav", "figure", "figcaption", "address",
            "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "table", "blockquote", "pre", "hr", "dl", "dt", "dd", "body", "center", "form", "fieldset", "details", "summary")
        private var pending = ArrayList<Run>()

        fun image(src: String) {
            val bytes = when {
                src.startsWith("data:") -> runCatching { Base64.decode(src.substringAfter(","), Base64.DEFAULT) }.getOrNull()
                else -> resource(src)
            } ?: return
            imageBlock(src.substringBefore('?'), bytes)?.let { out.add(it) }
        }

        fun flush(quote: Boolean) {
            val r = normalise(pending)
            if (r.isNotEmpty() && r.plain().isNotBlank()) out.add(Block.Para(r, quote = quote))
            pending = ArrayList()
        }

        /** HTML squashes runs of spaces; text starts and ends without them. */
        fun normalise(runs: List<Run>): List<Run> {
            val out = ArrayList<Run>()
            var lastSpace = true
            for (r in runs) {
                val sb = StringBuilder()
                for (ch in r.text) {
                    if (ch == BR) { sb.append('\n'); lastSpace = true; continue }
                    if (ch.isWhitespace() && ch != ' ') { if (!lastSpace) sb.append(' '); lastSpace = true } else { sb.append(ch); lastSpace = false }
                }
                out.add(r.copy(text = sb.toString()))
            }
            val t = tidy(out).toMutableList()
            if (t.isNotEmpty()) t[t.size - 1] = t.last().copy(text = t.last().text.trimEnd(' ', '\n'))
            return tidy(t).let { if (it.plain().isBlank()) emptyList() else it }
        }

        fun inline(n: Node, base: Run, into: MutableList<Run>) {
            when (n) {
                is TextNode -> into.add(base.copy(text = n.wholeText))
                is Element -> {
                    val tag = n.normalName()
                    if (tag in skip) return
                    if (tag == "br") { into.add(base.copy(text = BR.toString())); return }
                    if (tag == "img") return
                    val st = n.attr("style").lowercase()
                    val r = base.copy(
                        b = base.b || tag in setOf("b", "strong", "th") || "font-weight:bold" in st.replace(" ", "") || Regex("font-weight:\\s*[6-9]00").containsMatchIn(st),
                        i = base.i || tag in setOf("i", "em", "cite", "var", "dfn") || "font-style:italic" in st.replace(" ", ""),
                        u = base.u || tag in setOf("u", "ins"),
                        s = base.s || tag in setOf("s", "strike", "del"),
                        mono = base.mono || tag in setOf("code", "tt", "kbd", "samp"),
                        link = if (tag == "a" && n.hasAttr("href")) n.attr("href") else base.link,
                    )
                    for (c in n.childNodes()) inline(c, r, into)
                }
            }
        }

        fun block(e: Element, listDepth: Int, quote: Boolean) {
            for (c in e.childNodes()) {
                if (c is TextNode) { pending.add(Run(c.wholeText)); continue }
                if (c !is Element) continue
                val tag = c.normalName()
                if (tag in skip) continue
                if (tag == "img") { flush(quote); image(c.attr("src")); continue }
                if (tag !in blockTags) {
                    if (c.getElementsByTag("img").isNotEmpty() || c.children().any { it.normalName() in blockTags }) block(c, listDepth, quote)
                    else inline(c, Run(""), pending)
                    continue
                }
                flush(quote)
                when (tag) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val runs = ArrayList<Run>(); inline(c, Run(""), runs)
                        val r = normalise(runs)
                        if (r.isNotEmpty()) out.add(Block.Heading(tag[1] - '0', r))
                        c.getElementsByTag("img").forEach { image(it.attr("src")) }
                    }
                    "hr" -> out.add(Block.Rule)
                    "pre" -> out.add(Block.Para(listOf(Run(c.wholeText().trimEnd('\n'), mono = true)), code = true))
                    "ul", "ol" -> {
                        var n = c.attr("start").toIntOrNull() ?: 1
                        for (li in c.children()) {
                            if (li.normalName() != "li") { block(li, listDepth + 1, quote); continue }
                            val runs = ArrayList<Run>()
                            val rest = ArrayList<Element>()
                            for (k in li.childNodes()) {
                                if (k is Element && (k.normalName() in setOf("ul", "ol", "table", "pre", "blockquote") || k.normalName() == "img")) rest.add(k)
                                else if (k is Element && k.normalName() in setOf("p", "div")) { if (runs.isNotEmpty()) runs.add(Run("\n")); inline(k, Run(""), runs) }
                                else inline(k, Run(""), runs)
                            }
                            out.add(Block.Para(normalise(runs), list = listDepth + 1, bullet = if (tag == "ol") "${n++}." else "•"))
                            for (k in rest) { if (k.normalName() == "img") image(k.attr("src")) else block(Element("div").appendChild(k.clone()), listDepth + 1, quote) }
                        }
                    }
                    "table" -> {
                        val rows = ArrayList<List<List<Run>>>()
                        var header = false
                        for (tr in c.select("tr")) {
                            if (tr.parents().firstOrNull { it.normalName() == "table" } != c) continue
                            val cells = tr.children().filter { it.normalName() == "td" || it.normalName() == "th" }
                            if (rows.isEmpty() && cells.isNotEmpty() && cells.all { it.normalName() == "th" }) header = true
                            val row = ArrayList<List<Run>>()
                            for (cell in cells) {
                                val runs = ArrayList<Run>(); inline(cell, Run(""), runs)
                                row.add(normalise(runs))
                                repeat((cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 20) - 1) { row.add(emptyList()) }
                            }
                            rows.add(row)
                        }
                        if (rows.isNotEmpty()) out.add(Block.Table(rows, header = header))
                    }
                    "blockquote" -> { block(c, listDepth, true); flush(true) }
                    "li" -> { val runs = ArrayList<Run>(); inline(c, Run(""), runs); out.add(Block.Para(normalise(runs), list = listDepth + 1, bullet = "•")) }
                    "dt" -> { val runs = ArrayList<Run>(); inline(c, Run("", b = true), runs); normalise(runs).takeIf { it.isNotEmpty() }?.let { out.add(Block.Para(it)) } }
                    "dd" -> { val runs = ArrayList<Run>(); inline(c, Run(""), runs); normalise(runs).takeIf { it.isNotEmpty() }?.let { out.add(Block.Para(it, list = 1, bullet = " ")) } }
                    else -> {
                        val centered = tag == "center" || c.attr("align") == "center" || "text-align:center" in c.attr("style").replace(" ", "")
                        if (c.children().none { it.normalName() in blockTags } && c.getElementsByTag("img").isEmpty()) {
                            val runs = ArrayList<Run>(); inline(c, Run(""), runs)
                            val r = normalise(runs)
                            if (r.isNotEmpty()) out.add(Block.Para(r, quote = quote, align = if (centered) 1 else 0))
                        } else { block(c, listDepth, quote); flush(quote) }
                    }
                }
            }
            if (e.normalName() == "body") flush(quote)
        }
    }

    fun epub(bytes: ByteArray, title: String): Doc {
        val files = unzip(bytes)
        val opfPath = xml(files["META-INF/container.xml"])?.all("rootfile")?.firstOrNull()?.a("full-path")
            ?: files.keys.firstOrNull { it.endsWith(".opf") } ?: throw ConvertProblem("This eBook looks damaged.")
        val opf = xml(files[opfPath]) ?: throw ConvertProblem("This eBook looks damaged.")
        val manifest = opf.all("item").associate { (it.a("id") ?: "") to resolvePath(opfPath, it.a("href") ?: "") }
        val spine = opf.all("itemref").mapNotNull { manifest[it.a("idref")] }
        val blocks = ArrayList<Block>()
        for ((idx, path) in spine.withIndex()) {
            val html = files[path]?.let { decodeText(it) } ?: continue
            val part = fromHtml(html, title) { src -> files[resolvePath(path, src.substringBefore('#'))] }
            if (part.blocks.isEmpty()) continue
            if (idx > 0 && blocks.isNotEmpty()) blocks.add(Block.PageBreak)
            blocks.addAll(part.blocks)
        }
        val t = opf.all("title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: title
        return Doc(t, blocks)
    }
}

// ---------------------------------------------------------------- PDF (text only)

object PdfTextReader {
    /** Android 15 and newer can read the words in a PDF. */
    val canRead get() = Build.VERSION.SDK_INT >= 35

    @SuppressLint("NewApi")
    fun read(file: File, title: String): Doc {
        if (!canRead) throw ConvertProblem("Reading the words in a PDF needs Android 15 or newer.")
        val blocks = ArrayList<Block>()
        var words = 0
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (p in 0 until r.pageCount) {
                    val text = r.openPage(p).use { page -> page.textContents.joinToString("\n") { it.text } }
                    if (p > 0 && blocks.isNotEmpty()) blocks.add(Block.PageBreak)
                    val lines = text.replace("\r", "").split('\n').map { it.trimEnd() }
                    val longest = lines.maxOfOrNull { it.length } ?: 0
                    val para = StringBuilder()
                    fun end() { if (para.isNotBlank()) blocks.add(Block.Para(runsOf(para.toString().trim()))); para.clear() }
                    for (line in lines) {
                        if (line.isBlank()) { end(); continue }
                        words += line.split(' ').size
                        if (para.isNotEmpty()) para.append(if (para.endsWith("-")) "" else " ")
                        para.append(line.trim())
                        // a short line, or one ending a sentence well before the margin, ends the paragraph
                        if (line.length < longest * 0.7 || line.trim().endsWith(':')) end()
                    }
                    end()
                }
            }
        }
        if (words == 0) throw ConvertProblem("This PDF has no words in it, only pictures (it was probably scanned). Try turning it into pictures instead, or use Scan text.")
        return Doc(title, blocks)
    }
}
