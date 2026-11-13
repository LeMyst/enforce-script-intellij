package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.cfg.EnfusionConfigLexerAdapter
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgPropertyMixin

/**
 * The shapes in `config.cpp` the grammar could plausibly get wrong, and the references built on them.
 *
 * The corpus test proves 587 real files parse; these pin down *what* they parsed into, which is what
 * every reference here depends on.
 */
class EnfusionConfigTest : BasePlatformTestCase() {

    private fun parse(text: String, path: String = "config.cpp") = myFixture.configureByText(path, text).also {
        assertNull("unexpected parse error", PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java))
    }

    private fun classes(text: String) =
        PsiTreeUtil.findChildrenOfType(parse(text), EnCfgClassMixin::class.java).toList()

    // --- parsing ------------------------------------------------------------------------------

    fun testForwardDeclarationAndDefinitionAreBothClasses() {
        val decls = classes("class EffectArea;\nclass BREffectArea: EffectArea {};\n")
        assertEquals(listOf("EffectArea", "BREffectArea"), decls.map { it.name })
        assertTrue(decls[0].isForwardDeclaration())
        assertFalse(decls[1].isForwardDeclaration())
        assertEquals("EffectArea", decls[1].getSuperName())
    }

    /** `Scripts/Client/config.cpp` closes its last class without one and the engine accepts it. */
    fun testTrailingSemicolonIsOptional() {
        val decls = classes("class CfgVehicles\n{\n\tclass BlueZone\n\t{\n\t\tscope=2;\n\t}\n}\n")
        assertEquals(listOf("CfgVehicles", "BlueZone"), decls.map { it.name })
    }

    fun testArrayAppendAndRemove() {
        val file = parse(
            "class A\n{\n\tattachments[] += {\"a\", \"b\"};\n\tdoors[] -= {\"c\"};\n\tscope = 2;\n}\n",
        )
        val props = PsiTreeUtil.findChildrenOfType(file, EnCfgPropertyMixin::class.java).toList()
        assertEquals(listOf("attachments", "doors", "scope"), props.map { it.getKey() })
        assertTrue(props[0].isArray() && props[0].isAppend())
        assertTrue(props[1].isArray() && props[1].isRemove())
        assertFalse(props[2].isArray())
        assertEquals(listOf("a", "b"), props[0].stringValues())
    }

    fun testNestedArraysAndScientificNumbers() {
        val file = parse(
            "class A\n{\n\ttable[] = { {1, 2}, {3, 4} };\n\tsmall = 9.9999997e-05;\n\tneg = -1.5;\n}\n",
        )
        val props = PsiTreeUtil.findChildrenOfType(file, EnCfgPropertyMixin::class.java).toList()
        assertEquals(2, props[0].value()!!.elements().size)
    }

    /** Vanilla `DZ/sounds/hpp/config.cpp` declares test tones named like `1kHz_mono_1s_SoundShader`. */
    fun testClassNameMayStartWithDigits() {
        val decls = classes(
            "class CfgSoundShaders\n{\n\tclass 1kHz_mono_1s_SoundShader\n\t{\n\t\tvolume = 1;\n\t};\n" +
                "\tclass 1kHz_Louder: 1kHz_mono_1s_SoundShader {};\n};\n",
        )
        assertEquals(listOf("CfgSoundShaders", "1kHz_mono_1s_SoundShader", "1kHz_Louder"), decls.map { it.name })
        assertEquals("1kHz_mono_1s_SoundShader", decls[2].getSuperName())
    }

    /** The digit-led name rule must not take numbers, `1e5` included, which both rules match in full. */
    fun testNumbersStayNumbers() {
        val lexer = EnfusionConfigLexerAdapter()
        for (number in listOf("10", "2.5", "-1.5", "1e5", "9.9999997e-05", ".5")) {
            lexer.start(number)
            assertEquals(number, EnfusionConfigTypes.NUMBER, lexer.tokenType)
            assertEquals(number, number.length, lexer.tokenEnd)
        }
    }

    /**
     * A backslash is not an escape in this format. Paths are backslash-separated and routinely end in
     * one, so the closing quote here really does close the string — getting this wrong swallowed the
     * rest of four real configs.
     */
    fun testBackslashBeforeClosingQuoteEndsTheString() {
        val file = parse(
            "class A\n{\n\tmaterials[] = {\"DZ\\gear\\drinks\\data\\\", \"Drink.rvmat\"};\n\tscope = 2;\n}\n",
        )
        val props = PsiTreeUtil.findChildrenOfType(file, EnCfgPropertyMixin::class.java).toList()
        assertEquals(listOf("materials", "scope"), props.map { it.getKey() })
        assertEquals(listOf("DZ\\gear\\drinks\\data\\", "Drink.rvmat"), props[0].stringValues())
    }

    fun testDoubledQuoteIsTheOnlyEscape() {
        val file = parse("class A\n{\n\tname = \"say \"\"hi\"\"\";\n}\n")
        val props = PsiTreeUtil.findChildrenOfType(file, EnCfgPropertyMixin::class.java).toList()
        assertEquals(listOf("say \"\"hi\"\""), props[0].stringValues())
    }

    fun testDefineIsToleratedAsAComment() {
        val decls = classes("#define FOO 1\nclass A {};\n")
        assertEquals(listOf("A"), decls.map { it.name })
    }

    fun testMultipleClassesAtFileScope() {
        val decls = classes("class CfgPatches\n{\n\tclass Mine\n\t{\n\t\trequiredAddons[]={\"DZ_Data\"};\n\t};\n};\nclass CfgMods\n{\n};\n")
        assertEquals(listOf("CfgPatches", "Mine", "CfgMods"), decls.map { it.name })
    }

    // --- references ---------------------------------------------------------------------------

    fun testSuperClassResolvesAcrossFiles() {
        myFixture.addFileToProject("base/config.cpp", "class CfgVehicles\n{\n\tclass EffectArea\n\t{\n\t};\n};\n")
        myFixture.configureByText(
            "config.cpp",
            "class CfgVehicles\n{\n\tclass BREffectArea: Effect<caret>Area\n\t{\n\t};\n};\n",
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull("super class did not resolve", target)
        assertEquals("EffectArea", (target as EnCfgClassMixin).name)
        assertEquals("base/config.cpp", target.containingFile.virtualFile.path.substringAfter("/src/"))
    }

    /**
     * The load-order link. It exists only as a string matching a class name in a different addon, so
     * without this there is nothing to follow in either direction.
     */
    fun testRequiredAddonResolvesToItsCfgPatchesClass() {
        myFixture.addFileToProject(
            "dep/config.cpp",
            "class CfgPatches\n{\n\tclass DayZExpansion_Scripts\n\t{\n\t\tunits[]={};\n\t};\n};\n",
        )
        myFixture.configureByText(
            "config.cpp",
            "class CfgPatches\n{\n\tclass Mine\n\t{\n\t\trequiredAddons[]={\"DayZExpansion<caret>_Scripts\"};\n\t};\n};\n",
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull("required addon did not resolve", target)
        assertEquals("DayZExpansion_Scripts", (target as EnCfgClassMixin).name)
    }

    /** `dependencies[]` looks identical but names engine modules, which nothing declares. */
    fun testDependenciesArrayIsNotAnAddonReference() {
        myFixture.configureByText(
            "config.cpp",
            "class CfgMods\n{\n\tclass Mine\n\t{\n\t\tdependencies[]={\"Wo<caret>rld\"};\n\t};\n};\n",
        )
        assertNull(myFixture.getReferenceAtCaretPosition()?.resolve())
    }

    /**
     * The point of the whole exercise being cheap: three of the four providers here are the ones
     * written for script and layouts, reused with no modification.
     */
    fun testStringTableKeyResolvesFromAConfig() {
        myFixture.addFileToProject("stringtable.csv", "\"Language\",\"original\"\n\"STR_CfgShoulder0\",\"Shoulder\"\n")
        myFixture.configureByText(
            "config.cpp",
            "class CfgSlots\n{\n\tclass Slot_Melee\n\t{\n\t\tdisplayName=\"#STR_CfgSh<caret>oulder0\";\n\t};\n};\n",
        )
        assertNotNull("stringtable key did not resolve", myFixture.getReferenceAtCaretPosition()?.resolve())
    }

    fun testAssetPathResolvesFromAConfig() {
        myFixture.addFileToProject("Data/Inputs.xml", "<inputs></inputs>\n")
        myFixture.configureByText(
            "config.cpp",
            "class CfgMods\n{\n\tclass Mine\n\t{\n\t\tinputs=\"BattleRoyale/Data/In<caret>puts.xml\";\n\t};\n};\n",
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull("asset path did not resolve", target)
        assertEquals("Inputs.xml", (target as com.intellij.psi.PsiFile).name)
    }

    // --- rename -------------------------------------------------------------------------------

    /**
     * Crossing back out of the config: the new identifier has to come from *this* grammar, or the
     * tree ends up holding a leaf from another language's parser.
     */
    fun testRenamingAConfigClassUpdatesWhatExtendsIt() {
        val other = myFixture.addFileToProject(
            "sub/config.cpp",
            "class CfgVehicles\n{\n\tclass Derived: BREffectArea\n\t{\n\t};\n};\n",
        )
        myFixture.configureByText(
            "config.cpp",
            "class CfgVehicles\n{\n\tclass BREffect<caret>Area\n\t{\n\t};\n};\n",
        )
        myFixture.renameElementAtCaret("ZoneArea")
        assertTrue("declaration not renamed", myFixture.file.text.contains("class ZoneArea"))
        assertTrue("extends clause not updated: ${other.text}", other.text.contains(": ZoneArea"))
    }
}
