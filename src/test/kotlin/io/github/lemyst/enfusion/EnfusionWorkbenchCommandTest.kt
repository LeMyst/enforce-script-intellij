package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.WorkbenchCommand
import junit.framework.TestCase
import java.nio.file.Path

/** Workbench's command line and the mod folder it is given when none is set. */
class EnfusionWorkbenchCommandTest : TestCase() {

    private val root: Path = Path.of("").toAbsolutePath().root
    private val exe = root.resolve("Tools").resolve("Workbench").resolve("WorkbenchApp.exe")

    /** As the working command line has it: `-filePatching "-mod=P:\MyMod\"`. */
    fun testCommand() {
        assertEquals(listOf(exe.toString(), "-filePatching", "-mod=P:\\MyMod\\"), WorkbenchCommand.command(exe, "P:\\MyMod"))
        assertEquals(listOf(exe.toString(), "-filePatching", "-mod=P:\\MyMod\\"), WorkbenchCommand.command(exe, "P:\\MyMod\\"))
        assertEquals("no mod: file patching only", listOf(exe.toString(), "-filePatching"), WorkbenchCommand.command(exe, " "))
    }

    fun testGuessedMod() {
        val drive = root.resolve("work")
        val checkout = root.resolve("src").resolve("MyMod")
        val link = drive.resolve("MyMod")
        val other = drive.resolve("Other")
        val same = { a: Path, b: Path -> a == b || (a == link && b == checkout) }
        assertEquals("a link at the drive's root", link, WorkbenchCommand.guessMod(checkout, drive, listOf(other, link), same))
        assertEquals("on the drive itself", drive.resolve("Mine"), WorkbenchCommand.guessMod(drive.resolve("Mine"), drive, listOf(other), same))
        assertNull("not on the drive, no link", WorkbenchCommand.guessMod(root.resolve("elsewhere"), drive, listOf(other, link), same))
        assertNull(WorkbenchCommand.guessMod(null, drive, listOf(link), same))
    }
}
