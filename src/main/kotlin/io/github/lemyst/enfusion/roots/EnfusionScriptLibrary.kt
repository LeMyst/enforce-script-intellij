package io.github.lemyst.enfusion.roots

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.lang.EnfusionIcons
import javax.swing.Icon

/**
 * The configured script roots, presented as one library under External Libraries.
 *
 * It implements [ItemPresentation] because it must: `ExternalLibrariesNode` checks for that
 * interface and, when a library does not implement it, logs a warning and drops the library from
 * the Project view entirely. There is no built-in named `SyntheticLibrary` variant.
 *
 * `equals`/`hashCode` are abstract on the base class and are how the platform diffs libraries
 * across root changes, so they are defined over the root set.
 */
class EnfusionScriptLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {

    override fun getSourceRoots(): Collection<VirtualFile> = roots

    override fun getPresentableText(): String = EnfusionRoots.LIBRARY_NAME

    override fun getIcon(unused: Boolean): Icon = EnfusionIcons.FILE

    override fun equals(other: Any?): Boolean =
        other is EnfusionScriptLibrary && other.roots == roots

    override fun hashCode(): Int = roots.hashCode()
}
