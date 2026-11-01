package io.github.lemyst.enfusion.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * Only `.es` is claimed by extension. `.c` is claimed contextually by [EnfusionFileTypeOverrider],
 * so installing this plugin never hijacks C sources in unrelated projects.
 */
object EnfusionFileType : LanguageFileType(EnfusionLanguage) {

    override fun getName(): String = "EnfusionScript"

    override fun getDescription(): String = "Enforce Script (DayZ)"

    /** The label users see. [getName] stays `EnfusionScript`: it is an ID, persisted in settings. */
    override fun getDisplayName(): String = "Enforce Script"

    override fun getDefaultExtension(): String = "es"

    override fun getIcon(): Icon = EnfusionIcons.FILE
}
