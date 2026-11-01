package io.github.lemyst.enfusion.objfmt.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import io.github.lemyst.enfusion.objfmt.EnfusionObjectFileType
import io.github.lemyst.enfusion.objfmt.EnfusionObjectLanguage

class EnfusionObjectFile(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, EnfusionObjectLanguage) {

    override fun getFileType(): FileType = EnfusionObjectFileType

    override fun toString(): String = "Enfusion Layout File"

    /** True for an `.imageset`, whose class names and indexing rules differ from a layout's. */
    fun isImageSet(): Boolean = name.endsWith(".imageset", ignoreCase = true)
}
