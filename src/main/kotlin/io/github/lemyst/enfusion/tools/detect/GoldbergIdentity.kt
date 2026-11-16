package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * The Steam identity the Goldberg Steam emulator gives a game, written before each client starts.
 *
 * With the real Steam client every local DayZ client is the same Steam user, and the server kicks
 * one when the next joins. Goldberg, a replacement `steam_api64.dll` in the game's folder that the
 * user installs, reads its account name and Steam ID from two text files at start-up instead, so
 * writing different ones before each client gives each its own identity. Mod launch scripts do
 * exactly this: `account_name.txt` and `user_steam_id.txt`, with no line break, in
 * `%APPDATA%\Goldberg SteamEmu Saves\settings`.
 */
object GoldbergIdentity {

    /**
     * How long a client is given to read the files before the next one overwrites them. The game
     * reads them as the emulator loads, within its first seconds; mod scripts leave about three
     * seconds between clients.
     */
    const val SPACING_MS = 4_000L

    /** The first individual-account Steam ID; the others count up from it. */
    private const val STEAM_ID_BASE = 76561197960265728L

    fun settingsFolder(appData: Path): Path = appData.resolve("Goldberg SteamEmu Saves").resolve("settings")

    /** A 64-bit individual-account Steam ID as Goldberg takes it: 17 digits, `7656119…`. */
    fun isSteamId(text: String): Boolean = text.length == 17 && text.all { it.isDigit() } && text.startsWith("7656119")

    /**
     * The Steam ID [explicit] gives, or one derived from [name], so that clients with different
     * player names get different identities without anyone choosing numbers.
     */
    fun steamId(explicit: String?, name: String): String =
        explicit?.trim()?.takeIf { it.isNotEmpty() } ?: (STEAM_ID_BASE + (name.lowercase().hashCode().toLong() and 0x7fffffffL)).toString()

    /** The account name: the player name, or Goldberg's own default when there is none. */
    fun accountName(playerName: String?): String = playerName?.trim()?.takeIf { it.isNotEmpty() } ?: "Player"

    fun write(folder: Path, accountName: String, steamId: String) {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("account_name.txt"), accountName)
        Files.writeString(folder.resolve("user_steam_id.txt"), steamId)
    }

    /** How long to wait at [now] before writing again, the previous identity written at [lastWrite]. */
    fun waitBeforeWriting(lastWrite: Long?, now: Long): Long =
        if (lastWrite == null) 0 else (lastWrite + SPACING_MS - now).coerceAtLeast(0)
}
