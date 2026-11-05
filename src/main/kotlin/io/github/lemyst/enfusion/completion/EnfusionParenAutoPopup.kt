package io.github.lemyst.enfusion.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.lemyst.enfusion.lang.EnfusionLanguage

/**
 * Opens parameter info when `(` is typed, as other languages do.
 *
 * The platform leaves this to each language's typed handler. Whether the hint really appears is
 * still the user's setting (*Editor | General | Code Completion | Show the parameter info popup*),
 * and the handler itself decides whether the `(` opened a call it knows.
 */
class EnfusionParenAutoPopup : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c == '(' && file.language == EnfusionLanguage) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        }
        return Result.CONTINUE
    }
}
