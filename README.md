# TurtleLib ⚙️

Shared helper library for building Minecraft Paper plugins faster. Grab the modules you need, drop them into your plugin, and keep boilerplate out of your main project.

## Core Helpers

- **[UpdateChecker](https://github.com/hrobasti/turtle-lib/wiki/UpdateChecker) 🔔** – Async Modrinth/Hangar polling that reports the newest release across both, with prerelease and server-version filtering.
- **[VersionComparator](https://github.com/hrobasti/turtle-lib/wiki/VersionComparator) 📐** – Semantic + pre-release comparison with sensible fallbacks for suffixes.
- **[ServerMatcher](https://github.com/hrobasti/turtle-lib/wiki/ServerMatcher) 🧭** – Declarative server-version guard (exact versions, ranges, or whole minor series) that can warn or auto-disable plugins outside your supported range.
- **[ConfigWatcher](https://github.com/hrobasti/turtle-lib/wiki/ConfigWatcher) 👀** – Lightweight file watcher that detects on-disk changes and invokes safe main-thread callbacks.
- **[StartupBanner](https://github.com/hrobasti/turtle-lib/wiki/StartupBanner) 🎉** – MiniMessage banner loader for flashy enable logs.
- **[MessageService](https://github.com/hrobasti/turtle-lib/wiki/MessageService) 💬** – Locale sync + MiniMessage rendering (component, format, plain) with auto-prefix placeholders and localized console logging.
- **[LangLoader](https://github.com/hrobasti/turtle-lib/wiki/LangLoader) 📦** – Scans bundled `lang/*.yml`, syncs them into plugin data folders, and merges new keys without overwriting custom edits.
- **[ConfigKeyMigrator](https://github.com/hrobasti/turtle-lib/wiki/ConfigKeyMigrator) 🔀** – Renames legacy config/lang keys directly in the file text, keeping comments and formatting intact.
- **[ConfigDefaultsInserter](https://github.com/hrobasti/turtle-lib/wiki/ConfigDefaultsInserter) ➕** – Adds keys missing from a config file as text, with their comments, at the matching place; the rest of the file stays byte-identical.
- **[LegacyDataUpgrade](https://github.com/hrobasti/turtle-lib/wiki/LegacyDataUpgrade) 🧳** – One-time clean upgrade after a major release: backs up the old data folder, writes fresh files, and carries over only the values the admin had changed.
- **[FileChangeTracker](https://github.com/hrobasti/turtle-lib/wiki/FileChangeTracker) 🔎** – Lists which settings and files changed since the last reload (setting by setting for YAML, file by file for folders like `lang/`), for a localized change summary.
- **[LocaleFileChecker](https://github.com/hrobasti/turtle-lib/wiki/LocaleFileChecker) 🧪** – Unit-test helper that checks bundled lang files against the base locale (keys, tags, strict MiniMessage, untranslated values) and against the keys the code uses.

## Requirements

- Java 25+
- Gradle wrapper (included) targeting 9.8.0
- Paper API 26.3 (built against `26.3.build.49-alpha`) on the compile classpath
- The version catalog `gradle/libs.versions.toml` (loaded automatically by Gradle; it pins Paper, bStats, JUnit, the `turtleLib` version, the compile-only APIs of other plugins that Timberella supports, and the Shadow/Spotless Gradle plugins and is a copy of the workspace root's catalog, kept in sync by `sync-gradle-wrapper.ps1`)

## Quick Start

TurtleLib isn't published to a Maven repository; tagged releases attach the jar to GitHub Releases, and Gradle reads it from there. The wiki's [Getting Started](https://github.com/hrobasti/turtle-lib/wiki/Home#getting-started-) section has the Gradle setup, shading notes, and how to build a plugin against a local TurtleLib checkout.

## Release Checklist

1. Bump `version.properties` (e.g. `1.2.0`).
2. `./gradlew clean build` → JAR lands in `dist/turtle-lib-paper-<version>.jar`, the sources JAR in `dist/sources/turtle-lib-paper-<version>-sources.jar`, each with a `.sha256` checksum file next to it (verify with `sha256sum -c`). `dist/` is wiped at the start of every build. Run the full `build` for a release: a single `jar` or `sourcesJar` task still writes its checksum, but leaves the other jar missing.
3. `git tag <version>` (no `v` prefix — the tag name must equal `version.properties` exactly) and push it.
4. Create a GitHub Release for that tag and attach `dist/turtle-lib-paper-<version>.jar` **unrenamed**. The filename must match `turtle-lib-paper-<version>.jar` or Gradle's ivy lookup 404s.
5. Bump `turtleLib` in the workspace root's `gradle/libs.versions.toml` (it must equal the tag exactly) and run `sync-gradle-wrapper.ps1`, so timberella-plugin/underwatertrees-plugin pick up the new version.
6. Update the TurtleLib and Paper API versions in the Gradle example on the wiki's Home page, so it stays copyable.

Version bumps, including those from Dependabot pull requests, always go into the workspace root's `gradle/libs.versions.toml` first, then run `sync-gradle-wrapper.ps1`. The script aborts without copying if a repo's own catalog copy changed since the last sync; enter that change in the root catalog and sync again, or pass `-Force` to overwrite it on purpose. It also copies the Gradle wrapper and the `gradlew`/`gradlew.bat` start scripts byte for byte, without that check.

Release TurtleLib first, the plugins that depend on it afterwards. If `turtleLib` already names a version whose GitHub Release doesn't exist yet, the plugins' default build fails with a 404 on the jar; until the release is published, build them with `-PuseLocalTurtleLib=true`.

## Learn More

Deep dives for every helper (usage patterns, advanced settings, code samples) live in the [TurtleLib wiki](https://github.com/hrobasti/turtle-lib/wiki). Start at [Home](https://github.com/hrobasti/turtle-lib/wiki/Home) to pick the article you need.

## License & Credits

- TurtleLib is released under the MIT License (see `LICENSE`), Copyright (c) 2025-2026 [kroet.net](https://kroet.net). Releases before 2.0.0 were published under a proprietary license; kroet.net additionally makes them available under the MIT License.
- Third-party dependencies (Adventure/MIT, Gson/Apache 2.0) are listed in `THIRD_PARTY_LICENSES.md`. Both come with the `compileOnly` Paper API and are not bundled into the TurtleLib jar.
- Parts of this library and its documentation were produced with AI assistance and reviewed by the maintainer before release.
