package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop tools of DayZ Tools, by where they sit under its `Bin` folder. Command-line tools
 * (Binarize, CfgConvert, FileBank, DSSignFile, ...) are left out: they are run by Addon Builder.
 */
object DayZToolsCatalog {

    /** A tool and, for those that open a file given as their argument, the file extensions it takes. */
    data class Tool(val name: String, val exe: String, val opens: Set<String> = emptySet())

    val LAUNCHER = Tool("DayZ Tools Launcher", "Launcher/DayZToolsLauncher.exe")
    val WORKBENCH = Tool("Workbench", "Workbench/WorkbenchApp.exe")
    val OBJECT_BUILDER = Tool("Object Builder", "ObjectBuilder/ObjectBuilder.exe", setOf("p3d"))
    val TERRAIN_BUILDER = Tool("Terrain Builder", "TerrainBuilder/terrainBuilder.exe")
    val TEXVIEW = Tool("TexView 2", "ImageToPAA/TexView.exe", setOf("paa", "edds", "tga", "png"))
    val IMAGE_TO_PAA = Tool("ImageToPAA", "ImageToPAA/imageToPaaGUI.exe")
    val ADDON_BUILDER = Tool("Addon Builder", "AddonBuilder/AddonBuilder.exe")
    val PUBLISHER = Tool("Publisher", "Publisher/Publisher.exe")
    val CE_EDITOR = Tool("Central Economy Editor", "CeEditor/CeEditor.exe")
    val MATERIAL_EDITOR = Tool("Material Editor", "ObjectBuilder/MatEditor.exe", setOf("rvmat"))
    val BANK_REV = Tool("BankRev", "PboUtils/BankRevGUI.exe")
    val DS_UTILS = Tool("DSUtils", "DsUtils/DSUtils.exe")

    /** In menu order: the launcher, then the tools by how often a modder reaches for them. */
    val ALL = listOf(
        LAUNCHER, WORKBENCH, OBJECT_BUILDER, TERRAIN_BUILDER, TEXVIEW, IMAGE_TO_PAA, MATERIAL_EDITOR,
        ADDON_BUILDER, PUBLISHER, CE_EDITOR, BANK_REV, DS_UTILS,
    )

    /** The tools installed under [toolsBin]; none when it is unknown. */
    fun installed(toolsBin: Path?): List<Tool> =
        if (toolsBin == null) emptyList() else ALL.filter { Files.isRegularFile(toolsBin.resolve(it.exe)) }

    /** The installed tool that opens [extension] files, preferring the first one listed. */
    fun opener(toolsBin: Path?, extension: String): Tool? =
        installed(toolsBin).firstOrNull { extension.lowercase() in it.opens }
}
