package io.github.lemyst.enfusion.tools.logs

/**
 * Whether a starting game has compiled its scripts, read from its `script_*.log`.
 *
 * The engine compiles the modules in order and logs `SCRIPT : Module: Game; loaded …` for each; the
 * last one, `Mission`, means every module compiled. A failure is
 * `SCRIPT (E): Can't compile "Game" script module!`, followed by the errors as
 * `path.c(line): message`. Both shapes are those of the real client log.
 */
object ScriptCheckLog {

    sealed class Verdict {
        object Pending : Verdict()
        object Compiled : Verdict()

        /** [module] is the one named in *Can't compile*; [errors] are the error lines with a location. */
        class Failed(val module: String, val errors: List<String>) : Verdict()
    }

    /**
     * The config of the checking server. A server without one quits after ten seconds, possibly
     * before the scripts are compiled. Its mission names nothing that exists: the server is
     * stopped once the scripts compile, before it would load a mission, and a real one would have
     * its storage written to.
     */
    val SERVER_CONFIG = """
        // Written by Enforce Script's Check Scripts, for a server stopped once its scripts compile.
        hostname = "Enforce Script check";
        maxPlayers = 1;
        verifySignatures = 0;
        allowFilePatching = 1;
        class Missions
        {
        	class DayZ
        	{
        		template = "enforce_script_check.none";
        	};
        };
    """.trimIndent() + "\n"

    private val CANT_COMPILE = Regex("""Can't compile "(\w+)" script module""")

    fun verdict(lines: List<String>): Verdict {
        var failedModule: String? = null
        val errors = LinkedHashSet<String>()
        for (line in lines) {
            CANT_COMPILE.find(line)?.let { failedModule = it.groupValues[1] }
            val severity = DayZLogLines.severity(line)
            if (DayZLogLines.locations(line).isNotEmpty() && (severity == DayZLogLines.Severity.ERROR || failedModule != null)) {
                errors += line.trim()
            }
            if (failedModule == null && line.contains("Module: Mission;")) return Verdict.Compiled
        }
        val module = failedModule ?: return Verdict.Pending
        return Verdict.Failed(module, errors.toList())
    }
}
