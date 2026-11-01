package io.github.lemyst.enfusion.lang

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the `.c` extension collision with C/C++.
 *
 * A `.c` file is EnfusionScript only when it lives inside an Enfusion addon — detected by walking up
 * for a `config.cpp` or `$PBOPREFIX$` marker. That covers both a mod checkout and the vanilla script
 * root (`P:\scripts\config.cpp`), while leaving ordinary C projects untouched.
 *
 * M3 replaces the marker walk with "is under a configured Enfusion library root", and adds the
 * three-way user setting (In Enfusion projects / Always / Never).
 */
class EnfusionFileTypeOverrider : com.intellij.openapi.fileTypes.impl.FileTypeOverrider {

    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (!file.name.endsWith(".c", ignoreCase = true)) return null
        val parent = file.parent ?: return null
        return if (isInsideAddon(parent)) EnfusionFileType else null
    }

    private fun isInsideAddon(dir: VirtualFile): Boolean =
        cache.computeIfAbsent(dir.url) {
            var current: VirtualFile? = dir
            var depth = 0
            while (current != null && depth < MAX_DEPTH) {
                if (current.findChild("config.cpp") != null ||
                    current.findChild("config.cpp.disabled") != null ||
                    current.findChild("\$PBOPREFIX\$") != null
                ) {
                    return@computeIfAbsent true
                }
                current = current.parent
                depth++
            }
            false
        }

    companion object {
        private const val MAX_DEPTH = 12

        // Addon markers do not move during a session; a plain memo is enough and keeps this off the
        // hot path (getOverriddenFileType is called for every file the IDE touches).
        private val cache = ConcurrentHashMap<String, Boolean>()

        @JvmStatic
        fun clearCache() = cache.clear()

        @Suppress("unused")
        private fun ping(): FileTypeRegistry = FileTypeRegistry.getInstance()
    }
}
