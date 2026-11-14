package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZToolsCatalog
import junit.framework.TestCase
import java.nio.file.Files

/** Which DayZ Tools are listed, from what is installed under `Bin`. */
class EnfusionDayZToolsCatalogTest : TestCase() {

    fun testOnlyInstalledToolsAreListed() {
        val bin = Files.createTempDirectory("dayz-tools-bin")
        try {
            assertTrue(DayZToolsCatalog.installed(bin).isEmpty())
            assertTrue(DayZToolsCatalog.installed(null).isEmpty())
            for (exe in listOf("ObjectBuilder/ObjectBuilder.exe", "ImageToPAA/TexView.exe", "Launcher/DayZToolsLauncher.exe")) {
                Files.createDirectories(bin.resolve(exe).parent)
                Files.createFile(bin.resolve(exe))
            }
            assertEquals(
                listOf("DayZ Tools Launcher", "Object Builder", "TexView 2"),
                DayZToolsCatalog.installed(bin).map { it.name },
            )
            assertEquals("TexView 2", DayZToolsCatalog.opener(bin, "PAA")?.name)
            assertEquals("Object Builder", DayZToolsCatalog.opener(bin, "p3d")?.name)
            assertNull("Material Editor is not installed", DayZToolsCatalog.opener(bin, "rvmat"))
            assertNull(DayZToolsCatalog.opener(bin, "c"))
        } finally {
            bin.toFile().deleteRecursively()
        }
    }
}
