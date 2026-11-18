package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.KeyValues
import io.github.lemyst.enfusion.tools.detect.WorkshopPublish
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Workshop upload's file and command, built on plain values: nothing here runs SteamCMD or
 * reaches Steam.
 */
class EnfusionWorkshopPublishTest : TestCase() {

    /** Built segment by segment: `Path.of("P:\...")` is a single file name on Linux CI. */
    private val root: Path = Path.of(System.getProperty("java.io.tmpdir"))
    private val mod = root.resolve("Mods").resolve("@MyMod")

    private fun item(id: String? = null, visibility: WorkshopPublish.Visibility? = WorkshopPublish.Visibility.PRIVATE, title: String? = "My Mod") =
        WorkshopPublish.Item(id, mod, root.resolve("preview.png"), visibility, title, "A \"quoted\"\nline", "Fixed the doors.\nAdded a car.")

    fun testNewItemFile() {
        val text = WorkshopPublish.vdf(item())
        val parsed = KeyValues.parse(text).block("workshopitem")!!
        assertEquals("221100", parsed.string("appid"))
        assertEquals("0", parsed.string("publishedfileid"))
        assertEquals("2", parsed.string("visibility"))
        assertEquals("My Mod", parsed.string("title"))
        assertTrue(text, text.contains("\"changenote\"\t\"Fixed the doors.\\nAdded a car.\""))
        assertTrue(text, text.contains("\"description\"\t\"A \\\"quoted\\\"\\nline\""))
        // Backslashes are escaped, so a Windows path survives KeyValues.
        assertTrue(text, text.contains(WorkshopPublish.quote(mod.toString())))
    }

    /** An update leaves out what is not given, so the Workshop page keeps it. */
    fun testUpdateKeepsWhatIsNotGiven() {
        val text = WorkshopPublish.vdf(item(id = "3141592653", visibility = null, title = null))
        val parsed = KeyValues.parse(text).block("workshopitem")!!
        assertEquals("3141592653", parsed.string("publishedfileid"))
        assertNull(parsed.string("visibility"))
        assertNull(parsed.string("title"))
    }

    fun testQuoting() {
        assertEquals("\"C:\\\\Mods\\\\@MyMod\"", WorkshopPublish.quote("C:\\Mods\\@MyMod"))
        assertEquals("\"a\\tb\"", WorkshopPublish.quote("a\tb\r"))
    }

    fun testIdWrittenBackBySteamCmd() {
        val written = WorkshopPublish.vdf(item()).replace("\"publishedfileid\"\t\"0\"", "\"publishedfileid\"\t\"2718281828\"")
        assertEquals("2718281828", WorkshopPublish.publishedFileId(written))
        assertNull(WorkshopPublish.publishedFileId(WorkshopPublish.vdf(item())))
    }

    fun testBatchAndCommand() {
        val steamCmd = root.resolve("steam cmd").resolve("steamcmd.exe")
        val vdf = root.resolve("100%").resolve("item.vdf")
        val batch = WorkshopPublish.batch(steamCmd, "my_account", vdf, root.resolve("result.txt"))
        assertTrue(batch, batch.contains("\"$steamCmd\" +login my_account +workshop_build_item \"${vdf.toString().replace("%", "%%")}\" +quit"))
        assertTrue(batch, batch.contains("echo %ERRORLEVEL% > "))
        assertTrue(batch.contains("\r\n"))
        // The empty title keeps `start` from taking the batch file's path for one.
        assertEquals(listOf("cmd.exe", "/c", "start", "", "/wait", "x.bat"), WorkshopPublish.commandLine(Path.of("x.bat")))
    }

    fun testAccountNames() {
        assertTrue(WorkshopPublish.isAccountName("my_account99"))
        assertFalse(WorkshopPublish.isAccountName("me & you"))
        assertFalse(WorkshopPublish.isAccountName(""))
        assertNull(runCatching { WorkshopPublish.batch(root, "a b", root, root) }.getOrNull())
    }

    /** The sandbox review: an upload SteamCMD logged as OK ended with exit code 7. */
    fun testVerdictFromTheWorkshopLog() {
        val log = """
            [2026-01-01 10:00:00] Client version: 1
            [2026-01-01 10:00:09] [AppID 221100] Create new workshop item of type Community for AppID 221100 : 1234 (OK)
            [2026-01-01 10:00:09] [AppID 221100] Upload starting for workshop item 1234 by AppID 221100
            [2026-01-01 10:00:15] [AppID 221100] Upload finished for workshop item 1234 : OK
        """.trimIndent()
        assertEquals(WorkshopPublish.LoggedUpload("1234", "OK"), WorkshopPublish.lastUpload(log))
        assertTrue(WorkshopPublish.lastUpload(log)!!.succeeded)
        val failed = WorkshopPublish.lastUpload("... Upload finished for workshop item 1234 : AccessDenied\n")!!
        assertFalse(failed.succeeded)
        assertEquals("AccessDenied", failed.result)
        assertNull(WorkshopPublish.lastUpload("[2026-01-01 10:00:00] Client version: 1\n"))
        assertEquals(
            java.nio.file.Path.of("SteamCMD", "logs", "workshop_log.txt"),
            WorkshopPublish.workshopLog(java.nio.file.Path.of("SteamCMD", "steamcmd.exe")),
        )
    }

    fun testExitCode() {
        assertEquals(0, WorkshopPublish.exitCode("0 \r\n"))
        assertEquals(8, WorkshopPublish.exitCode("8"))
        assertNull(WorkshopPublish.exitCode(null))
    }

    fun testChangeNoteFromAChangelog() {
        val changelog = "# Changelog\n\n## 1.2.0\n\n- Fixed the doors.\n- Added a car.\n\n### Details\n\nMore.\n\n## 1.1.0\n\n- Older.\n"
        // `# Changelog` is the first heading: its section runs to the end, so the note is that.
        assertTrue(WorkshopPublish.changeNoteFrom(changelog).startsWith("## 1.2.0"))
        val versions = "## 1.2.0\n\n- Fixed the doors.\n- Added a car.\n\n### Details\n\nMore.\n\n## 1.1.0\n\n- Older.\n"
        assertEquals("- Fixed the doors.\n- Added a car.\n\n### Details\n\nMore.", WorkshopPublish.changeNoteFrom(versions))
        assertEquals("Just text.", WorkshopPublish.changeNoteFrom("Just text.\n"))
    }

    fun testSummaryAndSize() {
        val folder = Files.createTempDirectory("workshop")
        Files.writeString(folder.resolve("a.pbo"), "x".repeat(2048))
        Files.createDirectories(folder.resolve("keys"))
        Files.writeString(folder.resolve("keys").resolve("k.bikey"), "y")
        assertEquals(2 to 2049L, WorkshopPublish.contentSize(folder))
        val summary = WorkshopPublish.summary(item(), 2049, 2)
        assertTrue(summary, summary.startsWith("A new Workshop item"))
        assertTrue(summary, summary.contains("2 files, 2.0 KB"))
        assertTrue(summary, summary.contains("Visibility: Private"))
        assertTrue(summary, summary.contains("Fixed the doors."))
    }
}
