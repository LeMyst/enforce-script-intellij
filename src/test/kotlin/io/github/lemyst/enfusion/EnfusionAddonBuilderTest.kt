package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.AddonBuild
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** Addon Builder's command line and output, on hand-written samples shaped like its real output. */
class EnfusionAddonBuilderTest : TestCase() {

    private val bin = Path.of("D:\\Tools\\DayZ Tools\\Bin")

    fun testMinimalCommandLine() {
        val build = AddonBuild(Path.of("D:\\Work\\MyMod\\Scripts"), Path.of("D:\\Work\\@MyMod\\Addons"), clear = false)
        assertEquals(
            listOf(
                "D:\\Tools\\DayZ Tools\\Bin\\AddonBuilder\\AddonBuilder.exe",
                "D:\\Work\\MyMod\\Scripts",
                "D:\\Work\\@MyMod\\Addons",
                "-toolsDirectory=D:\\Tools\\DayZ Tools",
            ),
            AddonBuilderCommand.commandLine(bin, build),
        )
    }

    fun testEveryOption() {
        val build = AddonBuild(
            source = Path.of("D:\\Work\\MyMod\\Scripts"),
            destination = Path.of("D:\\Work\\@MyMod\\Addons"),
            prefix = " MyMod\\Scripts ",
            signKey = Path.of("D:\\Keys\\MyMod.biprivatekey"),
            packOnly = true,
            clear = true,
            temp = Path.of("D:\\Temp"),
            project = Path.of("P:\\"),
            include = Path.of("D:\\include.lst"),
            exclude = Path.of("D:\\exclude.lst"),
        )
        val args = AddonBuilderCommand.commandLine(bin, build).drop(3)
        assertEquals(
            listOf(
                "-packonly", "-clear", "-temp=D:\\Temp", "-prefix=MyMod\\Scripts", "-project=P:\\",
                "-sign=D:\\Keys\\MyMod.biprivatekey", "-include=D:\\include.lst", "-exclude=D:\\exclude.lst",
                "-toolsDirectory=D:\\Tools\\DayZ Tools",
            ),
            args,
        )
    }

    fun testPboIsNamedAfterTheSourceFolder() {
        val build = AddonBuild(Path.of("D:\\Work\\MyMod\\Scripts"), Path.of("D:\\Out"))
        assertEquals("Scripts.pbo", build.pboName)
        assertEquals(Path.of("D:\\Out\\Scripts.pbo"), build.pbo)
    }

    /** The exit code is 0 either way, so the verdict comes from the output. */
    fun testResultFromOutput() {
        val ok = """
            [2026-01-01 10:00:00,000] [INFO ]: Packing "D:\Temp\scripts"...
            [2026-01-01 10:00:00,500] [INFO ]: Done.
            [2026-01-01 10:00:00,600] [INFO ]: Build Successful
        """.trimIndent()
        assertEquals(AddonBuilderCommand.Result.SUCCEEDED, AddonBuilderCommand.result(ok))
        val failed = """
            [2026-01-01 10:00:00,000] [ERROR]: CfgConvert returned error. Canceling... [result]=1
            [2026-01-01 10:00:00,100] [ERROR]: !> Error reading config file 'D:\Work\MyMod\Scripts\config.cpp'
            [2026-01-01 10:00:00,200] [ERROR]: Build failed
        """.trimIndent()
        assertEquals(AddonBuilderCommand.Result.FAILED, AddonBuilderCommand.result(failed))
        assertEquals(AddonBuilderCommand.Result.UNKNOWN, AddonBuilderCommand.result("Setting breakpad minidump AppID = 830640"))
        assertTrue(AddonBuilderCommand.isError(failed.lines()[0]))
        assertFalse(AddonBuilderCommand.isError(ok.lines()[0]))
    }

    fun testConfigErrorLocation() {
        val line = "[ERROR]: !> File D:\\Work\\My Mod\\config.cpp, line 6: /CfgPatches/MyMod/: Missing '}'"
        val match = AddonBuilderCommand.CONFIG_ERROR.find(line)!!
        assertEquals("D:\\Work\\My Mod\\config.cpp", match.groupValues[1])
        assertEquals("6", match.groupValues[2])
    }

    fun testAddonDetectionAndPrefix() {
        val dir = Files.createTempDirectory("addon")
        try {
            assertFalse(AddonBuilderCommand.isAddon(dir))
            assertNull(AddonBuilderCommand.prefixOf(dir))
            Files.writeString(dir.resolve("config.cpp"), "class CfgPatches {};")
            assertTrue(AddonBuilderCommand.isAddon(dir))
            Files.writeString(dir.resolve("\$PBOPREFIX\$"), "\r\nMyMod\\Scripts\r\n")
            assertEquals("MyMod\\Scripts", AddonBuilderCommand.prefixOf(dir))
            Files.writeString(dir.resolve("\$PBOPREFIX\$"), "prefix=MyMod\\Data")
            assertEquals("MyMod\\Data", AddonBuilderCommand.prefixOf(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun testSigningFiles() {
        val build = AddonBuild(Path.of("D:\\Work\\MyMod\\Scripts"), Path.of("D:\\Work\\@MyMod\\Addons"))
        val key = Path.of("D:\\Keys\\MyMod.biprivatekey")
        assertEquals(Path.of("D:\\Work\\@MyMod\\Addons\\Scripts.pbo.MyMod.bisign"), AddonBuilderCommand.signatureFor(build, key))
        assertEquals(Path.of("D:\\Work\\@MyMod\\Keys\\MyMod.bikey"), AddonBuilderCommand.publicKeyFor(build, key))
    }
}
