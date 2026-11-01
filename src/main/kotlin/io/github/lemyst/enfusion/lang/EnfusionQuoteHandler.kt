package io.github.lemyst.enfusion.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import io.github.lemyst.enfusion.psi.EnfusionTypes

/** Double quotes only — the language has no character literals. */
class EnfusionQuoteHandler : SimpleTokenSetQuoteHandler(EnfusionTypes.STRING)
