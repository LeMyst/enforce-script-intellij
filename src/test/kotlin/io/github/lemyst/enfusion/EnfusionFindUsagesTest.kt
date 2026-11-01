package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Find Usages needs no resolver of its own: [io.github.lemyst.enfusion.nav.EnfusionFindUsagesProvider]
 * only supplies the words scanner the platform's default reference search runs on, and every
 * declaration it can find usages *of* already has real `PsiReference`s pointing at it. This exists to
 * pin that down as working, since it is easy to assume it still needs building.
 */
class EnfusionFindUsagesTest : BasePlatformTestCase() {

    fun testFindUsagesOnAMethodFindsBothCallSites() {
        myFixture.configureByText(
            "a.es",
            """
            class Foo
            {
            	void Bar<caret>()
            	{
            	}

            	void Baz()
            	{
            		Bar();
            		Bar();
            	}
            }
            """.trimIndent(),
        )
        val usages = myFixture.testFindUsages("a.es")
        assertEquals(2, usages.size)
    }

    fun testFindUsagesOnAFieldFindsItsUsage() {
        myFixture.configureByText(
            "a.es",
            """
            class Foo
            {
            	int m_Count<caret>;

            	void Bar()
            	{
            		m_Count = 1;
            	}
            }
            """.trimIndent(),
        )
        val usages = myFixture.testFindUsages("a.es")
        assertEquals(1, usages.size)
    }
}
