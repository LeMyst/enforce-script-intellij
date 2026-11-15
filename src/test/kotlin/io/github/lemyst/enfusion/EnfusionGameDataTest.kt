package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.ExtractGameData
import io.github.lemyst.enfusion.tools.detect.GameData
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** The extracted-data check and WorkDrive's extract command, without extracting anything. */
class EnfusionGameDataTest : TestCase() {

    fun testMissingFolders() {
        val drive = Files.createTempDirectory("work-drive")
        try {
            assertEquals(listOf("scripts", "DZ", "gui"), GameData.missing(drive))
            Files.createDirectories(drive.resolve("scripts"))
            Files.createDirectories(drive.resolve("DZ"))
            assertEquals(listOf("gui"), GameData.missing(drive))
            Files.createDirectories(drive.resolve("gui"))
            assertTrue(GameData.missing(drive).isEmpty())
        } finally {
            drive.toFile().deleteRecursively()
        }
    }

    /** Quiet when the drive is not mounted (the work-drive notification's case) and after opting out. */
    fun testWhenToAsk() {
        assertTrue(ExtractGameData.shouldAsk(driveMounted = true, missing = listOf("scripts"), optedOut = false))
        assertFalse(ExtractGameData.shouldAsk(driveMounted = false, missing = listOf("scripts"), optedOut = false))
        assertFalse(ExtractGameData.shouldAsk(driveMounted = true, missing = listOf("scripts"), optedOut = true))
        assertFalse(ExtractGameData.shouldAsk(driveMounted = true, missing = emptyList(), optedOut = false))
    }

    /** Paths built from the current root, so that the test parses the same on Linux CI. */
    fun testExtractCommand() {
        val root = Path.of("").toAbsolutePath().root
        val bin = root.resolve("Tools").resolve("Bin")
        val game = root.resolve("Games").resolve("DayZ")
        val drive = root.resolve("work")
        assertEquals(
            listOf(bin.resolve("WorkDrive").resolve("WorkDrive.exe").toString(), "/extractGameData", game.toString(), drive.toString()),
            GameData.extractCommand(bin, game, drive),
        )
    }
}
