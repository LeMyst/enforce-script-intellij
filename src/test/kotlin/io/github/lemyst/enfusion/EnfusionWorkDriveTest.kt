package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.WorkDriveCommands
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** The work-drive commands and reading back `subst`, without mounting anything. */
class EnfusionWorkDriveTest : TestCase() {

    fun testParseSubst() {
        val output = "P:\\: => F:\\Work Drive\r\nQ:\\: => \\??\\C:\\Some Very Long Folder\r\n\r\n"
        assertEquals(mapOf('P' to "F:\\Work Drive", 'Q' to "C:\\Some Very Long Folder"), WorkDriveCommands.parseSubst(output))
        assertTrue(WorkDriveCommands.parseSubst("").isEmpty())
        assertTrue(WorkDriveCommands.parseSubst("Invalid parameter - P:").isEmpty())
    }

    fun testUsesWorkDriveExeWhenDayZToolsIsThere() {
        val bin = Files.createTempDirectory("dayz-tools-bin")
        try {
            Files.createDirectories(bin.resolve("WorkDrive"))
            Files.createFile(bin.resolve("WorkDrive/WorkDrive.exe"))
            val source = Path.of("D:\\Work Drive")
            val exe = bin.resolve("WorkDrive/WorkDrive.exe").toString()
            assertEquals(listOf(exe, "/Mount", "D:\\Work Drive"), WorkDriveCommands.mount(bin, source, 'P'))
            assertEquals(listOf(exe, "/Dismount"), WorkDriveCommands.dismount(bin, 'P'))
        } finally {
            bin.toFile().deleteRecursively()
        }
    }

    fun testFallsBackToSubst() {
        val source = Path.of("D:\\Work Drive")
        assertEquals(listOf("subst", "P:", "D:\\Work Drive"), WorkDriveCommands.mount(null, source, 'P'))
        assertEquals(listOf("subst", "Q:", "/D"), WorkDriveCommands.dismount(Path.of("nowhere"), 'Q'))
    }

    fun testDriveOf() {
        assertEquals('P', WorkDriveCommands.driveOf("P:\\scripts"))
        assertEquals('P', WorkDriveCommands.driveOf("p:/scripts"))
        assertNull(WorkDriveCommands.driveOf("\\\\host\\share\\scripts"))
        assertNull(WorkDriveCommands.driveOf("scripts"))
    }
}
