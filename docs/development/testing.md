---
title: Testing
parent: Contributing
nav_order: 1
---

# Testing the plugin

## Unit tests: seconds, no IDE

```bash
./gradlew test                          # everything
./gradlew test --tests '*LexerTest*'    # one class
```

## Sandbox IDE: the real manual check

```bash
./gradlew runIde
```

This launches a **separate** IntelliJ instance with the plugin installed: the oldest supported
release (2025.2), the one the build compiles against. `./gradlew runIdeLatest` does the same on the
newest release (`latestPlatformVersion` in `gradle.properties`); run both before a release. It keeps its own config and
system directories under `build/idea-sandbox/`, so your day-to-day IDE settings, plugins and recent
projects are untouched. Open a DayZ mod checkout or the vanilla script root inside it.

**This is the acceptance gate for anything touching indexing, library roots or references** — none
of that can be exercised from `BasePlatformTestCase`, so a green test suite says nothing about it.
Every defect found so far in those areas was found here, not by the tests.

What to check, feature by feature: [Manual checks](manual-checks.md).

## Compatibility verification

```bash
./gradlew verifyPlugin
```

Runs the JetBrains Plugin Verifier against `recommended()` IDEs. It downloads a full IDE per target,
so treat it as a pre-release gate rather than a per-change check.

The plugin is built against 2025.2 and declares `sinceBuild = 252` with `untilBuild` open; the
verifier is what proves each newer release. The verifier does **not** check bytecode versions, so
also run `./gradlew verifyPluginProjectConfiguration`, which catches a JVM target newer than the
oldest supported IDE can load.

### Experimental API

Three **experimental API** usages are known and accepted, each load-bearing with no stable
equivalent:

- `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged`, which targets a re-index at this
  one library instead of firing a full roots change. It is experimental in every supported release.
- `TargetPresentation` (with its builder) and `Pointer`, in the texture hover popup. They are the
  return types of `computePresentation()` and `createPointer()`, which every `DocumentationTarget`
  must implement, so there is no way to offer documentation through that API without them. They
  are flagged only on the older releases: `TargetPresentation` on 2025.2 and 2025.3, `Pointer` up
  to 2026.1. From 2026.2 neither is experimental, and a released IDE never changes them.
- `InlayParameterHintsProvider`, with the `InlayInfo` and `HintInfo.MethodInfo` it returns, for
  the parameter-name hints. The platform marks the whole `com.intellij.codeInsight.hints` package
  experimental, in every supported release up to 2026.3 (10 usages on each). Only this API gives users an editable exclude list and *Do not show hints for
  current method*; the declarative inlay API, which the `auto` type hints use, has neither. Its
  deprecated `getSettingsPreview()` is not implemented; the preview is the resource
  `inlayProviders/Parameters/preview.es`.

Either would break loudly rather than silently if the platform changed it, and the verifier is what
would catch it. **Internal** API usage is deliberately kept at zero. (`FileTypeOverrider` was a
further such usage until 2025.2, when it stopped being marked experimental.)

## Corpus tests

`EnfusionCorpusParseTest` parses the entire vanilla corpus under two preprocessor flag
configurations and asserts zero `PsiErrorElement`s, exact text round-tripping and no
`BAD_CHARACTER`. That both flag corners give identical results is what validates the preprocessing
lexer's branch policy.

A note on what it forced: a spec-faithful grammar cannot parse vanilla DayZ. The shipped sources
contain `break` and `return` without semicolons, two `proto` declarations with no terminator, a
missing comma between enum constants, a stray comma *before* an enum value (`GROUND, = 1`), a
trailing comma in an argument list, `for(;;;)` with a stray fourth clause, an `if` or `else` with no
body, statements before the first `case` of a `switch`, and a stray `;` between an attribute and its
declaration — all accepted by the engine compiler. Each corresponding leniency in `Enfusion.bnf` carries a comment
naming the file that forced it, so it does not get "tidied up" later. One file
(`3_game/global/ammotypes.c`, `map<string, int>>`) is genuinely malformed and is recorded as a known
defect rather than excused by loosening generic parsing everywhere.

`EnfusionCorpusLexTest` lexes the same corpus and asserts the lexer emits no `BAD_CHARACTER`,
leaves no gaps, and covers every file end to end. It also prints throughput — a
sudden drop is an early warning that something started backtracking:

```
Lexed 2811 files / 416578 lines / 2595003 tokens in 1154ms (360986 lines/s)
```

`EnfusionTextureCorpusTest` decodes every `.edds` and `.paa` under `enfusionTextureRoots`
(sampled down to `enfusionCorpusLimit`, 2,000 per root by default) and fails on any file it calls
corrupt. A file in a format not decoded here is counted, not failed, and the run prints a breakdown
by format. Use `P:\gui`, `P:\DZ` (about 40,000 `.paa`, hence the sampling) and a few mods.

They read their roots from Gradle properties (`enfusionCorpusRoot`, `enfusionTextureRoots`, and
the others listed in AGENTS.md) and **skip** (rather than fail) when one is unset or the path is
absent, so CI works without them. The roots are machine-specific, so set them in your user-level
`~/.gradle/gradle.properties` rather than the committed one (the committed `gradle.properties` lists
every corpus property). Vanilla DayZ sources are proprietary and are never committed to this
repository.
