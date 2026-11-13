package io.github.lemyst.enfusion.pbo

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.util.text.StringUtil
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.pbo.format.Derapifier
import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.pbo.format.PboEntry
import io.github.lemyst.enfusion.pbo.format.PboFormatException
import io.github.lemyst.enfusion.pbo.format.PboPacking
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What the PBO browser shows for one entry. */
sealed interface PboEntryView {
    /** Opened as text. [text] uses `\n` only, which documents require. */
    class Text(val name: String, val fileType: FileType, val text: String) : PboEntryView

    /** Opened by the viewer registered for [fileType], such as the texture viewer. */
    class Binary(val name: String, val fileType: FileType, val bytes: ByteArray) : PboEntryView

    /** Not opened; [message] says why. */
    class Unavailable(val message: String) : PboEntryView
}

/**
 * Turns a PBO entry into something an editor tab can show. Kept apart from the browser's UI so it
 * can be tested on synthetic PBOs.
 */
object PboEntryViews {

    /** The texture viewer's own limit, and far beyond any script or config. */
    const val MAX_ENTRY: Long = 128L * 1024 * 1024

    /** How much of an entry of unknown type is looked at to decide whether it is text. */
    private const val SNIFF = 8 * 1024

    /** [label] names the PBO in the header of a rebuilt config. Reads the entry: off the EDT. */
    fun view(archive: PboArchive, entry: PboEntry, label: String): PboEntryView {
        val name = entry.name.substringAfterLast('/').ifEmpty { "(unnamed)" }
        when (entry.packing) {
            PboPacking.ENCRYPTED -> return PboEntryView.Unavailable("$name is encrypted; its content cannot be shown.")
            PboPacking.UNKNOWN -> return PboEntryView.Unavailable("$name uses a packing method this reader does not know.")
            else -> Unit
        }
        if (entry.size > MAX_ENTRY) {
            return PboEntryView.Unavailable("$name is ${StringUtil.formatFileSize(entry.size)}, too large to open.")
        }
        val bytes = try {
            archive.read(entry, MAX_ENTRY)
        } catch (e: PboFormatException) {
            return PboEntryView.Unavailable("$name cannot be read: ${e.message}")
        }

        // Binarised configs: config.bin, and also .rvmat, .bisurf and the like, which packers
        // rapify too. All are the same class/property syntax the config language reads.
        if (Derapifier.isRapified(bytes)) {
            val text = try {
                Derapifier.derapify(bytes)
            } catch (e: PboFormatException) {
                return PboEntryView.Unavailable("$name cannot be rebuilt: ${e.message}")
            }
            val shown = if (name.equals("config.bin", ignoreCase = true)) "config.cpp" else name
            val header = "// Rebuilt by Enforce Script from ${entry.name} in $label. Read-only.\n"
            return PboEntryView.Text(shown, EnfusionConfigFileType, header + text)
        }

        // A light file has no `config.cpp` or `$PBOPREFIX$` ancestor, so the file-type overrider
        // never claims it: the type is given explicitly.
        if (name.endsWith(".c", ignoreCase = true)) return PboEntryView.Text(name, EnfusionFileType, decode(bytes))

        val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
        return when {
            // Most of a PBO: .p3d, .ogg, .wrp... and text formats the IDE has no type for (.sqm,
            // .bikb). Text is shown as plain text; binary without a viewer is refused, not dumped.
            type == UnknownFileType.INSTANCE ->
                if (looksLikeText(bytes)) {
                    PboEntryView.Text(name, PlainTextFileType.INSTANCE, decode(bytes))
                } else {
                    val ext = name.substringAfterLast('.', "")
                    PboEntryView.Unavailable(
                        "$name is binary, and there is no viewer for ${if (ext.isEmpty()) "it" else ".$ext files"}.",
                    )
                }
            type.isBinary -> PboEntryView.Binary(name, type, bytes)
            else -> PboEntryView.Text(name, type, decode(bytes))
        }
    }

    /** UTF-8 when valid, with any BOM dropped; Latin-1 otherwise, which keeps every byte. */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
        return StringUtil.convertLineSeparators(text.removePrefix("﻿"))
    }

    /** No NUL in the first few KB, and next to no other control characters. */
    fun looksLikeText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, SNIFF)
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x20 && b != '\t'.code && b != '\n'.code && b != '\r'.code && b != 0x0C) control++
        }
        return control * 100 <= n
    }
}
