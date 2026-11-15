package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCleanup
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode
import io.github.lemyst.enfusion.tools.detect.DayZMod
import io.github.lemyst.enfusion.tools.detect.DayZModKind
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.DayZPlayer
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
            modList = listOf(mod("P:\\@CF"), mod(" D:\\Mods\\@My Mod "), mod("P:\\@Off", enabled = false)),
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
            modList = listOf(mod("@CF"), mod("@Admin", serverOnly = true)),
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
        options.port = " 2402 "
        options.connect = "  "
        options.extraArguments = "-dologs"
        options.setModRows(listOf(DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON, serverOnly = true)))
        val launch = options.toLaunch()
        assertEquals(DayZLaunchMode.SERVER, launch.mode)
        assertEquals(listOf(DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON, serverOnly = true)), launch.modList)
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

    /** A configuration saved with `;`-separated mod strings and one player name opens converted. */
    fun testLegacyOptionsAreConverted() {
        val options = io.github.lemyst.enfusion.tools.run.DayZRunOptions()
        options.mods = "@CF; P:\\Extra\\one.pbo"
        options.serverMods = "@Admin"
        options.playerName = "Survivor"
        assertEquals(
            listOf(mod("@CF"), DayZMod("P:\\Extra\\one.pbo", DayZModKind.PBO), mod("@Admin", serverOnly = true)),
            options.toLaunch().modList,
        )
        assertEquals(listOf(DayZPlayer("Survivor")), options.toLaunch().players)
        options.setModRows(listOf(mod("@CF")))
        assertNull("the old strings are not written again", options.mods)
        assertNull(options.serverMods)
    }

    /**
     * The project's addons are passed as the mod they were packed into, once, where the first addon
     * row is, never as their own folders; and they turn -filePatching on even when it is off.
     */
    fun testProjectAddonsBecomeThePackedMod() {
        val launch = DayZLaunch(
            diag = false,
            filePatching = false,
            window = false,
            modList = listOf(mod("@CF"), DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON), mod("@Other"), DayZMod("P:\\MyMod\\Data", DayZModKind.ADDON)),
        )
        assertEquals(
            listOf(retailExe, "-mod=@CF;S:\\@MyMod;@Other", "-filePatching"),
            DayZLaunchCommand.build(paths, launch, addonMod = "S:\\@MyMod"),
        )
        assertEquals("not packed yet: left out", "-mod=@CF;@Other", DayZLaunchCommand.build(paths, launch)[1])
    }

    /** The staged PBO folder goes once, where the first PBO row is; without it, PBOs are left out. */
    fun testPboRowsBecomeTheStagedMod() {
        val launch = DayZLaunch(
            diag = false,
            filePatching = false,
            window = false,
            modList = listOf(mod("@CF"), DayZMod("D:\\x\\a.pbo", DayZModKind.PBO), mod("@Mine"), DayZMod("D:\\x\\b.pbo", DayZModKind.PBO)),
        )
        assertEquals("-mod=@CF;S:\\@ExtraPBOs;@Mine", DayZLaunchCommand.build(paths, launch, pboMod = "S:\\@ExtraPBOs")[1])
        assertEquals("-mod=@CF;@Mine", DayZLaunchCommand.build(paths, launch)[1])
    }

    /** Each player is its own command line: its name, and a profile of its own under the configuration's. */
    fun testSeveralPlayers() {
        val alice = DayZPlayer("Alice")
        val bob = DayZPlayer("Bob", profile = "D:\\Bob")
        val launch = DayZLaunch(diag = false, filePatching = false, window = false, profiles = "D:\\Profiles", players = listOf(alice, bob))
        assertEquals(listOf(retailExe, "-profiles=D:\\Profiles\\Alice", "-name=Alice"), DayZLaunchCommand.build(paths, launch, alice))
        assertEquals(listOf(retailExe, "-profiles=D:\\Bob", "-name=Bob"), DayZLaunchCommand.build(paths, launch, bob))
        val one = launch.copy(players = listOf(alice))
        assertEquals("one player keeps the configuration's profile", "-profiles=D:\\Profiles", DayZLaunchCommand.build(paths, one, alice)[1])
    }

    fun testBareModNamesComeFromTheModsFolder() {
        assertEquals("P:\\Mods\\@CF", DayZLaunchCommand.resolveMod("@CF", "P:\\Mods"))
        assertEquals("P:\\Mods\\@CF", DayZLaunchCommand.resolveMod("@CF", "P:\\Mods\\"))
        assertEquals("D:\\Other\\@X", DayZLaunchCommand.resolveMod("D:\\Other\\@X", "P:\\Mods"))
        assertEquals("@CF", DayZLaunchCommand.resolveMod("@CF", " "))
        val launch = DayZLaunch(diag = false, filePatching = false, window = false, modList = listOf(mod("@CF"), mod("@My Mod")), modsFolder = "P:\\Mods")
        assertEquals("-mod=P:\\Mods\\@CF;P:\\Mods\\@My Mod", DayZLaunchCommand.build(paths, launch)[1])
    }

    fun testOfflineClientWithNameAndMission() {
        val launch = DayZLaunch(filePatching = false, window = false, players = listOf(DayZPlayer(" Survivor ")), mission = ".\\Missions\\empty.ChernarusPlus", password = "ignored offline")
        assertEquals(
            listOf(diagExe, "-name=Survivor", "-mission=.\\Missions\\empty.ChernarusPlus"),
            DayZLaunchCommand.build(paths, launch),
        )
    }

    fun testPasswordOnlyWhenJoiningAServer() {
        val launch = DayZLaunch(filePatching = false, window = false, connect = "127.0.0.1", port = 2302, password = "secret")
        assertEquals(listOf(diagExe, "-connect=127.0.0.1", "-port=2302", "-password=secret"), DayZLaunchCommand.build(paths, launch))
    }

    fun testConnectAndMissionTogetherIsAProblem() {
        try {
            DayZLaunchCommand.build(paths, DayZLaunch(connect = "127.0.0.1", mission = "m"))
            fail("built a client that both joins a server and plays offline")
        } catch (e: DayZLaunchCommand.Problem) {
            assertTrue(e.message!!, e.message!!.contains("not both"))
        }
    }

    fun testDrivesUsed() {
        val launch = DayZLaunch(
            modsFolder = "p:\\Mods",
            modList = listOf(mod("@CF"), mod("Q:\\@Other"), mod("R:\\@Unticked", enabled = false)),
            mission = ".\\missions\\x",
            profiles = "F:\\Profile",
            players = listOf(DayZPlayer("A", profile = "G:\\A")),
        )
        assertEquals(setOf('P', 'Q', 'F', 'G'), DayZLaunchCommand.drivesUsed(launch))
    }

    fun testCleanupStaysInItsFolder() {
        val root = Files.createTempDirectory("dayz-cleanup")
        try {
            val profile = Files.createDirectories(root.resolve("profile"))
            listOf("script_1.log", "DayZDiag_x64.RPT", "crash.mdmp", "server.ADM", "keep.txt").forEach { Files.writeString(profile.resolve(it), "x") }
            val nested = Files.createDirectories(profile.resolve("nested"))
            Files.writeString(nested.resolve("deep.log"), "x")
            assertEquals(4, DayZLaunchCleanup.clearLogs(profile).size)
            assertTrue(Files.exists(profile.resolve("keep.txt")))
            assertTrue("only the profile's own files", Files.exists(nested.resolve("deep.log")))

            val mission = Files.createDirectories(root.resolve("mission"))
            Files.createDirectories(mission.resolve("storage_1").resolve("data"))
            Files.createDirectories(mission.resolve("db"))
            assertEquals(1, DayZLaunchCleanup.wipeStorage(mission).size)
            assertFalse(Files.exists(mission.resolve("storage_1")))
            assertTrue(Files.exists(mission.resolve("db")))

            assertEmpty(DayZLaunchCleanup.clearLogs(root.resolve("not created yet")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testCleanupRefusesADriveRoot() {
        try {
            DayZLaunchCleanup.clearLogs(root)
            fail("cleaned a drive root")
        } catch (e: DayZLaunchCommand.Problem) {
            assertTrue(e.message!!, e.message!!.contains("drive root"))
        }
    }

    fun testProfileAndMissionFoldersAreRelativeToTheGame() {
        val command = listOf(diagExe, "-profiles=profiles\\a", "-mission=" + root.resolve("missions").resolve("m"))
        assertEquals(game.resolve("profiles\\a"), DayZLaunchCleanup.profileFolder(command, null))
        assertEquals(root.resolve("missions").resolve("m"), DayZLaunchCleanup.missionFolder(command))
        val fallback = root.resolve("LocalAppData").resolve("DayZ")
        assertEquals(fallback, DayZLaunchCleanup.profileFolder(listOf(diagExe), fallback))
        assertNull(DayZLaunchCleanup.missionFolder(listOf(diagExe)))
    }

    private fun assertEmpty(list: List<*>) = assertTrue(list.toString(), list.isEmpty())

    private fun mod(path: String, enabled: Boolean = true, serverOnly: Boolean = false) = DayZMod(path, DayZModKind.MOD, enabled, serverOnly)
}
