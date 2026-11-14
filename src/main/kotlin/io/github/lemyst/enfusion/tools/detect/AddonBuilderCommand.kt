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
