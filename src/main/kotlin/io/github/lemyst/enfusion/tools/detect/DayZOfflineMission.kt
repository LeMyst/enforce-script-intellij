package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether a mission can be played offline by a client. Its `init.c` decides which mission class the
 * game runs: `MissionGameplay` creates and selects the player's character offline, but a server
 * mission (every `dayzOffline.*` one) returns `new CustomMission()`, a `MissionServer`, whatever the
 * session. Started offline, such a mission never selects a character and the client sits on the
 * "IDLE MODE ACTIVE" banner, with `[IdleMode] Entering IN` in its RPT and nothing after.
 */
object DayZOfflineMission {

    /**
     * Why [initC] would leave an offline client idle, or null: it defines `CreateCustomMission`
     * and never makes a `MissionGameplay`. Without its own `CreateCustomMission` the game's default
     * is used, which runs `MissionGameplay` offline. Comments are left out.
     */
    fun problem(initC: String): String? {
        val code = withoutComments(initC)
        if (!Regex("""\bCreateCustomMission\s*\(""").containsMatchIn(code)) return null
        if (Regex("""\bMissionGameplay\b""").containsMatchIn(code)) return null
        return "Its init.c only ever creates a server mission (such as CustomMission), so an offline client never gets a character " +
            "and stays in IDLE MODE. Use a mission whose CreateCustomMission returns new MissionGameplay() when not on a server."
    }

    /** [problem] for the mission folder [mission], relative to [gameFolder] when relative; null when its init.c cannot be read. */
    fun problemOf(mission: String, gameFolder: Path?): String? {
        val folder = runCatching { Path.of(mission.trim()) }.getOrNull() ?: return null
        val absolute = if (folder.isAbsolute) folder else gameFolder?.resolve(folder) ?: return null
        val text = runCatching { Files.readString(absolute.resolve("init.c")) }.getOrNull() ?: return null
        return problem(text)
    }

    private fun withoutComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").lineSequence().joinToString("\n") { it.substringBefore("//") }
}
