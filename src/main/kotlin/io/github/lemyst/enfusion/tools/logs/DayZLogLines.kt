package io.github.lemyst.enfusion.tools.logs

/**
 * Reads the lines the game writes to `script_*.log`, `*.RPT` and `crash_*.log`.
 *
 * Three shapes of source location occur, all with a path as the game knows it (from the work
 * drive or a PBO prefix, forward slashes, the case the mod wrote):
 * - `@"scripts/3_Game/human.c,17": message`: warnings and errors in `SCRIPT (W)` / `SCRIPT (E)`
 *   lines;
 * - `scripts/3_Game/human.c(17): message`: the summary under *Can't compile "Game" script module!*;
 * - `scripts/3_Game/human.c:276 Function OnRPC`: a stack trace frame.
 *
 * This package uses no IntelliJ API, so the parsing is tested on hand-written lines.
 */
object DayZLogLines {

    enum class Severity { ERROR, WARNING, SCRIPT, OTHER }

    /** A source location inside a line: [start] to [end] is the text to turn into a link. */
    data class Location(val start: Int, val end: Int, val path: String, val line: Int)

    private val QUOTED = Regex("""@"([^"\r\n]+?\.c),(\d+)"""")
    private val PARENS = Regex("""(?<![\w/.])([\w\-. /\\]+?\.c)\((\d+)\)""")
    private val FRAME = Regex("""^\s*([\w\-. /\\]+?\.c):(\d+)(?=\s+Function\b|\s*$)""")

    /** Every source location in [line], in order. */
    fun locations(line: String): List<Location> {
        val found = ArrayList<Location>()
        for (m in QUOTED.findAll(line)) found += Location(m.range.first, m.range.last + 1, m.groupValues[1], m.groupValues[2].toInt())
        FRAME.find(line)?.let { m ->
            val path = m.groups[1]!!
            found += Location(path.range.first, m.range.last + 1, path.value.trim(), m.groupValues[2].toInt())
        }
        if (found.isEmpty()) {
            for (m in PARENS.findAll(line)) {
                val path = m.groups[1]!!
                val trimmed = path.value.trimStart()
                val start = path.range.first + (path.value.length - trimmed.length)
                found += Location(start, m.range.last + 1, trimmed, m.groupValues[2].toInt())
            }
        }
        return found.sortedBy { it.start }
    }

    /**
     * How serious a line is. `SCRIPT    (E):` and `SCRIPT    (W):` carry it outright; the lines of
     * a crash report or a stack trace that follow an error are [OTHER], and a console colours them
     * by the block they belong to.
     */
    fun severity(line: String): Severity {
        val tag = line.indexOf("SCRIPT")
        if (tag >= 0) {
            val rest = line.substring(tag + 6).trimStart()
            return when {
                rest.startsWith("(E)") -> Severity.ERROR
                rest.startsWith("(W)") -> Severity.WARNING
                else -> Severity.SCRIPT
            }
        }
        if (line.contains("Can't compile") || line.startsWith("Virtual Machine Exception") || line.contains("NULL pointer to instance")) {
            return Severity.ERROR
        }
        return Severity.OTHER
    }

    /** The file kinds the console follows, by the prefix of their names. */
    enum class Kind(val prefix: String, val extension: String, val title: String) {
        SCRIPT("script_", ".log", "Script"),
        RPT("DayZ", ".RPT", "RPT"),
        CRASH("crash_", ".log", "Crash"),
        ERROR("error_", ".log", "Error");

        fun matches(fileName: String): Boolean =
            fileName.startsWith(prefix, ignoreCase = true) && fileName.endsWith(extension, ignoreCase = true)
    }
}
