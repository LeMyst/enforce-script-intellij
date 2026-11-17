package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.check.CheckScripts
import io.github.lemyst.enfusion.tools.detect.DayZLaunch
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZMod
import io.github.lemyst.enfusion.tools.detect.DayZModKind
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.DayZPlayer
import io.github.lemyst.enfusion.tools.logs.ScriptCheckLog
import junit.framework.TestCase

/** Reading a compile verdict from script-log lines shaped like the real ones. */
class EnfusionScriptCheckTest : TestCase() {

    fun testStillCompiling() {
        val lines = listOf(
            "10:55:04.856 SCRIPT       : Module: GameLib; loaded 21x files; 31x classes",
            "10:55:05.100 SCRIPT       : Module: Game; loaded 814x files; 2211x classes",
        )
        assertSame(ScriptCheckLog.Verdict.Pending, ScriptCheckLog.verdict(lines))
        assertSame(ScriptCheckLog.Verdict.Pending, ScriptCheckLog.verdict(emptyList()))
    }

    fun testEveryModuleCompiled() {
        val lines = listOf(
            "10:58:21.590 SCRIPT    (W): @\"scripts/3_Game/human.c,17\": Missing script declaration",
            "10:58:25.626 SCRIPT       : Module: World; loaded 2610x files",
            "10:58:43.045 SCRIPT       : Module: Mission; loaded 610x files",
        )
        assertSame(ScriptCheckLog.Verdict.Compiled, ScriptCheckLog.verdict(lines))
    }

    /** The checking server's own config: file patching allowed, and a mission that does not exist. */
    fun testServerConfig() {
        val config = ScriptCheckLog.SERVER_CONFIG
        assertTrue(config, DayZLaunchCommand.allowsFilePatching(config))
        assertTrue(config, config.contains("template = \"enforce_script_check.none\";"))
    }

    /** The checks start one server or one client of their own on the configuration's mods. */
    fun testCheckLaunches() {
        val root = java.nio.file.Path.of("").toAbsolutePath().root
        val paths = DayZPaths(game = root.resolve("Games").resolve("DayZ"))
        val launch = DayZLaunch(
            startServer = true,
            players = listOf(DayZPlayer(name = "Alice")),
            modList = listOf(DayZMod("P:\\@CF", DayZModKind.MOD, true, false), DayZMod("P:\\@Admin", DayZModKind.MOD, true, true)),
            connect = "10.0.0.1",
            offlineMission = "missions\\x",
        )
        val profile = root.resolve("tmp").resolve("check").toString()
        val config = root.resolve("tmp").resolve("check").resolve("serverDZ.cfg").toString()

        val client = DayZLaunchCommand.buildClient(paths, CheckScripts.launchFor(launch, CheckScripts.Side.CLIENT, profile, config), null)
        assertTrue(client.toString(), client.contains("-profiles=$profile") && client.contains("-window"))
        assertTrue("the client mods, not the server-only one: $client", client.contains("-mod=P:\\@CF"))
        assertFalse(client.toString(), client.any { it.startsWith("-connect") || it.startsWith("-mission") || it.startsWith("-name") || it == "-server" })

        val server = DayZLaunchCommand.buildServer(paths, CheckScripts.launchFor(launch, CheckScripts.Side.SERVER, profile, config))
        assertTrue(server.toString(), server.contains("-config=$config") && server.contains("-profiles=$profile") && server.contains("-port=2402"))
        assertTrue(server.toString(), server.contains("-serverMod=P:\\@Admin"))
    }

    fun testCompileFailure() {
        val lines = listOf(
            "10:55:04.856 SCRIPT       : Module: GameLib; loaded 21x files",
            "10:55:04.979 SCRIPT    (E): @\"MyMod/Scripts/3_Game/settings.c,1\": Unknown type 'Settings'",
            "10:55:04.979 SCRIPT    (E): Can't compile \"Game\" script module!",
            "",
            "MyMod/Scripts/3_Game/settings.c(1): Unknown type 'Settings'",
        )
        val verdict = ScriptCheckLog.verdict(lines) as ScriptCheckLog.Verdict.Failed
        assertEquals("Game", verdict.module)
        assertEquals(
            listOf(
                "10:55:04.979 SCRIPT    (E): @\"MyMod/Scripts/3_Game/settings.c,1\": Unknown type 'Settings'",
                "MyMod/Scripts/3_Game/settings.c(1): Unknown type 'Settings'",
            ),
            verdict.errors,
        )
    }
}
