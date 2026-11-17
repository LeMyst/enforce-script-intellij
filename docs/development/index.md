---
title: Contributing
nav_order: 8
has_children: true
---

# Contributing

[AGENTS.md](https://github.com/LeMyst/enforce-script-intellij/blob/master/AGENTS.md) holds the
development rules and the release process, for humans and coding agents alike. Read it before
changing the grammar, the build, or anything that touches indexing.

## Building

Supports **IntelliJ 2025.2 and newer**. The build compiles against 2025.2, the oldest supported
release, and targets Java 21 bytecode because 2025.2 runs on JBR 21. No JDK needs installing:
`settings.gradle.kts` registers the foojay resolver, so Gradle provisions the toolchain itself.

```bash
./gradlew build          # compile, validate plugin.xml, run all tests  <- the pre-commit gate
./gradlew buildPlugin    # produce the installable zip in build/distributions/
```

The lexer is generated from `src/main/grammars/EnfusionLexer.flex` into `src/gen/` (gitignored) by the
`generateEnfusionLexer` task, which every compile task depends on.

## Working from inside IntelliJ

Open this project in IDEA and `runIde` appears in the Gradle tool window under *Tasks → intellij
platform*. Running it in **debug** mode lets you hot-swap most Kotlin changes without restarting the
sandbox. Changes to `plugin.xml`, the `.flex` lexer or the `.bnf` grammar always need a restart, since
those are regenerated at build time.

Two plugins are worth having in your main IDE for this work:

- **Plugin DevKit** — bundled with IDEA Ultimate, must be enabled manually in Community.
- **PsiViewer** — adds `Tools → View PSI Structure`. This is the primary tool for
  debugging the grammar: it shows the live PSI tree for the file in the editor, including any
  `PsiErrorElement`.

## This documentation

These pages are the `docs/` folder of the repository, built with Jekyll and the
[Just the Docs](https://just-the-docs.com/) theme. A change to user-visible behaviour updates its
page in the same pull request. A pull request that touches `docs/` builds the site, and a merge to
`master` publishes it; nothing needs installing locally.
