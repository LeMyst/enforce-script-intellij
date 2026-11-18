package io.github.lemyst.enfusion.nav

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.icons.AllIcons
import com.intellij.ide.util.MemberChooser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBCheckBox
import io.github.lemyst.enfusion.psi.EnfusionFile
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * *Code → Override Methods…* (`Ctrl+O`) in a class or a `modded class`: pick methods of the
 * superclass chain, and in a `modded class` of the class's other layers, and get each one's
 * signature with `override` and a `super` call.
 *
 * Copying a vanilla signature by hand is where the mistakes come from: a parameter modifier or a
 * default dropped is the *missing override* inspection at best and *Can't compile* at launch at
 * worst, and a forgotten `super` call compiles and silently drops vanilla's behaviour and every
 * other mod's layer.
 */
class EnOverrideMethodsHandler : LanguageCodeInsightActionHandler {

    override fun isValidFor(editor: Editor, file: PsiFile): Boolean =
        file is EnfusionFile && classAt(editor, file) != null

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (DumbService.isDumb(project)) {
            HintManager.getInstance().showErrorHint(editor, "Override Methods needs the index, which is being built")
            return
        }
        val cls = classAt(editor, file) ?: return
        val candidates = EnOverrideCandidates.of(cls)
        if (candidates.isEmpty()) {
            HintManager.getInstance().showErrorHint(editor, "No methods to override")
            return
        }
        val properties = PropertiesComponent.getInstance()
        val callSuper = JBCheckBox("Call super", properties.getBoolean(CALL_SUPER_KEY, true))
        val members = candidates.map(::EnMethodMember).toTypedArray()
        val selected: List<EnMethodDeclMixin> = if (ApplicationManager.getApplication().isUnitTestMode) {
            candidates
        } else {
            val chooser = MemberChooser(members, false, true, project, null, arrayOf(callSuper))
            chooser.title = "Select Methods to Override"
            chooser.setCopyJavadocVisible(false)
            chooser.show()
            if (chooser.exitCode != MemberChooser.OK_EXIT_CODE) return
            properties.setValue(CALL_SUPER_KEY, callSuper.isSelected, true)
            chooser.selectedElements?.map { it.method }.orEmpty()
        }
        if (selected.isEmpty()) return
        generate(project, editor, cls, selected, callSuper.isSelected)
    }

    companion object {
        private const val CALL_SUPER_KEY = "enfusion.overrideMethods.callSuper"

        /** The class whose body the caret is in, or whose declaration it is on. */
        fun classAt(editor: Editor, file: PsiFile): EnClassDeclMixin? {
            val element = file.findElementAt(editor.caretModel.offset)
                ?: file.findElementAt(editor.caretModel.offset - 1)
            return PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java, false)
        }

        /**
         * Inserts the overrides after the member at the caret, formats them, and puts the caret in
         * the first one's body. One undoable command.
         */
        fun generate(project: Project, editor: Editor, cls: EnClassDeclMixin, methods: List<EnMethodDeclMixin>, callSuper: Boolean) {
            val texts = methods.map { EnOverrideText.of(it, callSuper) }
            val file = cls.containingFile
            WriteCommandAction.runWriteCommandAction(project, "Override Methods", null, {
                val placement = EnOverrideText.placement(cls, editor.caretModel.offset) ?: return@runWriteCommandAction
                val document = editor.document
                val block = texts.joinToString("\n\n")
                val inserted = if (placement.afterBrace) "\n$block\n" else "\n\n$block"
                document.insertString(placement.offset, inserted)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                val start = placement.offset
                val end = start + inserted.length
                val marker = document.createRangeMarker(start, end)
                CodeStyleManager.getInstance(project).adjustLineIndent(file, com.intellij.openapi.util.TextRange(start, end))
                PsiDocumentManager.getInstance(project).commitDocument(document)
                // The caret goes at the end of the first body's first line, the super call.
                val text = document.charsSequence
                val firstBrace = text.indexOf('{', marker.startOffset)
                if (firstBrace in marker.startOffset until marker.endOffset) {
                    val lineEnd = document.getLineEndOffset(document.getLineNumber(firstBrace) + 1)
                    editor.caretModel.moveToOffset(minOf(lineEnd, marker.endOffset))
                }
                marker.dispose()
            }, file)
        }

        private fun CharSequence.indexOf(char: Char, from: Int): Int {
            for (i in from until length) if (this[i] == char) return i
            return -1
        }
    }
}

/** A method in the chooser, grouped under the class that declares it. */
private class EnMethodMember(val method: EnMethodDeclMixin) :
    MemberChooserObjectBase(EnOverrideText.chooserText(method), AllIcons.Nodes.Method), ClassMember {

    private val parent = EnClassNode(PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java))

    override fun getParentNodeDelegate(): MemberChooserObject = parent

    override fun equals(other: Any?): Boolean = other is EnMethodMember && other.method == method

    override fun hashCode(): Int = method.hashCode()
}

/**
 * A declaring class, as a group in the chooser. Each layer of a `modded class` is its own group,
 * with its file, since the layers share a name.
 */
private class EnClassNode(val cls: EnClassDeclMixin?) : MemberChooserObjectBase(
    cls?.let { (if (it.isModded()) "modded " else "") + it.name + (it.fileLocation()?.let { location -> "  ($location)" } ?: "") }
        ?: "file scope",
    if (cls?.isModded() == true) AllIcons.Nodes.AbstractClass else AllIcons.Nodes.Class,
) {
    override fun equals(other: Any?): Boolean = other is EnClassNode && other.cls == cls

    override fun hashCode(): Int = cls?.hashCode() ?: 0
}
