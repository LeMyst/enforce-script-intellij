package io.github.lemyst.enfusion.lexer

/**
 * Preprocessor flags. The authoritative engine list is `enum StaticDefines` in
 * `P:\scripts\staticdefinesdoc.c`, plus the extras declared in `P:\scripts\1_core\defines.c`.
 *
 * A project's *own* flags are not here and cannot be: they exist only in `defines[]` inside a
 * `config.cpp`. [io.github.lemyst.enfusion.cfg.EnfusionConfigDefines] reads those, and the two sets
 * are unioned for completion by
 * [io.github.lemyst.enfusion.lang.EnfusionDirectiveCompletionContributor].
 */
object EnfusionDefines {

    /** Every flag the plugin knows about, offered for completion inside `#ifdef`. */
    val KNOWN: Set<String> = setOf(
        // staticdefinesdoc.c
        "DEVELOPER", "RELEASE", "PREVIEW_BUILD", "PLATFORM_WINDOWS", "PLATFORM_MACOSX",
        "ENF_LINUX", "PLATFORM_CONSOLE", "PLATFORM_XBOX", "PLATFORM_MSSTORE", "PLATFORM_PS4",
        "X1_TODO_TEMP_GUI", "BULDOZER", "SERVER", "NO_GUI", "NO_GUI_INGAME", "WORKBENCH",
        "GAME_TEMPLATE", "ENF_DONE", "COMPONENT_SYSTEM", "DOXYGEN",
        "_DAYZ_CREATURE_DEBUG_SHADOW", "BOT", "PS3",
        // 1_core/defines.c and observed usage
        "DAYZ_X_XX", "ENABLE_LOGGING", "LOG_TO_FILE", "LOG_TO_SCRIPT", "LOG_TO_RPT",
        "FEATURE_CURSOR", "FEATURE_NETWORK_RECONCILIATION", "DIAG", "DIAG_DEVELOPER",
        "BUILD_EXPERIMENTAL", "SERVER_FOR_WINDOWS", "SERVER_FOR_X1", "SERVER_FOR_PS4",
        "SERVER_FOR_CONSOLE", "PLATFORM_LINUX", "AUTOTEST",
    )

    /**
     * What is considered "on" until the user configures otherwise.
     *
     * This only matters for the handful of `#ifdef` regions whose branches are individually
     * brace-unbalanced — everywhere else both branches are kept regardless (see
     * [EnfusionPreprocessingLexer]), so a wrong guess here costs resolution inside one branch, never
     * a parse.
     */
    val DEFAULT: Set<String> = setOf("PLATFORM_WINDOWS")
}
