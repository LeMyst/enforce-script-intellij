package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCleanup
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
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
            DayZLaunchCommand.buildClient(paths, launch),
        )
    }

    fun testRetailClientWithoutOptions() {
        val launch = DayZLaunch(diag = false, filePatching = false, window = false)
        assertEquals(listOf(retailExe), DayZLaunchCommand.buildClient(paths, launch))
    }

    fun testDedicatedServerUsesTheServerProfileFromSettings() {
        val launch = DayZLaunch(
            startServer = true,
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
            DayZLaunchCommand.buildServer(paths, launch),
        )
    }

    fun testDiagServer() {
        val launch = DayZLaunch(startServer = true, mission = "P:\\missions\\dayzOffline.chernarusplus", serverProfile = "D:\\P", profiles = "D:\\Clients")
        val command = DayZLaunchCommand.buildServer(paths, launch)
        assertEquals(listOf(diagExe, "-server"), command.take(2))
        assertTrue(command.contains("-mission=P:\\missions\\dayzOffline.chernarusplus"))
        assertTrue("the server's own profile, not the clients'", command.contains("-profiles=D:\\P"))
    }

    /**
     * One configuration, one mod list: the server loads every ticked mod and its server-only ones as
     * -serverMod=; the clients load the same mods without the server-only ones, and join the server
     * on this machine, without the mission the server plays.
     */
    fun testServerAndClientsShareTheModList() {
        val launch = DayZLaunch(
            startServer = true,
            filePatching = false,
            window = false,
            modList = listOf(mod("@CF"), mod("@Admin", serverOnly = true), DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON)),
            mission = "P:\\missions\\m",
        )
        val serverCommand = DayZLaunchCommand.buildServer(paths, launch, addonMod = "S:\\@MyMod")
        assertTrue(serverCommand.contains("-mod=@CF;S:\\@MyMod"))
        assertTrue(serverCommand.contains("-serverMod=@Admin"))
        assertEquals(
            listOf(diagExe, "-mod=@CF;S:\\@MyMod", "-filePatching", "-connect=127.0.0.1", "-port=2302"),
            DayZLaunchCommand.buildClient(paths, launch, addonMod = "S:\\@MyMod"),
        )
        val ownPort = launch.copy(port = 2402, connect = "10.0.0.5")
        assertEquals(
            "a server started here wins over Connect to",
            listOf("-connect=127.0.0.1", "-port=2402"),
            DayZLaunchCommand.buildClient(paths, ownPort).takeLast(2),
        )
    }

    /** A generated mod whose rows are all server only goes to the server's -serverMod= and to no client. */
    fun testServerOnlyGeneratedMod() {
        val launch = DayZLaunch(startServer = true, filePatching = false, window = false, modList = listOf(DayZMod("D:\\x\\a.pbo", DayZModKind.PBO, serverOnly = true)))
        assertTrue(DayZLaunchCommand.buildServer(paths, launch, pboMod = "S:\\@ExtraPBOs").contains("-serverMod=S:\\@ExtraPBOs"))
        assertFalse(DayZLaunchCommand.buildClient(paths, launch, pboMod = "S:\\@ExtraPBOs").any { it.startsWith("-mod=") })
    }

    fun testExtraArgumentsKeepQuotedSpaces() {
        assertEquals(listOf("-a", "b c", "-d="), DayZLaunchCommand.splitArguments("-a \"b c\"  -d=\"\""))
        assertEquals(emptyList<String>(), DayZLaunchCommand.splitArguments("   "))
        val launch = DayZLaunch(diag = false, filePatching = false, window = false, extraArguments = "-nosplash -name=\"Test Player\"")
        assertEquals(listOf(retailExe, "-nosplash", "-name=Test Player"), DayZLaunchCommand.buildClient(paths, launch))
    }

    fun testMissingFolderIsAProblem() {
        try {
            DayZLaunchCommand.buildClient(DayZPaths(), DayZLaunch())
            fail("built without a DayZ folder")
        } catch (e: DayZLaunchCommand.Problem) {
            assertEquals("The DayZ folder is not set.", e.message)
        }
        try {
            DayZLaunchCommand.buildServer(paths.copy(server = null), DayZLaunch(startServer = true, diag = false))
            fail("built without a server folder")
        } catch (e: DayZLaunchCommand.Problem) {
            assertEquals("The DayZ Server folder is not set.", e.message)
        }
    }

    fun testStoredOptionsBecomeALaunch() {
        val options = io.github.lemyst.enfusion.tools.run.DayZRunOptions()
        options.startServer = true
        options.port = " 2402 "
        options.connect = "  "
        options.extraArguments = "-dologs"
        options.setModRows(listOf(DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON, serverOnly = true)))
        val launch = options.toLaunch()
        assertTrue(launch.startServer)
        assertTrue(launch.startClients)
        assertEquals(listOf(DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON, serverOnly = true)), launch.modList)
        assertEquals(2402, launch.port)
        assertNull(launch.connect)
        assertEquals("clients start 5 s after the server by default", 5, launch.clientDelaySeconds)
    }

    /** A configuration saved when the server and the clients had a kind each opens as one of that side. */
    fun testSeparateKindsAreMigrated() {
        val server = io.github.lemyst.enfusion.tools.run.DayZRunOptions()
        server.mode = "SERVER"
        server.profiles = "D:\\ServerProfile"
        val launch = server.toLaunch()
        assertTrue(launch.startServer)
        assertFalse(launch.startClients)
        assertEquals("its profile is the server's", "D:\\ServerProfile", launch.serverProfile)
        assertNull(launch.profiles)
        assertNull("migrated once", server.mode)

        val buldozer = io.github.lemyst.enfusion.tools.run.DayZRunOptions()
        buldozer.mode = "BULDOZER"
        assertFalse(buldozer.toLaunch().startServer)
        assertTrue(buldozer.toLaunch().startClients)
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
            DayZLaunchCommand.buildClient(paths, launch, addonMod = "S:\\@MyMod"),
        )
        assertEquals("not packed yet: left out", "-mod=@CF;@Other", DayZLaunchCommand.buildClient(paths, launch)[1])
    }

    /** The staged PBO folder goes once, where the first PBO row is; without it, PBOs are left out. */
    fun testPboRowsBecomeTheStagedMod() {
        val launch = DayZLaunch(
            diag = false,
            filePatching = false,
            window = false,
            modList = listOf(mod("@CF"), DayZMod("D:\\x\\a.pbo", DayZModKind.PBO), mod("@Mine"), DayZMod("D:\\x\\b.pbo", DayZModKind.PBO)),
        )
        assertEquals("-mod=@CF;S:\\@ExtraPBOs;@Mine", DayZLaunchCommand.buildClient(paths, launch, pboMod = "S:\\@ExtraPBOs")[1])
        assertEquals("-mod=@CF;@Mine", DayZLaunchCommand.buildClient(paths, launch)[1])
    }

    /** Each player is its own command line: its name, and a profile of its own under the configuration's. */
    fun testSeveralPlayers() {
        val alice = DayZPlayer("Alice")
        val bob = DayZPlayer("Bob", profile = "D:\\Bob")
        val launch = DayZLaunch(diag = false, filePatching = false, window = false, profiles = "D:\\Profiles", players = listOf(alice, bob))
        assertEquals(listOf(retailExe, "-profiles=D:\\Profiles\\Alice", "-name=Alice"), DayZLaunchCommand.buildClient(paths, launch, alice))
        assertEquals(listOf(retailExe, "-profiles=D:\\Bob", "-name=Bob"), DayZLaunchCommand.buildClient(paths, launch, bob))
        val one = launch.copy(players = listOf(alice))
        assertEquals("one player keeps the configuration's profile", "-profiles=D:\\Profiles", DayZLaunchCommand.buildClient(paths, one, alice)[1])
    }

    fun testBareModNamesComeFromTheModsFolder() {
        assertEquals("P:\\Mods\\@CF", DayZLaunchCommand.resolveMod("@CF", "P:\\Mods"))
        assertEquals("P:\\Mods\\@CF", DayZLaunchCommand.resolveMod("@CF", "P:\\Mods\\"))
        assertEquals("D:\\Other\\@X", DayZLaunchCommand.resolveMod("D:\\Other\\@X", "P:\\Mods"))
        assertEquals("@CF", DayZLaunchCommand.resolveMod("@CF", " "))
        val launch = DayZLaunch(diag = false, filePatching = false, window = false, modList = listOf(mod("@CF"), mod("@My Mod")), modsFolder = "P:\\Mods")
        assertEquals("-mod=P:\\Mods\\@CF;P:\\Mods\\@My Mod", DayZLaunchCommand.buildClient(paths, launch)[1])
    }

    fun testOfflineClientWithNameAndMission() {
        val launch = DayZLaunch(filePatching = false, window = false, players = listOf(DayZPlayer(" Survivor ")), mission = ".\\Missions\\empty.ChernarusPlus", password = "ignored offline")
        assertEquals(
            listOf(diagExe, "-name=Survivor", "-mission=.\\Missions\\empty.ChernarusPlus"),
            DayZLaunchCommand.buildClient(paths, launch),
        )
    }

    fun testPasswordOnlyWhenJoiningAServer() {
        val launch = DayZLaunch(filePatching = false, window = false, connect = "127.0.0.1", port = 2302, password = "secret")
        assertEquals(listOf(diagExe, "-connect=127.0.0.1", "-port=2302", "-password=secret"), DayZLaunchCommand.buildClient(paths, launch))
    }

    fun testConnectAndMissionTogetherIsAProblem() {
        try {
            DayZLaunchCommand.buildClient(paths, DayZLaunch(connect = "127.0.0.1", mission = "m"))
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
