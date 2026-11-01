package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.rename.EnfusionNamesValidator

/**
 * Rename, and specifically the parts of it that a reference-following rename gets wrong.
 *
 * EnfusionScript binds several things by name with nothing in the text pointing either way — a
 * `modded class` to the class it patches, an `override` to what it overrides, a constructor to its
 * class. Each of those is a case where renaming the declaration and its references produces code
 * that still parses and is nonetheless broken, so each has a test here.
 */
class EnfusionRenameTest : BasePlatformTestCase() {

    private fun rename(newName: String, vararg files: Pair<String, String>): Map<String, String> {
        val psiFiles = files.map { (name, text) -> myFixture.addFileToProject(name, text) }
        myFixture.configureFromExistingVirtualFile(psiFiles.first().virtualFile)
        myFixture.renameElementAtCaret(newName)
        return psiFiles.associate { it.name to it.text }
    }

    fun testRenamingAClassUpdatesItsUsages() {
        val after = rename(
            "Vehicle",
            "a.es" to "class C<caret>ar\n{\n}\n",
            "b.es" to "class Garage\n{\n\tvoid Park(Car car)\n\t{\n\t\tCar other = car;\n\t}\n}\n",
        )
        assertEquals("class Vehicle\n{\n}\n", after["a.es"])
        assertTrue(after["b.es"]!!.contains("void Park(Vehicle car)"))
        assertTrue(after["b.es"]!!.contains("Vehicle other = car;"))
    }

    /**
     * A constructor is `void ClassName()` and nothing else — no keyword, no reference back to the
     * class. Renaming the class without it silently demotes the constructor to a method that is
     * never called.
     */
    fun testRenamingAClassRenamesItsConstructorAndDestructor() {
        val after = rename(
            "Vehicle",
            "a.es" to "class C<caret>ar\n{\n\tvoid Car()\n\t{\n\t}\n\n\tvoid ~Car()\n\t{\n\t}\n}\n",
        )
        assertTrue("constructor not renamed: ${after["a.es"]}", after["a.es"]!!.contains("void Vehicle()"))
        assertTrue("destructor not renamed: ${after["a.es"]}", after["a.es"]!!.contains("void ~Vehicle()"))
    }

    /**
     * `modded class X` names no supertype: the only thing tying it to `class X` is the spelling.
     * Rename one and the other has to follow, or the layer quietly stops patching anything.
     */
    fun testRenamingAClassRenamesItsModdedLayers() {
        val after = rename(
            "Vehicle",
            "a.es" to "class C<caret>ar\n{\n}\n",
            "b.es" to "modded class Car\n{\n\tvoid Honk()\n\t{\n\t}\n}\n",
        )
        assertEquals("modded class Vehicle\n{\n\tvoid Honk()\n\t{\n\t}\n}\n", after["b.es"])
    }

    fun testRenamingAMethodUpdatesCallSites() {
        val after = rename(
            "Start",
            "a.es" to "class Car\n{\n\tvoid B<caret>egin()\n\t{\n\t}\n}\n",
            "b.es" to "class Driver\n{\n\tvoid Go(Car car)\n\t{\n\t\tcar.Begin();\n\t}\n}\n",
        )
        assertTrue(after["a.es"]!!.contains("void Start()"))
        assertTrue("call site not updated: ${after["b.es"]}", after["b.es"]!!.contains("car.Start();"))
    }

    /** An override binds by name, so renaming the base without it is a compile error at build time. */
    fun testRenamingAMethodRenamesOverridesInSubclasses() {
        val after = rename(
            "Start",
            "a.es" to "class Car\n{\n\tvoid B<caret>egin()\n\t{\n\t}\n}\n",
            "b.es" to "class RaceCar extends Car\n{\n\toverride void Begin()\n\t{\n\t}\n}\n",
        )
        assertTrue("override not renamed: ${after["b.es"]}", after["b.es"]!!.contains("override void Start()"))
    }

    /** And the other direction: renaming the override has to carry the base method with it. */
    fun testRenamingAnOverrideRenamesTheBaseMethod() {
        val after = rename(
            "Start",
            "a.es" to "class RaceCar extends Car\n{\n\toverride void B<caret>egin()\n\t{\n\t}\n}\n",
            "b.es" to "class Car\n{\n\tvoid Begin()\n\t{\n\t}\n}\n",
        )
        assertTrue("base not renamed: ${after["b.es"]}", after["b.es"]!!.contains("void Start()"))
    }

    fun testRenamingALocalLeavesTheRestAlone() {
        val after = rename(
            "count",
            "a.es" to "class Car\n{\n\tint total;\n\n\tvoid Drive()\n\t{\n\t\tint t<caret>otal = 1;\n\t\ttotal = 2;\n\t}\n}\n",
        )
        val text = after["a.es"]!!
        assertTrue("local not renamed: $text", text.contains("int count = 1;"))
        assertTrue("field wrongly renamed: $text", text.contains("int total;"))
        assertTrue("assignment not renamed: $text", text.contains("count = 2;"))
    }

    /**
     * The one that spans both languages: a widget lives in a `.layout` and is looked up by a string
     * from script, so the rename has to cross from the object grammar into a quoted literal.
     */
    fun testRenamingAWidgetUpdatesTheScriptThatLooksItUp() {
        val after = rename(
            "HealthIcon",
            "hud.layout" to "PanelWidgetClass Root {\n {\n  ImageWidgetClass Row<caret>Icon {\n   visible 1\n  }\n }\n}\n",
            "a.es" to "class Hud\n{\n\tvoid Build(Widget root)\n\t{\n\t\troot.FindAnyWidget(\"RowIcon\");\n\t}\n}\n",
        )
        assertTrue("widget not renamed: ${after["hud.layout"]}", after["hud.layout"]!!.contains("ImageWidgetClass HealthIcon {"))
        assertTrue("lookup not updated: ${after["a.es"]}", after["a.es"]!!.contains("FindAnyWidget(\"HealthIcon\")"))
    }

    /**
     * A GUID-form widget (`FrameWidgetClass "{…}" {`) keeps its name in `Name "…"`, so that quoted
     * value is what rename rewrites. The GUID itself must survive untouched.
     */
    fun testRenamingAGuidNamedWidgetRewritesItsNameProperty() {
        val after = rename(
            "HealthIcon",
            "a.es" to "class Hud\n{\n\tvoid Build(Widget root)\n\t{\n\t\troot.FindAnyWidget(\"Row<caret>Icon\");\n\t}\n}\n",
            "hud.layout" to "FrameWidgetClass \"{00000000EE70746B}\" {\n Name \"RowIcon\"\n visible 1\n}\n",
        )
        val layout = after["hud.layout"]!!
        assertTrue("Name not renamed: $layout", layout.contains("Name \"HealthIcon\""))
        assertTrue("GUID altered: $layout", layout.contains("FrameWidgetClass \"{00000000EE70746B}\" {"))
        assertTrue("lookup not updated: ${after["a.es"]}", after["a.es"]!!.contains("FindAnyWidget(\"HealthIcon\")"))
    }

    fun testNamesValidatorRejectsKeywordsAndMalformedNames() {
        val validator = EnfusionNamesValidator()
        assertTrue(validator.isIdentifier("PlayerBase", project))
        assertTrue(validator.isIdentifier("_m_count2", project))
        assertFalse("a keyword is not a legal name", validator.isIdentifier("class", project))
        assertFalse("`out` is a real keyword here", validator.isIdentifier("out", project))
        assertFalse(validator.isIdentifier("2Fast", project))
        assertFalse(validator.isIdentifier("has space", project))
        // `modded` and `auto` lex as identifiers, so the compiler does allow them as names.
        assertTrue(validator.isIdentifier("modded", project))
    }
}
