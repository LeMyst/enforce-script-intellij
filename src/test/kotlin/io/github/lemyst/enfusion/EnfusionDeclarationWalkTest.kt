package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.resolve.EnDeclarationWalk
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * The walk behind type and member lookups. It skips method bodies, where nothing it looks for can
 * be, and must otherwise find what a walk over the whole tree finds, in the same order: member order
 * decides which declaration completion keeps.
 */
class EnfusionDeclarationWalkTest : BasePlatformTestCase() {

    private val text = """
        class A
        {
        	int m_First, m_Second;
        	enum Mode { ON, OFF };
        	void Run(int param)
        	{
        		int local;
        		auto inner = 1;
        	}
        	typedef int Id;
        	float m_Last;
        }
        enum Top { ONE };
        class B {}
    """.trimIndent()

    fun testMembersInDocumentOrderWithoutLocals() {
        myFixture.configureByText("a.es", text)
        val body = EnTypeInference.classesNamed("A", project).single().node.findChildByType(EnfusionTypes.CLASS_BODY)!!.psi
        val members = ArrayList<String?>()
        EnDeclarationWalk.forEachNamed(body, intoClassBodies = true) {
            if (EnfusionIndexes.isSymbolDeclaration(it)) members += it.name
        }
        assertEquals(listOf("m_First", "m_Second", "ON", "OFF", "Run", "m_Last"), members)
    }

    fun testClassLookupStaysOutOfClassBodies() {
        myFixture.configureByText("a.es", text)
        val classes = ArrayList<String?>()
        val types = ArrayList<String?>()
        EnDeclarationWalk.forEachNamed(myFixture.file, intoClassBodies = false) { if (it is EnClassDeclMixin) classes += it.name }
        EnDeclarationWalk.forEachNamed(myFixture.file, intoClassBodies = true) { if (EnfusionIndexes.isTypeDeclaration(it)) types += it.name }
        assertEquals(listOf("A", "B"), classes)
        assertEquals(listOf("A", "Mode", "Id", "Top", "B"), types)
    }
}
