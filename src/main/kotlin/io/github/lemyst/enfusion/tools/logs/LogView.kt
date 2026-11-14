package io.github.lemyst.enfusion.tools.logs

/**
 * Which lines of a log to show, and how: the filtering and collapsing behind the console, kept free
 * of IntelliJ API so that it is tested on plain lists.
 */
object LogView {

    data class Shown(val text: String, val severity: DayZLogLines.Severity)

    private val TIMESTAMP = Regex("""^\s*\d{1,2}:\d{2}:\d{2}(\.\d+)?\s+""")

    /**
     * Renders [lines] from [start]. With [onlyProblems], only errors and warnings are kept, plus the
     * lines of the report that follows an error (a compile summary, a stack trace) up to the next
     * timestamped line. With [collapse], a line repeated back to back, timestamps aside, is shown
     * once and followed by a count.
     *
     * Collapsing looks back across [start], so a repetition that continues in a newly appended
     * chunk is counted with the lines already shown.
     */
    fun render(lines: List<String>, start: Int, onlyProblems: Boolean, collapse: Boolean): List<Shown> {
        val out = ArrayList<Shown>()
        var inReport = false
        var reportSeverity = DayZLogLines.Severity.OTHER
        var previousKey: String? = null
        var repeats = 0
        // The state at `start` depends on the lines before it; replay them without output.
        for (i in lines.indices) {
            val line = lines[i]
            var severity = DayZLogLines.severity(line)
            val timestamped = TIMESTAMP.containsMatchIn(line)
            if (severity == DayZLogLines.Severity.ERROR || severity == DayZLogLines.Severity.WARNING) {
                inReport = true
                reportSeverity = severity
            } else if (timestamped || line.startsWith("----")) {
                inReport = false
            } else if (inReport && severity == DayZLogLines.Severity.OTHER) {
                severity = reportSeverity
            }
            val keep = !onlyProblems || severity == DayZLogLines.Severity.ERROR || severity == DayZLogLines.Severity.WARNING
            if (!keep) continue
            val key = TIMESTAMP.replace(line, "")
            if (collapse && key == previousKey && key.isNotBlank()) {
                repeats++
                continue
            }
            if (i >= start) {
                if (repeats > 0) out += Shown("    (repeated $repeats more ${if (repeats == 1) "time" else "times"})", DayZLogLines.Severity.OTHER)
                out += Shown(line, severity)
            }
            repeats = 0
            previousKey = key
        }
        if (repeats > 0 && lines.size > start) out += Shown("    (repeated $repeats more ${if (repeats == 1) "time" else "times"})", DayZLogLines.Severity.OTHER)
        return out
    }
}
