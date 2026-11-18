package io.github.lemyst.enfusion

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgPropertyMixin
import io.github.lemyst.enfusion.refs.EnConfigClassReference

/** Item class names and config paths in script strings, resolving into `config.cpp`. */
class EnfusionConfigClassReferenceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "MyMod/Data/config.cpp",
            """
            class CfgVehicles
            {
            	class Inventory_Base;
            	class Edible_Base: Inventory_Base
            	{
            		scope=0;
            	};
            	class Apple: Edible_Base
            	{
            		scope=2;
            		class Nutrition
            		{
            			energy=10;
            		};
            	};
            	class Pear: Edible_Base
            	{
            	};
            };
            class CfgAmmo
            {
            	class Bullet_Base
            	{
            		hit=8;
            	};
            	class Bullet_9x19: Bullet_Base
            	{
            	};
            };
            """.trimIndent(),
        )
        myFixture.addFileToProject("MyMod/Other/config.cpp", "class CfgVehicles\n{\n\tclass Apple\n\t{\n\t\tdisplayName=\"x\";\n\t};\n};")
    }

    private fun configRefs(text: String): List<EnConfigClassReference> {
        myFixture.configureByText("test.es", "class A\n{\n\tvoid F(EntityAI e)\n\t{\n\t\t$text\n\t}\n}")
        return PsiTreeUtil.collectElements(myFixture.file) { true }
            .flatMap { it.references.toList() }
            .filterIsInstance<EnConfigClassReference>()
    }

    private fun targets(reference: PsiPolyVariantReference): List<PsiElement> =
        reference.multiResolve(false).mapNotNull { it.element }

    fun testSpawnCallResolvesEveryDeclaration() {
        val ref = configRefs("e.GetInventory().CreateInInventory(\"Apple\");").single()
        val found = targets(ref)
        assertEquals(2, found.size)
        assertTrue(found.all { (it as EnCfgClassMixin).name == "Apple" })
    }

    /** A class only forward-declared still resolves, to that declaration. */
    fun testIsKindOfAndForwardDeclarations() {
        val refs = configRefs("if (e.IsKindOf(\"Inventory_Base\")) {}\nGetGame().IsKindOf(\"Apple\", \"Edible_Base\");")
        assertEquals(3, refs.size)
        assertTrue((targets(refs[0]).single() as EnCfgClassMixin).isForwardDeclaration())
        assertEquals("Edible_Base", (targets(refs[2]).single() as EnCfgClassMixin).name)
    }

    fun testOtherCallsGetNone() {
        assertEquals(0, configRefs("Print(\"Apple\");\ne.CreateInInventory(\"Apple\" + x);").size)
    }

    fun testConfigPathWordByWord() {
        val refs = configRefs("float h = GetGame().ConfigGetFloat(\"CfgAmmo Bullet_9x19 hit\");")
        assertEquals(listOf("CfgAmmo", "Bullet_9x19", "hit"), refs.map { it.value })
        assertEquals("CfgAmmo", (targets(refs[0]).single() as EnCfgClassMixin).name)
        assertEquals("Bullet_9x19", (targets(refs[1]).single() as EnCfgClassMixin).name)
        // `hit` is declared on the base.
        assertEquals("hit", (targets(refs[2]).single() as EnCfgPropertyMixin).getKey())
    }

    fun testSubclassInAPath() {
        val refs = configRefs("GetGame().ConfigGetFloat(\"CfgVehicles Apple Nutrition energy\");")
        assertEquals(listOf("CfgVehicles", "Apple", "Nutrition"), refs.map { it.value })
        assertEquals("Nutrition", (targets(refs[2]).single() as EnCfgClassMixin).name)
    }

    /** A concatenated path: the section of its first literal, in any case, as the engine reads it. */
    fun testConcatenatedPathAndCase() {
        val refs = configRefs("GetGame().ConfigGetText(\"cfgAmmo \" + ammo + \" hit\");")
        assertEquals(listOf("cfgAmmo"), refs.map { it.value })
        assertEquals("CfgAmmo", (targets(refs.single()).single() as EnCfgClassMixin).name)
    }

    fun testCompletionInASpawnCallLeavesOutBaseClasses() {
        myFixture.configureByText("test.es", "class A\n{\n\tvoid F(EntityAI e)\n\t{\n\t\te.CreateInInventory(\"<caret>\");\n\t}\n}")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue(offered.toString(), "Apple" in offered && "Pear" in offered)
        assertFalse(offered.toString(), "Edible_Base" in offered)
    }

    fun testFindUsagesFromTheConfigClass() {
        configRefs("e.GetInventory().CreateInInventory(\"Pear\");")
        val pear = PsiTreeUtil.findChildrenOfType(
            psiManager.findFile(myFixture.findFileInTempDir("MyMod/Data/config.cpp")!!)!!,
            EnCfgClassMixin::class.java,
        ).first { it.name == "Pear" }
        val usages = myFixture.findUsages(pear)
        assertEquals(1, usages.size)
        assertEquals("test.es", usages.single().file?.name)
    }
}
