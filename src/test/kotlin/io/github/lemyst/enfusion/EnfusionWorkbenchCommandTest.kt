package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.WorkbenchCommand
import junit.framework.TestCase
import java.nio.file.Files
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

    /**
     * An addon in `Scripts\` at the mod's root replaces the game's scripts\config.cpp and crashes
     * Workbench; one deeper, or a GUI addon, does not.
     */
    fun testCrashingAddon() {
        val mod = Files.createTempDirectory("mod")
        try {
            Files.createDirectories(mod.resolve("GUI")).resolve("config.cpp").let { Files.writeString(it, "") }
            Files.createDirectories(mod.resolve("Scripts").resolve("Client")).resolve("config.cpp").let { Files.writeString(it, "") }
            assertNull("Scripts\\Client and GUI are fine", WorkbenchCommand.crashingAddon(mod))
            Files.writeString(mod.resolve("Scripts").resolve("config.cpp"), "")
            assertEquals("Scripts", WorkbenchCommand.crashingAddon(mod))
            assertNull("no such folder", WorkbenchCommand.crashingAddon(mod.resolve("missing")))
        } finally {
            mod.toFile().deleteRecursively()
        }
    }
}
