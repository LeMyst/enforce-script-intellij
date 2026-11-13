# AGENTS.md

Guidance for anyone, human or coding agent, working on this repository. Read it before changing the
grammar, the build, or anything that touches indexing.

## What this is

An IntelliJ Platform plugin, published as **Enforce Script**, giving DayZ's scripting language real
PSI. It covers three languages:

| Language ID | Files | Grammar | Kotlin package |
|---|---|---|---|
| `EnfusionScript` | `.es`, and `.c` *contextually* | `Enfusion.bnf` + `EnfusionLexer.flex` | `lang`, `lexer`, `psi`, `resolve`, ... |
| `EnfusionObject` | `.layout`, `.imageset` | `EnfusionObject.bnf` + `EnfusionObjectLexer.flex` | `objfmt` |
| `EnfusionConfig` | `config.cpp`, `mod.cpp` by **file name** | `EnfusionConfig.bnf` + `EnfusionConfigLexer.flex` | `cfg` |

**Names:** everything users see says "Enforce Script", "Enforce Layout" or "Enforce Config".
Language IDs, file-type names, the plugin ID, packages and class names keep `Enfusion*` on purpose:
IDs are persisted in user settings and the plugin ID can never change once published. Only display
strings may be renamed.

The product is navigation. The language has no `#include` and no namespaces, so every symbol lives in
one flat global namespace spread across the mod, the vanilla corpus (`P:\scripts`, about 3,000 files)
and dependency mods. `README.md` describes user-visible behaviour, including what is
not supported.

## Toolchain and compatibility

- **Supported IDEs: 2025.2 and newer.** `platformVersion` in `gradle.properties` is the *oldest*
  supported release, and `sinceBuild` in `build.gradle.kts` must match it (`252`). Compiling against
  the oldest release is what keeps newer APIs out; `verifyPlugin` then proves the newer IDEs.
- **JVM target 21** (`kotlin { jvmToolchain(21) }`), because 2025.2 runs on JBR 21. Never raise it
  without raising `sinceBuild` too. A class file newer than the IDE's runtime fails to load, and the
  Plugin Verifier does **not** catch that. `verifyPluginProjectConfiguration` does.
- These three values move together: `platformVersion`, `sinceBuild` and the JVM target. Run
  `./gradlew verifyPluginProjectConfiguration` after touching any of them; it must report no issues.
- Gradle provisions JDKs itself (the foojay resolver in `settings.gradle.kts`), so none needs to be
  installed.
- The Kotlin stdlib is not bundled (`kotlin.stdlib.default.dependency = false`); the platform ships
  its own.

## Commands

```bash
./gradlew build                              # compile + all tests: the pre-commit gate
./gradlew test --tests '*LexerTest*'         # one test class
./gradlew runIde                             # sandbox IDE (oldest supported, 2025.2) with the plugin
./gradlew runIdeLatest                       # the same on the newest release (latestPlatformVersion)
./gradlew verifyPluginProjectConfiguration   # since-build / JVM target consistency
./gradlew verifyPlugin                       # Plugin Verifier vs recommended() IDEs (downloads IDEs)
./gradlew buildPlugin                        # installable zip in build/distributions/
```

### Corpus tests

`EnfusionCorpusParseTest`, `EnfusionCorpusLexTest`, `EnfusionObjectCorpusParseTest`,
`EnfusionConfigCorpusParseTest`, `EnfusionTextureCorpusTest` and `EnfusionPboCorpusTest` run over
real DayZ sources and are the strongest signal in the project. They **skip** when their root is
unset or absent, so a green run without them proves less
than it seems. Set the roots in your user-level `~/.gradle/gradle.properties`, **never** in the
committed `gradle.properties`:

```properties
enfusionCorpusRoot  = P:\\scripts
enfusionLayoutRoots = P:\\gui;<a mod checkout with hand-written layouts>
enfusionConfigRoots = P:\\DZ;P:\\scripts;<one or more mod checkouts>
enfusionPboRoots     = <a DayZ install's addons folder>;<one or more Workshop @Mod folders>
enfusionTextureRoots = P:\\gui;<one or more mod checkouts>
# enfusionCorpusLimit = 300   # optional sampling
```

Vanilla DayZ sources are proprietary. Never commit them, nor fixtures copied out of them.

## Source layout

- `src/main/grammars/`: the `.bnf` and `.flex` sources. The Grammar-Kit tasks generate the parsers,
  lexers and PSI into `src/gen/`, which is gitignored and regenerated on every compile. **Never edit
  `src/gen/`**; change the grammar instead.
- `src/main/java/`: the small hand-written Java the generated code calls into: token and element
  types, and `EnfusionParserUtil`.
- `src/main/kotlin/io/github/lemyst/enfusion/`:
  - `lexer`: the preprocessing lexer, which evaluates `#ifdef` branches.
  - `psi/impl`: mixins that the `.bnf` files attach to generated PSI.
  - `resolve`: type inference, member and identifier references, overrides.
  - `refs`: references out of string literals (layouts, stringtables, imagesets, inputs, RPC/timer
    dispatch names, asset paths, config classes).
  - `index`: the file-based indexes.
  - `roots`: library roots (vanilla scripts, bundled CF stub, vanilla configs).
  - `texture`: the `.edds` viewer. Its `format` subpackage is the decoder and uses no IntelliJ
    API, so it can be tested on plain bytes; keep it that way.
  - `pbo`: `.pbo` roots. Its `format` subpackage (PBO reader, LZSS, `config.bin` derapifier) uses
    no IntelliJ API, like `texture/format`. `PboCache` unpacks only the navigable entries of a PBO
    to the IDE's system directory; never unpack a whole PBO, since texture PBOs run to gigabytes.
  - `nav`, `hierarchy`, `rename`, `inspections`, `format`, `highlight`, `settings`, `startup`.
- `src/main/resources/META-INF/plugin.xml`: extension registrations. The Marketplace description,
  change notes, version and IDE range live in `build.gradle.kts` (`pluginConfiguration`) and are
  patched into `plugin.xml` at build time. Do not duplicate them in the XML.
- `src/main/resources/inspectionDescriptions/<ShortName>.html`: every inspection needs one.
- `src/main/resources/stubs/cf/`: the bundled Community Framework stub. It holds signatures only,
  declared `proto`, with no bodies.

## Rules that are easy to break

- **Grammar changes:** run the corpus tests under both define configurations, as the corpus test
  does. A rule that over-consumes produces *no* error element and silently swallows the rest of a
  file, which is how the `Class`-as-type-argument defect stayed green. Add a focused test for the
  construct as well.
- **PSI-driven index versions:** after a grammar change, bump `getVersion()` on the affected
  indexes in `index/`. Otherwise the platform keeps the stale index data and the fix seems not to
  work.
- **Grammar leniencies are deliberate.** Each one in `Enfusion.bnf` carries a comment naming the
  vanilla file that forced it, because the engine accepts code a spec-faithful grammar rejects. Do
  not tidy them away.
- **Never claim `.c` by extension.** A `.c` file is Enforce Script only under a `config.cpp` or
  `$PBOPREFIX$` ancestor (`EnfusionFileTypeOverrider`). Config files are claimed by exact file name
  so that the plugin never competes with C/C++.
- **No internal API.** Keep the verifier's internal-API count at zero. Experimental API usage
  (currently `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged`) must be load-bearing and
  documented in the README.
- **Resolution must be cached.** Resolve through `ResolveCache`, memoise through
  `CachedValuesManager` keyed on PSI and root modification trackers, and wrap recursive walks in
  `RecursionManager`.
- **Inspections stay quiet when unsure.** Each one is gated so that a class whose parent was never
  indexed produces no report. Measure a new rule against the vanilla corpus before enabling it by
  default.
- **Tests cannot exercise library roots or real indexing.** `BasePlatformTestCase` fixtures cover
  PSI, references and inspections. Anything touching roots, auto-detection or cross-root resolution
  needs a manual `runIde` check (see the checklist in `README.md`). Fixtures use `.es`, because `.c`
  needs a `config.cpp` ancestor.
- **No personal or machine-specific data in tracked files:** no absolute user paths, no local mod
  names. Use `P:\scripts` (the standard DayZ Tools work drive) or a generic mod name in examples.

## Licensing

The plugin is `GPL-3.0-or-later` (see `LICENSE`, which the build also packs into the jar).
Third-party material must be GPL-3.0-compatible and keep its own notice. The bundled Community
Framework stub is the one current case: it stays LGPL-2.1 and says so in its header. Never bundle
vanilla DayZ files or anything copied from them.

## Conventions

- Comments explain *why*: the constraint, the corpus evidence, or the failure a line prevents. Do not
  write comments that restate the code.
- Commit subjects are imperative and specific ("Fix `Class` as a type argument swallowing the rest
  of a file"). The body explains the cause and why this fix was chosen.
- Keep `README.md` in step with user-visible behaviour, including what is *not* supported.

## Continuous integration

- `.github/workflows/build.yml` runs on every push to `master` and every pull request. It builds,
  runs the tests and the configuration check, and uploads the plugin zip as an artifact. It also
  runs the Plugin Verifier in a second job. The corpus tests **skip** in CI, because the vanilla
  sources cannot be published, so a green CI run is not the whole picture: run them locally too.
- `.github/workflows/release.yml` runs when a `v*` tag is pushed and produces a **draft** GitHub
  release with the zip attached (see below).

## Releasing

1. Bump `pluginVersion` in `gradle.properties` and update `changeNotes` in `build.gradle.kts`. The
   change notes are also used as the GitHub release text.
2. Run `./gradlew build verifyPluginProjectConfiguration verifyPlugin` locally with the corpus
   roots set. All three must pass, and the verifier must report *Compatible* for every IDE.
3. Work through the `runIde` and `runIdeLatest` checklist in the README.
4. Commit, then tag and push: `git tag v<version> && git push origin v<version>`. The tag must
   equal `v` + `pluginVersion` or the release workflow refuses to run.
5. The release workflow tests, verifies and builds again, then creates a **draft** GitHub release
   with the zip. Download that zip, install it from disk to check it, and upload it to the
   Marketplace. The **first release** must be uploaded by hand at
   <https://plugins.jetbrains.com/plugin/add>, and JetBrains reviews new plugins manually. Then
   publish the draft release.

   **Publishing is final.** The repository has immutable releases enabled: once published, the zip
   and the tag can never be changed, and the tag name can never be reused, even if the release is
   deleted. Anything wrong with a draft can still be fixed by deleting the draft and its tag; after
   publishing, the only fix is a new version.
6. Signing is optional. Add the `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`
   repository secrets and the release zip is signed. Without them it is unsigned, and the IDE warns
   on install. For later releases, `PUBLISH_TOKEN=<token> ./gradlew publishPlugin` uploads directly;
   the token is a Marketplace personal access token.
7. The plugin ID (`io.github.lemyst.enfusionscript`) is permanent once published. Never change it.
