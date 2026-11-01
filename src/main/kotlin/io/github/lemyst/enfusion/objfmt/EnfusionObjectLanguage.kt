package io.github.lemyst.enfusion.objfmt

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import io.github.lemyst.enfusion.lang.EnfusionIcons
import javax.swing.Icon

/**
 * The Enfusion object format: `ClassName [InstanceName] { key value  { children } }`.
 *
 * `.layout` and `.imageset` are one language rather than two because they are literally the same
 * format — a widget tree and an imageset differ only in which class names appear in them. Giving it
 * real PSI is what turns `FindAnyWidget("RowHealthIcon")` and
 * `"set:battleroyale_gui image:DayZBRLogo_White"` into navigable references, and structure view,
 * folding and highlighting come along at no extra cost.
 *
 * `.styles` is deliberately **not** included. Despite living beside the layouts it is XML
 * (`<WidgetStyles><Widget Name="…"><Style … /></Widget>`), and the platform already handles it.
 */
object EnfusionObjectLanguage : Language("EnfusionObject") {
    override fun getDisplayName(): String = "Enforce Layout"
}

object EnfusionObjectFileType : LanguageFileType(EnfusionObjectLanguage) {

    override fun getName(): String = "EnfusionObject"

    override fun getDescription(): String = "Enforce Layout (DayZ .layout / .imageset)"

    /** The label users see. [getName] stays `EnfusionObject`: it is an ID, persisted in settings. */
    override fun getDisplayName(): String = "Enforce Layout"

    override fun getDefaultExtension(): String = "layout"

    override fun getIcon(): Icon = EnfusionIcons.FILE
}
