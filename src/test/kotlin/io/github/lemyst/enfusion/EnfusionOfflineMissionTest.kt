package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZOfflineMission
import junit.framework.TestCase
import java.nio.file.Files

/** Telling a mission a client can play offline from a server mission that leaves it idle. */
class EnfusionOfflineMissionTest : TestCase() {

    /** As every dayzOffline.* mission ends: a MissionServer, whatever the session. */
    private val serverMission = """
        void main() { CreateHive(); }
        class CustomMission : MissionServer {}
        Mission CreateCustomMission(string path)
        {
            return new CustomMission();
        }
    """.trimIndent()

    /** A mission made for offline testing: MissionGameplay unless on a real multiplayer server. */
    private val offlineMission = """
        void main() {}
        Mission CreateCustomMission(string path)
        {
            if (GetGame().IsMultiplayer() && GetGame().IsServer())
                return new MissionServer();
            return new MissionGameplay();
        }
    """.trimIndent()

    fun testServerMissionLeavesAnOfflineClientIdle() {
        assertNotNull(DayZOfflineMission.problem(serverMission))
        assertNull(DayZOfflineMission.problem(offlineMission))
    }

    /** No CreateCustomMission of its own: the game's default runs MissionGameplay offline. */
    fun testDefaultFactoryIsFine() {
        assertNull(DayZOfflineMission.problem("void main() {}"))
    }

    /** MissionGameplay named only in a comment does not make a server mission playable. */
    fun testCommentsDoNotCount() {
        assertNotNull(DayZOfflineMission.problem("// not a MissionGameplay\n/* MissionGameplay */\n$serverMission"))
    }

    fun testMissionFolderRelativeToTheGame() {
        val game = Files.createTempDirectory("game")
        try {
            val mission = Files.createDirectories(game.resolve("Missions").resolve("server.ChernarusPlus"))
            Files.writeString(mission.resolve("init.c"), serverMission)
            assertNotNull(DayZOfflineMission.problemOf("Missions/server.ChernarusPlus", game))
            assertNotNull(DayZOfflineMission.problemOf(mission.toString(), null))
            assertNull("no init.c to read: nothing said", DayZOfflineMission.problemOf("Missions/missing", game))
        } finally {
            game.toFile().deleteRecursively()
        }
    }
}
