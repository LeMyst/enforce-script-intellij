package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZApps
import io.github.lemyst.enfusion.tools.detect.DayZDetector
import io.github.lemyst.enfusion.tools.detect.DayZPathChecks
import io.github.lemyst.enfusion.tools.detect.DayZPaths
import io.github.lemyst.enfusion.tools.detect.DayZToolsSettings
import io.github.lemyst.enfusion.tools.detect.KeyValues
import io.github.lemyst.enfusion.tools.detect.SteamCmdInstall
import io.github.lemyst.enfusion.tools.detect.SteamLibraries
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** Steam and DayZ Tools detection, on hand-written fixture text and temporary folders. */
class EnfusionDayZDetectionTest : TestCase() {

    private lateinit var temp: Path

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("dayz-detect")
    }

    override fun tearDown() {
        try {
            temp.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testKeyValuesReadsNestedBlocksQuotesAndComments() {
        val kv = KeyValues.parse(
            """
            // written by Steam
            "AppState"
            {
                "appid"     "221100"
                "name"      "Quoted \"name\" with a \\ backslash"
                bare        value
                "UserConfig" { "language" "english" }
            }
            """.trimIndent()
        )
        val app = kv.block("appstate")!!
        assertEquals("221100", app.string("AppID"))
        assertEquals("Quoted \"name\" with a \\ backslash", app.string("name"))
        assertEquals("value", app.string("bare"))
        assertEquals("english", app.block("UserConfig")!!.string("language"))
        assertNull(app.string("UserConfig"))
    }

    fun testKeyValuesSurvivesTruncation() {
        val kv = KeyValues.parse("\"AppState\" { \"appid\" \"221100\" \"installdir\" \"Da")
        assertEquals("221100", kv.block("AppState")!!.string("appid"))
    }

    fun testLibraryFoldersCurrentLayout() {
        val steam = temp.resolve("Steam")
        val other = temp.resolve("Games/SteamLibrary")
        write(
            steam.resolve("steamapps/libraryfolders.vdf"),
            """
            "libraryfolders"
            {
                "0" { "path" "${escape(steam)}" "apps" { "228980" "1" } }
                "1" { "path" "${escape(other)}" "apps" { "221100" "1" } }
            }
            """.trimIndent()
        )
        assertEquals(listOf(steam.normalize(), other.normalize()), SteamLibraries.libraryFolders(steam))
    }

    fun testLibraryFoldersOlderLayout() {
        val steam = temp.resolve("Steam")
        val other = temp.resolve("SteamLibrary")
        write(
            steam.resolve("steamapps/libraryfolders.vdf"),
            """
            "LibraryFolders"
            {
                "TimeNextStatsReport" "1234"
                "ContentStatsID" "-42"
                "1" "${escape(other)}"
            }
            """.trimIndent()
        )
        assertEquals(listOf(steam.normalize(), other.normalize()), SteamLibraries.libraryFolders(steam))
    }

    fun testLibraryFoldersWithoutVdfIsSteamItself() {
        val steam = temp.resolve("Steam")
        Files.createDirectories(steam)
        assertEquals(listOf(steam.normalize()), SteamLibraries.libraryFolders(steam))
    }

    fun testFindAppNeedsManifestAndFolder() {
        val library = temp.resolve("Lib")
        manifest(library, DayZApps.DAYZ, "DayZ")
        assertNull("manifest without its folder", SteamLibraries.findApp(listOf(library), DayZApps.DAYZ))
        Files.createDirectories(library.resolve("steamapps/common/DayZ"))
        assertEquals(library.resolve("steamapps/common/DayZ"), SteamLibraries.findApp(listOf(library), DayZApps.DAYZ))
        assertNull(SteamLibraries.findApp(listOf(library), DayZApps.DAYZ_TOOLS))
    }

    fun testRegQueryOutput() {
        val output = "\r\nHKEY_CURRENT_USER\\Software\\Valve\\Steam\r\n    SteamPath    REG_SZ    d:/program files (x86)/steam\r\n\r\n"
        assertEquals("d:/program files (x86)/steam", SteamLibraries.parseRegQuery(output, "SteamPath"))
        assertNull(SteamLibraries.parseRegQuery("ERROR: The system was unable to find the specified registry key or value.", "SteamPath"))
    }

    fun testDayZToolsSettings() {
        val settings = DayZToolsSettings.parse(
            """
            [Game]
            user=0
            path=D:\Games\DayZ

            [ProjectDrive]
            user=1
            path=D:\Work Drive
            Letter=p
            """.trimIndent()
        )
        assertEquals("D:\\Work Drive", settings.workDriveSource)
        assertEquals("P", settings.workDriveLetter)
        assertEquals("D:\\Games\\DayZ", settings.gamePath)
        assertNull(DayZToolsSettings.parse("[ProjectDrive]\nLetter=PP").workDriveLetter)
    }

    fun testDetectFindsEverythingAcrossLibraries() {
        val steam = temp.resolve("Steam")
        val second = temp.resolve("SteamLibrary")
        val workDrive = temp.resolve("Work Drive")
        Files.createDirectories(workDrive)
        write(
            steam.resolve("steamapps/libraryfolders.vdf"),
            "\"libraryfolders\" { \"0\" { \"path\" \"${escape(steam)}\" } \"1\" { \"path\" \"${escape(second)}\" } }",
        )
        val tools = manifest(steam, DayZApps.DAYZ_TOOLS, "DayZ Tools", folder = true)
        write(tools.resolve("settings.ini"), "[ProjectDrive]\npath=$workDrive\nLetter=P\n")
        val game = manifest(second, DayZApps.DAYZ, "DayZ", folder = true)
        val server = manifest(second, DayZApps.DAYZ_SERVER, "DayZServer", folder = true)
        val appData = temp.resolve("AppData/Local")
        Files.createDirectories(appData.resolve("DayZ"))

        val paths = DayZDetector.detect(listOf(steam, temp.resolve("missing")), appData)
        assertEquals(game, paths.game)
        assertEquals(server, paths.server)
        assertEquals(tools, paths.tools)
        assertEquals(workDrive, paths.workDriveSource)
        assertEquals("P", paths.workDriveLetter)
        assertEquals(appData.resolve("DayZ"), paths.clientProfile)
        assertNull(paths.serverProfile)
        assertEquals(game.resolve("DayZDiag_x64.exe"), paths.gameDiagExe)
    }

    fun testDetectFallsBackToToolsGamePath() {
        val steam = temp.resolve("Steam")
        val elsewhere = temp.resolve("Elsewhere/DayZ")
        Files.createDirectories(elsewhere)
        val tools = manifest(steam, DayZApps.DAYZ_TOOLS, "DayZ Tools", folder = true)
        write(tools.resolve("settings.ini"), "[Game]\npath=$elsewhere\n")
        assertEquals(elsewhere, DayZDetector.detect(listOf(steam), null).game)
    }

    fun testDetectWithNothingInstalled() {
        assertEquals(DayZPaths(), DayZDetector.detect(listOf(temp.resolve("nowhere")), temp.resolve("nowhere")))
    }

    fun testSteamRootCandidates() {
        val roots = DayZDetector.steamRootCandidates("d:/program files (x86)/steam", "C:\\Program Files (x86)", null)
        assertEquals(2, roots.size)
        assertEquals(Path.of("d:/program files (x86)/steam").normalize(), roots[0])
    }

    /** SteamCMD has no installer and no record: only the folder the plugin downloads into is found. */
    fun testDetectSteamCmdWhereThePluginInstallsIt() {
        val localAppData = Files.createDirectories(temp.resolve("Local"))
        assertNull(DayZDetector.detect(emptyList(), localAppData).steamCmd)
        val folder = Files.createDirectories(localAppData.resolve("SteamCMD"))
        assertNull("an empty folder is not an install", DayZDetector.detect(emptyList(), localAppData).steamCmd)
        Files.createFile(folder.resolve(DayZPaths.STEAMCMD_EXE))
        val detected = DayZDetector.detect(emptyList(), localAppData)
        assertEquals(folder, detected.steamCmd)
        assertEquals(folder.resolve("steamcmd.exe"), detected.steamCmdExe)
        assertEquals(folder, DayZPaths().orElse(detected).steamCmd)
        assertEquals("steamcmd.exe not found in this folder", DayZPathChecks.steamCmd(localAppData))
        assertNull(DayZPathChecks.steamCmd(folder))
    }

    /** Only the archive's root `steamcmd.exe` is written: no other name can reach outside the folder. */
    fun testExtractSteamCmd() {
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            for ((name, bytes) in listOf("../evil.exe" to "x", "sub/steamcmd.exe" to "y", "steamcmd.exe" to "MZ-steamcmd")) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(bytes.toByteArray())
                out.closeEntry()
            }
        }
        val folder = temp.resolve("SteamCMD")
        val exe = SteamCmdInstall.extract(zip.toByteArray().inputStream(), folder)
        assertEquals(folder.resolve("steamcmd.exe"), exe)
        assertEquals("MZ-steamcmd", Files.readString(exe))
        assertEquals(listOf("steamcmd.exe"), Files.list(folder).use { files -> files.map { it.fileName.toString() }.toList() })
        assertFalse(Files.exists(temp.resolve("evil.exe")))

        val empty = java.io.ByteArrayOutputStream().also { java.util.zip.ZipOutputStream(it).close() }
        val other = temp.resolve("Other")
        try {
            SteamCmdInstall.extract(empty.toByteArray().inputStream(), other)
            fail("an archive without steamcmd.exe is refused")
        } catch (_: java.io.IOException) {
        }
        assertFalse("nothing is created for an archive without the executable", Files.exists(other))
    }

    fun testConfiguredPathsWinOverDetected() {
        val configured = DayZPaths(game = Path.of("A"), workDriveLetter = "Q")
        val detected = DayZPaths(game = Path.of("B"), tools = Path.of("T"), workDriveLetter = "P")
        val merged = configured.orElse(detected)
        assertEquals(Path.of("A"), merged.game)
        assertEquals(Path.of("T"), merged.tools)
        assertEquals("Q", merged.workDriveLetter)
    }

    fun testChecks() {
        val game = Files.createDirectories(temp.resolve("DayZ"))
        assertEquals("DayZ_x64.exe not found in this folder", DayZPathChecks.game(game))
        Files.createFile(game.resolve("DayZ_x64.exe"))
        assertNull(DayZPathChecks.game(game))
        assertEquals("Folder not found", DayZPathChecks.game(temp.resolve("nope")))
        val tools = Files.createDirectories(temp.resolve("Tools"))
        assertNotNull(DayZPathChecks.tools(tools))
        Files.createDirectories(tools.resolve("Bin"))
        assertNull(DayZPathChecks.tools(tools))
        assertNull(DayZPathChecks.driveLetter("p"))
        assertNotNull(DayZPathChecks.driveLetter("PP"))
        assertNotNull(DayZPathChecks.driveLetter("1"))
    }

    private fun manifest(library: Path, appId: Int, installDir: String, folder: Boolean = false): Path {
        write(
            library.resolve("steamapps/appmanifest_$appId.acf"),
            "\"AppState\"\n{\n\t\"appid\"\t\t\"$appId\"\n\t\"installdir\"\t\t\"$installDir\"\n}\n",
        )
        val path = library.resolve("steamapps/common/$installDir")
        if (folder) Files.createDirectories(path)
        return path
    }

    private fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    /** Steam doubles the backslashes in the paths it writes. */
    private fun escape(path: Path): String = path.toString().replace("\\", "\\\\")
}
