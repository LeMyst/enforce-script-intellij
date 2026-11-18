package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnModdedOverrideWithoutSuperInspection

/** *Override in a modded class does not call super*: what it reports, and its fix. */
class EnfusionModdedSuperTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(EnModdedOverrideWithoutSuperInspection())
        myFixture.addFileToProject(
            "vanilla.es",
            """
            class ParamsReadContext {}
            class ItemBase
            {
            	void SetActions() {}
            	void EEInit() {}
            	bool OnStoreLoad(ParamsReadContext ctx, int version) { return true; }
            	bool CanPutInCargo(ItemBase parent) { return true; }
            	int GetCount() { return 1; }
            	void Tick(float dt) {}
            }
            """.trimIndent(),
        )
    }

    private fun warning(name: String) =
        "<weak_warning descr=\"'$name' overrides a method in a modded class without calling 'super.$name', " +
            "which drops vanilla's and every other mod's '$name'\">$name</weak_warning>"

    fun testVoidOverrideWithoutSuperIsReported() {
        myFixture.configureByText(
            "test.es",
            "modded class ItemBase\n{\n\toverride void ${warning("SetActions")}() {}\n}",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testSuperInAnyBranchIsEnough() {
        myFixture.configureByText(
            "test.es",
            """
            modded class ItemBase
            {
            	override void Tick(float dt)
            	{
            		if (dt > 1)
            			super.Tick(dt);
            	}
            	override void EEInit()
            	{
            		GetGame();
            		super.EEInit();
            	}
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting(false, false, true)
    }

    /** A value-returning replacement is the norm; the always-wrong list is the exception. */
    fun testValueReturningOnlyOnTheList() {
        myFixture.configureByText(
            "test.es",
            """
            modded class ItemBase
            {
            	override bool CanPutInCargo(ItemBase parent) { return false; }
            	override int GetCount() { return 2; }
            	override bool ${warning("OnStoreLoad")}(ParamsReadContext ctx, int version) { return true; }
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testOptionReportsValueReturning() {
        myFixture.enableInspections(EnModdedOverrideWithoutSuperInspection().apply { reportValueReturning = true })
        myFixture.configureByText(
            "test.es",
            "modded class ItemBase\n{\n\toverride int ${warning("GetCount")}() { return 2; }\n}",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testPlainSubclassIsNotReported() {
        myFixture.configureByText(
            "test.es",
            "class Apple : ItemBase\n{\n\toverride void SetActions() {}\n}",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    /** Nothing to compare against: a base the plugin never indexed says nothing. */
    fun testUnresolvedBaseIsSilent() {
        myFixture.configureByText(
            "test.es",
            "modded class UnknownThing\n{\n\toverride void SetActions() {}\n}",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testFixAddsTheCallToAVoidMethod() {
        myFixture.configureByText(
            "test.es",
            "modded class ItemBase\n{\n\toverride void Ti<caret>ck(float dt)\n\t{\n\t\tPrint(dt);\n\t}\n}",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Add super call"))
        myFixture.checkResult(
            "modded class ItemBase\n{\n\toverride void Tick(float dt)\n\t{\n\t\tsuper.Tick(dt);\n\t\tPrint(dt);\n\t}\n}",
        )
    }

    /** The sandbox review's `override void SetActions() {}` gave `super.SetActions();}`. */
    fun testFixOnAnEmptyBodyOnOneLine() {
        myFixture.configureByText(
            "test.es",
            "modded class ItemBase\n{\n\toverride void Set<caret>Actions() {}\n}",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Add super call"))
        myFixture.checkResult("modded class ItemBase\n{\n\toverride void SetActions() {\n\t\tsuper.SetActions();\n\t}\n}")
    }

    fun testFixBeforeAStatementOnTheBraceLine() {
        myFixture.configureByText(
            "test.es",
            "modded class ItemBase\n{\n\toverride void Ti<caret>ck(float dt) { Print(dt);\n\t}\n}",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Add super call"))
        myFixture.checkResult("modded class ItemBase\n{\n\toverride void Tick(float dt) {\n\t\tsuper.Tick(dt);\n\t\tPrint(dt);\n\t}\n}")
    }

    fun testFixKeepsTheBoolShape() {
        myFixture.configureByText(
            "test.es",
            "modded class ItemBase\n{\n\toverride bool OnStore<caret>Load(ParamsReadContext ctx, int version)\n\t{\n\t\treturn true;\n\t}\n}",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Add super call"))
        myFixture.checkResult(
            "modded class ItemBase\n{\n\toverride bool OnStoreLoad(ParamsReadContext ctx, int version)\n\t{\n" +
                "\t\tif (!super.OnStoreLoad(ctx, version))\n\t\t\treturn false;\n\n\t\treturn true;\n\t}\n}",
        )
    }
}
