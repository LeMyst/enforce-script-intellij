package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.logs.DayZLogLines
import io.github.lemyst.enfusion.tools.logs.DayZLogTail
import io.github.lemyst.enfusion.tools.logs.LogView
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** Reading the game's logs, on hand-written lines shaped like the real ones. */
class EnfusionDayZLogsTest : TestCase() {

    fun testQuotedLocation() {
        val line = "10:58:21.590 SCRIPT    (W): @\"scripts/3_Game/human.c,17\": Missing script declaration"
        val location = DayZLogLines.locations(line).single()
        assertEquals("scripts/3_Game/human.c", location.path)
        assertEquals(17, location.line)
        assertEquals("@\"scripts/3_Game/human.c,17\"", line.substring(location.start, location.end))
    }

    fun testCompileSummaryLocation() {
        val line = "MyMod/Scripts/3_Game/settings.c(1): Unknown type 'Settings'"
        val location = DayZLogLines.locations(line).single()
        assertEquals("MyMod/Scripts/3_Game/settings.c", location.path)
        assertEquals(1, location.line)
        assertEquals("MyMod/Scripts/3_Game/settings.c(1)", line.substring(location.start, location.end))
    }

    fun testStackFrameLocation() {
        val line = "MyMod/Scripts/5_Mission/gui/menu.c:276 Function OnClick"
        val location = DayZLogLines.locations(line).single()
        assertEquals("MyMod/Scripts/5_Mission/gui/menu.c", location.path)
        assertEquals(276, location.line)
    }

    fun testLinesWithoutLocations() {
        assertTrue(DayZLogLines.locations("10:58:20.621 SCRIPT       : Registered 375 temporary action enum(s)").isEmpty())
        assertTrue(DayZLogLines.locations("Class:      'MyMenu'").isEmpty())
    }

    fun testSeverity() {
        assertEquals(DayZLogLines.Severity.ERROR, DayZLogLines.severity("10:55:04.979 SCRIPT    (E): Can't compile \"Game\" script module!"))
        assertEquals(DayZLogLines.Severity.WARNING, DayZLogLines.severity("10:58:21.590 SCRIPT    (W): @\"a.c,1\": x"))
        assertEquals(DayZLogLines.Severity.SCRIPT, DayZLogLines.severity("10:58:20.589 SCRIPT       : Module: Game; loaded"))
        assertEquals(DayZLogLines.Severity.ERROR, DayZLogLines.severity("Virtual Machine Exception"))
        assertEquals(DayZLogLines.Severity.OTHER, DayZLogLines.severity("Stack trace:"))
    }

    fun testKinds() {
        assertTrue(DayZLogLines.Kind.SCRIPT.matches("script_2026-01-01_10-00-00.log"))
        assertTrue(DayZLogLines.Kind.RPT.matches("DayZ_x64_2026-01-01_10-00-00.RPT"))
        assertTrue(DayZLogLines.Kind.RPT.matches("DayZServer_x64_2026-01-01_10-00-00.rpt"))
        assertTrue(DayZLogLines.Kind.CRASH.matches("crash_2026-01-01_10-00-00.log"))
        assertFalse(DayZLogLines.Kind.SCRIPT.matches("crash_2026-01-01_10-00-00.log"))
    }

    fun testViewKeepsErrorReportsWithTheirError() {
        val lines = listOf(
            "10:00:00.000 SCRIPT       : Module: Game; loaded",
            "10:00:01.000 SCRIPT    (E): Virtual Machine Exception",
            "",
            "Class:      'MyMenu'",
            "Stack trace:",
            "MyMod/Scripts/5_Mission/menu.c:276 Function OnClick",
            "10:00:02.000 SCRIPT       : back to normal",
        )
        val shown = LogView.render(lines, 0, onlyProblems = true, collapse = false)
        assertEquals(lines.subList(1, 6), shown.map { it.text })
        assertTrue(shown.all { it.severity == DayZLogLines.Severity.ERROR })
    }

    fun testViewCollapsesRepeatsIgnoringTimestamps() {
        val lines = listOf(
            "10:00:00.000 SCRIPT    (W): same",
            "10:00:00.100 SCRIPT    (W): same",
            "10:00:00.200 SCRIPT    (W): same",
            "10:00:01.000 SCRIPT       : other",
        )
        val shown = LogView.render(lines, 0, onlyProblems = false, collapse = true).map { it.text }
        assertEquals(listOf("10:00:00.000 SCRIPT    (W): same", "    (repeated 2 more times)", "10:00:01.000 SCRIPT       : other"), shown)
        assertEquals(4, LogView.render(lines, 0, onlyProblems = false, collapse = false).size)
    }

    fun testViewRendersOnlyFromStart() {
        val lines = listOf("10:00:00.000 SCRIPT       : a", "10:00:00.100 SCRIPT       : b")
        assertEquals(listOf("10:00:00.100 SCRIPT       : b"), LogView.render(lines, 1, onlyProblems = false, collapse = true).map { it.text })
    }

    fun testTailFollowsTheNewestFileAndItsAppends() {
        val folder = Files.createTempDirectory("dayz-logs")
        try {
            val tail = DayZLogTail(folder, DayZLogLines.Kind.SCRIPT)
            assertNull(tail.poll())
            val first = folder.resolve("script_2026-01-01_10-00-00.log")
            Files.writeString(first, "line one\nline two\npartial")
            val chunk = tail.poll()!!
            assertTrue(chunk.restarted)
            assertEquals(listOf("line one", "line two"), chunk.lines)
            assertNull(tail.poll())
            Files.writeString(first, "line one\nline two\npartial end\r\nline four\n")
            assertEquals(listOf("partial end", "line four"), tail.poll()!!.lines)

            val second = folder.resolve("script_2026-01-01_11-00-00.log")
            Files.writeString(second, "new run\n")
            Files.setLastModifiedTime(second, FileTime.fromMillis(Files.getLastModifiedTime(first).toMillis() + 60_000))
            Files.writeString(folder.resolve("crash_2026-01-01_12-00-00.log"), "not a script log\n")
            val next = tail.poll()!!
            assertTrue(next.restarted)
            assertEquals(second, next.file)
            assertEquals(listOf("new run"), next.lines)

            tail.pinned = first
            assertEquals(first, tail.poll()!!.file)
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
