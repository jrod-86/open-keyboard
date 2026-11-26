// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** A file the person picked. */
data class InFile(val uri: Uri, val name: String, val size: Long, val fmt: Fmt?, val ext: String)

/** A finished file, saved in Downloads/Converted. */
data class OutFile(val name: String, val uri: Uri, val mime: String, val size: Long)

object Converter {
    const val FOLDER = "Converted"
    private const val MAX_BYTES = 200L * 1024 * 1024

    fun describe(ctx: Context, uri: Uri): InFile {
        var name: String? = null; var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
            }
        }
        val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        return InFile(uri, n, size, Fmt.fromName(n, mime), n.substringAfterLast('.', "").lowercase())
    }

    /** What all the picked files can become. Several photos can also be put together into one PDF. */
    fun targets(files: List<InFile>): List<Fmt> {
        val fmts = files.mapNotNull { it.fmt }
        if (fmts.size != files.size || fmts.isEmpty()) return emptyList()
        return fmts.map { Fmt.targetsFor(it, PdfTextReader.canRead).toSet() }.reduce { a, b -> a intersect b }
            .let { set -> Fmt.targetsFor(fmts.first(), PdfTextReader.canRead).filter { it in set } }
    }

    /**
     * Converts every picked file to [to]. Photos going to PDF are put in one PDF together (one per page) when
     * [joinPhotos] is on. Returns the saved files.
     */
    fun run(ctx: Context, files: List<InFile>, to: Fmt, letter: Boolean, joinPhotos: Boolean, progress: (String) -> Unit): List<OutFile> {
        val pageSize = if (letter) PdfWriter.LETTER else PdfWriter.A4
        val out = ArrayList<OutFile>()
        val tmp = File(ctx.cacheDir, "convert").apply { deleteRecursively(); mkdirs() }
        try {
            if (to == Fmt.PDF && joinPhotos && files.size > 1 && files.all { it.fmt?.kind == Kind.IMAGE }) {
                progress("Putting ${files.size} photos into one PDF…")
                val bytes = files.map { read(ctx, it) }
                val name = files.first().name.substringBeforeLast('.') + " and ${files.size - 1} more"
                out.add(save(ctx, name, Fmt.PDF) { ImageWriter.imagesToPdf(bytes, it, pageSize) })
                return out
            }
            for ((i, f) in files.withIndex()) {
                val label = if (files.size > 1) "${f.name} (${i + 1} of ${files.size})" else f.name
                progress("Reading $label…")
                out.addAll(one(ctx, f, to, pageSize, tmp) { progress(it) })
            }
            return out
        } finally { tmp.deleteRecursively() }
    }

    private fun one(ctx: Context, f: InFile, to: Fmt, pageSize: PdfWriter.Page, tmp: File, progress: (String) -> Unit): List<OutFile> {
        val from = f.fmt ?: throw ConvertProblem(unknownMessage(f))
        val base = f.name.substringBeforeLast('.').ifBlank { "Converted" }
        val bytes = read(ctx, f)

        // pictures
        if (from.kind == Kind.IMAGE) return when (to) {
            Fmt.PDF -> listOf(save(ctx, base, to) { ImageWriter.imagesToPdf(listOf(bytes), it, pageSize) })
            Fmt.JPG, Fmt.PNG, Fmt.WEBP -> listOf(save(ctx, base, to) { it.write(ImageWriter.convert(bytes, to)) })
            else -> listOf(write(ctx, Doc(base, listOf(Block.Image(bytes, from.mime))), base, to, pageSize, tmp, progress))
        }

        // PDF -> pictures, or its words into a document
        if (from == Fmt.PDF) {
            val pdf = File(tmp, "in.pdf").apply { writeBytes(bytes) }
            if (to.kind == Kind.IMAGE) return pages(ctx, pdf, base, to, progress)
            progress("Reading the words in the PDF…")
            return listOf(write(ctx, PdfTextReader.read(pdf, base), base, to, pageSize, tmp, progress))
        }

        val doc = when (from) {
            Fmt.DOCX -> DocxReader.read(bytes, base)
            Fmt.ODT -> OdfReader.read(bytes, base, slides = false)
            Fmt.ODP -> OdfReader.read(bytes, base, slides = true)
            Fmt.PPTX -> PptxReader.read(bytes, base)
            Fmt.RTF -> RtfReader.read(bytes, base)
            Fmt.TXT -> TextReader.read(bytes, base)
            Fmt.MD -> HtmlReader.markdown(bytes, base)
            Fmt.HTML -> HtmlReader.read(bytes, base)
            Fmt.EPUB -> HtmlReader.epub(bytes, base)
            Fmt.XLSX -> XlsxReader.read(bytes, base)
            Fmt.ODS -> OdsReader.read(bytes, base)
            Fmt.CSV -> CsvReader.read(bytes, base)
            else -> throw ConvertProblem(unknownMessage(f))
        }
        if (doc.blocks.isEmpty()) throw ConvertProblem("${f.name} looks empty, there was nothing to convert.")
        if (to.kind == Kind.IMAGE) {
            progress("Laying out the pages…")
            val pdf = File(tmp, "pages.pdf")
            pdf.outputStream().use { PdfWriter.write(doc, it, pageSize) }
            return pages(ctx, pdf, base, to, progress)
        }
        return listOf(write(ctx, doc, base, to, pageSize, tmp, progress))
    }

    private fun write(ctx: Context, doc: Doc, base: String, to: Fmt, pageSize: PdfWriter.Page, tmp: File, progress: (String) -> Unit): OutFile {
        progress("Writing ${to.label}…")
        return when (to) {
            Fmt.PDF -> save(ctx, base, to) { PdfWriter.write(doc, it, pageSize) }
            Fmt.DOCX -> save(ctx, base, to) { DocxWriter.write(doc, it) }
            Fmt.ODT -> save(ctx, base, to) { OdtWriter.write(doc, it) }
            Fmt.HTML -> save(ctx, base, to) { it.write(TextWriters.html(doc).toByteArray()) }
            Fmt.MD -> save(ctx, base, to) { it.write(TextWriters.markdown(doc).toByteArray()) }
            Fmt.TXT -> save(ctx, base, to) { it.write(TextWriters.text(doc).toByteArray()) }
            Fmt.RTF -> save(ctx, base, to) { it.write(TextWriters.rtf(doc).toByteArray(Charsets.US_ASCII)) }
            Fmt.EPUB -> save(ctx, base, to) { TextWriters.epub(doc, it) }
            Fmt.XLSX -> save(ctx, base, to) { SheetWriters.xlsx(doc, it) }
            Fmt.ODS -> save(ctx, base, to) { SheetWriters.ods(doc, it) }
            Fmt.CSV -> {
                // CSV holds one sheet: the first one (others are named in the file name when there are several)
                val sheets = SheetWriters.sheets(doc)
                val (name, rows) = sheets.first()
                val n = if (sheets.size > 1) "$base - $name" else base
                // UTF-8 with a BOM so Excel shows accents and other alphabets properly
                save(ctx, n, to) { it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())); it.write(SheetWriters.csv(rows).toByteArray()) }
            }
            else -> throw ConvertProblem("Turning files into ${to.label} isn't possible yet.")
        }
    }

    private fun pages(ctx: Context, pdf: File, base: String, to: Fmt, progress: (String) -> Unit): List<OutFile> {
        val out = ArrayList<OutFile>()
        ImageWriter.pdfPages(pdf, to) { i, count, bytes ->
            progress("Page ${i + 1} of $count…")
            out.add(save(ctx, if (count > 1) "$base - page ${i + 1}" else base, to) { it.write(bytes) })
        }
        return out
    }

    private fun read(ctx: Context, f: InFile): ByteArray {
        if (f.size > MAX_BYTES) throw ConvertProblem("${f.name} is too big (over 200 MB) to convert on the phone.")
        val input = ctx.contentResolver.openInputStream(f.uri) ?: throw ConvertProblem("${f.name} couldn't be opened.")
        input.use {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buf); if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw ConvertProblem("${f.name} is too big (over 200 MB) to convert on the phone.")
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        }
    }

    fun unknownMessage(f: InFile) = when (f.ext) {
        in Fmt.oldOffice -> "${f.name} is an old Office file (.${f.ext}, from before 2007). Those can't be read yet. If you can, open it in Word, Excel or PowerPoint and save it as .${f.ext}x first."
        "pages", "numbers", "key" -> "${f.name} is an Apple iWork file; those can't be read. Export it from Pages, Numbers or Keynote as Word, Excel or PDF first."
        "" -> "${f.name} has no file type, so the converter doesn't know what's inside."
        else -> "Files ending in .${f.ext} can't be converted (yet)."
    }

    /** Saves into Downloads/Converted, where the Files app and other apps can find it. */
    private fun save(ctx: Context, base: String, fmt: Fmt, write: (OutputStream) -> Unit): OutFile {
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(100).ifBlank { "Converted" }
        val name = "$clean.${fmt.ext}"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, fmt.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw ConvertProblem("Couldn't save into Downloads.")
            try {
                var size = 0L
                resolver.openOutputStream(uri)!!.use { os -> val c = CountingStream(os); write(c); size = c.count }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val shown = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                return OutFile(shown ?: name, uri, fmt.mime, size)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
        // Android 9: the app's own folder, shared through a FileProvider
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        var file = File(dir, name); var k = 1
        while (file.exists()) file = File(dir, "$clean (${k++}).${fmt.ext}")
        file.outputStream().use(write)
        return OutFile(file.name, FileProvider.getUriForFile(ctx, ctx.packageName + ".media", file), fmt.mime, file.length())
    }

    private class CountingStream(val os: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { os.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { os.write(b, off, len); count += len }
        override fun flush() = os.flush()
        override fun close() = os.close()
    }
}
