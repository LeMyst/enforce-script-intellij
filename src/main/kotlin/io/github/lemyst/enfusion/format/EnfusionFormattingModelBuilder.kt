package io.github.lemyst.enfusion.format

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider

class EnfusionFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        return FormattingModelProvider.createFormattingModelForPsiFile(
            file,
            EnfusionBlock(file.node, formattingContext.codeStyleSettings),
            formattingContext.codeStyleSettings,
        )
    }
}
