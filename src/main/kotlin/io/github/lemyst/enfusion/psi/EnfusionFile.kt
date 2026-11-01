package io.github.lemyst.enfusion.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.lang.EnfusionLanguage

class EnfusionFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, EnfusionLanguage) {
    override fun getFileType(): FileType = EnfusionFileType
    override fun toString(): String = "EnfusionScript File"
}
