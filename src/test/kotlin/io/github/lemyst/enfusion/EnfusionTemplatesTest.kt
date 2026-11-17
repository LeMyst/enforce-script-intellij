package io.github.lemyst.enfusion

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.templates.EnTemplateScopes
import io.github.lemyst.enfusion.templates.NewEnforceScriptFileAction
import java.util.Properties

/** Live templates in their scopes, and the file templates producing code that parses. */
class EnfusionTemplatesTest : BasePlatformTestCase() {

    /** The Enforce Script templates applicable where the caret is in [text]. */
    private fun applicable(text: String): Set<String> {
        myFixture.configureByText("test.es", text)
        return TemplateManagerImpl.listApplicableTemplates(TemplateActionContext.expanding(myFixture.file, myFixture.editor))
            .filter { it.groupName == "Enforce Script" }
            .mapTo(HashSet()) { it.key }
    }

    fun testFileScope() {
        assertEquals(setOf("modc", "menu", "weh", "ifs", "ifc"), applicable("<caret>"))
        assertEquals(setOf("modc", "menu", "weh", "ifs", "ifc"), applicable("class A {}\nmod<caret>"))
    }

    fun testClassBody() {
        assertEquals(setOf("onrpc", "ifs", "ifc"), applicable("class A\n{\n\tonr<caret>\n}"))
        // A half-written member breaks the parse; the scope still holds.
        assertEquals(setOf("onrpc", "ifs", "ifc"), applicable("class A : B\n{\n\tint m_X;\n\tonr<caret>\n\tvoid F() {}\n}"))
    }

    fun testStatement() {
        assertEquals(setOf("ifs", "ifc"), applicable("class A\n{\n\tvoid F()\n\t{\n\t\tif (x)\n\t\t{\n\t\t\t<caret>\n\t\t}\n\t}\n}"))
    }

    fun testNotInCommentsOrStrings() {
        assertEquals(emptySet<String>(), applicable("// mod<caret>"))
        assertEquals(emptySet<String>(), applicable("class A { string s = \"mo<caret>\"; }"))
    }

    fun testScopesAfterEnumsAndArrays() {
        myFixture.configureByText("test.es", "enum E { A, B }\nclass C\n{\n\tint a[] = { 1, 2 };\n\t<caret>\n}")
        assertEquals(EnTemplateScopes.Scope.CLASS, EnTemplateScopes.scopeAt(myFixture.file, myFixture.caretOffset))
        myFixture.configureByText("test2.es", "enum E\n{\n\tA,<caret>\n}")
        assertEquals(EnTemplateScopes.Scope.OTHER, EnTemplateScopes.scopeAt(myFixture.file, myFixture.caretOffset))
    }

    fun testExpandedModdedClass() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("test.es", "modc<caret>")
        myFixture.type("\t")
        myFixture.type("PlayerBase")
        myFixture.type("\t")
        myFixture.checkResult("modded class PlayerBase\n{\n\t<caret>\n}")
    }

    fun testFileTemplatesParse() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("MyMod/Scripts/5_Mission")
        myFixture.addFileToProject("MyMod/config.cpp", "class CfgPatches {};")
        val psiDir = psiManager.findDirectory(dir)!!
        for (name in listOf(NewEnforceScriptFileAction.CLASS, NewEnforceScriptFileAction.MODDED_CLASS, NewEnforceScriptFileAction.SCRIPTED_MENU)) {
            val template = FileTemplateManager.getInstance(project).getInternalTemplate(name)
            val properties = Properties(FileTemplateManager.getInstance(project).defaultProperties)
            properties.setProperty("LAYOUT", "MyMod/GUI/layouts/my_menu.layout")
            val created = WriteCommandAction.writeCommandAction(project).compute<PsiFile, Throwable> {
                FileTemplateUtil.createFromTemplate(template, "My" + name.removePrefix("Enforce Script ").replace(" ", ""), properties, psiDir).containingFile
            }
            assertTrue(created.name, created.name.endsWith(".c"))
            // Inside an addon, so the `.c` is Enforce Script and the parse below means something.
            assertEquals(created.name, io.github.lemyst.enfusion.lang.EnfusionLanguage, created.language)
            assertNull(
                "${created.name} does not parse: ${created.text}",
                PsiTreeUtil.findChildOfType(created, PsiErrorElement::class.java),
            )
        }
    }
}
