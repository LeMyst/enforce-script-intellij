package io.github.lemyst.enfusion.cfg

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import io.github.lemyst.enfusion.lang.EnfusionIcons
import javax.swing.Icon

/**
 * The Bohemia config format: `class Name : Parent { key = value; arr[] = {…}; };`.
 *
 * This is where an addon is actually wired together — which folders become which script stage, which
 * addons must load first, which `Inputs.xml` and imagesets ship, which compile-time `defines[]` are
 * on. The plugin has always *depended* on these files, since
 * [io.github.lemyst.enfusion.lang.EnfusionFileTypeOverrider] walks up looking for one to decide
 * whether a `.c` is EnfusionScript, but until now it never read one.
 *
 * The `.cpp` extension collision with C++ never arises, unlike the `.c` one: these files are named
 * exactly `config.cpp`, `config.cpp.disabled` and `mod.cpp`, so the `fileType` extension point
 * matches them by **file name** and no `FileTypeOverrider` is needed.
 */
object EnfusionConfigLanguage : Language("EnfusionConfig") {
    override fun getDisplayName(): String = "Enforce Config"
}

object EnfusionConfigFileType : LanguageFileType(EnfusionConfigLanguage) {

    override fun getName(): String = "EnfusionConfig"

    override fun getDescription(): String = "Enforce Config (DayZ config.cpp / mod.cpp)"

    /** The label users see. [getName] stays `EnfusionConfig`: it is an ID, persisted in settings. */
    override fun getDisplayName(): String = "Enforce Config"

    /**
     * Never actually used to claim a file — registration is by exact name — but the platform
     * requires one, and it must not be `cpp` or the file type would start competing with C++.
     */
    override fun getDefaultExtension(): String = ""

    override fun getIcon(): Icon = EnfusionIcons.FILE
}
