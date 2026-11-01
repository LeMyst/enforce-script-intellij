package io.github.lemyst.enfusion.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElementBuilder
import io.github.lemyst.enfusion.cfg.EnfusionConfigDefines
import io.github.lemyst.enfusion.lexer.EnfusionDefines
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Completion for the flag in `#ifdef` / `#ifndef`.
 *
 * Worth having because a mistyped flag is silent in both directions: `#ifdef DAYZ_BATTLEROYAL` never
 * matches and the guarded code simply never compiles in, with nothing reported at build time or
 * after. The offered set is the engine's own list plus every flag the project's own `config.cpp`
 * files declare in `defines[]`, which is the only place a mod's flags exist at all.
 *
 * A directive is lexed as one token running to end of line, so this cannot key off a PSI element for
 * the flag — there is none. It matches on the token's text up to the caret instead, and supplies its
 * own prefix so the platform filters against the partial flag rather than against the `#`.
 */
class EnfusionDirectiveCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val element = parameters.position
        if (element.node?.elementType !== EnfusionTypes.PP_DIRECTIVE) return

        val offsetInToken = parameters.offset - element.textRange.startOffset
        if (offsetInToken < 0 || offsetInToken > element.textLength) return
        val head = element.text.substring(0, offsetInToken)
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
        val match = DIRECTIVE.find(head) ?: return

        val prefixed = result.withPrefixMatcher(match.groupValues[2])
        val project = element.project
        for (flag in EnfusionDefines.KNOWN + EnfusionConfigDefines.harvest(project)) {
            prefixed.addElement(LookupElementBuilder.create(flag))
        }
        prefixed.stopHere()
    }

    private companion object {
        /** `#ifdef FOO` with the caret anywhere in `FOO`, including at its start. */
        val DIRECTIVE = Regex("""#\s*(ifdef|ifndef)\s+(\w*)$""")
    }
}
