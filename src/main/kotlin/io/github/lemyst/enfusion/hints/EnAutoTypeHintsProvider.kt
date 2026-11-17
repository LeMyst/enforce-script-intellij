package io.github.lemyst.enfusion.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionFile
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * The type an `auto` variable holds, after its name: `auto p: Param1<int> = new Param1<int>(5)`,
 * `foreach (auto zone: Zone : m_Zones)`. The plugin infers it already, for Go to Declaration and
 * completion; this only shows it.
 *
 * Shown after the name, `name: Type`, as the IDE does for languages that write the type after the
 * name: before the name it would read as a second type. Nothing is shown when inference gives up,
 * or when it finds several types that disagree.
 */
class EnAutoTypeHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is EnfusionFile) Collector else null

    private object Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val hint = EnAutoTypeHints.hintFor(element) ?: return
            sink.addPresentation(InlineInlayPosition(hint.offset, relatedToPrevious = true), hintFormat = HintFormat.default) {
                text(hint.text)
            }
        }
    }
}

internal object EnAutoTypeHints {

    class Hint(val offset: Int, val text: String)

    fun hintFor(element: PsiElement): Hint? {
        val type = element.node?.elementType
        if (type !== EnfusionTypes.VAR_DECLARATOR && type !== EnfusionTypes.FOREACH_VAR) return null
        if (!EnTypeInference.isAutoDeclaration(element)) return null
        val name = (element as? EnNamedElement)?.nameIdentifier ?: return null
        val texts = EnTypeInference.typesOfDeclaration(element, element.project)
            .map(EnTypeInference::presentableText)
            .distinct()
        val text = texts.singleOrNull() ?: return null
        return Hint(name.textRange.endOffset, ": $text")
    }
}
