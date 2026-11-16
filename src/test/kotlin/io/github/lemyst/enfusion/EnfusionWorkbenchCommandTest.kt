package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.WorkbenchCommand
import junit.framework.TestCase
import java.nio.file.Path

/** Workbench's command line; finding the project on the work drive is EnfusionDayZModsTest's. */
class EnfusionWorkbenchCommandTest : TestCase() {

    private val root: Path = Path.of("").toAbsolutePath().root
    private val exe = root.resolve("Tools").resolve("Workbench").resolve("WorkbenchApp.exe")

    /** As the working command line has it: `-filePatching "-mod=P:\MyMod\"`. */
    fun testCommand() {
        assertEquals(listOf(exe.toString(), "-filePatching", "-mod=P:\\MyMod\\"), WorkbenchCommand.command(exe, "P:\\MyMod"))
        assertEquals(listOf(exe.toString(), "-filePatching", "-mod=P:\\MyMod\\"), WorkbenchCommand.command(exe, "P:\\MyMod\\"))
        assertEquals("no mod: file patching only", listOf(exe.toString(), "-filePatching"), WorkbenchCommand.command(exe, " "))
    }
}
