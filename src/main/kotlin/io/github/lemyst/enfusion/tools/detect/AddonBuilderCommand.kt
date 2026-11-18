package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/** One addon to pack, with Addon Builder's options. */
data class AddonBuild(
    /** The addon folder: the one holding its `config.cpp`. */
    val source: Path,
    /** Where the `.pbo` goes, usually `@MyMod/Addons`. */
    val destination: Path,
    /** `-prefix`; null lets Addon Builder take it from `$PBOPREFIX$` or work it out. */
    val prefix: String? = null,
    /** `-sign`: a `.biprivatekey`. Only its path is ever stored, never the key. */
    val signKey: Path? = null,
    /** `-packonly`: store files as they are instead of binarising. */
    val packOnly: Boolean = false,
    /** `-clear`: empty the addon's temp folder first. */
    val clear: Boolean = true,
    /** `-temp`. */
    val temp: Path? = null,
    /** `-project`, when the addon is part of a larger tree. */
    val project: Path? = null,
    /** `-include`: a file of patterns copied into the PBO as they are. */
    val include: Path? = null,
    /** `-exclude`: a file of patterns left out of the PBO. */
    val exclude: Path? = null,
    /** Include patterns typed in the options, written to a file for `-include` before a build. */
    val includePatterns: List<String>? = null,
    /** Exclude patterns typed in the options, written to a file for `-exclude` before a build. */
    val excludePatterns: List<String>? = null,
) {
    /** Addon Builder names the PBO after the source folder. */
    val pboName: String get() = source.fileName.toString() + ".pbo"
    val pbo: Path get() = destination.resolve(pboName)
}

/**
 * The command line for DayZ Tools' Addon Builder, and reading its output.
 *
 * The options are those of its own help text (`AddonBuilder.exe -help`). Checked by packing a test
 * addon: the PBO is named after the source folder, and the process **exits with code 0 even when
 * the build fails**, so the result is read from its last line, `Build Successful` or
 * `Build failed`, never from the exit code.
 */
object AddonBuilderCommand {

    const val EXE = "AddonBuilder/AddonBuilder.exe"

    enum class Result { SUCCEEDED, FAILED, UNKNOWN }

    fun commandLine(toolsBin: Path, build: AddonBuild): List<String> {
        val args = ArrayList<String>()
        args += toolsBin.resolve(EXE).toString()
        args += build.source.toString()
        args += build.destination.toString()
        if (build.packOnly) args += "-packonly"
        if (build.clear) args += "-clear"
        build.temp?.let { args += "-temp=$it" }
        build.prefix?.takeIf { it.isNotBlank() }?.let { args += "-prefix=${it.trim()}" }
        build.project?.let { args += "-project=$it" }
        build.signKey?.let { args += "-sign=$it" }
        build.include?.let { args += "-include=$it" }
        build.exclude?.let { args += "-exclude=$it" }
        // Otherwise Addon Builder looks DayZ Tools up "from the last use with Steam".
        toolsBin.parent?.let { args += "-toolsDirectory=$it" }
        return args
    }

    /**
     * What a binarised PBO gets as it is when no include list is set. Addon Builder's command line
     * copies nothing but what it binarises without `-include`, so a PBO held `config.bin` alone:
     * no scripts, layouts or stringtable. Its window does copy, from its own `WhiteList` setting:
     * `*.emat;*.edds;*.ptc;*.c;*.imageset;*.layout;*.ogg`. That list loses `stringtable.csv` too,
     * and so the one here adds what the PBOs of 20 Workshop mods ship: stringtables, XML and JSON,
     * styles, fonts, sounds, textures and materials. Checked on a scratch addon: `.paa` and
     * `.rvmat` reached the PBO only when listed. Models are left to binarisation.
     */
    val DEFAULT_INCLUDE = listOf(
        "*.c", "*.layout", "*.imageset", "*.styles", "*.edds", "*.emat", "*.ptc",
        "*.paa", "*.rvmat", "*.ogg", "*.wss", "*.fnt", "*.fxy", "*.jpg",
        "*.csv", "*.xml", "*.json", "*.txt", "*.html", "*.cfg",
    )

    /**
     * An include or exclude list as typed in the options: the patterns themselves, as Addon
     * Builder's window takes them (`*.c;*.layout`), or the path of a list file, as its command line
     * takes. Patterns are told by a wildcard or a `;`: a Windows path cannot hold `*` or `?`, and
     * `*.c` read as a path was once dropped without a word, leaving the default list in its place.
     */
    sealed interface ListSetting {
        data class File(val path: Path) : ListSetting
        data class Patterns(val patterns: List<String>) : ListSetting
    }

    fun listSetting(value: String?): ListSetting? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.any { it == '*' || it == '?' || it == ';' }) {
            // Addon Builder's help: "separator is ';' and ','".
            val patterns = text.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            return if (patterns.isEmpty()) null else ListSetting.Patterns(patterns)
        }
        return runCatching { Path.of(text) }.getOrNull()?.let { ListSetting.File(it) }
    }

    /** [build] with the include and exclude lists typed in the options, [include] and [exclude]. */
    fun withListSettings(build: AddonBuild, include: String?, exclude: String?): AddonBuild {
        val inc = listSetting(include)
        val exc = listSetting(exclude)
        return build.copy(
            include = (inc as? ListSetting.File)?.path,
            includePatterns = (inc as? ListSetting.Patterns)?.patterns,
            exclude = (exc as? ListSetting.File)?.path,
            excludePatterns = (exc as? ListSetting.Patterns)?.patterns,
        )
    }

    /**
     * [build] ready for its command line: its include list written into [folder], and never an
     * `-exclude`.
     *
     * Addon Builder 1.0.240639 crashes on its own `-exclude` before building anything
     * (`ArgumentNullException` in `TextureHeaderBinazrizeTask`), whatever the list holds, DayZ
     * Tools' own `exclude.lst` included, and whatever else is passed. Its `-include`, though, takes
     * exact paths inside the addon as well as wildcards. So an exclude list is applied here: the
     * addon's files that the include list matches, less those the exclude list matches, are
     * written as the include list, one path each. That covers the files copied as they are; what
     * Addon Builder binarises (`config.cpp`, models) is not on either list.
     *
     * With no exclude list, typed patterns are written as they are, and [DEFAULT_INCLUDE] when the
     * build sets no include list and is binarised; a pack-only build stores every file anyway. The
     * file is named after the addon's folder, so that two addons never share one. [files] lists the
     * addon's files, relative, with `\`.
     */
    fun withListFiles(build: AddonBuild, folder: Path, files: (Path) -> List<String> = ::filesIn): AddonBuild {
        val exclude = build.excludePatterns ?: build.exclude?.let { patternsIn(Files.readString(it)) }
        val include = when {
            exclude != null -> {
                val wanted = build.includePatterns ?: build.include?.let { patternsIn(Files.readString(it)) } ?: DEFAULT_INCLUDE
                files(build.source).filter { matchesAny(wanted, it) && !matchesAny(exclude, it) }.map(::asPattern)
            }
            else -> build.includePatterns ?: DEFAULT_INCLUDE.takeIf { build.include == null && !build.packOnly }
        }
        // Only a list file of the user's own, or pack only, and no exclude list: nothing to write.
        if (include == null) return build
        Files.createDirectories(folder)
        val stem = build.source.fileName.toString() + "-" + Integer.toHexString(build.source.toString().lowercase().hashCode())
        val file = folder.resolve("$stem.include.lst")
        Files.writeString(file, include.joinToString(";"))
        return build.copy(include = file, exclude = null, includePatterns = null, excludePatterns = null)
    }

    /**
     * Why [build]'s lists cannot be used, or null: a list file that is not there, which Addon
     * Builder would not report, or an exclude list on a pack-only build, which stores every file
     * whatever its include list says, so the exclude list has nothing to work through.
     */
    fun listProblem(build: AddonBuild, exists: (Path) -> Boolean = Files::isRegularFile): String? {
        listOfNotNull(build.include, build.exclude).firstOrNull { !exists(it) }?.let {
            return "the list file $it does not exist. Type patterns such as *.c;*.layout, or the path of a list file."
        }
        if (build.packOnly && (build.exclude != null || build.excludePatterns != null)) {
            return "a pack-only build stores every file and cannot leave any out: Addon Builder's own exclude list crashes it, and pack only ignores include lists. Clear the exclude list, or binarise."
        }
        return null
    }

    /**
     * The patterns in a list file: one or more a line, separated by `;` or `,`, with a line that
     * starts with `;` a comment, as in DayZ Tools' `exclude.lst`.
     */
    fun patternsIn(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith(";") }
        .flatMap { it.split(';', ',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /**
     * Whether [relative], a path inside the addon written with `\`, matches one of [patterns], as
     * Addon Builder matches its include list: case aside, a pattern matches the path (`data\*`
     * reaches `data\deep\file.txt`) or the file name alone (`notes.txt` in any folder); `*` stands
     * for any run of characters and `?` for one.
     */
    fun matchesAny(patterns: List<String>, relative: String): Boolean {
        val name = relative.substringAfterLast('\\')
        return patterns.any { pattern ->
            val regex = globRegex(pattern.replace('/', '\\'))
            regex.matches(relative) || regex.matches(name)
        }
    }

    private fun globRegex(pattern: String): Regex = Regex(
        pattern.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } },
        RegexOption.IGNORE_CASE,
    )

    /** A path as an include pattern: `,` and `;` would split it, and `?` matches them as well. */
    private fun asPattern(relative: String): String = relative.replace(',', '?').replace(';', '?')

    /** The files under [folder], relative, with `\`, in a stable order. */
    fun filesIn(folder: Path): List<String> = Files.walk(folder).use { paths ->
        paths.filter { Files.isRegularFile(it) }
            .map { folder.relativize(it).joinToString("\\") }
            .sorted()
            .toList()
    }

    /**
     * The lists [build] packs with, as text for the release build's record of an addon: editing a
     * list changes the PBO as much as editing a file does. A list file is read, so a change to its
     * patterns counts, and the default list is spelled out, so a new default rebuilds too.
     */
    fun listsRecord(build: AddonBuild, read: (Path) -> String? = { runCatching { Files.readString(it) }.getOrNull() }): String {
        fun describe(patterns: List<String>?, file: Path?, default: List<String>?): String = when {
            patterns != null -> patterns.joinToString(";")
            file != null -> "$file: " + (read(file) ?: "missing").replace("\r", "").replace('\n', ' ')
            else -> default?.joinToString(";").orEmpty()
        }
        val include = describe(build.includePatterns, build.include, DEFAULT_INCLUDE.takeUnless { build.packOnly })
        return "include=$include\nexclude=${describe(build.excludePatterns, build.exclude, null)}\n"
    }

    /**
     * Windows' path limit, less its terminating NUL. Addon Builder copies each file it packs into
     * its temp folder first, and a copy whose path is longer fails with a bare `Build failed`. A
     * release build whose temp folder sat 200 characters deep, in a sandbox IDE's system folder,
     * failed on every file more than about 60 characters into its addon.
     */
    const val MAX_PATH = 259

    /**
     * A temp folder for Addon Builder under [systemTemp], Windows' own temp folder, which is where
     * Addon Builder works by default. It is short, unlike the IDE's system folder, so deep addon
     * paths stay under [MAX_PATH]. [key] keeps builds apart.
     */
    fun shortTemp(systemTemp: Path, key: String): Path =
        systemTemp.resolve("enfusion-ab").resolve(Integer.toHexString(key.hashCode()))

    /**
     * The file Addon Builder was writing when it stopped, when its path is past [MAX_PATH]: the
     * copy of its last `Syncing file "…"` with no `File sync done` after it, in the `[sync]=`
     * folder it named, or the `[dest]=` of a `Copying PBO` with no `Done.` after it.
     */
    fun tooLongCopy(output: String): String? {
        var folder: String? = null
        var pending: String? = null
        for (line in output.lineSequence()) {
            SYNC_FOLDER.find(line)?.let { folder = it.groupValues[1].trim() }
            SYNCING_FILE.find(line)?.let { file -> folder?.let { pending = it.trimEnd('\\') + "\\" + file.groupValues[1] } }
            COPYING_PBO.find(line)?.let { pending = it.groupValues[1].trim() }
            if (line.contains("File sync done") || line.trimEnd().endsWith("]: Done.")) pending = null
        }
        return pending?.takeIf { it.length > MAX_PATH }
    }

    /** What to say about [tooLongCopy]'s result. */
    fun tooLongMessage(copy: String): String =
        "Addon Builder could not write $copy: ${copy.length} characters, past Windows' limit of 260. " +
            "A shorter path to the addon's files or to the output folder avoids it."

    /**
     * Two lines the Steam API prints on stderr each time Addon Builder starts. They are no error,
     * and shown in red they hid the real ones.
     */
    fun isSteamNoise(text: String): Boolean =
        text.startsWith("Setting breakpad minidump AppID") || text.startsWith("Steam_SetMinidumpSteamID")

    private val SYNC_FOLDER = Regex("""\[sync]=(.+)$""")
    private val SYNCING_FILE = Regex("""Syncing file "(.+)"""")
    private val COPYING_PBO = Regex("""Copying PBO\. \[source]=.*, \[dest]=(.+)$""")

    /** The build's outcome from its output, the last verdict line winning. */
    fun result(output: String): Result {
        var result = Result.UNKNOWN
        for (line in output.lineSequence()) {
            when {
                line.contains("Build Successful", ignoreCase = true) -> result = Result.SUCCEEDED
                line.contains("Build failed", ignoreCase = true) -> result = Result.FAILED
            }
        }
        return result
    }

    /** Whether an output line is an error: `[2026-11-14 01:53:43,507] [ERROR]: ...`. */
    fun isError(line: String): Boolean = line.contains("[ERROR]") || line.contains("[FATAL]")

    /** `File <path>, line <n>:` as CfgConvert reports a config error. */
    val CONFIG_ERROR = Regex("""File (.+?), line (\d+):""")

    /** Whether [dir] is an addon folder: one with a `config.cpp` at its root. */
    fun isAddon(dir: Path): Boolean = Files.isRegularFile(dir.resolve("config.cpp"))

    /**
     * The prefix written in the addon's `$PBOPREFIX$`, or null. The file is usually the prefix
     * alone; some tools write `prefix=...`, which is read too.
     */
    fun prefixOf(dir: Path): String? = runCatching {
        val file = dir.resolve("\$PBOPREFIX\$")
        if (!Files.isRegularFile(file)) return null
        val first = Files.readAllLines(file).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        first.substringAfter("prefix=", first).trim().ifEmpty { null }
    }.getOrNull()

    /**
     * The signature DSSignFile writes next to the PBO for [signKey]: `<pbo>.<key name>.bisign`.
     */
    fun signatureFor(build: AddonBuild, signKey: Path): Path =
        build.destination.resolve("${build.pboName}.${signKey.fileName.toString().substringBeforeLast('.')}.bisign")

    /** The public key a server needs, by the same name, in the mod's `Keys` folder next to `Addons`. */
    fun publicKeyFor(build: AddonBuild, signKey: Path): Path? =
        build.destination.parent?.resolve("Keys")?.resolve(signKey.fileName.toString().substringBeforeLast('.') + ".bikey")
}
