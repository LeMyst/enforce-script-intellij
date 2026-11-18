package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.AddonBuild
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** Addon Builder's command line and output, on hand-written samples shaped like its real output. */
class EnfusionAddonBuilderTest : TestCase() {

    /*
     * Paths are built a segment at a time under the real filesystem root: a literal
     * `Path.of("D:\\Work\\...")` is a single file name on Linux, so CI would test something else.
     */
    private val root: Path = Path.of("").toAbsolutePath().root
    private fun path(vararg segments: String): Path = segments.fold(root) { p, s -> p.resolve(s) }

    private val tools = path("Tools", "DayZ Tools")
    private val bin = tools.resolve("Bin")

    /**
     * Without `-include`, Addon Builder's command line packed `config.bin` alone: no scripts,
     * layouts or stringtable. A build that sets no list gets the plugin's.
     */
    fun testDefaultIncludeList() {
        val folder = Files.createTempDirectory("addon-include")
        try {
            val build = AddonBuild(path("Work", "MyMod", "Scripts"), path("Work", "@MyMod", "Addons"))
            val withList = AddonBuilderCommand.withDefaultInclude(build, folder)
            val list = withList.include!!
            assertEquals(folder.resolve("include.lst"), list)
            val patterns = Files.readString(list).split(';')
            for (needed in listOf("*.c", "*.layout", "*.imageset", "*.csv", "*.edds", "*.paa", "*.rvmat")) {
                assertTrue(needed, needed in patterns)
            }
            assertTrue("-include=$list" in AddonBuilderCommand.commandLine(bin, withList))

            val own = build.copy(include = path("Work", "mine.lst"))
            assertSame("a list the user set is kept", own, AddonBuilderCommand.withDefaultInclude(own, folder))
            val packOnly = build.copy(packOnly = true)
            assertSame("pack only stores every file", packOnly, AddonBuilderCommand.withDefaultInclude(packOnly, folder))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    /** The sandbox review: copies past Windows' 260 characters failed with a bare "Build failed". */
    fun testTooLongCopyIsNamed() {
        val deep = "C:\\" + "d".repeat(200) + "\\temp\\client"
        val output = """
            [2026-01-01 10:00:00,000] [INFO ]: Syncing folders... [source]=P:\MyMod\Scripts\Client, [sync]=$deep
            [2026-01-01 10:00:00,001] [INFO ]: Syncing file "3_Game\Short.c"
            [2026-01-01 10:00:00,002] [INFO ]: File sync done
            [2026-01-01 10:00:00,003] [INFO ]: Syncing file "3_Game\Some\Deep\Folder\AFileWithAVeryLongNameIndeed.c"
            [2026-01-01 10:00:00,004] [ERROR]: Build failed
        """.trimIndent()
        val copy = "$deep\\3_Game\\Some\\Deep\\Folder\\AFileWithAVeryLongNameIndeed.c"
        assertEquals(copy, AddonBuilderCommand.tooLongCopy(output))
        assertTrue(AddonBuilderCommand.tooLongMessage(copy).contains("${copy.length} characters"))
        assertNull("a short copy failed for another reason", AddonBuilderCommand.tooLongCopy(output.replace(deep, "C:\\temp\\client")))
        assertNull("every copy finished", AddonBuilderCommand.tooLongCopy(output.substringBefore("[2026-01-01 10:00:00,003]")))
    }

    fun testShortTempFolder() {
        val systemTemp = path("Users", "me", "AppData", "Local", "Temp")
        val one = AddonBuilderCommand.shortTemp(systemTemp, "release one")
        assertEquals(systemTemp.resolve("enfusion-ab"), one.parent)
        assertEquals(one, AddonBuilderCommand.shortTemp(systemTemp, "release one"))
        assertFalse(one == AddonBuilderCommand.shortTemp(systemTemp, "release two"))
    }

    fun testMinimalCommandLine() {
        val source = path("Work", "MyMod", "Scripts")
        val destination = path("Work", "@MyMod", "Addons")
        assertEquals(
            listOf(
                bin.resolve("AddonBuilder").resolve("AddonBuilder.exe").toString(),
                source.toString(),
                destination.toString(),
                "-toolsDirectory=$tools",
            ),
            AddonBuilderCommand.commandLine(bin, AddonBuild(source, destination, clear = false)),
        )
    }

    fun testEveryOption() {
        val temp = path("Temp")
        val key = path("Keys", "MyMod.biprivatekey")
        val include = path("include.lst")
        val exclude = path("exclude.lst")
        val build = AddonBuild(
            source = path("Work", "MyMod", "Scripts"),
            destination = path("Work", "@MyMod", "Addons"),
            prefix = " MyMod\\Scripts ",
            signKey = key,
            packOnly = true,
            clear = true,
            temp = temp,
            project = root,
            include = include,
            exclude = exclude,
        )
        val args = AddonBuilderCommand.commandLine(bin, build).drop(3)
        assertEquals(
            listOf(
                "-packonly", "-clear", "-temp=$temp", "-prefix=MyMod\\Scripts", "-project=$root",
                "-sign=$key", "-include=$include", "-exclude=$exclude",
                "-toolsDirectory=$tools",
            ),
            args,
        )
    }

    fun testPboIsNamedAfterTheSourceFolder() {
        val build = AddonBuild(path("Work", "MyMod", "Scripts"), path("Out"))
        assertEquals("Scripts.pbo", build.pboName)
        assertEquals(path("Out", "Scripts.pbo"), build.pbo)
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
        val build = AddonBuild(path("Work", "MyMod", "Scripts"), path("Work", "@MyMod", "Addons"))
        val key = path("Keys", "MyMod.biprivatekey")
        assertEquals(path("Work", "@MyMod", "Addons", "Scripts.pbo.MyMod.bisign"), AddonBuilderCommand.signatureFor(build, key))
        assertEquals(path("Work", "@MyMod", "Keys", "MyMod.bikey"), AddonBuilderCommand.publicKeyFor(build, key))
    }
}
