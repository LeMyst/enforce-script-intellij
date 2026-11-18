import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.grammarkit") version "2023.3.0.4"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// 21, not newer: the oldest supported IDE (2025.2, see `sinceBuild`) runs on JBR 21, and a class
// file newer than its runtime fails to load with UnsupportedClassVersionError -- which the Plugin
// Verifier does NOT detect. `verifyPluginProjectConfiguration` does, and must stay clean.
kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // `intellijIdea(...)`, not `create(IC, ...)`: JetBrains merged Community and Ultimate into a
        // single distribution at 2025.3, so `IC` is no longer a published type at all -- which is
        // also why the old `ideaIC-<version>.win.zip` artifacts stop existing at that version.
        //
        // `platformVersion` is the OLDEST supported release, not the newest: compiling against it
        // is what guarantees no newer API slips in, and `verifyPlugin` then proves the newer IDEs.
        intellijIdea(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    // One settings page; building the searchable-options index is pure cost here.
    buildSearchableOptions = false

    pluginConfiguration {
        // The ID is permanent once published; the display name is not. The name avoids the
        // "DayZ" and "Enfusion" trademarks, and the description still says DayZ, which is what
        // Marketplace search matches on.
        id = "io.github.lemyst.enfusionscript"
        name = "Enforce Script"
        version = providers.gradleProperty("pluginVersion")
        // Required: the verifier rejects a descriptor without one, before running any compatibility
        // check at all. Marketplace renders it as HTML.
        description = """
            Language support for <b>Enforce Script</b>, the scripting language of DayZ mods.
            <p>
            Navigation-first, because the language makes finding things hard: there is no
            <code>#include</code> and no namespaces, so every symbol lives in one flat global
            namespace assembled from whole directories, spread across a mod, the ~3,000-file vanilla
            corpus and every dependency mod.
            <ul>
              <li>Code completion across every <code>modded class</code> layer and the superclass
                  chain, answered by the same type inference as Go to Declaration</li>
              <li>Parameter info on every overload a call may mean, with default values</li>
              <li>Go to Declaration on types, members, locals and free functions, across every
                  <code>modded class</code> layer</li>
              <li>Go to Declaration from string literals &mdash; widget names into
                  <code>.layout</code> files, stringtable keys into <code>stringtable.csv</code>,
                  imageset references, RPC and timer dispatch names, keybind actions</li>
              <li>Go to Class / Go to Symbol, structure view, find usages, type hierarchy, rename,
                  folding including <code>#ifdef</code> regions</li>
              <li>Inspections for mistakes the engine only reports at build time, such as a
                  missing <code>override</code> or a dispatch string naming no method</li>
              <li>A real PSI language for <code>.layout</code> / <code>.imageset</code> and for
                  <code>config.cpp</code> / <code>mod.cpp</code></li>
              <li>Indexes the vanilla script tree and dependency mod sources automatically</li>
              <li>Packed <code>.pbo</code> files and whole Workshop folders as dependency roots,
                  and a browser that opens any entry of a PBO</li>
              <li>A viewer for <code>.edds</code> and <code>.paa</code> textures that outlines each
                  imageset image on its atlas, with texture and icon previews on hover and in the
                  gutter</li>
              <li>DayZ tooling: the paths and the work drive, DayZ Tools, Addon Builder PBOs, a run
                  configuration for a server and clients with the game's logs in a console, a
                  release build and Workshop publishing</li>
              <li>A preview of <code>.layout</code> files beside their text, imageset packing and
                  extraction, and colour swatches with a picker</li>
              <li>Quick Documentation, inlay hints, Override Methods, live and file templates,
                  <code>stringtable.csv</code> as a table, and formatters for scripts and configs</li>
            </ul>
            <p>
            <a href="https://lemyst.github.io/enforce-script-intellij/">Documentation</a>, with
            what each feature does and what it does not support yet.
            <p>
            Unofficial community project, not affiliated with or endorsed by Bohemia Interactive.
            DayZ and Enfusion are trademarks of Bohemia Interactive a.s.
        """.trimIndent()
        changeNotes = """
            <p>The biggest release yet: the DayZ tools come into the IDE, with new editing aids
            and tools for layouts and imagesets.</p>
            <p><b>DayZ tools</b> (Windows, under <i>Tools | DayZ</i>)</p>
            <ul>
              <li><b>Set up once.</b> <i>Settings | Languages &amp; Frameworks | Enforce Script |
                  DayZ Installation</i> finds DayZ, DayZ Server, DayZ Tools and the work drive from
                  Steam and DayZ Tools' own settings. From there: <i>Mount Work Drive</i>,
                  <i>Start Workbench</i> on the project's mod with file patching, any DayZ Tools
                  program, and <i>Extract Game Data…</i>, offered when the work drive has none.</li>
              <li><b>Build PBO</b> on an addon folder runs Addon Builder and shows its log, with a
                  config error's line as a link. PBOs keep their scripts, layouts, images,
                  stringtables and <code>LICENSE</code> / <code>NOTICE</code> files, and each addon
                  can have its own include and exclude lists.</li>
              <li><b>Run DayZ</b> with a <i>DayZ</i> run configuration: a server and any number of
                  clients, or an offline mission. The project's addons are packed into a mod first,
                  only those that changed, and the game's logs open in a console where errors link
                  to their script line. <i>Check Scripts</i> compiles the mod with DayZ Diag.</li>
              <li><b>Release and publish.</b> <i>DayZ Mod Build</i> builds every addon for release,
                  signed, with third-party PBOs and keys, rebuilding only what changed.
                  <i>DayZ Workshop Publish</i> uploads it with SteamCMD, which it can download.</li>
              <li><i>File | New | DayZ Mod…</i> and <i>DayZ Addon…</i> start a mod or an addon with
                  a working <code>config.cpp</code>. Optional toolbar buttons reach all of the
                  above.</li>
            </ul>
            <p><b>Writing scripts</b></p>
            <ul>
              <li><b>Quick Documentation</b> (<kbd>Ctrl+Q</kbd>) shows a declaration's signature, its
                  Doxygen comment, and where it comes from: its <code>modded class</code> layers and
                  what it overrides.</li>
              <li><b>Inlay hints</b> name the parameter of a literal argument, so
                  <code>CallLater(Refresh, 1000, false)</code> shows that <code>false</code> is
                  <code>repeat</code>, and show the type of an <code>auto</code> variable.</li>
              <li><b>Override Methods</b> (<kbd>Ctrl+O</kbd>) writes overrides for the superclass
                  chain and the other <code>modded class</code> layers, with their
                  <code>super</code> calls. Gutter icons show which layers and subclasses override a
                  class or a method.</li>
              <li><b>New inspections:</b> a <code>modded class</code> override that never calls
                  <code>super</code>; a name used from a later script module, such as a
                  <code>5_Mission</code> class in <code>3_Game</code>; a config base class that
                  Addon Builder would not find, with a quick fix.</li>
              <li><b>Colour swatches</b> beside layout colours, <code>ARGB</code> /
                  <code>ARGBF</code> calls, colour constants and <code>.styles</code> entries. Click
                  one to pick a colour.</li>
              <li><b>Live templates</b> (<code>modc</code>, <code>onrpc</code>, <code>ifs</code>, …)
                  and <i>New | Enforce Script File</i> for a class, a <code>modded class</code> or a
                  scripted menu.</li>
              <li><b>Formatting</b> follows vanilla's indentation for braceless bodies, case labels
                  and continuation lines, and <code>config.cpp</code> / <code>mod.cpp</code> can be
                  reformatted too, indentation only.</li>
              <li><b>Stringtables:</b> <code>stringtable.csv</code> gets a <i>Table</i> tab that shows
                  what each language still needs, a missing key can be created from the code, and
                  <code>"#STR_…"</code> strings fold to their text.</li>
              <li>Item class names in script strings, such as
                  <code>CreateInInventory("Apple")</code>, go to their config class.</li>
            </ul>
            <p><b>Layouts and images</b></p>
            <ul>
              <li><b>Layout preview</b> beside a <code>.layout</code>'s text, with panels, buttons
                  and windows drawn in the game's styles and colours.</li>
              <li><b>Imagesets:</b> <i>Pack into Imageset…</i> turns a folder of pictures into an
                  <code>.edds</code> atlas (BGRA8, DXT1 or DXT5, with LZ4) and its
                  <code>.imageset</code>; <i>Extract Images…</i> turns an imageset back into
                  pictures.</li>
              <li>BC7 textures now show in the viewer, the previews and the gutter.</li>
            </ul>
            <p>The documentation is now a <a href="https://lemyst.github.io/enforce-script-intellij/">website</a>,
            with a page for each feature, including what it does not support yet.</p>
        """.trimIndent()
        vendor {
            name = "Myst"
            url = "https://github.com/LeMyst/enforce-script-intellij"
        }
        ideaVersion {
            // Must agree with `platformVersion` (gradle.properties) and with the JVM target above.
            // `untilBuild` stays open; the verifier is what proves each newer release.
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }

    // Both blocks read environment variables and are inert when they are unset: `signPlugin` is
    // skipped without a certificate, and only `publishPlugin` needs the token. The first release
    // must be uploaded by hand on plugins.jetbrains.com regardless; see AGENTS.md.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // `recommended()` works now that the Gradle plugin is current. It was pinned to explicit
    // versions for a while because the old plugin (2.2.1) predated JetBrains merging Community and
    // Ultimate at 2025.3: it only knew the retired `ideaIC-<version>.win.zip` naming, so every
    // recent IDE looked like it had no binary and the task died before verifying anything. That was
    // stale tooling, not a JetBrains problem.
    pluginVerification { ides { recommended() } }
}

// `runIde` opens the oldest supported IDE, because that is what the build compiles against.
// `runIdeLatest` opens the newest release, so manual checks cover both ends of the range.
intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            version = providers.gradleProperty("latestPlatformVersion")
        }
    }
}

grammarKit {
    jflexRelease.set("1.10.18")
    grammarKitRelease.set("2023.3.4")
}

// `tasks.register(...)` rather than the `by tasks.registering(...)` delegate: the delegate form is
// deprecated as of Gradle 9.6 and the Kotlin DSL compiles that deprecation as an *error*, not a
// warning, so the build simply stops.
val generateEnfusionLexer = tasks.register<GenerateLexerTask>("generateEnfusionLexer") {
    sourceFile.set(file("src/main/grammars/EnfusionLexer.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/gen/io/github/lemyst/enfusion/lexer"))
    purgeOldFiles.set(true)
}

val generateEnfusionParser = tasks.register<GenerateParserTask>("generateEnfusionParser") {
    sourceFile.set(file("src/main/grammars/Enfusion.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/gen"))
    pathToParser.set("io/github/lemyst/enfusion/parser/EnfusionParser.java")
    pathToPsiRoot.set("io/github/lemyst/enfusion/psi")
    purgeOldFiles.set(true)
}

// The `.layout` / `.imageset` object format. One lexer and grammar serve both: they are the same
// format, differing only in which class names appear.
val generateEnfusionObjectLexer = tasks.register<GenerateLexerTask>("generateEnfusionObjectLexer") {
    sourceFile.set(file("src/main/grammars/EnfusionObjectLexer.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/gen/io/github/lemyst/enfusion/objfmt/lexer"))
    purgeOldFiles.set(true)
}

val generateEnfusionObjectParser = tasks.register<GenerateParserTask>("generateEnfusionObjectParser") {
    sourceFile.set(file("src/main/grammars/EnfusionObject.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/gen"))
    pathToParser.set("io/github/lemyst/enfusion/objfmt/parser/EnfusionObjectParser.java")
    pathToPsiRoot.set("io/github/lemyst/enfusion/objfmt/psi")
    purgeOldFiles.set(true)
}

// The `config.cpp` / `mod.cpp` config format. A third language, and the simplest of the three: the
// whole corpus contains no `#include`, `#ifdef` or `enum`, so unlike the script side there is no
// preprocessing lexer.
val generateEnfusionConfigLexer = tasks.register<GenerateLexerTask>("generateEnfusionConfigLexer") {
    sourceFile.set(file("src/main/grammars/EnfusionConfigLexer.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/gen/io/github/lemyst/enfusion/cfg/lexer"))
    purgeOldFiles.set(true)
}

val generateEnfusionConfigParser = tasks.register<GenerateParserTask>("generateEnfusionConfigParser") {
    sourceFile.set(file("src/main/grammars/EnfusionConfig.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/gen"))
    pathToParser.set("io/github/lemyst/enfusion/cfg/parser/EnfusionConfigParser.java")
    pathToPsiRoot.set("io/github/lemyst/enfusion/cfg/psi")
    purgeOldFiles.set(true)
}

val generateSources = listOf(
    generateEnfusionLexer,
    generateEnfusionParser,
    generateEnfusionObjectLexer,
    generateEnfusionObjectParser,
    generateEnfusionConfigLexer,
    generateEnfusionConfigParser,
)

sourceSets["main"].java.srcDirs("src/gen")

tasks.withType<KotlinCompile>().configureEach { dependsOn(generateSources) }
tasks.withType<JavaCompile>().configureEach { dependsOn(generateSources) }

// The GPL requires every distributed copy to carry the license text, and the Marketplace zip is a
// distributed copy. Shipping it in the jar's META-INF satisfies that without a separate file.
tasks.processResources {
    from("LICENSE") { into("META-INF") }

    // The plugin's version for the pre-filled issue reports (FeedbackLinks). Looking the plugin up
    // at runtime is internal API from 2026.2 (`PluginManagerCore.getPlugin`,
    // `PluginManager.getPluginByClass`), which the verifier rejects, so the build writes it instead.
    val pluginVersion = providers.gradleProperty("pluginVersion")
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("**/feedback/version.properties") { expand("version" to pluginVersion.get()) }
}

tasks.test {
    maxHeapSize = "4g"
    systemProperty(
        "enfusion.corpus.root",
        providers.gradleProperty("enfusionCorpusRoot").orNull ?: "",
    )
    // `-PenfusionCorpusLimit=300` samples the corpus instead of walking all 2,811 files.
    systemProperty(
        "enfusion.corpus.limit",
        providers.gradleProperty("enfusionCorpusLimit").orNull ?: "",
    )
    systemProperty(
        "enfusion.layout.roots",
        providers.gradleProperty("enfusionLayoutRoots").orNull ?: "",
    )
    systemProperty(
        "enfusion.config.roots",
        providers.gradleProperty("enfusionConfigRoots").orNull ?: "",
    )
    systemProperty(
        "enfusion.texture.roots",
        providers.gradleProperty("enfusionTextureRoots").orNull ?: "",
    )
    systemProperty(
        "enfusion.pbo.roots",
        providers.gradleProperty("enfusionPboRoots").orNull ?: "",
    )
    systemProperty(
        "enfusion.stringtable.roots",
        providers.gradleProperty("enfusionStringTableRoots").orNull ?: "",
    )
    // The corpus tests report progress and throughput as they run; without this Gradle
    // buffers stdout until the test method returns, which makes a long run unobservable.
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
