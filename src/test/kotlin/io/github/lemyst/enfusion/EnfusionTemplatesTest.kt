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

    /**
     * A scripted menu's layout path and a packed atlas's texture path start from the addon's game
     * path. The sandbox review got `Users/.../Scripts/Client/GUI/layouts/...` for a checkout on `C:`:
     * any drive letter was taken for the work drive.
     */
    fun testGamePathOfAnAddonWithoutPrefix() {
        val prefix = io.github.lemyst.enfusion.texture.PackImageSet::gamePrefix
        assertEquals("on the work drive", "MyMod/Scripts/Client", prefix("P:/MyMod/Scripts/Client", 'P', null))
        assertEquals("another work drive letter", "MyMod/GUI", prefix("w:/MyMod/GUI", 'W', null))
        assertEquals("under the project's folder", "MyMod/Scripts/Client", prefix("C:/Dev/MyMod/Scripts/Client", 'P', "C:/Dev/MyMod"))
        assertEquals("the project's folder itself", "MyMod", prefix("C:/Dev/MyMod", 'P', "C:\\Dev\\MyMod"))
        assertNull("another drive, outside the project", prefix("C:/Dev/Other/Addon", 'P', "C:/Dev/MyMod"))
        assertNull("another drive, no project", prefix("C:/Users/me/MyMod/Scripts", 'P', null))
        assertNull("a folder merely starting like the project's", prefix("C:/Dev/MyModExtra/X", 'P', "C:/Dev/MyMod"))
    }
}
