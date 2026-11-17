package io.github.lemyst.enfusion.nav

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverriders

/**
 * Go to Implementation (`Ctrl+Alt+B`) and Quick Definition: the same list as the downward gutter
 * icon, from a declaration or from a reference to one. A class gives its `modded class` layers
 * and its subclasses; a method, its redeclarations in the layers and its overrides below.
 */
class EnImplementationsSearch : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {

    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        val targets = when (val element = parameters.element) {
            is EnClassDeclMixin -> EnOverriders.below(element).let { it.layers + it.subclasses }
            is EnMethodDeclMixin -> EnOverriders.overridersOf(element)
            else -> return
        }
        val scope = parameters.scope
        for (target in targets) {
            val file = target.containingFile?.virtualFile
            if (file != null && !scope.contains(file)) continue
            if (!consumer.process(target)) return
        }
    }
}
