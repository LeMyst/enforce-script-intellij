package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Path

/** What a row of a DayZ run configuration's mod list is. */
enum class DayZModKind(val title: String) {
    /** An `@Mod` folder with its packed `Addons`. */
    MOD("Mod"),

    /**
     * A folder of the project with a `config.cpp`, packed at launch into a generated mod (see
     * [DayZAddonPacking]) when its files changed. Listed through the work drive, whose path gives
     * its prefix, and run with `-filePatching`, so that the game can also read the loose files.
     */
    ADDON("Addon (built at launch)"),

    /** A loose `.pbo`, staged into a generated mod folder at launch (see [DayZPboStaging]). */
    PBO("PBO"),
}

/** A row of the mod list. Unticked rows are kept, so that a rescan does not bring them back. */
data class DayZMod(
    val path: String,
    val kind: DayZModKind,
    val enabled: Boolean = true,
    /** Server only: `-serverMod=` rather than `-mod=`. */
    val serverOnly: Boolean = false,
)

/** One client of a Client configuration, which may start several. */
data class DayZPlayer(
    /** `-name=`, and the Goldberg account name. */
    val name: String? = null,
    /** The Goldberg Steam ID; empty derives one from the name. */
    val steamId: String? = null,
    /** `-profiles=`; empty takes one from the configuration's (see [DayZLaunchCommand.profileOf]). */
    val profile: String? = null,
)

object DayZMods {

    fun kindOf(path: String): DayZModKind = when {
        path.endsWith(".pbo", ignoreCase = true) -> DayZModKind.PBO
        else -> DayZModKind.MOD
    }

    /** The rows a configuration saved before the list had, from its `;`-separated strings. */
    fun fromLegacy(mods: List<String>, serverMods: List<String>): List<DayZMod> =
        mods.map { DayZMod(it, kindOf(it)) } + serverMods.map { DayZMod(it, kindOf(it), serverOnly = true) }

    /**
     * [current] after a rescan of the project: [addons] (the project's `config.cpp` folders, as
     * passed to the game) and [rootMods] (its `@` folders) that are not listed yet are added, ticked;
     * addon rows whose folder is gone are dropped. Nothing else changes: a row the user unticked
     * stays unticked, and other kinds and the order are left alone.
     */
    fun rescan(current: List<DayZMod>, addons: List<String>, rootMods: List<String>, exists: (String) -> Boolean): List<DayZMod> {
        val kept = current.filter { it.kind != DayZModKind.ADDON || exists(it.path) }
        val known = kept.mapTo(HashSet()) { key(it.path) }
        val added = ArrayList<DayZMod>()
        for (path in rootMods) if (known.add(key(path))) added += DayZMod(path, DayZModKind.MOD)
        for (path in addons) if (known.add(key(path))) added += DayZMod(path, DayZModKind.ADDON)
        return kept + added
    }

    /** Paths compare as Windows does: without case, and with either separator and no trailing one. */
    fun key(path: String): String = path.trim().replace('/', '\\').trimEnd('\\').lowercase()

}

/**
 * Where a folder of the project is on the work drive. File patching reads the work drive, and mod
 * setup scripts link the checkout there (`P:\MyMod` → `C:\…\MyMod`), so an addon is passed as
 * `P:\MyMod\Scripts` rather than by its checkout path.
 */
object WorkDriveLink {

    /** A folder and the entry at the drive's root that is the same folder. */
    data class Link(val folder: Path, val onDrive: Path)

    /**
     * The link for [folder] or the nearest of its ancestors: [folder] itself when it lies on the
     * drive, else the entry of [entries] (the folders at [driveRoot]) that [sameFile] says is the
     * same folder, links followed.
     */
    fun linkOf(folder: Path, driveRoot: Path, entries: List<Path>, sameFile: (Path, Path) -> Boolean): Link? {
        if (folder.startsWith(driveRoot)) return Link(driveRoot, driveRoot)
        var candidate: Path? = folder
        while (candidate != null && candidate.nameCount > 0) {
            val dir = candidate
            entries.firstOrNull { sameFile(it, dir) }?.let { return Link(dir, it) }
            candidate = dir.parent
        }
        return null
    }

    /** [path] through [link], or null when it is not under the linked folder. */
    fun onDrive(path: Path, link: Link): Path? =
        if (path.startsWith(link.folder)) link.onDrive.resolve(link.folder.relativize(path)) else null
}
