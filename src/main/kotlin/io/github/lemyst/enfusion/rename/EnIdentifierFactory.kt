package io.github.lemyst.enfusion.rename

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.objfmt.EnfusionObjectFileType
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin

/**
 * Fresh identifier leaves for rename to graft in.
 *
 * A leaf cannot be conjured directly — it has to come out of a real parse, or the new node carries
 * no valid element type and the tree is quietly corrupted. So each identifier is produced by parsing
 * the smallest declaration that contains one and plucking its name.
 *
 * There are two of them because the plugin has two languages, and a leaf from the wrong parser is
 * the same corruption in a subtler form: a widget in a `.layout` renamed with an EnfusionScript IDENT
 * would produce a tree whose token types belong to another grammar.
 */
object EnIdentifierFactory {

    fun createScriptIdentifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("_rename.es", EnfusionFileType, "class $name {}")
        return PsiTreeUtil.findChildOfType(file, EnClassDeclMixin::class.java)?.nameIdentifier
            ?: throw IncorrectOperationException("Not a valid Enforce Script identifier: $name")
    }

    fun createConfigIdentifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("config.cpp", EnfusionConfigFileType, "class $name {};\n")
        return PsiTreeUtil.findChildOfType(file, EnCfgClassMixin::class.java)?.nameIdentifier
            ?: throw IncorrectOperationException("Not a valid config class name: $name")
    }

    fun createObjectIdentifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project)
            // The brace has to stay on the declaration's own line: the object grammar separates a
            // declaration from a property by the brace immediately following, so an Allman layout
            // does not parse at all.
            .createFileFromText("_rename.layout", EnfusionObjectFileType, "WidgetClass $name {\n}\n")
        return PsiTreeUtil.findChildOfType(file, EnObjDeclMixin::class.java)?.nameIdentifier
            ?: throw IncorrectOperationException("Not a valid widget name: $name")
    }

    /**
     * The quoted `Name` value of a GUID-form widget (`FrameWidgetClass "{…}" {` + `Name "…"`),
     * which is that form's name identifier. A quote or line break would end the string early.
     */
    fun createObjectNameValue(project: Project, name: String): PsiElement {
        if (name.isEmpty() || name.any { it == '"' || it == '\n' || it == '\r' }) {
            throw IncorrectOperationException("Not a valid widget name: $name")
        }
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "_rename.layout",
            EnfusionObjectFileType,
            "WidgetClass \"{0000000000000000}\" {\n Name \"$name\"\n}\n",
        )
        return PsiTreeUtil.findChildOfType(file, EnObjDeclMixin::class.java)?.nameIdentifier
            ?: throw IncorrectOperationException("Not a valid widget name: $name")
    }
}
