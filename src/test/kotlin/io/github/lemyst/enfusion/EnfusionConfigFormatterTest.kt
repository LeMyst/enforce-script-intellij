package io.github.lemyst.enfusion

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The `config.cpp` / `mod.cpp` formatter: indentation only. The corpus tests reformat the configs
 * of `enfusionConfigRoots` and check that nothing but indentation changes, alignment tabs inside a
 * line included, and that nearly every line keeps its indentation.
 */
class EnfusionConfigFormatterTest : BasePlatformTestCase() {

    private fun reformat(text: String, name: String = "config.cpp"): String {
        myFixture.configureByText(name, text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.file.text
    }

    fun testClassBodiesAreIndentedWithTabs() {
        assertEquals(
            "class CfgPatches\n{\n\tclass MyMod\n\t{\n\t\tunits[]={};\n\t};\n};",
            reformat("class CfgPatches\n{\nclass MyMod\n{\nunits[]={};\n};\n};"),
        )
    }

    /** Vanilla's shape: the array's `{` on the line after its name, at the property's level. */
    fun testMultiLineArray() {
        assertEquals(
            "class A\n{\n\trequiredAddons[]=\n\t{\n\t\t\"DZ_Data\",\n\t\t\"DZ_Scripts\"\n\t};\n};",
            reformat("class A\n{\nrequiredAddons[]=\n{\n\"DZ_Data\",\n\"DZ_Scripts\"\n};\n};"),
        )
        assertEquals(
            "class A\n{\n\tunits[] = {\n\t\t\"A\",\n\t\t\"B\"\n\t};\n};",
            reformat("class A\n{\n    units[] = {\n  \"A\",\n\"B\"\n          };\n};"),
        )
    }

    fun testNestedArrays() {
        assertEquals(
            "class A\n{\n\thealthLevels[]=\n\t{\n\t\t{\n\t\t\t1,\n\t\t\t{}\n\t\t}\n\t};\n};",
            reformat("class A\n{\nhealthLevels[]=\n{\n{\n1,\n{}\n}\n};\n};"),
        )
    }

    /** Spacing and one-line arrays are left as written. */
    fun testAlignmentTabsInsideALineAreKept() {
        val source = "class CfgPatches\n{\n\tclass X\n\t{\n\t\tunits[]\t\t\t= {};\n\t\trequiredVersion\t= 0.1;\n\t};\n};"
        assertEquals(source, reformat(source))
        assertEquals("indentation still changes", source, reformat(source.replace("\n\t\tunits", "\nunits")))
    }

    fun testSpacingIsKept() {
        val source = "class A : B\n{\n\tscope = 2;\n\tunits[]={\"X\" ,  \"Y\"};\n\tclass Fwd;\n};"
        assertEquals(source, reformat(source))
    }

    fun testCommentsAndDirectives() {
        assertEquals(
            "class A\n{\n\t// the scope\n\tscope=2;\n#ifdef X\n\tx=1;\n#endif\n};",
            reformat("class A\n{\n// the scope\nscope=2;\n\t#ifdef X\nx=1;\n#endif\n};"),
        )
    }

    fun testModCpp() {
        assertEquals("name=\"My Mod\";\npicture=\"\";", reformat("  name=\"My Mod\";\npicture=\"\";", "mod.cpp"))
    }

    /** The indentation Enter gives the new line after the caret's line. */
    private fun indentAfterEnter(before: String): String {
        myFixture.configureByText("config.cpp", before)
        myFixture.type("\n")
        val document = myFixture.editor.document
        val start = document.getLineStartOffset(document.getLineNumber(myFixture.caretOffset))
        return document.text.substring(start, myFixture.caretOffset)
    }

    fun testEnterInsideAClassBody() {
        assertEquals("\t\t", indentAfterEnter("class A\n{\n\tclass B: A\n\t{<caret>\n\t};\n};"))
        assertEquals("\t", indentAfterEnter("class A\n{\n\tscope=2;<caret>\n};"))
    }

    fun testEnterInsideAnArray() {
        assertEquals("\t\t", indentAfterEnter("class A\n{\n\tunits[]=\n\t{<caret>\n\t};\n};"))
    }

    fun testVanillaConfigsKeepTheirIndentation() {
        val roots = configRoots()
        if (roots.isEmpty()) {
            println("[cfgformat] no config roots, skipping")
            return
        }
        for (root in roots) {
            val files = root.walkTopDown()
                .onEnter { it.name != ".git" && it.name != ".claude" }
                .filter { it.isFile && (it.name == "config.cpp" || it.name == "mod.cpp") }
                .take(MAX_FILES_PER_ROOT)
                .toList()
            if (files.isEmpty()) continue
            var lines = 0
            var moved = 0
            val damaged = ArrayList<String>()
            val examples = ArrayList<String>()
            for (file in files) {
                val source = file.readText().replace("\r\n", "\n")
                val before = source.lines()
                val after = reformat(source).lines()
                if (tokens(before) != tokens(after)) {
                    damaged += file.path
                    continue
                }
                for (i in before.indices) {
                    if (before[i].isBlank()) continue
                    lines++
                    if (indentWidth(before[i]) != indentWidth(after[i])) {
                        moved++
                        if (examples.size < 8) examples += "${file.parentFile.name}/${file.name}:${i + 1}: ${before[i].trim().take(60)}"
                    }
                }
            }
            val share = 100.0 * (lines - moved) / lines.coerceAtLeast(1)
            println("[cfgformat] ${root.name}: ${files.size} files, $lines lines, ${"%.2f".format(share)}% keep their indentation, ${damaged.size} damaged")
            examples.forEach { println("[cfgformat]   $it") }
            assertTrue("formatting altered tokens in ${damaged.take(3)}", damaged.isEmpty())
            if (root.name.equals("DZ", ignoreCase = true)) {
                assertTrue("only ${"%.2f".format(share)}% of vanilla config lines keep their indentation", share >= VANILLA_KEPT)
            }
        }
    }

    private fun configRoots(): List<File> = System.getProperty("enfusion.config.roots").orEmpty().split(';')
        .map(String::trim).filter(String::isNotEmpty).map(::File).filter(File::isDirectory)

    /** Each line without its indentation and trailing whitespace: everything else must stay. */
    private fun tokens(lines: List<String>) = lines.map { it.trim() }

    private companion object {
        const val MAX_FILES_PER_ROOT = 400

        /** Vanilla's configs are machine-written from `config.bin` and uniform: all of them must hold. */
        const val VANILLA_KEPT = 100.0

        fun indentWidth(line: String): Int = line.takeWhile { it == '\t' || it == ' ' }.sumOf { if (it == '\t') 4 else 1 as Int }
    }
}
