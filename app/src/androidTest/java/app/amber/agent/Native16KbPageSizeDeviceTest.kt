package app.amber.agent

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.ui.components.richtext.nativebridge.MarkdownParserNative
import app.amber.core.infra.NativeLibraryOnDisk
import app.amber.document.nativebridge.OfficeNativeSwitch
import app.amber.highlight.HighlightToken
import app.amber.highlight.nativebridge.HighlightNativeSwitch
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit 16 KB run: -e amberNative16KbFixture true. Never infer the kernel size from ELF headers. */
@RunWith(AndroidJUnit4::class)
class Native16KbPageSizeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun require16KbKernel() {
        assumeTrue("Explicit 16 KB device fixture required", InstrumentationRegistry.getArguments()
            .getString("amberNative16KbFixture") == "true")
        assertEquals("Must run on a real 16 KB kernel", 16384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        println("Native16Kb: pageSize=16384 fingerprint=${Build.FINGERPRINT}")
    }

    @Test
    fun allRustLibrariesLoadAndBothParserLibrariesExecute() = runBlocking {
        listOf("highlight_parser", "office_parsers", "markdown_parser", "markdown_preprocess",
            "html_diff_normalizer", "regex_transformer", "sync_crypto")
            .forEach { System.loadLibrary(it) }
        assertTrue(MarkdownParserNative.renderHtml("**Amber16KB**").orEmpty().contains("<strong>Amber16KB</strong>"))

        val originalHighlight = HighlightNativeSwitch.config
        val originalOffice = OfficeNativeSwitch.config
        val fixture = File.createTempFile("native-16kb-", ".xlsx", context.cacheDir)
        try {
            HighlightNativeSwitch.config = object : HighlightNativeSwitch.Config {
                override fun enabled() = true
                override fun samplingRate() = 0f
                override fun onLoadFailure(error: Throwable) { throw AssertionError("highlight load failed", error) }
                override fun onNativePanic(stage: String, error: Throwable?) { throw AssertionError(stage, error) }
                override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) = Unit
            }
            val javaTokens = HighlightNativeSwitch.highlightOrNull("class Amber { int answer = 42; }", "java") {
                error("JVM fallback must not hide a native failure")
            }
            assertTrue(javaTokens.orEmpty().any { it is HighlightToken.Token.StringContent })
            OfficeNativeSwitch.config = object : OfficeNativeSwitch.Config {
                override fun enabled() = true
                override fun onLoadFailure(error: Throwable) { throw AssertionError("office load failed", error) }
                override fun onNativePanic(stage: String, error: Throwable?) { throw AssertionError(stage, error) }
            }
            writeWorkbook(fixture)
            val parsed = OfficeNativeSwitch.parseXlsxOrNull(fixture).orEmpty()
            assertTrue(parsed, parsed.contains("Amber16KB") && parsed.contains("42"))
        } finally {
            HighlightNativeSwitch.config = originalHighlight
            OfficeNativeSwitch.config = originalOffice
            fixture.delete()
        }
    }

    @Test
    fun sqliteExtensionLoadsAndExecutesFtsQueries() {
        val library = requireNotNull(NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so"))
        val factory = RequerySQLiteOpenHelperFactory(listOf(
            RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                options.customExtensions.add(SQLiteCustomExtension(library.absolutePath.removeSuffix(".so"), null))
                options
            },
        ))
        // Requery's in-memory branch bypasses createConfiguration(), including
        // custom extensions. Use an isolated file DB like the production owner.
        val database = File(context.cacheDir, "native16kb-${System.nanoTime()}.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(database.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE VIRTUAL TABLE probe USING fts5(body, tokenize='simple')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        try {
            factory.create(config).use { helper ->
                val db = helper.writableDatabase
                db.execSQL("INSERT INTO probe(body) VALUES (?)", arrayOf("Amber native page size"))
                db.query("SELECT count(*) FROM probe WHERE probe MATCH ?", arrayOf("native")).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(database.absolutePath)
        }
    }

    private fun writeWorkbook(file: File) {
        val entries = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Probe" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:B1"/><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Amber16KB</t></is></c><c r="B1"><v>42</v></c></row></sheetData></worksheet>""",
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
