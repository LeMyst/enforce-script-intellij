package io.github.lemyst.enfusion.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.lemyst.enfusion.lang.EnfusionLanguage
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Opens the completion popup on `.`, as it opens on a letter.
 *
 * The platform schedules the popup on identifier characters by itself; after a dot it does so
 * only for languages that ask. Without this, `player.` waits for Ctrl+Space, which is exactly where
 * member completion is wanted most.
 */
class EnfusionDotAutoPopup : TypedHandlerDelegate() {

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '.' || file.language != EnfusionLanguage) return Result.CONTINUE
        // A path or a sentence: a dot typed in a string, comment or directive starts no member.
        val offset = editor.caretModel.offset
        val type = file.findElementAt(maxOf(offset - 1, 0))?.node?.elementType
        if (type in EnfusionTokenSets.STRINGS || type in EnfusionTokenSets.COMMENTS ||
            type === EnfusionTypes.PP_DIRECTIVE
        ) {
            return Result.CONTINUE
        }
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}
