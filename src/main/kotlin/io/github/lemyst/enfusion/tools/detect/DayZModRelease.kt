package io.github.lemyst.enfusion.tools.detect

import java.nio.file.Path
import kotlin.io.path.name

/**
 * A mod built for release: the `@MyMod` folder players and servers download.
 *
 * ```
 * @MyMod\
 *     addons\   the project's addons, binarised and signed by Addon Builder, and third-party PBOs
 *               copied with their .bisign
 *     keys\     the .bikey of the mod's signing key, and the third-party ones
 *     mod.cpp   copied from the project
 *     …         extra files such as LICENSE
 * ```
 */
data class DayZModRelease(
    /** The `@MyMod` folder. */
    val output: Path,
    val addons: List<DayZAddonPacking.Addon>,
    /** Third-party `.pbo` files, copied into `addons\` with the `.bisign` files next to them. */
    val pbos: List<Path> = emptyList(),
    /** Third-party `.bikey` files, copied into `keys\`. */
    val keys: List<Path> = emptyList(),
    /** The `.biprivatekey` Addon Builder signs with; its `.bikey` beside it goes into `keys\`. */
    val signKey: Path? = null,
    val modCpp: Path? = null,
    val extras: List<Extra> = emptyList(),
    /** Name the built PBOs, and so their signatures, in lower case: `scripts_client.pbo`. */
    val lowerCaseNames: Boolean = false,
) {
    /** A file copied into the mod: [target] is relative to the mod's folder, such as `LICENSE`. */
    data class Extra(val source: Path, val target: String)

    /** The name [addon]'s PBO ships under in `addons\`. */
    fun pboNameOf(addon: DayZAddonPacking.Addon): String = if (lowerCaseNames) addon.pboName.lowercase() else addon.pboName
}

/**
 * What a release build does, decided before anything runs: which addons to build, which files to
 * copy, and which files in `addons\` and `keys\` no longer belong to the mod.
 *
 * An addon is built again only when something that ends up in its PBO changed since the last build:
 * its files (the manifest of [DayZAddonPacking]), its include and exclude lists, its prefix or the
 * signing key. It is also built
 * when its PBO is missing, or its signature is while the mod is signed.
 */
object DayZModReleasePlan {

    const val ADDONS = "addons"
    const val KEYS = "keys"

    data class Plan(
        val build: List<DayZAddonPacking.Addon>,
        val upToDate: List<DayZAddonPacking.Addon>,
        /** Source to target, for third-party PBOs and their signatures, keys, `mod.cpp` and extras. */
        val copies: List<Pair<Path, Path>>,
        /** Files in `addons\` and `keys\` that nothing of this build produces or copies any more. */
        val delete: List<Path>,
    )

    /** The `.bikey` that goes with a `.biprivatekey`: the same name, next to it. */
    fun publicKey(signKey: Path): Path = signKey.resolveSibling(keyName(signKey) + ".bikey")

    /** The name a key is known by, as its file names carry it: `MyMod` for `MyMod.biprivatekey`. */
    fun keyName(key: Path): String = key.name.substringBeforeLast('.')

    /** The signature Addon Builder writes for [pboName] signed with [signKey]. */
    fun signatureName(pboName: String, signKey: Path): String = "$pboName.${keyName(signKey)}.bisign"

    /**
     * What goes into an addon's manifest besides its files: a change of prefix, of signing key or
     * of the name it ships under changes the PBO too, so it is rebuilt. The name is there because a
     * case-insensitive disk finds the old manifest under the new case.
     */
    fun manifestOf(addon: DayZAddonPacking.Addon, files: String, signKey: Path?, pboName: String = addon.pboName): String =
        "prefix=${addon.prefix}\nkey=${signKey?.let(::keyName).orEmpty()}\nname=$pboName\n$files"

    /**
     * The plan for [release]. [manifest] is an addon's manifest now, [previous] the one recorded by
     * its last build (null when there is none), [exists] whether a file is there, [inAddons] and
     * [inKeys] the files already in `addons\` and `keys\`, and [signaturesOf] the `.bisign` files
     * next to a third-party PBO.
     */
    fun plan(
        release: DayZModRelease,
        manifest: (DayZAddonPacking.Addon) -> String,
        previous: (DayZAddonPacking.Addon) -> String?,
        exists: (Path) -> Boolean,
        inAddons: List<Path>,
        inKeys: List<Path>,
        signaturesOf: (Path) -> List<Path>,
    ): Plan {
        val twins = release.addons.groupBy { release.pboNameOf(it).lowercase() }.values.firstOrNull { it.size > 1 }
        if (twins != null) {
            throw DayZLaunchCommand.Problem("Two addons would both be built as ${release.pboNameOf(twins[0])}: ${twins[0].folder} and ${twins[1].folder}.")
        }
        val addonsDir = release.output.resolve(ADDONS)
        val keysDir = release.output.resolve(KEYS)

        val build = ArrayList<DayZAddonPacking.Addon>()
        val upToDate = ArrayList<DayZAddonPacking.Addon>()
        for (addon in release.addons) {
            val pbo = addonsDir.resolve(release.pboNameOf(addon))
            val signed = release.signKey?.let { addonsDir.resolve(signatureName(release.pboNameOf(addon), it)) }
            val stale = previous(addon) != manifest(addon) || !exists(pbo) || (signed != null && !exists(signed))
            if (stale) build += addon else upToDate += addon
        }

        val copies = ArrayList<Pair<Path, Path>>()
        for (pbo in release.pbos) {
            for (file in listOf(pbo) + signaturesOf(pbo)) copies += file to addonsDir.resolve(file.name)
        }
        val keys = release.keys + listOfNotNull(release.signKey?.let(::publicKey))
        for (key in keys) copies += key to keysDir.resolve(key.name)
        release.modCpp?.let { copies += it to release.output.resolve("mod.cpp") }
        for (extra in release.extras) copies += extra.source to release.output.resolve(extra.target)

        val clash = copies.groupBy { it.second.toString().lowercase() }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (clash != null) throw DayZLaunchCommand.Problem("Two files of the mod would both be copied to $clash.")
        val builtNames = release.addons.flatMap { addon ->
            listOfNotNull(release.pboNameOf(addon), release.signKey?.let { signatureName(release.pboNameOf(addon), it) })
        }
        val wantedInAddons = (builtNames + copies.filter { it.second.parent == addonsDir }.map { it.second.name })
            .mapTo(HashSet()) { it.lowercase() }
        val wantedInKeys = keys.mapTo(HashSet()) { it.name.lowercase() }
        val delete = inAddons.filter { isPackedFile(it) && it.name.lowercase() !in wantedInAddons } +
            inKeys.filter { it.name.endsWith(".bikey", ignoreCase = true) && it.name.lowercase() !in wantedInKeys }
        return Plan(build, upToDate, copies, delete)
    }

    /** The files a build manages in `addons\`: PBOs and their signatures, nothing else a user put there. */
    private fun isPackedFile(file: Path): Boolean =
        file.name.endsWith(".pbo", ignoreCase = true) || file.name.endsWith(".bisign", ignoreCase = true)
}
