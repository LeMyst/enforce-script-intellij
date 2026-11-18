package io.github.lemyst.enfusion.tools.detect

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Installing SteamCMD, Valve's command-line Steam client, which publishing to the Workshop runs.
 * Few modders have it, so the plugin can download Valve's archive and unpack its one executable.
 */
object SteamCmdInstall {

    /** Valve's download for Windows, as the Valve Developer Community's SteamCMD page gives it. */
    const val DOWNLOAD_URL = "https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip"

    /**
     * Where the plugin installs it, and where detection looks: per user, so no administrator
     * rights are needed. SteamCMD keeps its login and updates beside itself, so this is a folder
     * of its own that outlives the IDE's caches.
     */
    fun defaultFolder(localAppData: Path?): Path? = localAppData?.resolve("SteamCMD")

    /**
     * Writes the archive's `steamcmd.exe` into [folder] and returns its path. Only that entry, at
     * the archive's root, is taken: SteamCMD downloads the rest of itself on its first start, and
     * no other entry's name can then write outside [folder]. The file appears complete or not at
     * all, so an interrupted download never leaves a truncated executable to run.
     */
    fun extract(zip: InputStream, folder: Path): Path {
        ZipInputStream(zip).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory || !entry.name.equals(DayZPaths.STEAMCMD_EXE, ignoreCase = true)) continue
                Files.createDirectories(folder)
                val exe = folder.resolve(DayZPaths.STEAMCMD_EXE)
                val partial = folder.resolve(DayZPaths.STEAMCMD_EXE + ".part")
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING)
                Files.move(partial, exe, StandardCopyOption.REPLACE_EXISTING)
                return exe
            }
        }
        throw IOException("The SteamCMD archive has no ${DayZPaths.STEAMCMD_EXE}.")
    }
}
