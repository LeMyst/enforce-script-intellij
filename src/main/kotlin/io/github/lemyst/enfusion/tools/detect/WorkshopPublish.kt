package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Publishing a mod to the Steam Workshop with SteamCMD's `workshop_build_item`, which reads a
 * KeyValues file describing the item. DayZ Tools' Publisher has no command line (it is
 * `Publisher.exe`, WPF, with no argument handling), so SteamCMD is the scriptable way, and the one
 * #61 settles on. No IntelliJ API, so the file and the command are tested on their own.
 *
 * SteamCMD keeps its own Steam login. The plugin passes only the account name; the password and
 * the Steam Guard code are typed into SteamCMD's own console window the first time, and SteamCMD
 * remembers the login after that. The plugin never asks for, sees or stores them.
 */
object WorkshopPublish {

    /** DayZ's Steam app ID, which owns its Workshop. */
    const val DAYZ_APP_ID = "221100"

    /** Workshop visibility, as `workshop_build_item` numbers it. */
    enum class Visibility(val code: Int, val label: String) {
        PUBLIC(0, "Public"),
        FRIENDS(1, "Friends only"),
        PRIVATE(2, "Private"),
        UNLISTED(3, "Unlisted");

        companion object {
            fun ofCode(code: Int?): Visibility? = entries.firstOrNull { it.code == code }
        }
    }

    class Item(
        /** Empty to create a new item: SteamCMD writes the new ID back into the file. */
        val publishedFileId: String?,
        /** The `@MyMod` folder uploaded as the item's content. */
        val contentFolder: Path,
        val previewFile: Path?,
        /** Null keeps the item's current visibility. */
        val visibility: Visibility?,
        /** Null keeps the item's current title and description; a new item needs a title. */
        val title: String?,
        val description: String?,
        val changeNote: String,
    )

    /**
     * The `workshop_build_item` file. A value left null is left out, so an update changes only
     * what is given: title and description written on the Workshop page stay as they are.
     */
    fun vdf(item: Item): String = buildString {
        append("\"workshopitem\"\n{\n")
        fun put(key: String, value: String?) {
            if (value == null) return
            append('\t').append(quote(key)).append('\t').append(quote(value)).append('\n')
        }
        put("appid", DAYZ_APP_ID)
        put("publishedfileid", item.publishedFileId?.trim()?.ifEmpty { null } ?: "0")
        put("contentfolder", item.contentFolder.toString())
        put("previewfile", item.previewFile?.toString())
        put("visibility", item.visibility?.code?.toString())
        put("title", item.title?.trim()?.ifEmpty { null })
        put("description", item.description?.ifEmpty { null })
        put("changenote", item.changeNote)
        append("}\n")
    }

    /** KeyValues quoting: backslashes, quotes and line breaks escaped. */
    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> {}
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /** The item ID in a file SteamCMD has run, or null while it is still 0. */
    fun publishedFileId(vdfText: String): String? =
        KeyValues.parse(vdfText).block("workshopitem")?.string("publishedfileid")?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    /** A Steam account name: letters, digits and `_`, as Steam allows them. */
    fun isAccountName(account: String): Boolean = ACCOUNT.matches(account)

    private val ACCOUNT = Regex("[A-Za-z0-9_]{1,64}")

    /**
     * The batch file run in SteamCMD's own window: SteamCMD, then its exit code written to
     * [result] for the IDE to read, then a pause so that its output can be read before the window
     * closes. `%` is doubled, the one character a batch file would expand in a path.
     */
    fun batch(steamCmd: Path, account: String, vdf: Path, result: Path): String {
        require(isAccountName(account)) { "Not a Steam account name: $account" }
        fun path(p: Path) = "\"" + p.toString().replace("%", "%%") + "\""
        return listOf(
            "@echo off",
            "title SteamCMD: Workshop upload",
            "${path(steamCmd)} +login $account +workshop_build_item ${path(vdf)} +quit",
            "echo %ERRORLEVEL% > ${path(result)}",
            "echo.",
            "echo SteamCMD has finished. This window can be closed.",
            "pause > nul",
        ).joinToString("\r\n", postfix = "\r\n")
    }

    /**
     * The batch file in a console window of its own (`start /wait`), so that the password and
     * Steam Guard can be typed the first time, and the IDE waits for the window to close. `start`
     * takes its first quoted argument as the window's title: the empty one here is written `""` on
     * the command line, so the batch file's own path, quoted when it has spaces, is not taken for it.
     */
    fun commandLine(batch: Path): List<String> = listOf("cmd.exe", "/c", "start", "", "/wait", batch.toString())

    /** SteamCMD's exit code as the batch file wrote it, or null when it did not run to the end. */
    fun exitCode(resultText: String?): Int? = resultText?.trim()?.toIntOrNull()

    /**
     * The change note from a changelog: the first section of a Markdown file, its heading left out,
     * up to the next heading of the same or a higher level. `## 1.2.0` then its list gives the
     * list. A file with no heading gives all of it.
     */
    fun changeNoteFrom(changelog: String): String {
        val lines = changelog.replace("\r\n", "\n").lines()
        val first = lines.indexOfFirst { HEADING.matches(it) }
        if (first < 0) return changelog.trim()
        val level = HEADING.matchEntire(lines[first])!!.groupValues[1].length
        val body = ArrayList<String>()
        for (line in lines.drop(first + 1)) {
            val heading = HEADING.matchEntire(line)
            if (heading != null && heading.groupValues[1].length <= level) break
            body += line
        }
        return body.joinToString("\n").trim()
    }

    private val HEADING = Regex("""^(#{1,6})\s+.*$""")

    /** What is about to be uploaded, for the confirmation shown before anything runs. */
    fun summary(item: Item, sizeBytes: Long, files: Int): String = buildString {
        append(if (item.publishedFileId.isNullOrBlank()) "A new Workshop item" else "Workshop item ${item.publishedFileId}")
        append('\n')
        append("Content: ${item.contentFolder} (${files} files, ${humanSize(sizeBytes)})\n")
        item.visibility?.let { append("Visibility: ${it.label}\n") } ?: append("Visibility: unchanged\n")
        item.title?.takeIf { it.isNotBlank() }?.let { append("Title: $it\n") }
        append("Change note:\n").append(item.changeNote.ifBlank { "(none)" })
    }

    /** The files and bytes under [folder], what SteamCMD will upload. */
    fun contentSize(folder: Path): Pair<Int, Long> {
        if (!Files.isDirectory(folder)) return 0 to 0L
        var files = 0
        var bytes = 0L
        Files.walk(folder).use { stream ->
            stream.filter { it.isRegularFile() }.forEach {
                files++
                bytes += Files.size(it)
            }
        }
        return files to bytes
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes bytes"
    }
}
