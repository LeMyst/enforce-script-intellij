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
            </ul>
            <p>
            Unofficial community project, not affiliated with or endorsed by Bohemia Interactive.
            DayZ and Enfusion are trademarks of Bohemia Interactive a.s.
        """.trimIndent()
        changeNotes = """
            <ul>
              <li><b>Texture viewer.</b> <code>.edds</code> and <code>.paa</code> files open as
                  images, with zoom, a transparency checkerboard, a picker for every mip level and
                  the pixel format in the status line. DXT1, DXT3, DXT5 and uncompressed textures
                  are shown, raw or compressed. BC7 font atlases and the rare uncompressed
                  <code>.paa</code> formats are named instead of shown.</li>
              <li><b>Imageset outlines.</b> Over an atlas, the viewer outlines every image an
                  imageset places on it, on the 1x and the 2x texture alike, so an icon with no
                  <code>ImageSetDefClass</code> yet stands out. Hovering an outline names the image;
                  double-clicking opens its entry. <i>Show Imageset Regions</i> turns them off.</li>
              <li><b>Previews on hover.</b> Hovering a texture path, such as
                  <code>picture="…/Mod_Logo.paa"</code>, shows the texture. Hovering an imageset
                  image, such as <code>"set:dayz_gui image:DayZLogo"</code>, shows that one icon cut
                  out of its atlas.</li>
              <li><b>Texture icons in the gutter</b> beside imageset entries and beside lines
                  that name a texture or an imageset image, in scripts, layouts and configs.
                  Clicking one goes to the entry or the texture. They can be turned off under
                  <i>Settings | Editor | General | Gutter Icons</i>, for configs separately.</li>
              <li>Find in Files no longer matches inside textures, so texture folders no longer
                  need excluding to keep results clean.</li>
              <li>Texture paths now resolve to textures larger than 2.5 MB, and paths that start
                  with a <code>{GUID}</code>, as imagesets and layouts write them.</li>
              <li><b>Packed <code>.pbo</code> roots.</b> A root in <i>Settings | Languages &amp;
                  Frameworks | Enforce Script</i> can be a <code>.pbo</code> file, for dependencies
                  that exist only as Workshop mods. Its scripts, configs, layouts, imagesets and
                  stringtables navigate like a source checkout, and <code>config.bin</code> is
                  rebuilt as a readable <code>config.cpp</code>. Only those files are unpacked, to a
                  cache in the IDE's system folder, and they open read-only.</li>
              <li>A <b>folder root</b> now also brings in every <code>.pbo</code> under it, so one
                  entry covers an <code>@Mod</code>, a whole <code>!Workshop</code> folder or a
                  game install's <code>addons</code>. Folders are scanned again each time the
                  project opens, behind a progress bar that can be cancelled. Adding a folder with
                  more than 20 PBOs asks first.</li>
              <li><b>PBO browser.</b> Opening a <code>.pbo</code> lists its entries, with a filter.
                  <kbd>Enter</kbd> or a double-click opens entries read-only: scripts and layouts
                  with their language, binarised configs as text, textures in the viewer.</li>
              <li>A config class whose name starts with digits, such as vanilla's
                  <code>1kHz_mono_1s_SoundShader</code>, no longer breaks parsing of the rest of
                  the file.</li>
            </ul>
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
