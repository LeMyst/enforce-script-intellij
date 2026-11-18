package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.nav.EnOverrideCandidates
import io.github.lemyst.enfusion.nav.EnOverrideMethodsHandler
import io.github.lemyst.enfusion.nav.EnOverrideText
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Override Methods (`Ctrl+O`): which methods are offered, and the text generated for them. In
 * unit-test mode the handler takes every candidate, as if all were ticked in the chooser.
 */
class EnfusionOverrideMethodsTest : BasePlatformTestCase() {

    private fun base() = myFixture.addFileToProject(
        "base.es",
        """
        class EntityAI {}
        class Base
        {
        	void Base() {}
        	void ~Base() {}
        	void EEInit() {}
        	bool CanPutInCargo(EntityAI parent) { return true; }
        	protected void Hidden(out int a, inout float b, bool create_local = false) {}
        	static void Helper() {}
        	proto native void Native();
        	private void Secret() {}
        	sealed void Final() {}
        	event protected void EOnFrame(EntityAI other, float timeSlice) {}
        }
        """.trimIndent(),
    )

    private fun candidates(text: String): List<String> {
        myFixture.configureByText("test.es", text)
        val cls = EnOverrideMethodsHandler.classAt(myFixture.editor, myFixture.file)!!
        return EnOverrideCandidates.of(cls).map { it.name!! }
    }

    fun testOffersWhatASubclassCanOverride() {
        base()
        assertEquals(
            listOf("CanPutInCargo", "Hidden", "EOnFrame"),
            candidates("class Derived : Base\n{\n\toverride void EEInit() {}\n\t<caret>\n}"),
        )
    }

    /** A `modded class` is the class itself: its other layers' private methods are its own. */
    fun testModdedClassOffersOtherLayersPrivateMethods() {
        base()
        val offered = candidates("modded class Base\n{\n\t<caret>\n}")
        assertTrue(offered.toString(), "Secret" in offered)
        assertTrue(offered.toString(), "EEInit" in offered)
        assertFalse(offered.toString(), "Helper" in offered)
        assertFalse(offered.toString(), "Base" in offered)
    }

    fun testOverloadsAreSeparateSignatures() {
        myFixture.addFileToProject(
            "sender.es",
            "class Sender\n{\n\tvoid Send(int c) {}\n\tvoid Send(int c, string d) {}\n}",
        )
        assertEquals(
            listOf("Send"),
            candidates("class S : Sender\n{\n\toverride void Send(int c) {}\n\t<caret>\n}"),
        )
    }

    fun testGeneratedTextKeepsModifiersAndDefaults() {
        base()
        myFixture.configureByText("test.es", "class A {}")
        val hidden = findMethod("Hidden")
        assertEquals(
            "override protected void Hidden(out int a, inout float b, bool create_local = false)\n" +
                "{\nsuper.Hidden(a, b, create_local);\n}",
            EnOverrideText.of(hidden, callSuper = true),
        )
        assertEquals(
            "override bool CanPutInCargo(EntityAI parent)\n{\nreturn super.CanPutInCargo(parent);\n}",
            EnOverrideText.of(findMethod("CanPutInCargo"), callSuper = true),
        )
        assertEquals(
            "override bool CanPutInCargo(EntityAI parent)\n{\nreturn false;\n}",
            EnOverrideText.of(findMethod("CanPutInCargo"), callSuper = false),
        )
        assertEquals(
            "override protected void EOnFrame(EntityAI other, float timeSlice)\n{\nsuper.EOnFrame(other, timeSlice);\n}",
            EnOverrideText.of(findMethod("EOnFrame"), callSuper = true),
        )
    }

    fun testGenericParameterKeptAsDeclared() {
        myFixture.addFileToProject("c.es", "class Container<Class T>\n{\n\tvoid Set(T value) {}\n}")
        myFixture.configureByText("test.es", "class A {}")
        assertEquals(
            "override void Set(T value)\n{\nsuper.Set(value);\n}",
            EnOverrideText.of(findMethod("Set"), callSuper = true),
        )
    }

    fun testInsertedAfterTheMemberAtTheCaretAndIndented() {
        myFixture.addFileToProject("p.es", "class Parent\n{\n\tvoid A() {}\n\tint B(int x) { return x; }\n}")
        myFixture.configureByText(
            "test.es",
            "class Child : Parent\n{\n\tint m_Count; //!< count\n<caret>\n\tvoid Other() {}\n}",
        )
        invokeHandler()
        myFixture.checkResult(
            "class Child : Parent\n{\n\tint m_Count; //!< count\n\n" +
                "\toverride void A()\n\t{\n\t\tsuper.A();<caret>\n\t}\n\n" +
                "\toverride int B(int x)\n\t{\n\t\treturn super.B(x);\n\t}\n\n" +
                "\tvoid Other() {}\n}",
        )
    }

    fun testInsertedIntoAnEmptyBody() {
        myFixture.addFileToProject("p.es", "class Parent\n{\n\tvoid A() {}\n}")
        myFixture.configureByText("test.es", "class Child : Parent {<caret>}")
        invokeHandler()
        myFixture.checkResult("class Child : Parent {\n\toverride void A()\n\t{\n\t\tsuper.A();<caret>\n\t}\n}")
    }

    /** `Ctrl+O` finds the handler through the language extension, not through the class name. */
    fun testRegisteredForTheLanguage() {
        val handler = com.intellij.lang.CodeInsightActions.OVERRIDE_METHOD
            .forLanguage(io.github.lemyst.enfusion.lang.EnfusionLanguage)
        assertTrue(handler.toString(), handler is EnOverrideMethodsHandler)
    }

    private fun invokeHandler() {
        val handler = EnOverrideMethodsHandler()
        assertTrue(handler.isValidFor(myFixture.editor, myFixture.file))
        handler.invoke(project, myFixture.editor, myFixture.file)
    }

    private fun findMethod(name: String): EnMethodDeclMixin {
        val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
        val files = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "es", scope)
        for (file in files) {
            val psi = psiManager.findFile(file) ?: continue
            com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psi, EnMethodDeclMixin::class.java)
                .firstOrNull { it.name == name }?.let { return it }
        }
        error("no method $name")
    }
}
