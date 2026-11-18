package io.github.lemyst.enfusion.nav

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnClassHierarchy
import io.github.lemyst.enfusion.resolve.EnOverrideResolution

/**
 * Go to Super Method (`Ctrl+U`): from a method, what it overrides, the way the upward gutter icon
 * and Ctrl+Click on its name already go; from anywhere else in a class, the class it extends or,
 * for a `modded class`, the class it patches.
 */
class EnGotoSuperHandler : CodeInsightActionHandler {

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (DumbService.isDumb(project)) return
        val targets = targetsAt(file, editor.caretModel.offset)
        if (targets.isEmpty()) return
        PsiTargetNavigator(targets).navigate(editor, "Choose Super Declaration")
    }

    companion object {
        /**
         * The method the caret is in, unless the caret is inside its body: `Ctrl+U` on a line of
         * a method body still means that method, as in Java.
         */
        fun targetsAt(file: PsiFile, offset: Int): List<PsiElement> {
            val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return emptyList()
            val method = PsiTreeUtil.getParentOfType(leaf, EnMethodDeclMixin::class.java, false)
            if (method != null) return EnOverrideResolution.superDeclarationsOf(method)
            val cls = PsiTreeUtil.getParentOfType(leaf, EnClassDeclMixin::class.java, false) ?: return emptyList()
            return EnClassHierarchy.supertypesOf(cls)
        }
    }
}
