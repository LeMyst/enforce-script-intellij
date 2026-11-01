package io.github.lemyst.enfusion.cfg.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.cfg.EnfusionConfigLanguage

class EnfusionConfigFile(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, EnfusionConfigLanguage) {

    override fun getFileType(): FileType = EnfusionConfigFileType

    override fun toString(): String = "Enfusion Config File"
}
