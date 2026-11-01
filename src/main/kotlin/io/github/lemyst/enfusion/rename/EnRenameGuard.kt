package io.github.lemyst.enfusion.rename

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings

/**
 * Why a rename must not go ahead.
 *
 * Two things make this necessary here that would not be in an ordinary language plugin.
 *
 * The vanilla script tree is mounted as a *library*, but unlike a jar its files are ordinary
 * writable files on the `P:` work drive — the platform's usual read-only protection does not apply,
 * so nothing else stops a rename from rewriting the game's own extracted source in place.
 *
 * And a rename does not stay where it starts: [EnRenameFamily] deliberately pulls in the base a
 * `modded class` patches and the method an `override` overrides, which for mod code is very often
 * exactly that vanilla declaration. Renaming your own `override void ConnectFromCLI()` therefore
 * *means* renaming vanilla's — so the refusal has to be checked over the whole family, not just the
 * element the caret sits on.
 */
object EnRenameGuard {

    /** A user-facing reason, or null when the rename may proceed. */
    fun refusalReason(element: PsiElement): String? {
        val name = (element as? PsiNamedElement)?.name ?: "This declaration"
        originOutsideProject(element)?.let {
            return "'$name' is declared in $it, which this project only reads. It cannot be renamed here."
        }
        for (relative in EnRenameFamily.of(element)) {
            val origin = originOutsideProject(relative) ?: continue
            return "'$name' is bound by name to a declaration in $origin, which this project only " +
                "reads. Renaming it here would break that link, so the refactoring is refused."
        }
        return null
    }

    /**
     * Where [element] lives, when that is outside the project's own sources.
     *
     * A file with no [VirtualFile] is in memory — a rename preview or a test fixture — and is
     * allowed: it is not anybody's source tree.
     */
    private fun originOutsideProject(element: PsiElement): String? {
        val file = element.containingFile?.virtualFile ?: return null
        val path = file.path.replace('\\', '/')
        for (root in EnfusionProjectSettings.getInstance(element.project).roots) {
            val normalised = root.replace('\\', '/').trimEnd('/')
            if (normalised.isNotEmpty() && path.startsWith("$normalised/", ignoreCase = true)) {
                return "the script root $normalised"
            }
        }
        // Covers the bundled Community Framework stubs, which sit under the IDE's system directory
        // rather than under a configured root.
        if (ProjectFileIndex.getInstance(element.project).isInLibrary(file)) {
            return "a library (${file.name})"
        }
        return null
    }
}
