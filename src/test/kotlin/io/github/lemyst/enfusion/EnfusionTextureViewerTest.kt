package io.github.lemyst.enfusion

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.texture.EnTextureEditorProvider
import io.github.lemyst.enfusion.texture.EnTextureFileType

/** `.edds` gets its own binary file type, and the viewer is the editor that opens it. */
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

    fun testViewerIgnoresOtherFiles() {
        val layout = myFixture.addFileToProject("MyMod/GUI/layouts/hud.layout", "FrameWidgetClass Root {\n}\n")
        assertFalse(EnTextureEditorProvider().accept(project, layout.virtualFile))
    }
}
