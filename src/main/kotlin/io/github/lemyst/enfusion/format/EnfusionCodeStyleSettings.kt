package io.github.lemyst.enfusion.format

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings

/**
 * Code style options specific to EnfusionScript.
 *
 * Exactly one so far, and it exists because the corpus does not settle the question. Vanilla writes
 * preprocessor directives both ways — 143 indented against 29 at column 0 in `dayzgame.c` alone — so
 * whichever the formatter picked would be wrong for somebody's file. It is a preference, so it is a
 * setting.
 */
class EnfusionCodeStyleSettings(container: CodeStyleSettings) :
    CustomCodeStyleSettings("EnfusionCodeStyleSettings", container) {

    /**
     * Whether `#ifdef`, `#else` and `#endif` are pinned to the first column rather than indented with
     * the code around them.
     *
     * Defaults to true, the C-family convention, and the more useful one for reading: a directive at
     * column 0 is visible at a glance as a *compile-time* boundary rather than blending into the
     * runtime control flow it wraps.
     *
     * Only the directive line itself moves. Code inside the region — including an inactive branch —
     * is indented normally, because it is still code.
     */
    @JvmField
    var PREPROCESSOR_AT_FIRST_COLUMN: Boolean = true
}
