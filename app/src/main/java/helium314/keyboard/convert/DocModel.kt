// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.convert

/**
 * Open Keyboard Converter (test 233, Jarrod): every file is read into this simple document model, then written out
 * in the chosen type. Reading and writing are both done on the phone, by our own code (no LibreOffice, no internet).
 */

/** A piece of text with one look. */
data class Run(
    val text: String,
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
    val s: Boolean = false,
    val link: String? = null,
    val mono: Boolean = false,
)

sealed class Block {
    data class Heading(val level: Int, val runs: List<Run>) : Block()
    /** [list] is how deep in a list (0 = not a list), [bullet] is "•" or "3." etc. [align]: 0 left, 1 centre, 2 right. */
    data class Para(
        val runs: List<Run>,
        val list: Int = 0,
        val bullet: String? = null,
        val align: Int = 0,
        val quote: Boolean = false,
        val code: Boolean = false,
    ) : Block()
    /** rows -> cells -> runs. [name] is the sheet name for spreadsheets. */
    data class Table(val rows: List<List<List<Run>>>, val name: String? = null, val header: Boolean = false) : Block()
    class Image(val bytes: ByteArray, val mime: String) : Block()
    data object PageBreak : Block()
    data object Rule : Block()
}

class Doc(val title: String, val blocks: List<Block>) {
    val tables get() = blocks.filterIsInstance<Block.Table>()
}

fun List<Run>.plain() = joinToString("") { it.text }
fun runsOf(text: String) = if (text.isEmpty()) emptyList() else listOf(Run(text))

enum class Kind { DOC, SHEET, SLIDES, PDF, IMAGE }

/** The file types the converter knows. [canRead]/[canWrite] say which way each one goes. */
enum class Fmt(val ext: String, val mime: String, val label: String, val kind: Kind, val canRead: Boolean = true, val canWrite: Boolean = true) {
    PDF("pdf", "application/pdf", "PDF", Kind.PDF),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word", Kind.DOC),
    ODT("odt", "application/vnd.oasis.opendocument.text", "OpenDocument", Kind.DOC),
    RTF("rtf", "application/rtf", "Rich text", Kind.DOC),
    TXT("txt", "text/plain", "Plain text", Kind.DOC),
    MD("md", "text/markdown", "Markdown", Kind.DOC),
    HTML("html", "text/html", "Web page", Kind.DOC),
    EPUB("epub", "application/epub+zip", "eBook", Kind.DOC),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel", Kind.SHEET),
    ODS("ods", "application/vnd.oasis.opendocument.spreadsheet", "OpenDocument sheet", Kind.SHEET),
    CSV("csv", "text/csv", "CSV", Kind.SHEET),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint", Kind.SLIDES, canWrite = false),
    ODP("odp", "application/vnd.oasis.opendocument.presentation", "OpenDocument slides", Kind.SLIDES, canWrite = false),
    JPG("jpg", "image/jpeg", "JPG picture", Kind.IMAGE),
    PNG("png", "image/png", "PNG picture", Kind.IMAGE),
    WEBP("webp", "image/webp", "WebP picture", Kind.IMAGE),
    HEIC("heic", "image/heic", "HEIC photo", Kind.IMAGE, canWrite = false),
    GIF("gif", "image/gif", "GIF", Kind.IMAGE, canWrite = false),
    BMP("bmp", "image/bmp", "BMP", Kind.IMAGE, canWrite = false);

    companion object {
        private val aliases = mapOf(
            "htm" to HTML, "xhtml" to HTML, "markdown" to MD, "jpeg" to JPG, "heif" to HEIC, "text" to TXT, "log" to TXT,
            "tsv" to CSV, "docm" to DOCX, "dotx" to DOCX, "xlsm" to XLSX, "ppsx" to PPTX, "pptm" to PPTX, "ott" to ODT,
        )

        /** Old binary Office files we can't read; the app says so plainly instead of failing. */
        val oldOffice = setOf("doc", "xls", "ppt")

        fun fromName(name: String?, mime: String?): Fmt? {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            entries.firstOrNull { it.ext == ext }?.let { return it }
            aliases[ext]?.let { return it }
            val m = mime?.lowercase()?.substringBefore(';') ?: return null
            entries.firstOrNull { it.mime == m }?.let { return it }
            return when {
                m == "text/x-markdown" -> MD
                m == "application/xhtml+xml" -> HTML
                m == "text/comma-separated-values" || m == "text/tab-separated-values" -> CSV
                m == "image/heif" -> HEIC
                m == "image/jpg" -> JPG
                m.startsWith("text/") -> TXT
                else -> null
            }
        }

        /** What a file of this type can be turned into, most useful first. */
        fun targetsFor(from: Fmt, pdfText: Boolean): List<Fmt> {
            val docs = listOf(PDF, DOCX, ODT, TXT, HTML, MD, RTF, EPUB)
            val sheets = listOf(XLSX, ODS, CSV)
            val pics = listOf(JPG, PNG)
            val list = when (from.kind) {
                Kind.DOC -> docs + pics
                Kind.SLIDES -> listOf(PDF, DOCX, ODT, TXT, HTML, MD, RTF) + pics
                Kind.SHEET -> listOf(PDF) + sheets + listOf(HTML, DOCX, ODT, TXT, MD) + pics
                Kind.PDF -> pics + (if (pdfText) listOf(DOCX, ODT, TXT, HTML, MD, RTF, EPUB) else emptyList())
                Kind.IMAGE -> listOf(PDF, JPG, PNG, WEBP, DOCX, ODT)
            }
            return list.filter { it != from && it.canWrite }
        }
    }
}
