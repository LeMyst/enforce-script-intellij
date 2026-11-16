package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.GoldbergIdentity
import junit.framework.TestCase
import java.nio.file.Files

/** The Goldberg Steam emulator identity a client is given before it starts. */
class EnfusionGoldbergTest : TestCase() {

    fun testSteamIds() {
        assertTrue(GoldbergIdentity.isSteamId("76561198000000001"))
        assertFalse("too short", GoldbergIdentity.isSteamId("7656119800000000"))
        assertFalse("not an individual account", GoldbergIdentity.isSteamId("12345678901234567"))
        assertEquals("76561198000000001", GoldbergIdentity.steamId(" 76561198000000001 ", "Alice"))
    }

    /** Clients told apart only by their player names still get distinct, valid, stable IDs. */
    fun testDerivedSteamIds() {
        val alice = GoldbergIdentity.steamId(null, "Alice")
        val bob = GoldbergIdentity.steamId("", "Bob")
        assertTrue(GoldbergIdentity.isSteamId(alice))
        assertTrue(GoldbergIdentity.isSteamId(bob))
        assertFalse(alice == bob)
        assertEquals("the same name, the same identity", alice, GoldbergIdentity.steamId(null, "alice"))
        assertEquals("Player", GoldbergIdentity.accountName(" "))
    }

    /** As mod launch scripts write them: no line break, which Goldberg would keep in the name. */
    fun testFilesHaveNoLineBreak() {
        val appData = Files.createTempDirectory("appdata")
        try {
            val folder = GoldbergIdentity.settingsFolder(appData)
            GoldbergIdentity.write(folder, "Alice", "76561198000000001")
            assertEquals(appData.resolve("Goldberg SteamEmu Saves").resolve("settings"), folder)
            assertEquals("Alice", Files.readString(folder.resolve("account_name.txt")))
            assertEquals("76561198000000001", Files.readString(folder.resolve("user_steam_id.txt")))
        } finally {
            appData.toFile().deleteRecursively()
        }
    }

    fun testSpacingBetweenClients() {
        assertEquals("the first client starts at once", 0L, GoldbergIdentity.waitBeforeWriting(null, 10_000))
        assertEquals(GoldbergIdentity.SPACING_MS - 1_000, GoldbergIdentity.waitBeforeWriting(10_000, 11_000))
        assertEquals(0L, GoldbergIdentity.waitBeforeWriting(10_000, 10_000 + GoldbergIdentity.SPACING_MS + 1))
    }
}
