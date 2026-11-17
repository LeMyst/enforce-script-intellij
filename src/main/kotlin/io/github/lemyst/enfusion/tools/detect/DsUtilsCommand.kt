package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Path

/**
 * DayZ Tools' signing utilities, as their usage texts give them:
 * - `DSSignFile private_key file_to_sign [-v2]` writes `<file>.<key>.bisign` next to the file, a
 *   v3 signature unless `-v2`;
 * - `dsCheckSignatures checked_dir keys_dir` checks every signature in a folder against the keys in
 *   another, one line per PBO: `Signature … is OK`, `is wrong`, `Key not found for signature …`,
 *   `No signature found for …`.
 *
 * A release build signs with DSSignFile after naming each PBO, rather than through Addon Builder,
 * so that every signature is made for the file name the mod ships, then checks the whole folder.
 */
object DsUtilsCommand {

    const val SIGN_FILE = "DsUtils/DSSignFile.exe"
    const val CHECK_SIGNATURES = "DsUtils/DSCheckSignatures.exe"

    fun sign(toolsBin: Path, privateKey: Path, file: Path): List<String> =
        listOf(toolsBin.resolve(SIGN_FILE).toString(), privateKey.toString(), file.toString())

    fun check(toolsBin: Path, checked: Path, keys: Path): List<String> =
        listOf(toolsBin.resolve(CHECK_SIGNATURES).toString(), checked.toString(), keys.toString())

    /** The lines of a check's output that report a problem: anything about a signature but "is OK". */
    fun problems(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { line ->
            line.contains("is wrong", ignoreCase = true) ||
                line.contains("Key not found", ignoreCase = true) ||
                line.contains("No signature found", ignoreCase = true) ||
                line.contains("not loaded", ignoreCase = true)
        }
        .toList()
}
