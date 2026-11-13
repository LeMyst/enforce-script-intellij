package io.github.lemyst.enfusion

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.texture.EnTextureEditorProvider
import io.github.lemyst.enfusion.texture.EnTextureFileType

/** `.edds` and `.paa` get their own binary file type, and the viewer is the editor that opens them. */
class EnfusionTextureViewerTest : BasePlatformTestCase() {

    fun testEddsIsABinaryTexture() {
        val file = myFixture.tempDirFixture.createFile("MyMod/GUI/imagesets/my_gui.edds")
        runWriteAction { file.setBinaryContent(TextureBytes.eddsBgra(4, 4) { _, _ -> 0xFFFF0000.toInt() }) }
        assertEquals(EnTextureFileType, file.fileType)
        assertTrue(file.fileType.isBinary)

        val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
            .single { it is EnTextureEditorProvider }
        assertTrue(provider.accept(project, file))
    }

    fun testPaaIsATextureToo() {
        val file = myFixture.tempDirFixture.createFile("MyMod/Data/logo.paa")
        runWriteAction {
            file.setBinaryContent(TextureBytes.paaDxt1(4, 4, TextureBytes.bc1Block(0xF800, 0x001F), lzo = false))
        }
        assertEquals(EnTextureFileType, file.fileType)
        assertTrue(EnTextureEditorProvider().accept(project, file))
    }

    fun testViewerIgnoresOtherFiles() {
        val layout = myFixture.addFileToProject("MyMod/GUI/layouts/hud.layout", "FrameWidgetClass Root {\n}\n")
        assertFalse(EnTextureEditorProvider().accept(project, layout.virtualFile))
    }
}
