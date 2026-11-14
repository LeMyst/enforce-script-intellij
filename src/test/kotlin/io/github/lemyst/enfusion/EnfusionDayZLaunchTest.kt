package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** The command lines of the DayZ run configuration, without starting anything. */
class EnfusionDayZLaunchTest : TestCase() {

    /*
     * The folders are built a segment at a time under the real filesystem root: a literal
     * `Path.of("D:\\Games\\DayZ")` is a single file name on Linux, so CI would test something else.
     * The launch options are strings handed to the game verbatim, so they stay literal.
     */
    private val root: Path = Path.of("").toAbsolutePath().root
    private val game = root.resolve("Games").resolve("DayZ")
    private val server = root.resolve("Games").resolve("DayZServer")
    private val serverProfile = root.resolve("Servers").resolve("profile")
    private val diagExe = game.resolve("DayZDiag_x64.exe").toString()
    private val retailExe = game.resolve("DayZ_x64.exe").toString()

    private val paths = DayZPaths(
        game = game,
        server = server,
        workDriveLetter = "P",
        serverProfile = serverProfile,
    )

    fun testDiagClient() {
        val launch = DayZLaunch(
            mods = listOf("P:\\@CF", " D:\\Mods\\@My Mod "),
            profiles = "D:\\Profiles\\client",
            connect = "127.0.0.1",
            port = 2302,
        )
        assertEquals(
            listOf(
                diagExe,
                "-mod=P:\\@CF;D:\\Mods\\@My Mod",
                "-profiles=D:\\Profiles\\client",
                "-filePatching",
                "-window",
                "-connect=127.0.0.1",
                "-port=2302",
            ),
            DayZLaunchCommand.build(paths, launch),
        )
    }

    fun testRetailClientWithoutOptions() {
        val launch = DayZLaunch(diag = false, filePatching = false, window = false)
        assertEquals(listOf(retailExe), DayZLaunchCommand.build(paths, launch))
    }

    fun testDedicatedServerUsesTheServerProfileFromSettings() {
        val launch = DayZLaunch(
            mode = DayZLaunchMode.SERVER,
            diag = false,
            mods = listOf("@CF"),
            serverMods = listOf("@Admin"),
            serverConfig = "serverDZ.cfg",
            port = 2302,
            mission = "ignored without Diag",
        )
        assertEquals(
            listOf(
                server.resolve("DayZServer_x64.exe").toString(),
                "-config=serverDZ.cfg",
                "-port=2302",
                "-profiles=$serverProfile",
                "-mod=@CF",
                "-serverMod=@Admin",
                "-filePatching",
            ),
            DayZLaunchCommand.build(paths, launch),
        )
    }

    fun testDiagServer() {
        val launch = DayZLaunch(mode = DayZLaunchMode.SERVER, mission = "P:\\missions\\dayzOffline.chernarusplus", profiles = "D:\\P")
        val command = DayZLaunchCommand.build(paths, launch)
        assertEquals(listOf(diagExe, "-server"), command.take(2))
        assertTrue(command.contains("-mission=P:\\missions\\dayzOffline.chernarusplus"))
        assertTrue(command.contains("-profiles=D:\\P"))
    }

    /** As DayZ Tools' Object Builder starts its external viewer. */
    fun testBuldozer() {
        val launch = DayZLaunch(mode = DayZLaunchMode.BULDOZER, filePatching = false)
        assertEquals(
            listOf(retailExe, "-buldozer", "-window", "-noland", "-nopause", "-profiles=P:\\Buldozer", "-name:Buldozer"),
            DayZLaunchCommand.build(paths, launch),
        )
    }

    fun testExtraArgumentsKeepQuotedSpaces() {
        assertEquals(listOf("-a", "b c", "-d="), DayZLaunchCommand.splitArguments("-a \"b c\"  -d=\"\""))
        assertEquals(emptyList<String>(), DayZLaunchCommand.splitArguments("   "))
        val launch = DayZLaunch(diag = false, filePatching = false, window = false, extraArguments = "-nosplash -name=\"Test Player\"")
        assertEquals(listOf(retailExe, "-nosplash", "-name=Test Player"), DayZLaunchCommand.build(paths, launch))
    }

    fun testMissingFolderIsAProblem() {
        try {
            DayZLaunchCommand.build(DayZPaths(), DayZLaunch())
            fail("built without a DayZ folder")
        } catch (e: DayZLaunchCommand.Problem) {
            assertEquals("The DayZ folder is not set.", e.message)
        }
        try {
            DayZLaunchCommand.build(paths.copy(server = null), DayZLaunch(mode = DayZLaunchMode.SERVER, diag = false))
            fail("built without a server folder")
        } catch (e: DayZLaunchCommand.Problem) {
            assertEquals("The DayZ Server folder is not set.", e.message)
        }
    }

    fun testStoredOptionsBecomeALaunch() {
        val options = io.github.lemyst.enfusion.tools.run.DayZRunOptions()
        options.mode = "SERVER"
        options.mods = "@CF; @MyMod"
        options.port = " 2402 "
        options.connect = "  "
        options.extraArguments = "-dologs"
        val launch = options.toLaunch()
        assertEquals(DayZLaunchMode.SERVER, launch.mode)
        assertEquals(listOf("@CF", "@MyMod"), launch.mods)
        assertEquals(2402, launch.port)
        assertNull(launch.connect)
        options.mode = "NOT_A_MODE"
        assertEquals(DayZLaunchMode.CLIENT, options.toLaunch().mode)
    }

    fun testModHelpers() {
        assertEquals(listOf("@A", "@B"), DayZLaunchCommand.splitMods(" @A ; ;@B;"))
        val root = Files.createTempDirectory("mods")
        try {
            Files.createDirectories(root.resolve("@Two"))
            Files.createDirectories(root.resolve("@One"))
            Files.createDirectories(root.resolve("Scripts"))
            assertEquals(listOf(root.resolve("@One").toString(), root.resolve("@Two").toString()), DayZLaunchCommand.modFolders(listOf(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
