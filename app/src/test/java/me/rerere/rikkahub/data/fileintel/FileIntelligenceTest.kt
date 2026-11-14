package me.rerere.rikkahub.data.fileintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileIntelligenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `detector sniffs pdf magic bytes even when named bin`() {
        val pdfHeader = "%PDF-1.7\n%some binary data\n".toByteArray(Charsets.ISO_8859_1)
        val result = FileDetector.detectFromBytes(pdfHeader, pdfHeader.size, extensionHint = "bin")

        assertEquals("pdf", result.format)
        assertEquals(FileCategory.DOCUMENTS, result.category)
        assertEquals("application/pdf", result.mimeType)
        assertTrue(result.magicMatched)
        assertTrue(result.isExtensionMismatch) // .bin claiming PDF
    }

    @Test
    fun `detector sniffs png image signature`() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0, 0, 0, 100, // width = 100
            0, 0, 0, 50,  // height = 50
        )
        val result = FileDetector.detectFromBytes(pngHeader, pngHeader.size, extensionHint = "png")

        assertEquals("png", result.format)
        assertEquals(FileCategory.IMAGES, result.category)
        assertFalse(result.isExtensionMismatch)
        assertTrue(result.magicMatched)
    }

    @Test
    fun `detector detects extension mismatch spoofing`() {
        // PNG header with an exe extension
        val pngHeader = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        )
        val result = FileDetector.detectFromBytes(pngHeader, pngHeader.size, extensionHint = "exe")

        assertEquals("png", result.format)
        assertTrue(result.isExtensionMismatch)
    }

    @Test
    fun `file intelligence identifies zip archive and detects zip slip`() {
        val zipFile = tempFolder.newFile("test_archive.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("normal.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("../malicious.sh"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
        }

        val report = FileIntelligence.inspect(zipFile)

        assertEquals("zip", report.detection.format)
        assertEquals(FileCategory.ARCHIVES, report.detection.category)
        val zip = report.zipInfo
        assertTrue(zip != null)
        assertEquals(2, zip?.totalEntries)
        assertTrue(zip?.isZipSlipSuspected == true)
        assertTrue(report.warnings.any { it.contains("Zip Slip") })
    }

    @Test
    fun `file intelligence identifies apk structure`() {
        val apkFile = tempFolder.newFile("app.apk")
        ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("manifest".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dex".toByteArray())
            zos.closeEntry()
        }

        val report = FileIntelligence.inspect(apkFile)

        assertEquals("apk", report.detection.format)
        assertEquals(FileCategory.ARCHIVES, report.detection.category)
        assertTrue(report.apkInfo != null)
        assertEquals(1, report.apkInfo?.dexFiles?.size)
    }

    @Test
    fun `file with double dot filename does not trigger zip slip false positive`() {
        val zipFile = tempFolder.newFile("safe_archive.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("config..v2.json"))
            zos.write("{}".toByteArray())
            zos.closeEntry()
        }

        val report = FileIntelligence.inspect(zipFile)
        val zip = report.zipInfo
        assertNotNull(zip)
        assertFalse(zip!!.isZipSlipSuspected)
        assertTrue(report.warnings.none { it.contains("Zip Slip") })
    }

    @Test
    fun `empty zip archive with EOCD header is detected as zip`() {
        // Standard 22-byte End of Central Directory record for empty zip
        val emptyZipHeader = byteArrayOf(
            0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val result = FileDetector.detectFromBytes(emptyZipHeader, emptyZipHeader.size, extensionHint = "zip")
        assertEquals("zip", result.format)
        assertTrue(result.magicMatched)
    }

    @Test
    fun `media inspector calculates accurate dimensions for webp vp8x`() {
        val webpFile = tempFolder.newFile("sample.webp")
        val header = ByteArray(32)
        // "RIFF"
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // "WEBP"
        header[8] = 'W'.code.toByte(); header[9] = 'E'.code.toByte(); header[10] = 'B'.code.toByte(); header[11] = 'P'.code.toByte()
        // "VP8X"
        header[12] = 'V'.code.toByte(); header[13] = 'P'.code.toByte(); header[14] = '8'.code.toByte(); header[15] = 'X'.code.toByte()
        // Width: 100 (stored as 100 - 1 = 99 = 0x63, 0x00, 0x00)
        header[24] = 0x63.toByte()
        header[25] = 0x00
        header[26] = 0x00
        // Height: 50 (stored as 50 - 1 = 49 = 0x31, 0x00, 0x00)
        header[27] = 0x31.toByte()
        header[28] = 0x00
        header[29] = 0x00
        webpFile.writeBytes(header)

        val media = me.rerere.rikkahub.data.fileintel.inspectors.MediaInspector.inspect(webpFile)
        assertEquals(100, media.width)
        assertEquals(50, media.height)
    }

    @Test
    fun `pdf inspector reports 0 pages for invalid document`() {
        val corruptedPdf = tempFolder.newFile("corrupted.pdf")
        corruptedPdf.writeText("Not really a PDF")
        val info = me.rerere.rikkahub.data.fileintel.inspectors.PdfInspector.inspect(corruptedPdf)
        assertEquals(0, info.pageCount)
    }
}
