package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.pbo.format.Derapifier
import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.pbo.format.PboFormatException
import io.github.lemyst.enfusion.pbo.format.PboPacking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Every `.pbo` under the `enfusionPboRoots` roots (`;`-separated, e.g. a DayZ install's `addons`
 * folder and a few Workshop `@Mod` folders), read the way the cache reads them.
 *
 * Header: parses, and every entry's data lies within the file. Content: every navigable entry the
 * cache would keep reads back (decompressing where packed) and every `config.bin` derapifies into
 * text the config parser accepts with no error element. Encrypted and obfuscated PBOs are counted
 * and skipped, not failed: the cache skips them too.
 *
 * Skips when the property is unset. Real PBOs are never committed.
 */
class EnfusionPboCorpusTest : BasePlatformTestCase() {

    private val kept = setOf("c", "layout", "imageset", "csv", "xml", "cpp", "hpp", "h", "json")

    fun testCorpus() {
        val roots = (System.getProperty("enfusion.pbo.roots") ?: "")
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { Paths.get(it) }.filter { Files.exists(it) }
        if (roots.isEmpty()) {
            println("EnfusionPboCorpusTest: no enfusionPboRoots, skipped")
            return
        }
        val pbos = roots.flatMap { root ->
            Files.walk(root).use { s -> s.filter { it.toString().endsWith(".pbo", ignoreCase = true) }.toList() }
        }.sorted()

        var entries = 0
        var configs = 0
        var unreadable = 0
        val failures = ArrayList<String>()
        for (pbo in pbos) {
            val archive = try {
                PboArchive.open(pbo)
            } catch (e: PboFormatException) {
                unreadable++
                println("unreadable: $pbo: ${e.message}")
                continue
            }
            archive.use {
                for (entry in archive.entries) {
                    if (entry.packing == PboPacking.ENCRYPTED) continue
                    val name = entry.name.substringAfterLast('/')
                    val isBin = name.equals("config.bin", ignoreCase = true)
                    if (!isBin && name.substringAfterLast('.', "").lowercase() !in kept) continue
                    if (entry.size > 16L * 1024 * 1024) continue
                    entries++
                    try {
                        val bytes = archive.read(entry)
                        if (isBin && Derapifier.isRapified(bytes)) {
                            configs++
                            checkConfig(pbo, entry.name, Derapifier.derapify(bytes), failures)
                        }
                    } catch (e: PboFormatException) {
                        failures += "$pbo!${entry.name}: ${e.message}"
                    }
                }
            }
        }
        println("EnfusionPboCorpusTest: ${pbos.size} PBOs ($unreadable unreadable), $entries entries read, $configs configs derapified")
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }

    private fun checkConfig(pbo: Path, entry: String, text: String, failures: MutableList<String>) {
        val file = myFixture.configureByText("config.cpp", text)
        val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        if (error != null) {
            val at = error.textOffset
            failures += "$pbo!$entry: parse error '${error.errorDescription}' near: " +
                text.substring(maxOf(0, at - 80), minOf(text.length, at + 40)).replace('\n', ' ')
        }
    }
}
