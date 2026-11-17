package io.github.lemyst.enfusion.tools.detect

/**
 * The files *New DayZ Mod* and *New DayZ Addon* write, as text. Nothing here touches the disk or
 * the IDE, so every template is tested on its own.
 *
 * The part worth getting right is the addon's `config.cpp`: the `files[]` of each script module
 * must be the addon's prefix followed by the module's folder, with forward slashes
 * (`MyMod/Scripts/3_Game`), and a mismatch shows up only when the game fails to load the module.
 */
object DayZModTemplates {

    /**
     * A script module as `CfgMods` declares it: its folder, its class under `defs`, and its name
     * in `dependencies[]`.
     */
    enum class Module(val folder: String, val configClass: String, val dependency: String) {
        CORE("1_Core", "engineScriptModule", "Core"),
        GAME_LIB("2_GameLib", "gameLibScriptModule", "GameLib"),
        GAME("3_Game", "gameScriptModule", "Game"),
        WORLD("4_World", "worldScriptModule", "World"),
        MISSION("5_Mission", "missionScriptModule", "Mission"),
    }

    /**
     * What an addon is for, which decides the dialog's defaults: its folder, its script modules,
     * its `requiredAddons[]`, and the folders or files it starts with. A mod usually splits into
     * addons like these, each its own PBO.
     */
    enum class AddonKind(
        val label: String,
        val folder: String,
        val modules: Set<Module>,
        val requiredAddons: List<String>,
        /** Folders created inside the addon. */
        val subfolders: List<String> = emptyList(),
        /** Whether the addon starts with a `stringtable.csv`. */
        val stringTable: Boolean = false,
    ) {
        SCRIPTS("Scripts", "Scripts", setOf(Module.GAME, Module.WORLD, Module.MISSION), listOf("DZ_Data", "DZ_Scripts")),
        DATA("Data: models, textures, item configs", "Data", emptySet(), listOf("DZ_Data")),
        GUI("GUI: layouts and imagesets", "GUI", emptySet(), listOf("DZ_Data"), subfolders = listOf("layouts", "imagesets")),
        LANGUAGE("Language: stringtable.csv", "LanguageCore", emptySet(), listOf("DZ_Data"), stringTable = true),
        ;

        override fun toString(): String = label
    }

    /** What `mod.cpp` says about the mod in the launcher. */
    data class ModInfo(
        val name: String,
        val author: String = "",
        val tooltip: String = "",
        val overview: String = "",
        val action: String = "",
        val version: String = "1.0",
    )

    /** An addon: one folder of the mod, packed into one PBO under [prefix]. */
    data class Addon(
        /** The `CfgPatches` class, such as `MyMod_Scripts`. */
        val name: String,
        /** As `$PBOPREFIX$` writes it: `MyMod\Scripts`. */
        val prefix: String,
        val requiredAddons: List<String>,
        val modules: Set<Module>,
        /** The `CfgMods` class's `name`, shown by the launcher; the addon's class when empty. */
        val modName: String = "",
        val defines: List<String> = emptyList(),
    ) {
        /** The prefix with forward slashes, as `files[]` paths are written. */
        val path: String get() = prefix.replace('\\', '/').trim('/')

        /**
         * `CfgMods`' `dir`: the mod's folder, the prefix without the addon's own folder
         * (`MyMod` for `MyMod\Scripts`, `Author/MyMod` for `Author\MyMod\Scripts`, as mods write it).
         */
        val dir: String get() = path.substringBeforeLast('/', path)
    }

    /** A licence offered for a new mod; [toString] is what the dialog's list shows. */
    enum class License(val label: String) {
        NONE("None"),
        MIT("MIT"),
        GPL3("GPL-3.0"),
        ;

        override fun toString(): String = label
    }

    fun modCpp(info: ModInfo): String = buildString {
        line("name", info.name)
        if (info.tooltip.isNotBlank()) line("tooltip", info.tooltip)
        if (info.overview.isNotBlank()) line("overview", info.overview)
        if (info.action.isNotBlank()) line("action", info.action)
        if (info.author.isNotBlank()) line("author", info.author)
        if (info.version.isNotBlank()) line("version", info.version)
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append(" = ").append(quote(value)).append(";\n")
    }

    /** A config string: quotes doubled, as the config parser reads them. Line breaks become spaces. */
    fun quote(value: String): String = "\"" + value.replace("\r\n", " ").replace('\n', ' ').replace("\"", "\"\"") + "\""

    fun configCpp(addon: Addon): String = buildString {
        append("class CfgPatches\n{\n")
        append("\tclass ").append(addon.name).append("\n\t{\n")
        append("\t\tunits[] = {};\n")
        append("\t\tweapons[] = {};\n")
        append("\t\trequiredVersion = 0.1;\n")
        append("\t\trequiredAddons[] =\n\t\t{\n")
        append(addon.requiredAddons.joinToString(",\n") { "\t\t\t" + quote(it) })
        if (addon.requiredAddons.isNotEmpty()) append("\n")
        append("\t\t};\n")
        append("\t};\n};\n")
        if (addon.modules.isEmpty()) return@buildString

        val modules = Module.entries.filter { it in addon.modules }
        append("\nclass CfgMods\n{\n")
        append("\tclass ").append(addon.name).append("\n\t{\n")
        append("\t\tdir = ").append(quote(addon.dir)).append(";\n")
        append("\t\tname = ").append(quote(addon.modName.ifBlank { addon.name })).append(";\n")
        append("\t\ttype = \"mod\";\n")
        append("\t\tdependencies[] = { ").append(modules.joinToString(", ") { quote(it.dependency) }).append(" };\n")
        if (addon.defines.isNotEmpty()) {
            append("\t\tdefines[] = { ").append(addon.defines.joinToString(", ") { quote(it) }).append(" };\n")
        }
        append("\t\tclass defs\n\t\t{\n")
        for (module in modules) {
            append("\t\t\tclass ").append(module.configClass).append("\n\t\t\t{\n")
            append("\t\t\t\tvalue = \"\";\n")
            append("\t\t\t\tfiles[] = { ").append(quote(addon.path + "/" + module.folder)).append(" };\n")
            append("\t\t\t};\n")
        }
        append("\t\t};\n")
        append("\t};\n};\n")
    }

    /** Addon Builder reads the prefix from the first line, with backslashes. */
    fun pboPrefix(addon: Addon): String = addon.prefix.replace('/', '\\').trim('\\') + "\n"

    /**
     * The first script file of a module: a reminder of what the module can see, since naming a
     * class from a later module is the mistake the compiler reports least clearly.
     */
    fun moduleScript(module: Module): String {
        val before = Module.entries.filter { it.ordinal < module.ordinal }.map { it.folder }
        val after = Module.entries.filter { it.ordinal > module.ordinal }.map { it.folder }
        val sees = if (before.isEmpty()) "only itself" else "itself and " + before.joinToString(", ")
        val not = if (after.isEmpty()) "" else "; not " + after.joinToString(", ")
        return "// ${module.folder}: code here sees $sees$not.\n"
    }

    fun gitignore(): String = """
        # Built PBOs and their signatures
        *.pbo
        *.bisign
        # Never commit a private signing key
        *.biprivatekey
        # Game and tool logs
        *.rpt
        *.log
        *.mdmp
        # The IDE's folder
        .idea/
    """.trimIndent() + "\n"

    fun readme(info: ModInfo): String = buildString {
        append("# ").append(info.name).append("\n")
        if (info.overview.isNotBlank()) append("\n").append(info.overview.trim()).append("\n")
    }

    /** The MIT licence, for [year] and [holder]. */
    fun mit(year: Int, holder: String): String = """
        MIT License

        Copyright (c) $year $holder

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.
    """.trimIndent() + "\n"

    /** Why [name] cannot be a config class name, or null when it can. */
    fun classNameProblem(name: String): String? = when {
        name.isEmpty() -> "Enter a class name"
        !CLASS_NAME.matches(name) -> "Letters, digits and underscores only, not starting with a digit"
        else -> null
    }

    /** Why [prefix] cannot be an addon prefix, or null when it can. */
    fun prefixProblem(prefix: String): String? {
        val path = prefix.replace('\\', '/').trim('/')
        return when {
            path.isEmpty() -> "Enter the prefix, such as MyMod\\Scripts"
            path.split('/').any { it.isBlank() || !PREFIX_SEGMENT.matches(it) } -> "Folder names of letters, digits, '_', '-' and '.' only"
            else -> null
        }
    }

    /**
     * The `CfgPatches` class for an addon in [folder] of [mod]: `MyMod_Scripts`, with anything a class
     * name cannot hold replaced.
     */
    fun addonName(mod: String, folder: String): String {
        val raw = listOf(mod, folder).filter { it.isNotBlank() }.joinToString("_")
        val clean = raw.replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (clean.firstOrNull()?.isDigit() == true) "_$clean" else clean
    }

    private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val PREFIX_SEGMENT = Regex("[A-Za-z0-9_.\\-]+")
}
